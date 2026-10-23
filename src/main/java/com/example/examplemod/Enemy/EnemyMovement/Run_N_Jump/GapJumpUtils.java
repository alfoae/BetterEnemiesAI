package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.EnemyBreak_N_BuildUtils;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.*;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeWalk.BodyDims;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeWalk.Spot;
import com.example.examplemod.debug.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Спільні утиліти стрибка + читання "цей сегмент шляху вимагає стрибка" з реального Path.
 * <p>
 * v4 — виявлення розриву більше НЕ робить свій скан наперед. Цим тепер займається
 * {@link GapJumpNodeEvaluator} прямо всередині pathfinding-графа (через
 * {@link GapJumpPathNavigation}) — тобто сам {@code sharedPath}, яким і так користується
 * PursuitEnemyMeleeBehavior, вже містить "далекий" вузол там, де є прохідний стрибок. Це, до
 * речі, автоматично гасить окрему проблему координації з BuildPathGoal, яку доводилось руками
 * розводити пріоритетом раніше: щойно розрив стає прохідним через стрибок, createPath() більше
 * не повертає unreachable, і {@code isPathBlocked} для НЬОГО просто перестає бути true — тож
 * BuildPathGoal сам по собі туди більше не претендує, без жодної спеціальної умови.
 * <p>
 * v5 — ФІЗИКА ПОЛЬОТУ винесена в {@link GapJumpPhysics} (точна ванільна модель, звірена з
 * незалежною реалізацією). Тут лишилась лише ОЦІНКА дальності для NodeEvaluator-а
 * ({@link #estimateMaxJumpRangeBlocks}) та читання Path.
 */
public final class GapJumpUtils {

    /**
     * (v15: НЕ ВИКОРИСТОВУЄТЬСЯ. Дальність для графа тепер рахується з фізики -
     * {@link GapJumpPhysics#maxGapBlocks}: розбіг + ванільний спринт-стрибок + повітряне керування як у гравця. Константа
     * лишена лише як пам'ять про стару евристику.)
     * <p>
     * Калібрувальний коефіцієнт ОЦІНКИ "на скільки блоків взагалі варто вважати розрив прохідним":
     * {@code maxGap = floor(topSpeed * RANGE_ESTIMATE_FACTOR)}. Це ДОРІВНЮЄ старому
     * {@code 10 тіків * 0.85} — навмисно лишив ті самі числа, щоб не змінювати, які розриви
     * NodeEvaluator вважає прохідними (зомбі в спринті → 3 блоки, як і було).
     * <p>
     * УВАГА: це НЕ модель польоту. Раніше цей самий коефіцієнт використовувався ще й для
     * обчислення швидкості відриву ({@code відстань / 8.5}) — і саме це ламало стрибок на 3 блоки:
     * реальна дальність польоту = швидкість × {@link GapJumpPhysics#FLIGHT_FACTOR_TO_LANDING} ≈ ×4.92,
     * а не ×8.5. Швидкість відриву тепер рахує лише {@link GapJumpPhysics#launchSpeedForDistance}.
     */
    static final double RANGE_ESTIMATE_FACTOR = 8.5;

    /**
     * Горизонтальна відстань між сусідніми вузлами шляху, з якої вважаємо сегмент "стрибком", а не звичайним кроком.
     */
    private static final double JUMP_SEGMENT_THRESHOLD = GapJumpRays.MIN_JUMP_DISTANCE;

    private GapJumpUtils() {
    }

    /**
     * Ванільний множник спринту: {@code LivingEntity.setSprinting(true)} додає до атрибута швидкості
     * тимчасовий модифікатор +0.3 (MULTIPLY_TOTAL), тобто ×1.3. Тільки поки прапор спринту увімкнений.
     */
    static final double SPRINT_SPEED_FACTOR = 1.3;

    /**
     * Швидкість-уставка бігу моба (те, що MoveControl множить на прискорення): атрибут швидкості ×
     * множник бігу. Спільна для оцінки дальності та для передбачення кроку в GapJumpAssistGoal.
     * <p>
     * <b>Спринт рахується "як увімкнений" завжди, коли моб у погоні.</b> Раніше атрибут читався як є, тож
     * оцінка залежала від того, чи прапор спринту ввімкнений САМЕ ЗАРАЗ — а він вимикається на кожному
     * {@code stop()} ({@code PursuitEnemyMeleeBehavior}, ця ціль) і вмикається лише в
     * {@code applyDefaultRun}, який у {@code PursuitEnemyMeleeBehavior.tick()} стоїть ПІСЛЯ
     * {@code getOrComputePath}. У результаті шлях, з яким моб їде в кожен стрибок, завжди рахувався при
     * sprint=false: для зомбі {@code 0.23 * 1.5 * 8.5 = 2.9 -> 2}, і перестрибування (відстань 4)
     * у графі просто не з'являлось, хоча пізніші перерахунки (вже в спринті: {@code 3.8 -> 3}) його мали —
     * моб їхав "блок за блоком" по шляху першого, короткозорого розрахунку.
     * Правило спринту одне на всіх ({@code Run_N_JumpUtils.applyDefaultRun}: sprint = isMemoryChasing),
     * тож у погоні множник додаємо самі; поза погонею (блукання) нічого не змінюється.
     */
    public static double runSpeedSetpoint(Mob mob) {
        double speed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (!mob.isSprinting() && PursuitEnemyBehavior.isMemoryChasing(mob)) {
            speed *= SPRINT_SPEED_FACTOR;
        }
        return speed * Run_N_JumpUtils.getRunSpeedModifier(mob);
    }

    /**
     * Множник для {@code MoveControl.setWantedPosition}, при якому ефективна уставка бігу моба (атрибут * множник) дорівнює
     * {@code setpoint}: так ціль регулює швидкість розбігу, не чіпаючи ні атрибут, ні прапор спринту.
     */
    static double speedModifierFor(Mob mob, double setpoint) {
        double attribute = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        return attribute > 1.0E-6 ? setpoint / attribute : Run_N_JumpUtils.getRunSpeedModifier(mob);
    }

    /**
     * Теоретична максимальна дальність стрибка цього моба на повній швидкості (не поточній) для ЗВИЧАЙНОГО
     * блока під ногами, на рівному. Дальність із урахуванням блока відриву й Δy - {@link #maxJumpGap}.
     * <p>
     * v15: рахується з фізики ({@link GapJumpPhysics#maxGapBlocks}), а не як {@code max(5, швидкість * 8.5)}. Стара
     * нижня межа в 5 блоків існувала, бо керований політ долітав будь-куди; тепер моб летить сам, тож стеля - те, що
     * справді дає розбіг і стрибок (для зомбі в спринті на рівному - 3 блоки розриву).
     */
    public static int estimateMaxJumpRangeBlocks(Mob mob) {
        return GapJumpPhysics.maxGapBlocks(runSpeedSetpoint(mob), GapJumpPhysics.ORDINARY_TAKEOFF, 0.0);
    }

    /**
     * Властивості опори (тертя через хук NeoForge, гальмо ходьби, jumpFactor) для балістичної моделі польоту.
     */
    static GapJumpPhysics.Takeoff takeoffOf(BlockState floor, LevelReader level, BlockPos floorPos, Entity entity) {
        Block block = floor.getBlock();
        return GapJumpPhysics.Takeoff.of(floor.getFriction(level, floorPos, entity), runSlowdown(block), block.getJumpFactor());
    }

    /**
     * Скільки блоків розриву вважати прохідними, коли моб відривається з опори {@code takeoff} і приземляється на
     * {@code deltaY} вище (від'ємне - нижче) - те, що раніше давало {@code estimateMaxJumpRangeBlocks(mob, factor)}.
     * {@code 0} - стрибок (чи така висота) недосяжні: наприклад, вгору з меду.
     */
    static int maxJumpGap(Mob mob, GapJumpPhysics.Takeoff takeoff, double deltaY) {
        return GapJumpPhysics.maxGapBlocks(runSpeedSetpoint(mob), takeoff, deltaY);
    }

    /**
     * Ванільний {@code SlimeBlock.stepOn}: щотіку, поки моб іде по слайму, горизонтальна швидкість множиться на
     * {@code 0.4 + 0.2 * |vy|} (при ходьбі |vy| майже 0). Це НЕ властивість блока (як speedFactor), тож із
     * самого блока його не прочитати - тому слайм (і нащадки {@code SlimeBlock} з модів) розпізнається окремо.
     */
    static final double SLIME_STEP_SLOWDOWN = 0.4;

    /**
     * (v15: для ПЛАНУВАННЯ більше не використовується - див. {@link #maxJumpGap}; лишено як довідкову оцінку.)
     * <p>
     * Множник дальності стрибка для блока, З ЯКОГО моб відривається, ДЛЯ ТАКОГО Δy (вгору/вниз/рівно). Береться
     * з САМОГО блока, тож працює й для блоків з інших модів:
     * <ul>
     *   <li><b>тертя</b> - через {@code BlockState.getFriction(level, pos, entity)} (хук NeoForge, той самий
     *       виклик, що й у ванільному {@code LivingEntity.travel}); блоки з модів перевизначають саме його;</li>
     *   <li><b>speedFactor</b> - гальмо ходьби (пісок душ, мед = 0.4): у ванілі вони мають ЗВИЧАЙНЕ тертя 0.6,
     *       а липкими їх робить саме це;</li>
     *   <li><b>jumpFactor</b> - мед = 0.5 (стрибок нижчий і коротший); ванільну висоту стрибка ми не чіпаємо,
     *       лише знаємо, що з цього блока далеко не долетіти (а вгору - може й ЗОВСІМ не піднятись).</li>
     * </ul>
     * Приблизні значення для ванільних блоків, Δy=0 (детальніше - {@link GapJumpPhysics#blockRangeFactor}):
     * звичайний 1.00, лід 1.45, блакитний лід 1.54, пісок душ 0.58, мед 0.43, слайм 0.34. Для Δy=+1 (вгору)
     * множник ЗАВЖДИ менший, навіть на звичайному блоці (~0.33 - вузьке вікно на підйом), а з меду вгору
     * взагалі недосяжно (0.0 - апекс стрибка нижчий за повний блок). Для Δy=-1 (вниз) - трохи більший за
     * рівний (більше часу на політ).
     *
     * @param floor    стан блока під ногами (блок відриву)
     * @param level    світ (для хука тертя; у навігації - {@code mob.level()})
     * @param floorPos позиція цього блока
     * @param entity   моб, для якого рахуємо (передається в хук тертя; може бути {@code null})
     * @param deltaY   landing.y - floorPos.getY(): {@code 0} рівно, {@code >0} вгору, {@code <0} вниз
     */
    public static double blockRangeFactor(BlockState floor, LevelReader level, BlockPos floorPos, Entity entity, double deltaY) {
        Block block = floor.getBlock();
        double friction = floor.getFriction(level, floorPos, entity);
        return GapJumpPhysics.blockRangeFactor(friction, runSlowdown(block), block.getJumpFactor(), deltaY);
    }

    /**
     * Те саме на рівному (Δy=0) - як до появи сходинок.
     */
    public static double blockRangeFactor(BlockState floor, LevelReader level, BlockPos floorPos, Entity entity) {
        return blockRangeFactor(floor, level, floorPos, entity, 0.0);
    }

    /**
     * Гальмо ходьби по блоку за тік: {@code speedFactor} (пісок душ, мед = 0.4; блоки з модів - будь-яке) і для
     * слайма ще {@link #SLIME_STEP_SLOWDOWN}. 1.0 - без гальма.
     */
    private static double runSlowdown(Block block) {
        double slowdown = block.getSpeedFactor();
        return block instanceof SlimeBlock ? slowdown * SLIME_STEP_SLOWDOWN : slowdown;
    }

    /**
     * Яку частку горизонтальної швидкості лишає ОДИН наземний тік на цьому блоці (ванільна фізика):
     * {@code тертя * 0.91 * гальмо ходьби}. Звичайний блок - 0.546, лід - 0.89, слайм - 0.29, пісок душ і
     * мед - 0.22. Береться з САМОГО блока (тертя через хук NeoForge), тож працює й для блоків з інших модів.
     * (До v15 потрібно було, щоб відрізняти гальмо блока від удару - {@code GapJumpAssistGoal.checkExternalImpulse}; тепер
     * ціль швидкість не перезаписує, тож метод лишено лише як довідковий.)
     */
    public static double groundRetention(BlockState floor, LevelReader level, BlockPos floorPos, Entity entity) {
        return floor.getFriction(level, floorPos, entity) * GapJumpPhysics.AIR_FRICTION * runSlowdown(floor.getBlock());
    }

    /**
     * Чи долетить моб до landing (на живій відстані {@code realDistance}, {@code deltaYBlocks} нижче,
     * завжди {@code <0}) ПРОСТО ЗБІГШИ з краю, без стрибка (без вертикального імпульсу {@code 0.42*jumpFactor}).
     * <p>
     * Навпаки, ніж може здатись: САМ СТРИБОК дає БІЛЬШУ дальність, не меншу - підйом ПЕРЕД падінням додає
     * часу в польоті (спершу вгору, тоді вниз повз рівень відриву і далі до landing), тож без стрибка часу
     * менше, і природний "долетить" - коротший. Це саме той сенс, у якому перевірка тут "залежить від
     * швидкості моба" (як і просили): на БЛИЗЬКОМУ спуску навіть коротшого, безстрибкового вікна вистачає -
     * тоді стрибок не потрібен, і виглядає природніше (без зайвого підскоку на рівному місці перед самим
     * краєм), так само, як гравець просто збігає з виступу, а не підстрибує щоразу. Стрибок (і, відповідно,
     * {@link GapJumpAssistGoal#launch}, що його викликає) лишається потрібним, коли: (а) Δy>=0 (вгору чи
     * рівно - без стрибка НІКУДИ не долетіти), або (б) відстань більша за те, що покриє коротше
     * безстрибкове падіння - тоді довший висхідно-низхідний політ зі стрибка потрібен саме заради
     * додаткового часу (і, відповідно, дальності).
     *
     * @param runSpeedSetpoint {@link #runSpeedSetpoint(Mob)} моба - швидкість, з якою він біжить до краю
     */
    public static boolean reachableWithoutJump(BlockState floor, LevelReader level, BlockPos floorPos, Entity entity,
                                               double deltaYBlocks, double realDistance, double runSpeedSetpoint) {
        return reachableWithoutJump(floor.getFriction(level, floorPos, entity), deltaYBlocks, realDistance, runSpeedSetpoint);
    }

    /**
     * Те саме, коли тертя блока відриву вже відоме (точка стояння на нестандартній опорі - див. {@link #takeoffProps}).
     */
    public static boolean reachableWithoutJump(double friction, double deltaYBlocks, double realDistance,
                                               double runSpeedSetpoint) {
        // v15: та сама балістична модель, що й для стрибка (розбіг на повній швидкості, збіг з краю, політ із легким
        // повітряним керуванням), а не {@code швидкість * сума множників}.
        return GapJumpPhysics.reachableWithoutJump(GapJumpPhysics.Takeoff.of(friction, 1.0, 1.0),
                runSpeedSetpoint, deltaYBlocks, realDistance);
    }

    // =====================================================================================
    // ТОЧКА ВІДРИВУ ЗА ВАНІЛЬНИМИ ПРАВИЛАМИ (а не «блок під вузлом»)
    // =====================================================================================

    private static final Map<Mob, CachedSegment> SEGMENT_CACHE = new WeakHashMap<>();

    static TakeoffProps takeoffProps(LevelReader level, Entity entity, double x, double surfaceY, double z) {
        BlockPos belowPos = BlockPos.containing(x, surfaceY - 0.500001, z);
        BlockState below = level.getBlockState(belowPos);
        Block belowBlock = below.getBlock();
        Block feetBlock = level.getBlockState(BlockPos.containing(x, surfaceY, z)).getBlock();
        double friction = below.getFriction(level, belowPos, entity);
        float feetSpeed = feetBlock.getSpeedFactor();
        double slowdown = feetSpeed != 1.0F ? feetSpeed : belowBlock.getSpeedFactor();
        if (belowBlock instanceof SlimeBlock) {
            slowdown *= SLIME_STEP_SLOWDOWN;
        }
        float feetJump = feetBlock.getJumpFactor();
        double jumpFactor = feetJump != 1.0F ? feetJump : belowBlock.getJumpFactor();
        return new TakeoffProps(belowPos, friction, slowdown, jumpFactor);
    }

    /**
     * Фізика й дальність для {@link ShapeJump#choose}: одна й та сама і в графі шляхів
     * ({@link GapJumpNodeEvaluator}), і при виконанні ({@link #planJump}) - тому обидва обирають ОДНУ пару точок.
     */
    static ShapeJump.Model jumpModel(LevelReader level, Entity mob, double runSpeed) {
        return new ShapeJump.Model() {
            @Override
            public ShapeJump.Physics physics(Spot takeoff) {
                TakeoffProps p = takeoffProps(level, mob, takeoff.x(), takeoff.surfaceY(), takeoff.z());
                return new ShapeJump.Physics(p.jumpFactor(), p.friction() * GapJumpPhysics.AIR_FRICTION, runSpeed, p.slowdown());
            }

            @Override
            public ShapeJump.RangeModel range(Spot takeoff) {
                TakeoffProps p = takeoffProps(level, mob, takeoff.x(), takeoff.surfaceY(), takeoff.z());
                GapJumpPhysics.Takeoff tk = GapJumpPhysics.Takeoff.of(p.friction(), p.slowdown(), p.jumpFactor());
                return deltaY -> {
                    int gap = GapJumpPhysics.maxGapBlocks(runSpeed, tk, deltaY);
                    return gap < 1 ? -1.0 : GapJumpRays.maxFlight(gap);
                };
            }
        };
    }

    /**
     * Точний план стрибка {@code edge -> landingCell} за РЕАЛЬНОЮ формою колізії (див. {@link ShapeJump}).
     * {@code null} - якщо обидві точки звичайні (повний блок на цілому рівні: тоді виконавець лишається на
     * старому коді, нічого не змінюється) або якщо геометрію розв'язати не вдалось (світ змінився тощо).
     */
    static ShapeJump.Plan planJump(Mob mob, BlockPos edge, BlockPos landingCell) {
        if (!ShapeSettings.enabledFor(mob)) {
            return null; // форм-орієнтованість вимкнена (config) - старий код, як і в графі шляхів
        }
        try {
            LevelReader level = mob.level();
            ShapeProbe.CachedSource src = new ShapeProbe.CachedSource(level, state -> false);
            float width = mob.getBbWidth();
            BodyDims dims = new BodyDims(width, mob.getBbHeight(), Math.max(0.5, mob.maxUpStep()), 1.0,
                    Math.max(1, mob.getMaxFallDistance()));
            int nw = Mth.floor(width + 1.0F);
            List<Spot> from = ShapeWalk.candidates(src, edge.getX(), edge.getY(), edge.getZ(), nw, dims);
            List<Spot> to = ShapeWalk.candidates(src, landingCell.getX(), landingCell.getY(), landingCell.getZ(), nw, dims);
            if (from.isEmpty() || to.isEmpty()) {
                return null;
            }
            ShapeJump.Plan plan = ShapeJump.choose(src, dims, from, to, jumpModel(level, mob, runSpeedSetpoint(mob)));
            return plan == null || plan.plain() ? null : plan;
        } catch (RuntimeException e) {
            return null; // геометрія недоступна - лишаємось на старому коді (центр клітинки)
        }
    }

    /** Рядок для логу: блок під краєм, його коефіцієнти й дальність в УСІ треті боки (рівно/вгору/вниз).
     * Раніше показував лише рівну дальність, навіть коли сам стрибок був похилий - через це в лозі
     * "макс. розрив звідси=3" виглядало так, ніби 4-блоковий розрив вгору мав спрацювати, хоча реальна
     * (похила) дальність зовсім інша. Лише діагностика. */
    public static String describeTakeoffBlock(Mob mob, BlockPos feet) {
        BlockPos floorPos = feet.below();
        BlockState floor = mob.level().getBlockState(floorPos);
        Block block = floor.getBlock();
        GapJumpPhysics.Takeoff tk = takeoffOf(floor, mob.level(), floorPos, mob);
        return BuiltInRegistries.BLOCK.getKey(block)
                + " | тертя=" + String.format("%.3f", floor.getFriction(mob.level(), floorPos, mob))
                + " speedFactor=" + String.format("%.2f", block.getSpeedFactor())
                + " jumpFactor=" + String.format("%.2f", block.getJumpFactor())
                + (block instanceof SlimeBlock ? " (слайм: гальмо ходьби x" + SLIME_STEP_SLOWDOWN + ")" : "")
                + " | макс.розрив рівно=" + maxJumpGap(mob, tk, 0.0)
                + " вгору(+" + GapJumpPhysics.JUMP_UP_LIMIT_BLOCKS + ")=" + maxJumpGap(mob, tk, GapJumpPhysics.JUMP_UP_LIMIT_BLOCKS)
                + " вниз(-" + GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS + ")=" + maxJumpGap(mob, tk, -GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS);
    }

    /**
     * Дивиться в РЕАЛЬНИЙ поточний Path моба (уже побудований {@link GapJumpNodeEvaluator}-ом,
     * якщо на мобі стоїть {@link GapJumpPathNavigation}) і шукає, чи серед НАЙБЛИЖЧИХ пари
     * сусідніх вузлів попереду є "стрибковий" сегмент — тобто пара вузлів, що лежать далі одне
     * від одного, ніж дає звичайний крок. Не пересканює місцевість сама — тільки читає те, що
     * вже порахував pathfinder.
     *
     * <p>
     * v17: це "що ЗАПЛАНОВАНО попереду" - БЕЗ перевірки, де моб стоїть зараз. Для рішення "вже пора стрибати" (поступитись
     * гоалу, стартувати гоал) бери {@link #findUpcomingJumpSegment}.
     *
     * @return дані про стрибок, або {@code null}, якщо найближчий відрізок шляху - звичайний крок
     */
    public static GapJump findPlannedJumpSegment(Mob mob) {
        Path path = mob.getNavigation().getPath();
        if (path == null) {
            return null;
        }
        int from = Math.max(0, path.getNextNodeIndex() - 1);
        // Pursuit питає це по кілька разів на тік, а точний план (геометрія + симуляція) коштує дорожче за
        // арифметику над вузлами: у межах одного тіку й того самого Path відповідь та сама.
        CachedSegment cached = SEGMENT_CACHE.get(mob);
        if (cached != null && cached.path == path && cached.from == from && cached.tick == mob.tickCount) {
            return cached.jump;
        }
        GapJump jump = findJumpSegmentInPath(path, from, mob);
        if (jump != null) {
            SEGMENT_CACHE.put(mob, new CachedSegment(path, from, mob.tickCount, jump));
        }
        return jump;
    }

    /**
     * v17: множник швидкості ходьби при підході до входу на паркур (звичайна ходьба, без бігу).
     */
    public static final double ENTRY_CREEP_SPEED_MODIFIER = 1.0;

    /**
     * v17: стрибок, який моб може виконувати ПРЯМО ЗАРАЗ: {@link #findPlannedJumpSegment} плюс одна умова - моб стоїть на
     * ТОМУ Ж рівні, що й клітинка відриву.
     * <p>
     * Раніше сюди віддавався ПЕРШИЙ стрибок будь-де в шляху. Над урвищем цього вистачало (моб, що біжить до краю платформи,
     * завжди стоїть на ній), а коли під паркуром є ПІДЛОГА, шлях починається з ходьби по підлозі (обхід, підйом на перший
     * блок): Pursuit одразу поступався, а GapJumpAssistGoal стартував прямо з підлоги, за кілька блоків від краю й нижче за
     * нього, - "запізніле керування" (along>front) вистрілювало стрибок у нікуди, а далі моб лишався без керування й
     * перехоплював будівельник. Тепер, поки моб не піднявся на рівень відриву, тут {@code null}: Pursuit сам іде шляхом (не
     * поступається), гоал не стартує.
     */
    public static GapJump findUpcomingJumpSegment(Mob mob) {
        GapJump jump = findPlannedJumpSegment(mob);
        if (jump == null) {
            return null;
        }
        if (!isReadyToJump(mob, jump)) {
            final BlockPos edge = jump.edge();
            DebugLog.logThrottled(DebugLog.Cat.JUMP, mob, "jump-notready-" + mob.getId(), 1000L, () ->
                    "стрибок у шляху є (edge=" + edge.toShortString() + "), але моб ще не готовий до відриву (не на рівні краю або "
                            + "в польоті поза вікном порятунку): моб.y=" + String.format("%.2f", mob.getY()) + " edge.y=" + edge.getY()
                            + " onGround=" + mob.onGround() + " -> Pursuit іде шляхом далі, GapJumpAssistGoal не стартує");
            return null;
        }
        return jump;
    }
    /**
     * v17: допуск по висоті між ногами моба й клітинкою відриву: ноги мають бути в межах клітинки. Трохи нижче її дна
     * теж можна: плити/нерівності, похибки float, а головне - "запізніле керування" ({@code GapJumpAssistGoal}
     * рятує моба, що вже зійшов з краю й просів до {@code RESCUE_MAX_DROP}=0.3 блока), тому допуск із запасом більший за 0.3.
     * Різниця рівнів, яку гейт має ловити (підлога проти першого блока паркуру), - ціла клітинка.
     */
    static final double TAKEOFF_LEVEL_TOLERANCE = 0.35;
    /**
     * v17: наскільки далеко за центр краю (вздовж осі стрибка) моб ще може бути, щоб це було "запізніле керування" (Pursuit
     * біг ~2 тіки до передачі: до 1.5-2 блоків на швидкому мобі), а не хибний старт з точки приземлення.
     */
    static final double MAX_PAST_EDGE = 2.5;
    /**
     * v17: з якої відстані (по горизонталі до центру клітинки відриву) моб починає підходити до входу повільно. Спринт
     * гаситься тертям за ~3 тіки (~1.5 блока), але швидкі типи мобів гальмують довше: на симуляції підходу до стовпчика 1x1
     * (ванільні MoveControl/колізії, випадкові кути й швидкості) зомбі потрапляє на блок із першого стрибка в 100% випадків
     * уже з 4 блоків, а мобу в півтора раза швидшому за зомбі (атрибут 0.35 проти 0.231) потрібно 5. Константа (а не залежна
     * від швидкості моба), щоб на межі не було тремтіння "біг/ходьба": атрибут швидкості моба змінюється разом зі спринтом.
     */
    static final double ENTRY_CREEP_RADIUS = 5.0;
    /**
     * v17: на скільки клітинка відриву має бути ВИЩЕ за ноги моба, щоб вважати це підйомом (а не рівною підлогою).
     */
    static final double ENTRY_STEP_MIN_RISE = 0.5;

    /**
     * v17: чи може моб почати цей стрибок просто зараз - ТІ САМІ правила, за якими {@code GapJumpAssistGoal.canUse()} його
     * прийме (інакше Pursuit поступився б, а гоал відмовив - моб лишився б без керування):
     * <ul>
     *   <li>ноги моба в діапазоні висот клітинки відриву (моб на тому ж рівні, що й край);</li>
     *   <li>на землі - готовий; у повітрі - лише у вікні "запізнілого керування": просів не більше за
     *       {@code RESCUE_MAX_DROP} і не летить угору (стрибок-підйом на перший блок паркуру - це політ угору, тут він
     *       ще не готовий);</li>
     *   <li>шлях до краю йде по тій самій поверхні (усі вузли до краю на його висоті), а не в обхід через підлогу;</li>
     *   <li>моб не далі {@link #MAX_PAST_EDGE} за краєм по осі стрибка (інакше це не запізніле керування, а хибний старт).</li>
     * </ul>
     */
    static boolean isReadyToJump(Mob mob, GapJump jump) {
        double y = mob.getY();
        BlockPos edge = jump.edge();
        if (y < edge.getY() - TAKEOFF_LEVEL_TOLERANCE || y >= edge.getY() + 1.0) {
            return false;
        }
        if (!mob.onGround()) {
            double drop = jump.takeoffPoint().y - y;
            if (drop < -0.05 || drop > GapJumpAssistGoal.RESCUE_MAX_DROP || mob.getDeltaMovement().y > 0.1) {
                return false;
            }
        }
        // Збіг рівнів ще не означає, що моб може просто добігти до краю: край може бути на ТОМУ Ж рівні, але в іншому місці, куди
        // шлях веде в обхід (вниз на підлогу й назад), а не по поверхні, на якій моб стоїть. Старт гоала посеред такого
        // шляху - це біг прямо до краю через порожнечу або "запізніле керування" далеко за краєм (стрибок на місці).
        return isWalkToTakeoffLevel(mob, edge) && !isPastTakeoff(mob, jump);
    }

    /**
     * v17: індекс вузла відриву в шляху: перша пара (i, i+1) від {@code from}, що є стрибком за тим самим критерієм, що й у
     * {@link #findJumpSegmentInPath}, і вузол i якої - це клітинка {@code edge}. {@code -1}, якщо не знайдено.
     */
    private static int takeoffIndexOf(Path path, int from, BlockPos edge) {
        int lookahead = path.getNodeCount() - 1;
        for (int i = from; i < lookahead; i++) {
            Node a = path.getNode(i);
            if (a.x != edge.getX() || a.y != edge.getY() || a.z != edge.getZ()) {
                continue;
            }
            Node b = path.getNode(i + 1);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            if (b instanceof HopNode || dx * dx + dz * dz > JUMP_SEGMENT_THRESHOLD * JUMP_SEGMENT_THRESHOLD) {
                return i;
            }
        }
        return -1;
    }

    /**
     * v17: чи веде шлях до клітинки відриву {@code edge} ПО ТІЙ САМІЙ ПОВЕРХНІ: усі вузли від наступного за поточним до самого
     * краю - на висоті краю. Якщо шлях спускається/піднімається (обхід через підлогу, сходинки), прямого бігу до краю нема:
     * мобу спершу треба пройти шлях, а не стрибати. Не знайшли вузла краю (застарілий кеш) - не блокуємо.
     */
    private static boolean isWalkToTakeoffLevel(Mob mob, BlockPos edge) {
        Path path = mob.getNavigation().getPath();
        if (path == null) {
            return true;
        }
        int from = Math.max(0, path.getNextNodeIndex() - 1);
        int takeoffIndex = takeoffIndexOf(path, from, edge);
        for (int i = from + 1; i <= takeoffIndex; i++) {
            if (path.getNode(i).y != edge.getY()) {
                return false;
            }
        }
        return true;
    }

    /**
     * v17: чи моб уже далеко ЗА краєм (по осі від краю до точки приземлення) - тоді стрибок з цього краю робити нема звідки.
     */
    private static boolean isPastTakeoff(Mob mob, GapJump jump) {
        Vec3 takeoff = jump.takeoffPoint();
        double dx = jump.landing().x - takeoff.x;
        double dz = jump.landing().z - takeoff.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-6) {
            return false;
        }
        double along = ((mob.getX() - takeoff.x) * dx + (mob.getZ() - takeoff.z) * dz) / length;
        return along > MAX_PAST_EDGE;
    }

    /**
     * v17: чи треба підходити ПОВІЛЬНО до входу на паркур.
     * <p>
     * Перший стрибок шляху стартує з клітинки, яка ВИЩЕ за моба (перший блок паркуру на підлозі): на нього треба ВЗІЙТИ, а
     * це ванільний стрибок-підйом (MoveControl сам відриває, коли до вузла менше блока). Зі спринту він летить 5 блоків
     * (ванільний поштовх спринту + швидкість бігу), тож по блоку 1x1 моб гарантовано промахується й приземляється на
     * підлогу далі (так і було в логу). Пішки (без спринту, множник 1.0) той самий стрибок летить &lt;1 блока - і моб
     * лишається на блоці. Умова вузька (тільки вхід перед СТРИБКОМ паркуру), тож звичайні підйоми по сходах і виступах
     * не гальмуються.
     */
    public static boolean shouldCreepToEntry(Mob mob) {
        GapJump planned = findPlannedJumpSegment(mob);
        if (planned == null) {
            return false;
        }
        BlockPos edge = planned.edge();
        if (edge.getY() <= mob.getY() + ENTRY_STEP_MIN_RISE) {
            return false; // моб уже на рівні відриву (або вище) - підйом не потрібен
        }
        double dx = edge.getX() + 0.5 - mob.getX();
        double dz = edge.getZ() + 0.5 - mob.getZ();
        return dx * dx + dz * dz <= ENTRY_CREEP_RADIUS * ENTRY_CREEP_RADIUS;
    }

    /**
     * v17: чи є в шляху хоч один стрибковий сегмент (шлях паркуром). Для {@code EnemyBreak_N_BuildUtils.isPathBlocked}:
     * досяжна ціль нагорі, до якої веде паркур, не "заблокована" - будувати вежу не треба.
     */
    public static boolean pathHasJump(Path path) {
        return path != null && findJumpSegmentInPath(path, 0) != null;
    }

    /**
     * v18: чи є стрибковий сегмент у шляху ПОПЕРЕДУ моба - від найближчого до нього вузла й далі. Відрізняється від
     * {@link #pathHasJump} (той дивиться весь шлях, включно з уже пройденими стрибками) і працює для ЧАСТКОВИХ шляхів
     * (A* не дотягнувся до гравця, але попереду ще є стрибки): для них {@code Path.canReach()==false}, і раніше це читалось
     * як "заблоковано", хоча мобу є куди йти паркуром. Для {@code EnemyBreak_N_BuildUtils}: маршрут паркуром попереду -
     * будувати рано.
     */
    public static boolean hasJumpAhead(Path path, Mob mob) {
        if (path == null || path.getNodeCount() < 2) {
            return false;
        }
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < path.getNodeCount(); i++) {
            Node n = path.getNode(i);
            double dx = n.x + 0.5 - mob.getX();
            double dy = (n.y - mob.getY()) * 2.0; // по висоті суворіше: вузол над/під мобом не "найближчий"
            double dz = n.z + 0.5 - mob.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        return findJumpSegmentInPath(path, nearest) != null;
    }

    /**
     * Те саме, що {@link #findUpcomingJumpSegment}, але від ПОТОЧНОГО положення моба й по СВІЖОМУ шляху:
     * бере спільний кеш {@code EnemyBreak_N_BuildUtils.getOrComputePath} (той самий, з якого їде
     * Pursuit; рахується не більше разу на тік на моба, тож додаткового createPath() це не дає).
     * Потрібне для ланцюжка стрибків: у тіку приземлення навігатор моба порожній (ціль його зупиняє на
     * старті), тож {@link #findPlannedJumpSegment} нічого не бачить, і наступний стрибок мусив би чекати,
     * поки Pursuit заново покладе шлях у навігатор (~2 тіки, поки моб "повзе" без керування).
     *
     * @return наступний стрибок від поточного положення, або {@code null}, якщо шляху нема чи поруч лише кроки
     */
    public static GapJump findFreshJumpSegment(Mob mob) {
        Vec3 chasePos = PursuitEnemyBehavior.getChasePosition(mob);
        if (chasePos == null) {
            return null;
        }
        // "Свіжий" = порахований від ПОТОЧНОГО місця (моб щойно приземлився), а не застиглий тротлінгом
        // недосяжності відповідь, порахована ще до стрибка (інакше ланцюжок обривався, а моб стояв ~1 с).
        EnemyBreak_N_BuildUtils.invalidatePathCache(mob);
        Path path = EnemyBreak_N_BuildUtils.getOrComputePath(mob, chasePos);
        if (path == null || path.getNodeCount() < 2) {
            return null;
        }
        return findJumpSegmentInPath(path, 0, mob);
    }

    /**
     * Шукає стрибковий сегмент серед пар вузлів Path, починаючи з індексу {@code from}.
     * <p>
     * ВЕСЬ залишок шляху, не лише перші кілька вузлів (раніше тут було {@code from+3} - реальний
     * знайдений баг, див. чат: для короткого локального шляху (кілька звичайних кроків до краю,
     * потім ОДИН стрибок у самому кінці) стрибок міг опинитись за межею цього вікна, і ЦЯ функція
     * поверталась {@code null}, хоча сам стрибок у Path був). Шлях тут завжди короткий (локальний
     * відрізок до найближчої перепони, не маршрут через усю карту) - сканувати його цілком дешево,
     * це проста арифметика над координатами вузлів, не повторний пошук шляху.
     */
    static GapJump findJumpSegmentInPath(Path path, int from) {
        return findJumpSegmentInPath(path, from, null);
    }

    /**
     * Що під ногами в точці стояння {@code (x, surfaceY, z)}: блок, що дає ТЕРТЯ, і множники швидкості/стрибка.
     * Раніше скрізь брався {@code вузол.below()} - це вірно лише для поверхні на ЦІЛІЙ висоті. Ванільні правила:
     * <ul>
     *   <li>тертя - блок на {@code 0.500001} нижче ніг ({@code getBlockPosBelowThatAffectsMyMovement}): для
     *       плити це блок ПІД плитою, для піску душ (поверхня 14/16 усередині ЙОГО клітинки) - він сам;</li>
     *   <li>speedFactor і jumpFactor - блок у клітинці ніг, а якщо там 1.0 - блок із попереднього пункту
     *       ({@code getBlockSpeedFactor}, {@code getBlockJumpFactor}).</li>
     * </ul>
     * Для звичайної підлоги (цілий рівень, повітря в клітинці ніг) це в точності {@code вузол.below()}.
     */
    record TakeoffProps(BlockPos frictionPos, double friction, double slowdown, double jumpFactor) {
    }

    private record CachedSegment(Path path, int from, int tick, GapJump jump) {
    }

    /**
     * Те саме, але з мобом: для сегмента з НЕСТАНДАРТНОЮ опорою на кінцях ({@link #planJump}) додається точний
     * план (куди саме відриватись/приземлятись і на якій висоті), а якщо є вузли до відриву - ще й шлях підходу
     * ({@link #buildApproach}). Без моба (чи для звичайних блоків) - як і раніше: центри клітинок, цілі висоти.
     */
    static GapJump findJumpSegmentInPath(Path path, int from, Mob mob) {
        int lookahead = path.getNodeCount() - 1;
        for (int i = from; i < lookahead; i++) {
            Node a = path.getNode(i);
            Node b = path.getNode(i + 1);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            boolean hop = b instanceof HopNode; // "гоп" на сусідню клітинку: відстань 1, але це все одно стрибок
            if (hop || dx * dx + dz * dz > JUMP_SEGMENT_THRESHOLD * JUMP_SEGMENT_THRESHOLD) {
                BlockPos edge = new BlockPos(a.x, a.y, a.z);
                BlockPos landingCell = new BlockPos(b.x, b.y, b.z);
                int gapBlocks = (int) Math.round(Math.sqrt(dx * dx + dz * dz)) - 1;
                Vec3 landing = Vec3.atBottomCenterOf(landingCell);
                ShapeJump.Plan plan = null;
                Path approach = null;
                if (mob != null) {
                    plan = planJump(mob, edge, landingCell);
                    if (plan != null) {
                        landing = new Vec3(plan.landX(), plan.landY(), plan.landZ());
                    }
                    approach = buildApproach(path, from, i, plan != null);
                }
                if (hop && plan == null) {
                    continue; // без точного плану гоп не виконати (старий код стрибка розрахований на блоки, не на люки)
                }
                return new GapJump(edge, landing, Math.max(1, gapBlocks), plan, approach);
            }
        }
        return null;
    }

    /**
     * Підхід: вузли шляху між {@code from} (де моб уже побував) і вузлом відриву {@code takeoffIndex} включно -
     * окремий {@link Path}, який GapJumpAssistGoal віддає навігатору. Тоді моб доходить до краю ТИМ САМИМ
     * рухом, що й у Pursuit (точні точки біля люків/плит, гальмо на краях - {@code PathNavigationMixin}), а не
     * прямою до центру краю, яка на кривих блоках веде повз опору. Лише коли це потрібно: або стрибок точний
     * ({@code exact}), або сам шлях уточнений ({@link ShapePathAccess} має точки). Для звичайних блоків - {@code null}
     * (прямий розбіг, як і раніше).
     */
    private static Path buildApproach(Path path, int from, int takeoffIndex, boolean exact) {
        int start = from + 1;
        if (start > takeoffIndex) {
            return null;
        }
        Vec3[] spots = null;
        boolean[] tight = null;
        Object holder = path;
        if (holder instanceof ShapePathAccess access) {
            spots = access.betterEnemies$spots();
            tight = access.betterEnemies$tight();
        }
        boolean refined = spots != null && tight != null
                && spots.length > takeoffIndex && tight.length > takeoffIndex;
        if (!exact && !refined) {
            return null;
        }
        List<Node> nodes = new ArrayList<>(takeoffIndex - start + 1);
        for (int n = start; n <= takeoffIndex; n++) {
            nodes.add(path.getNode(n));
        }
        Path approach = new Path(nodes, path.getTarget(), true);
        if (refined) {
            Object approachHolder = approach;
            if (approachHolder instanceof ShapePathAccess target) {
                target.betterEnemies$setWaypoints(
                        Arrays.copyOfRange(spots, start, takeoffIndex + 1),
                        Arrays.copyOfRange(tight, start, takeoffIndex + 1));
            }
        }
        return approach;
    }

    /**
     * (v5: GapJumpAssistGoal більше не відходить для розгону — швидкість відриву задавалась явно, тож
     * розбіг на дальність не впливав. УВАГА, v15: тепер розбіг НАВПАКИ визначає дальність стрибка, і якщо довгі
     * стрибки з вузьких опор після гальмування недолітатимуть, саме цей метод - заготовка для "відійти й розігнатись".)
     * <p>
     * Йде по прямій у напрямку {@code dir} від моба, шукаючи найдальшу БЕЗПЕЧНУ точку в межах
     * {@code desiredBlocks} — якщо позаду теж обрив (чи стіна), зупиняється на останньому
     * твердому й прохідному блоці перед ним, навіть якщо це менше за {@code desiredBlocks}.
     * Повертає поточну позицію моба, якщо небезпечно навіть на 1 блок.
     */
    public static Vec3 findSafeRetreatPoint(Mob mob, Vec3 dir, int desiredBlocks) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return mob.position();
        }
        BlockPos feet = mob.blockPosition();
        BlockPos lastSafe = feet;
        for (int step = 1; step <= desiredBlocks; step++) {
            BlockPos column = feet.offset((int) Math.round(dir.x * step), 0, (int) Math.round(dir.z * step));
            boolean solidFloor = level.getBlockState(column.below()).isSolid();
            boolean passable = !level.getBlockState(column).isSolid();
            if (!solidFloor || !passable) {
                break; // далі в цьому напрямку теж небезпечно - зупиняємось тут
            }
            lastSafe = column;
        }
        return Vec3.atBottomCenterOf(lastSafe);
    }

    /**
     * Край (останній твердий вузол перед стрибком), точка приземлення і ширина розриву в блоках.
     * Для косого/діагонального стрибка {@code gapBlocks} лише наближене (округлена відстань між
     * центрами мінус 1) і потрібне тільки для логів; уся геометрія береться з {@code edge}/{@code landing}.
     * <p>
     * Для НЕСТАНДАРТНОЇ опори на кінцях (плита, край люка, стовпчик...) є ще {@code plan} - точний план за
     * реальною формою колізії ({@link ShapeJump}); тоді {@code landing} уже реальна точка приземлення з
     * реальною висотою поверхні, а точка відриву, межі опори й ліміт кроку беруться з плану (див. методи нижче).
     * Для звичайних блоків {@code plan == null} і нічого не змінюється. {@code approach} - вузли шляху від
     * поточного місця до вузла відриву (для навігатора), або {@code null} (прямий розбіг, як і раніше).
     */
    public record GapJump(BlockPos edge, Vec3 landing, int gapBlocks, ShapeJump.Plan plan, Path approach) {

        public GapJump(BlockPos edge, Vec3 landing, int gapBlocks) {
            this(edge, landing, gapBlocks, null, null);
        }

        /**
         * Є точний план (нестандартна опора). Тоді {@link #landing} - уже РЕАЛЬНА точка приземлення (x, z і висота
         * поверхні), а не центр клітинки на цілій висоті; інакше все як раніше.
         */
        public boolean exact() {
            return this.plan != null;
        }

        /** Точка відриву: на опорі (з реальною висотою) або, як раніше, центр блока-краю. */
        public Vec3 takeoffPoint() {
            return this.plan != null
                    ? new Vec3(this.plan.takeoffX(), this.plan.takeoffY(), this.plan.takeoffZ())
                    : Vec3.atBottomCenterOf(this.edge);
        }

        /** Скільки блоків вздовж стрибка від точки відриву, поки центр моба ще над опорою (передній край). */
        public double front(double dirX, double dirZ) {
            return this.plan != null ? this.plan.frontAlong() : GapJumpPhysics.frontBorder(dirX, dirZ);
        }

        /** Де вздовж стрибка хітбокс повністю сходить з опори відриву (далі onGround=false). */
        public double lose(double dirX, double dirZ, double halfWidth) {
            return this.plan != null ? this.plan.loseAlong() : GapJumpPhysics.plainLoseAlong(dirX, dirZ, halfWidth);
        }

        /** Наскільки вбік від осі стрибка моб ще стоїть на опорі відриву. */
        public double lateralLimit(double halfWidth, double dirX, double dirZ) {
            return this.plan != null ? this.plan.lateralLimit() : (0.5 + halfWidth) * (Math.abs(dirX) + Math.abs(dirZ));
        }

        /** Найбільший горизонтальний крок за тік, при якому хітбокс на тіку торкання ще над опорою приземлення. */
        public double maxStep(double halfWidth) {
            return this.plan != null ? this.plan.maxStep() : GapJumpPhysics.maxFlightStep(halfWidth);
        }

        /** Чи пора відриватись (див. {@link GapJumpPhysics#shouldTakeOff}); для точного плану - за реальними межами опори. */
        public boolean takeoffNow(double relX, double relZ, double dirX, double dirZ, double speedAlong,
                                  double halfWidth, double groundAccel) {
            return this.plan != null
                    ? GapJumpPhysics.shouldTakeOffAlong(relX, relZ, dirX, dirZ, speedAlong, groundAccel,
                    this.plan.frontAlong(), this.plan.loseAlong())
                    : GapJumpPhysics.shouldTakeOff(relX, relZ, dirX, dirZ, speedAlong, halfWidth, groundAccel);
        }

        @Override
        public String toString() {
            return "GapJump[edge=" + this.edge + ", landing=" + this.landing + ", gap=" + this.gapBlocks
                    + (this.plan != null ? ", ТОЧНИЙ" : "") + (this.approach != null ? ", підхід=" + this.approach.getNodeCount() + " вуз." : "")
                    + "]";
        }
    }
}