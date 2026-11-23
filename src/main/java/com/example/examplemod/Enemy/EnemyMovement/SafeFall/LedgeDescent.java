package com.example.examplemod.Enemy.EnemyMovement.SafeFall;

import com.example.examplemod.Config;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.EnemyBreak_N_BuildUtils;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.PlaceReason;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.*;
import com.example.examplemod.debug.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * v26: СПУСК ВИСТУПАМИ НА СТІНІ - стик між чистим планувальником ({@link LedgePlanner}) і грою.
 * <p>
 * ЯК ЦЕ ПРАЦЮЄ. Проблема: моб із малим HP на висоті (стовп, урвище, дах) не мав шляху вниз - усі падіння смертельні, і він застрягав
 * ({@code FallDebug.reportStuckIfLethal}). Тепер:
 * <ol>
 *   <li><b>Граф.</b> {@link GapJumpNodeEvaluator} з краю, де прямий спуск би бив, питає {@link LedgePlanner}: чи можна спуститись
 *       ступенями? Якщо так - в граф додається ОДНЕ ребро {@link DropNode} на підлогу внизу з {@link LedgeHint} і ціною
 *       "виступи * ledgeStageCost" (смертельні ребра, що раніше відкидались, стають такими ребрами).</li>
 *   <li><b>Виконання.</b> {@link GapJumpAssistGoal} розгортає ребро в кроки плану. КОЖЕН крок - звичайний збіг RNJ без стрибка на
 *       сусідню колонку (або за кут) з метою "верх виступу"; у польоті {@link #airTick} ставить виступ через той самий
 *       {@code EnemyBreak_N_BuildUtils.placeBlock}, що й Break_N_Build (причина {@link PlaceReason#DROP_LEDGE}: ТІЛЬКИ до стіни
 *       збоку, не в повітрі). Приземлившись на виступ, моб СХОДИТЬ З НЬОГО далі й ставить наступний; виступи лишаються стояти й
 *       зникають за таймером тимчасових блоків. Ламати виступ під собою - лише крайній випадок (шахта 1x1).</li>
 *   <li><b>Безпека.</b> Кожен ступінь закінчується на твердому блоці, тож перервати спуск між ступенями безпечно. Якщо виконавець
 *       не може відтворити план (світ змінився) чи моб промахнувся повз виступ - {@link #noteFailure}: ребра виступів для цього
 *       моба вимикаються на {@link #FAIL_COOLDOWN_TICKS} тіків, шлях перераховується (а не виконується сліпий падіння на всю висоту).</li>
 * </ol>
 */
public final class LedgeDescent {

    /** Ліміт вузлів одного пошуку плану (запобіжник часу): успішний спуск зазвичай потребує кількох десятків вузлів. */
    static final int MAX_PLAN_NODES = 300;

    /**
     * Ширші за це мобі на виступі 1x1 не вміщуються (рейвен, голем...) - для них спуск виступами вимкнено.
     */
    static final double MAX_MOB_WIDTH = 0.9;
    /** Після збою ребра виступів для моба вимкнено на стільки тіків (шлях тим часом перераховується без них). */
    static final long FAIL_COOLDOWN_TICKS = 200L;
    /** Скільки тіків після ламання виступу ігноруємо "стоїть на землі": прапор onGround оновиться лише після travel(). */
    static final int BREAK_GRACE_TICKS = 3;
    private LedgeDescent() {
    }

    private static final Map<Mob, Long> FAILED_UNTIL = new WeakHashMap<>();

    // =====================================================================================================
    // Налаштування й параметри
    // =====================================================================================================

    /** Чи вмикати спуск виступами для цього моба: конфіг, ті самі вимикачі, що в Break_N_Build, ширина, пауза після збою. */
    public static boolean enabledFor(Mob mob) {
        if (mob == null || !Config.LEDGE_DESCENT.get() || !Config.ENABLE_MOB_TERRAFORMING.get()) {
            return false;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return false;
        }
        if (mob.getBbWidth() > MAX_MOB_WIDTH) {
            return false;
        }
        Long until = FAILED_UNTIL.get(mob);
        return until == null || level.getGameTime() >= until;
    }

    /** Висота ступеня - не більше безпечної висоти падіння моба; null - виступи для цього моба безглузді (імунний чи безпечна <1). */
    static LedgePlanner.Params paramsFor(Mob mob, FallMath.Profile profile) {
        if (profile == null || profile.immune()) {
            return null;
        }
        int safe = (int) Math.floor(profile.safeFall() + 1.0E-6);
        if (safe < 1) {
            return null;
        }
        int stage = Math.max(1, Math.min(Config.LEDGE_STAGE_BLOCKS.get(), safe));
        int height = Math.max(1, (int) Math.ceil(mob.getBbHeight() - 0.05));
        return new LedgePlanner.Params(stage, height, Config.LEDGE_MAX_STAGES.get(), Config.LEDGE_BREAK_FALLBACK.get(),
                MAX_PLAN_NODES);
    }

    static int settleTicks() {
        return Config.LEDGE_SETTLE_TICKS.get();
    }

    // =====================================================================================================
    // Світ для планувальника
    // =====================================================================================================

    /** Блоки, на які не можна стати, у які не можна поставити блок і крізь які не можна падати: шкодять чи тримають. */
    private static boolean hazard(BlockState s) {
        return s.is(BlockTags.FIRE) || s.is(BlockTags.CAMPFIRES) || s.is(Blocks.COBWEB) || s.is(Blocks.POWDER_SNOW)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.CACTUS) || s.is(Blocks.MAGMA_BLOCK)
                || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.HONEY_BLOCK) || s.is(Blocks.SLIME_BLOCK);
    }

    /**
     * Світ для {@link LedgePlanner} через читач блоків ({@code getBlockStateAt} графа чи {@code Level::getBlockState} виконавця) -
     * ОДНА й та сама логіка в обох місцях, тож план графа й план виконавця збігаються. Невідоме (null) = "ні" в усьому.
     */
    static LedgePlanner.World worldOf(Function<BlockPos, BlockState> reader) {
        return new LedgePlanner.World() {
            private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

            private BlockState at(int x, int y, int z) {
                return reader.apply(this.cursor.set(x, y, z));
            }

            @Override
            public boolean solid(int x, int y, int z) {
                BlockState s = at(x, y, z);
                return s != null && s.isSolid();
            }

            @Override
            public boolean floor(int x, int y, int z) {
                BlockState s = at(x, y, z);
                return s != null && !s.isAir() && !hazard(s) && s.getFluidState().isEmpty()
                        && s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            }

            @Override
            public boolean free(int x, int y, int z) {
                BlockState s = at(x, y, z);
                if (s == null) {
                    return false;
                }
                if (s.isAir()) {
                    return true;
                }
                return s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
                        && s.getFluidState().isEmpty() && !hazard(s) && !s.is(BlockTags.CLIMBABLE);
            }

            @Override
            public boolean placeable(int x, int y, int z) {
                BlockState s = at(x, y, z);
                return s != null && (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty() && !hazard(s);
            }
        };
    }

    // =====================================================================================================
    // Виконання плану (стан кроку, зв'язок з GapJumpAssistGoal)
    // =====================================================================================================

    /** Сегмент RNJ для поточного кроку: збіг з опори кроку на колонку цілі; ціль приземлення - верх виступа (чи підлога). */
    static GapJumpUtils.GapJump jumpFor(Run run) {
        LedgePlanner.Step s = run.step();
        BlockPos edge = new BlockPos(s.fromX(), s.fromY(), s.fromZ());
        Vec3 landing = Vec3.atBottomCenterOf(new BlockPos(s.toX(), s.landingY(), s.toZ()));
        Path approach = run.index == 0 && run.base != null ? run.base.approach() : null;
        return new GapJumpUtils.GapJump(edge, landing, 1, null, approach, s.exitX(), s.exitZ(), null);
    }

    /**
     * Розгортає ребро {@code jump} (з {@link LedgeHint}) у план: ТОЙ САМИЙ планувальник і ті самі параметри, що в графі.
     *
     * @return стан виконання або null, якщо плану нема (світ змінився, вимкнено)
     */
    static Run begin(Mob mob, GapJumpUtils.GapJump jump) {
        LedgeHint hint = jump.ledge();
        if (hint == null || !enabledFor(mob)) {
            return null;
        }
        try {
            LedgePlanner.Params params = paramsFor(mob, FallDamage.profileOf(mob));
            if (params == null) {
                return null;
            }
            Level level = mob.level();
            BlockPos edge = jump.edge();
            LedgePlanner.Plan plan = LedgePlanner.plan(worldOf(level::getBlockState), edge.getX(), edge.getY(), edge.getZ(),
                    hint.dx(), hint.dz(), params);
            if (plan == null || plan.steps().isEmpty()) {
                return null;
            }
            log(mob, "ПЛАН: виступів=" + plan.ledges() + " ламань=" + plan.breaks() + " кроків=" + plan.steps().size()
                    + " | з " + edge.toShortString() + " на підлогу (" + plan.landX() + "," + plan.landY() + "," + plan.landZ() + ")"
                    + " | ступінь=" + params.stage());
            return new Run(plan, jump);
        } catch (RuntimeException ex) {
            log(mob, "ПЛАН: виняток " + ex);
            return null;
        }
    }

    /**
     * Чи минув темп постановки блока цим мобом: якщо ні, відрив від опори варто відкласти - у польоті на постановку є лише
     * кілька тіків (вікно {@code ledgePlaceMinAbove..ledgePlaceMaxAbove}), і втрачений тік означав би падіння зі шкодою.
     */
    static boolean readyToPlace(Mob mob) {
        return EnemyBreak_N_BuildUtils.canPlaceNow(mob, PlaceReason.DROP_LEDGE, mob.level().getGameTime());
    }

    /**
     * Щотіку в польоті кроку: поставити виступ, але НЕ РАНІШЕ, ніж моб наблизиться до точки приземлення: коли його ноги не вище за
     * {@code ledgePlaceMaxAbove} (1 бл) над верхом виступу. Моб за тік пролітає ~0.5-0.65 бл, тож вікно {@code 0.5..1} бл він
     * проходить за 1-2 тіки: якщо цей тік винесе його нижче {@code ledgePlaceMinAbove} (0.5 бл), ставимо одразу. Далі повторюємо
     * спробу щотіку, поки не вийде ({@code placeBlock} сам стежить за темпом, видимістю клітинки й хітбоксом; виступ нижче ніг, тож
     * у хітбокс не потрапляє, доки моб не торкнеться верху).
     */
    static void airTick(Mob mob, Run run) {
        if (run.placed) {
            return;
        }
        BlockPos cell = run.ledgeCell();
        if (cell == null || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (level.getBlockState(cell).isSolid()) {
            run.placed = true; // уже стоїть (наш попередній блок чи чужий) - лишається приземлитись
            return;
        }
        double above = mob.getY() - (cell.getY() + 1.0);
        // goal.tick() іде ДО travel(): deltaMovement.y - це саме той рух, який моб зробить у цьому тіку
        double thisStep = Math.max(0.0, -mob.getDeltaMovement().y);
        boolean inWindow = above <= Config.LEDGE_PLACE_MAX_ABOVE.get();
        boolean lastChance = above - thisStep < Config.LEDGE_PLACE_MIN_ABOVE.get(); // після цього тіка моб уже буде нижче вікна
        if (!inWindow && !lastChance) {
            return; // ще зарано: блок не з'являється раніше, ніж моб підійде до точки приземлення
        }
        run.placeTries++;
        if (EnemyBreak_N_BuildUtils.placeBlock(level, cell, mob, PlaceReason.DROP_LEDGE)) {
            run.placed = true;
            log(mob, "виступ ПОСТАВЛЕНО " + cell.toShortString() + " зі спроби " + run.placeTries
                    + " | моб над виступом на " + String.format("%.2f", above) + " бл");
        } else if (run.placeTries == 1 || run.placeTries % 5 == 0) {
            log(mob, "виступ ще не поставлено " + cell.toShortString() + " (спроба " + run.placeTries
                    + ", моб над виступом на " + String.format("%.2f", above) + " бл)");
        }
    }

    /** Моб стоїть на виступі {@code cell}: блок на місці, хітбокс над його верхньою гранню (центр може трохи виступати за край). */
    static boolean standsOn(Mob mob, BlockPos cell) {
        if (!mob.level().getBlockState(cell).isSolid()) {
            return false;
        }
        AABB box = mob.getBoundingBox();
        if (Math.abs(box.minY - (cell.getY() + 1.0)) > 0.25) {
            return false;
        }
        return box.maxX > cell.getX() && box.minX < cell.getX() + 1.0
                && box.maxZ > cell.getZ() && box.minZ < cell.getZ() + 1.0;
    }

    /** КРАЙНІЙ ВИПАДОК: зламати власний виступ під ногами. Чужі й ванільні блоки не чіпає. */
    static boolean breakStandingLedge(Mob mob, Run run) {
        BlockPos cell = run.standingLedge();
        if (cell == null || !(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        boolean ok = EnemyBreak_N_BuildUtils.breakOwnTemporaryBlock(level, cell, mob);
        log(mob, ok ? "КРАЙНІЙ ВИПАДОК: зламав виступ під собою " + cell.toShortString()
                : "не вдалося зламати виступ " + cell.toShortString() + " (це не наш тимчасовий блок)");
        return ok;
    }

    /**
     * Стан виконання плану в {@link GapJumpAssistGoal}.
     */
    static final class Run {
        final LedgePlanner.Plan plan;
        /** Початковий сегмент з графа: з нього беремо шлях підходу до краю (лише для першого кроку). */
        final GapJumpUtils.GapJump base;
        /** Поточний крок плану (рух ІЗ поточної опори). */
        int index;
        /** Виступ поточного кроку вже стоїть. */
        boolean placed;
        /** Скільки тіків ще постояти на виступі перед наступним кроком. */
        int settle;
        /** Тіки після ламання виступу, коли onGround ще може бути хибним "true". */
        int grace;
        /** Скільки разів у польоті цього кроку пробували поставити виступ. */
        int placeTries;

        Run(LedgePlanner.Plan plan, GapJumpUtils.GapJump base) {
            this.plan = plan;
            this.base = base;
        }

        LedgePlanner.Step step() {
            return this.plan.steps().get(this.index);
        }

        /** Клітинка виступу, який ставимо на цьому кроці; null - крок без блока (останній збіг). */
        BlockPos ledgeCell() {
            LedgePlanner.Step s = step();
            return s.placesLedge() ? new BlockPos(s.toX(), s.ledgeY(), s.toZ()) : null;
        }

        /** Виступ під ногами на початку кроку (null - перший крок: стоїмо на справжній опорі краю). */
        BlockPos standingLedge() {
            if (this.index == 0) {
                return null;
            }
            LedgePlanner.Step s = step();
            return new BlockPos(s.fromX(), s.fromY() - 1, s.fromZ());
        }

        void advance(int settleTicks) {
            this.index++;
            this.placed = false;
            this.placeTries = 0;
            this.settle = Math.max(0, settleTicks);
        }

        String debug() {
            LedgePlanner.Step s = step();
            return "крок " + (this.index + 1) + "/" + this.plan.steps().size() + " " + s.kind()
                    + " з (" + s.fromX() + "," + s.fromY() + "," + s.fromZ() + ") у колонку (" + s.toX() + "," + s.toZ() + ")"
                    + (s.placesLedge() ? " виступ y=" + s.ledgeY() + (this.placed ? " [стоїть]" : " [ще нема]") : " [без блока]")
                    + " settle=" + this.settle + " grace=" + this.grace;
        }
    }

    /**
     * Збій виконання: ребра виступів для цього моба вимикаються на {@link #FAIL_COOLDOWN_TICKS}, поточний шлях скидається, а новий
     * рахується вже без них - моб не піде сліпим падінням на всю висоту за застарілим ребром.
     */
    static void noteFailure(Mob mob, String why) {
        FAILED_UNTIL.put(mob, mob.level().getGameTime() + FAIL_COOLDOWN_TICKS);
        mob.getNavigation().stop();
        EnemyBreak_N_BuildUtils.requestFreshPath(mob);
        log(mob, "ЗБІЙ: " + why + " -> виступи вимкнено для цього моба на " + FAIL_COOLDOWN_TICKS + " тіків, шлях буде перераховано");
    }

    static void log(Mob mob, String msg) {
        if (DebugLog.on(DebugLog.Cat.JUMP)) {
            DebugLog.log(DebugLog.Cat.JUMP, mob, "[ЛЕДЖ] " + msg);
        }
    }
}
