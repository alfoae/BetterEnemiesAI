package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.HopNode;
import com.example.examplemod.debug.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * v21 (ДЕБАГ, категорія {@code [FALL]} у {@code logs/betterenemiesai-debug.log}): усе, що моб "думає" про падіння, в одному місці.
 * Нічого не змінює в поведінці - лише читає й друкує. Усі записи йдуть тільки коли ввімкнено {@link DebugLog.Cat#FALL} (разом із
 * загальним дебагом), а важкі збирачі (статистика пошуку) - лише для ОДНОГО пошуку на моба раз на 2 с.
 * <p>
 * Що друкується (шукай у файлі за {@code [FALL]}):
 * <ol>
 *   <li><b>ПРОФІЛЬ ПАДІННЯ</b> - на початку пошуку шляху: HP, поглинання, резерв, безпечна висота, множник, Опір, захист чарами,
 *       імунітет; скільки блоків моб переживе, нижній предел стрибкових ребер, таблиця шкоди за глибиною.
 *       Якщо профіль НЕ побудовано (вимкнено в конфігу або виняток у {@code FallDamage.profileOf}) - окремий рядок з причиною.</li>
 *   <li><b>ПОШУК ШЛЯХУ</b> - наприкінці пошуку (один із 2 с): скільки вузлів розкрито й на яких рівнях Y (чи дивився A* НИЖЧЕ за старт),
 *       кандидати стрибка вниз за Δy, відсічені як смертельні, на який блок (звичайний/сіно/вода/...), найменша шкода серед
 *       кандидатів, чи запускалось глибоке сканування (Δy &lt; -3) і чому пропускалось.</li>
 *   <li><b>ШЛЯХ</b> - після побудови шляху: КОЖЕН спуск у ньому - СТРИБОК (граф рахував шкоду) чи ПІШКИ-ЗРИВ (ванільне ребро,
 *       шкоду не рахував), звідки куди, скільки блоків падіння, на який блок, очікувана шкода.</li>
 *   <li><b>ВІДРИВ</b> - коли {@code GapJumpAssistGoal} стартує спуск: край, точка приземлення, прогноз падіння/шкоди.
 *       <b>ПРИЗЕМЛЕННЯ</b> - фактичне падіння й втрата HP проти прогнозу.</li>
 *   <li><b>ПАДІННЯ</b> - будь-яке падіння моба від 2 блоків (подія гри), навіть без стрибка: відстань і множник блока, який
 *       застосувала САМА гра.</li>
 * </ol>
 */
public final class FallDebug {

    private static final Map<Mob, String> LAST_PROFILE_SIG = new WeakHashMap<>();

    // Вид приземлення за тим, що вирішує множник шкоди (дзеркалить FallDamage.landingMultiplier + те, чого він не вміє).
    private static final int K_NORMAL = 0;
    private static final int K_HAY = 1;
    private static final int K_BED = 2;
    private static final int K_WATER = 3;
    private static final int K_WEB = 4;
    private static final int K_SLIME = 5;
    private static final int K_CLIMB = 6;

    private FallDebug() {
    }

    /**
     * Лише моби, що когось переслідують/шукають або саме виконують стрибок: пошук шляху йде в усіх наземних мобів світу, і без цього
     * фільтра лог заливали б корови з вівцями.
     */
    static boolean relevant(Mob mob) {
        return mob != null && (mob.getTarget() != null || PursuitEnemyBehavior.getChasePosition(mob) != null
                || GapJumpAssistGoal.isActive(mob));
    }

    // ------------------------------------------------------------------------------------------
    // Форматування
    // ------------------------------------------------------------------------------------------

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String blockName(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private static String cell(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    private static int landingKind(BlockState below, BlockState feet, BlockState head) {
        if (feet.getFluidState().is(FluidTags.WATER) || head.getFluidState().is(FluidTags.WATER)) {
            return K_WATER;
        }
        if (feet.is(Blocks.COBWEB) || head.is(Blocks.COBWEB)) {
            return K_WEB;
        }
        if (below.is(Blocks.SLIME_BLOCK)) {
            return K_SLIME;
        }
        if (below.is(Blocks.HAY_BLOCK) || below.is(Blocks.HONEY_BLOCK)) {
            return K_HAY;
        }
        if (below.is(BlockTags.BEDS)) {
            return K_BED;
        }
        if (feet.is(BlockTags.CLIMBABLE) || head.is(BlockTags.CLIMBABLE)) {
            return K_CLIMB;
        }
        return K_NORMAL;
    }

    private static String kindName(int kind) {
        return switch (kind) {
            case K_HAY -> "сіно/мед (x0.2)";
            case K_BED -> "ліжко (x0.5)";
            case K_WATER -> "вода (0)";
            case K_WEB -> "павутиння (0)";
            case K_SLIME -> "слайм (0)";
            case K_CLIMB -> "ДРАБИНА/ЛІАНИ (гра скидає падіння, модель - НЕ знає)";
            default -> "звичайний (x1.0)";
        };
    }

    /**
     * Що під/в клітинці приземлення й який множник шкоди з цього виходить за {@link FallDamage#landingMultiplier}.
     */
    static String describeLanding(BlockState below, BlockState feet, BlockState head) {
        double multiplier = FallDamage.landingMultiplier(below, feet, head);
        int kind = landingKind(below, feet, head);
        StringBuilder sb = new StringBuilder();
        sb.append("блоки: під=").append(blockName(below)).append(" ноги=").append(blockName(feet))
                .append(" голова=").append(blockName(head)).append(" | вид=").append(kindName(kind))
                .append(" | множник у моделі=").append(f2(multiplier));
        if (kind == K_CLIMB) {
            sb.append(" !! драбину/ліани FallDamage.landingMultiplier НЕ враховує - рахує як звичайний блок");
        }
        return sb.toString();
    }

    private static String dmg(FallMath.Profile p, double fall, double multiplier) {
        float damage = FallMath.damageAfterReductions(p, fall, multiplier);
        return f1(damage) + (FallMath.survives(p, damage) ? "" : "✗");
    }

    private static double normalApex() {
        return FallMath.apexHeight(GapJumpPhysics.JUMP_VERTICAL_VELOCITY, GapJumpPhysics.GRAVITY_PER_TICK,
                GapJumpPhysics.VERTICAL_DRAG_PER_TICK);
    }

    /**
     * Профіль побудовано: HP/чари/ефекти, скільки моб переживе, нижній предел і таблиця шкоди за глибиною.
     */
    static void logProfile(Mob mob, FallMath.Profile p, int cap, double apex, int survivable, int downLimit, float costPerHp) {
        try {
            String sig = f1(p.health) + "|" + f1(p.absorption) + "|" + f1(p.reserve) + "|" + f2(p.safeFall) + "|" + f2(p.fallMultiplier)
                    + "|" + p.resistanceLevel + "|" + f1(p.protection) + "|" + p.immune + "|" + downLimit + "|" + mob.getMaxFallDistance();
            boolean changed = !sig.equals(LAST_PROFILE_SIG.get(mob));
            // той самий профіль - не частіше разу на 5 с; змінився (HP міняється від кожного удару) - не частіше разу на 1 с
            if (!DebugLog.throttle("fall-profile-" + mob.getId(), changed ? 1000L : 5000L)) {
                return;
            }
            LAST_PROFILE_SIG.put(mob, sig);

            StringBuilder sb = new StringBuilder(1100);
            sb.append("ПРОФІЛЬ ПАДІННЯ (початок пошуку шляху)").append(changed ? " [ЗМІНИВСЯ]" : "");
            sb.append("\n    HP=").append(f1(p.health)).append('/').append(f1(mob.getMaxHealth()))
                    .append(" +поглинання=").append(f1(p.absorption))
                    .append(" | резерв після падіння=").append(f1(p.reserve))
                    .append(" | безпечна висота=").append(f2(p.safeFall))
                    .append(" | множник шкоди=").append(f2(p.fallMultiplier))
                    .append(" | Опір=").append(p.resistanceLevel == 0 ? "нема" : "рівень " + p.resistanceLevel)
                    .append(" | захист чарами (Невагомість/Захист)=").append(f1(p.protection))
                    .append(" | імунітет=").append(p.immune);
            sb.append("\n    [імунітет: тип=").append(mob.getType().is(EntityTypeTags.FALL_DAMAGE_IMMUNE))
                    .append(" повільне_падіння=").append(mob.hasEffect(MobEffects.SLOW_FALLING))
                    .append(" невразливий=").append(mob.isInvulnerableTo(mob.damageSources().fall())).append(']');
            sb.append("\n    апекс звичайного стрибка=").append(f2(apex))
                    .append(" | найглибше, що переживе на ЗВИЧАЙНИЙ блок (maxSurvivableDrop)=").append(survivable)
                    .append(" (стеля jumpDownMaxBlocks=").append(cap).append(')')
                    .append(" -> нижній предел стрибкових ребер=-").append(downLimit)
                    .append(" (не менше -").append(GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS).append(')')
                    .append(" | вартість 1 HP шкоди в A*=").append(f1(costPerHp));
            sb.append("\n    ванільний mob.getMaxFallDistance()=").append(mob.getMaxFallDistance())
                    .append(" - ним обмежені ПІШІ ребра спуску (збіг з краю): вони НЕ рахують ні HP, ні чари, ні блок унизу");

            int show = Math.min(24, Math.max(cap, 8));
            sb.append("\n    шкода (HP) за глибиною СТРИБКА Δy [звичайний | сіно/мед x0.2 | ліжко x0.5], ✗ = не виживе з резервом:\n       ");
            for (int drop = 1; drop <= show; drop++) {
                double fall = apex + drop;
                sb.append(" -").append(drop).append(':').append(dmg(p, fall, 1.0)).append('|').append(dmg(p, fall, 0.2))
                        .append('|').append(dmg(p, fall, 0.5));
            }
            sb.append("\n    шкода (HP) за глибиною ЗБІГУ З КРАЮ пішки (без апекса) [звичайний]:\n       ");
            for (int drop = 2; drop <= show + 1; drop++) {
                sb.append(" -").append(drop).append(':').append(dmg(p, drop, 1.0));
            }
            DebugLog.log(DebugLog.Cat.FALL, mob, sb.toString());
        } catch (RuntimeException ignored) {
            // дебаг не повинен ламати пошук шляху
        }
    }

    // ------------------------------------------------------------------------------------------
    // 1. Профіль падіння (початок пошуку шляху)
    // ------------------------------------------------------------------------------------------

    /**
     * Профіль не будується, бо вимкнено в конфігу ({@code fallDamageAwareJumps=false}).
     */
    static void logProfileOff(Mob mob) {
        if (!DebugLog.throttle("fall-profile-off-" + mob.getId(), 10000L)) {
            return;
        }
        DebugLog.log(DebugLog.Cat.FALL, mob, "ПРОФІЛЬ ПАДІННЯ ВИМКНЕНО: fallDamageAwareJumps=false -> стара логіка: фіксований нижній предел -"
                + GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS + ", без штрафів за шкоду й без відсікання смертельних приземлень");
    }

    /**
     * Профіль НЕ побудовано через виняток (раніше це мовчки перемикало пошук на стару логіку).
     */
    static void logProfileFailed(Mob mob, RuntimeException failure) {
        if (!DebugLog.throttle("fall-profile-fail-" + mob.getId(), 5000L)) {
            return;
        }
        StackTraceElement[] trace = failure == null ? new StackTraceElement[0] : failure.getStackTrace();
        DebugLog.log(DebugLog.Cat.FALL, mob, "!!! ПРОФІЛЬ ПАДІННЯ НЕ ПОБУДОВАНО - виняток у FallDamage.profileOf; пошук іде за СТАРОЮ логікою"
                + " (нижній предел -" + GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS + ", без штрафів і без відсікання смертельних): "
                + failure + (trace.length > 0 ? " @ " + trace[0] : ""));
    }

    /**
     * Для кожного ребра шляху, що веде ВНИЗ: це СТРИБКОВЕ ребро (граф рахував шкоду) чи ванільний ПІШКИ-ЗРИВ (не рахував), звідки
     * куди, скільки блоків падіння, на який блок і яка очікувана шкода. Викликається, коли шлях змінився (MobDebug).
     */
    public static void logPathDescents(Mob mob, Path path) {
        if (mob == null || path == null || !DebugLog.on(DebugLog.Cat.FALL)) {
            return;
        }
        try {
            FallMath.Profile p = FallDamage.profileOf(mob);
            double apex = normalApex();
            Level level = mob.level();
            int nodes = path.getNodeCount();
            double thr = GapJumpRays.MIN_JUMP_DISTANCE * GapJumpRays.MIN_JUMP_DISTANCE;
            StringBuilder edges = new StringBuilder();
            int descents = 0;
            int walkOffs = 0;
            float totalLoss = 0.0F;
            int firstY = Integer.MIN_VALUE;
            for (int i = 0; i + 1 < nodes; i++) {
                Node a = path.getNode(i);
                Node b = path.getNode(i + 1);
                int dy = b.y - a.y;
                double dx = b.x - a.x;
                double dz = b.z - a.z;
                boolean jump = b instanceof HopNode || dx * dx + dz * dz > thr;
                if (jump ? dy >= 0 : dy > -2) {
                    continue; // нас цікавлять лише падіння: стрибки вниз і зриви від 2 блоків (сходинка в 1 блок - не падіння)
                }
                descents++;
                if (!jump) {
                    walkOffs++;
                }
                double fall = jump ? FallMath.fallBlocks(apex, dy) : -dy;
                BlockPos landing = new BlockPos(b.x, b.y, b.z);
                BlockState below = level.getBlockState(landing.below());
                BlockState feet = level.getBlockState(landing);
                BlockState head = level.getBlockState(landing.above());
                double multiplier = FallDamage.landingMultiplier(below, feet, head);
                float damage = FallMath.damageAfterReductions(p, fall, multiplier);
                totalLoss += FallMath.healthLoss(p, damage);
                if (firstY == Integer.MIN_VALUE) {
                    firstY = a.y;
                }
                edges.append("\n    ребро #").append(i).append(": (").append(a.x).append(',').append(a.y).append(',').append(a.z)
                        .append(") -> (").append(b.x).append(',').append(b.y).append(',').append(b.z).append(") Δy=").append(dy)
                        .append(jump ? " СТРИБОК (граф рахував шкоду)" : " ПІШКИ-ЗРИВ (ванільне ребро: A* шкоду НЕ рахував)")
                        .append(" | падіння≈").append(f2(fall)).append(" бл | шкода≈").append(f1(damage)).append(" HP")
                        .append(FallMath.survives(p, damage) ? "" : " ✗СМЕРТЕЛЬНО")
                        .append(" | ").append(describeLanding(below, feet, head));
            }
            if (descents == 0) {
                return;
            }
            Node last = path.getNode(nodes - 1);
            String sb = "ШЛЯХ: спусків=" + descents + " (з них ПІШКИ-ЗРИВІВ=" + walkOffs + ')' +
                    " | перший спуск з Y=" + firstY + ", кінець шляху Y=" + last.y +
                    " | сумарна очікувана втрата≈" + f1(totalLoss) + " HP при HP=" + f1(p.health) +
                    "+" + f1(p.absorption) + " | ванільний mob.getMaxFallDistance()=" + mob.getMaxFallDistance() +
                    " | вузлів=" + nodes + " canReach=" + path.canReach() +
                    edges;
            DebugLog.log(DebugLog.Cat.FALL, mob, sb);
        } catch (RuntimeException ignored) {
            // дебаг не повинен ламати пошук шляху
        }
    }

    // ------------------------------------------------------------------------------------------
    // 2. Статистика одного пошуку шляху (GapJumpNodeEvaluator)
    // ------------------------------------------------------------------------------------------

    /**
     * Сегмент стрибка/збігу, що ПОЧИНАЄТЬСЯ: звідки й куди, скільки блоків падіння, блок приземлення, прогноз шкоди. Друкується
     * лише для спусків (Δy &lt;= -1); повертає прогноз для {@link #logLanding}, або {@code null}.
     */
    static Prediction logSegment(Mob mob, BlockPos edge, Vec3 landing, double segDeltaY, double jumpFactor, boolean runOff) {
        if (mob == null || segDeltaY > -0.99 || !DebugLog.on(DebugLog.Cat.FALL)) {
            return null;
        }
        try {
            FallMath.Profile p = FallDamage.profileOf(mob);
            double apex = runOff ? 0.0 : FallMath.apexHeight(GapJumpPhysics.JUMP_VERTICAL_VELOCITY * jumpFactor,
                    GapJumpPhysics.GRAVITY_PER_TICK, GapJumpPhysics.VERTICAL_DRAG_PER_TICK);
            double fall = Math.max(0.0, apex - segDeltaY);
            Level level = mob.level();
            BlockPos land = new BlockPos(Mth.floor(landing.x), Mth.floor(landing.y + 1.0E-3), Mth.floor(landing.z));
            BlockState below = level.getBlockState(land.below());
            BlockState feet = level.getBlockState(land);
            BlockState head = level.getBlockState(land.above());
            double multiplier = FallDamage.landingMultiplier(below, feet, head);
            float damage = FallMath.damageAfterReductions(p, fall, multiplier);
            float loss = FallMath.healthLoss(p, damage);
            float hp = p.health + p.absorption;
            String sb = (runOff ? "ЗБІГ З КРАЮ (без стрибка)" : "ВІДРИВ (стрибок)") + ": край=" + cell(edge) +
                    " -> приземлення=(" + f2(landing.x) + ',' + f2(landing.y) + ',' + f2(landing.z) +
                    ") клітинка=" + cell(land) + " | Δy=" + f2(segDeltaY) +
                    "\n    падіння≈" + f2(fall) + " бл (апекс=" + f2(apex) + " + глибина " + f2(-segDeltaY) +
                    "; безпечно до " + f2(p.safeFall) + ')' +
                    " | " + describeLanding(below, feet, head) +
                    "\n    шкода≈" + f1(damage) + " HP (втрата здоров'я=" + f1(loss) + ") | HP(+погл.) " +
                    f1(hp) + " -> " + f1(hp - damage) + " | виживе=" + FallMath.survives(p, damage) +
                    " (резерв " + f1(p.reserve) + ')' +
                    "\n    для порівняння ТІЄЇ Ж глибини: на звичайний блок=" + dmg(p, fall, 1.0) +
                    " | сіно/мед=" + dmg(p, fall, 0.2) + " | вода/павутиння/слайм=" + dmg(p, fall, 0.0) +
                    " | ванільний mob.getMaxFallDistance()=" + mob.getMaxFallDistance();
            DebugLog.log(DebugLog.Cat.FALL, mob, sb);
            return new Prediction(fall, damage);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // 3. Спуски в побудованому шляху
    // ------------------------------------------------------------------------------------------

    /**
     * Результат: фактичне падіння й втрата HP проти прогнозу {@link #logSegment}.
     */
    static void logLanding(Mob mob, double airFallMax, float hpAtLaunch, Prediction predicted) {
        if (mob == null || predicted == null || !DebugLog.on(DebugLog.Cat.FALL)) {
            return;
        }
        try {
            float hpNow = mob.getHealth() + mob.getAbsorptionAmount();
            boolean haveLaunch = !Float.isNaN(hpAtLaunch);
            float lost = haveLaunch ? hpAtLaunch - hpNow : Float.NaN;
            StringBuilder sb = new StringBuilder(400);
            sb.append("ПРИЗЕМЛЕННЯ (результат): фактичне падіння (макс. mob.fallDistance у польоті)=").append(f2(airFallMax))
                    .append(" бл, прогноз=").append(f2(predicted.fallBlocks()))
                    .append(" | HP(+погл.) при відриві=").append(haveLaunch ? f1(hpAtLaunch) : "н/д")
                    .append(" -> зараз=").append(f1(hpNow))
                    .append(" (втрата=").append(haveLaunch ? f1(lost) : "н/д").append(", прогноз шкоди=").append(f1(predicted.damage())).append(')');
            if (haveLaunch && Math.abs(lost - predicted.damage()) > 1.0F) {
                sb.append("  <-- прогноз НЕ збігся з фактом (перевір блок приземлення, чари, ефекти, регенерацію, чи не було іншої шкоди)");
            }
            DebugLog.log(DebugLog.Cat.FALL, mob, sb.toString());
        } catch (RuntimeException ignored) {
            // тільки дебаг
        }
    }

    // ------------------------------------------------------------------------------------------
    // 4. Виконавець (GapJumpAssistGoal)
    // ------------------------------------------------------------------------------------------

    /**
     * Падіння моба від 2 блоків за подією гри ({@code LivingFallEvent}, ДО нанесення шкоди) - ВІДСТАНЬ і МНОЖНИК БЛОКА, які
     * застосувала сама гра: це "правда" для порівняння з прогнозом моделі, навіть коли моб зійшов з краю без жодного стрибка.
     */
    public static void logActualFall(Mob mob, float distance, float damageMultiplier) {
        if (!DebugLog.on(DebugLog.Cat.FALL) || !relevant(mob)
                || !DebugLog.throttle("fall-event-" + mob.getId(), 250L)) {
            return;
        }
        try {
            FallMath.Profile p = FallDamage.profileOf(mob);
            float damage = FallMath.damageAfterReductions(p, distance, damageMultiplier);
            String kind = damageMultiplier >= 1.0F ? "звичайний" : damageMultiplier <= 0.0F ? "вода/павутиння/слайм (без шкоди)"
                    : damageMultiplier <= 0.25F ? "сіно/мед" : "ліжко/інше";
            DebugLog.log(DebugLog.Cat.FALL, mob, "ПАДІННЯ (подія гри LivingFallEvent, до нанесення шкоди): відстань=" + f2(distance)
                    + " бл | множник блока (як рахує САМА гра)=" + f2(damageMultiplier) + " (" + kind + ")"
                    + " | шкода за моделлю≈" + f1(damage) + " HP | HP до удару=" + f1(mob.getHealth()) + "+" + f1(mob.getAbsorptionAmount())
                    + " | де=" + cell(mob.blockPosition()) + " | GapJumpAssistGoal активна=" + GapJumpAssistGoal.isActive(mob));
        } catch (RuntimeException ignored) {
            // тільки дебаг
        }
    }

    /**
     * Прогноз падіння/шкоди для сегмента, який збирається виконати {@code GapJumpAssistGoal} (потім порівнюється з фактом).
     */
    record Prediction(double fallBlocks, float damage) {
    }

    // ------------------------------------------------------------------------------------------
    // 5. Будь-яке падіння (подія гри)
    // ------------------------------------------------------------------------------------------

    /**
     * Збирається ЛИШЕ в одному пошуку на моба раз на 2 с ({@code GapJumpNodeEvaluator#prepare}); друкується в
     * {@code GapJumpNodeEvaluator#done}. Рівний паркур без жодного спуску нічого не друкує.
     */
    static final class SearchStats {
        private static final int REL_DOWN = 12;
        private static final int REL_UP = 3;
        private static final int DY_SLOTS = 25; // Δy = 0..-24

        private final Mob mob;
        private final FallMath.Profile profile;
        private final int[] expandedByRel = new int[REL_DOWN + REL_UP + 1];
        private final int[] candByDy = new int[DY_SLOTS];
        private final int[] lethalByDy = new int[DY_SLOTS];
        private final int[] landKinds = new int[7];
        int deepTriggered;
        int deepSkippedFree;
        private int startY = Integer.MIN_VALUE;
        private int expanded;
        private int scanned;
        private int candFlatOrUp;
        private float bestDamage = Float.MAX_VALUE;
        private String bestDesc;
        private float rejectedBestLoss = Float.MAX_VALUE;
        private String rejectedDesc;

        SearchStats(Mob mob, FallMath.Profile profile) {
            this.mob = mob;
            this.profile = profile;
        }

        private static String describeEdge(BlockPos origin, int dx, int dz, int dy) {
            BlockPos landing = origin.offset(dx, dy, dz);
            return cell(origin) + " -> " + cell(landing) + " Δy=" + dy + " відстань=" + f1(Math.sqrt((double) dx * dx + (double) dz * dz));
        }

        private static String levelsText(int[] counts) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < counts.length; i++) {
                if (counts[i] > 0) {
                    sb.append(" -").append(i).append(':').append(counts[i]);
                }
            }
            return sb.length() == 0 ? " немає" : sb.toString();
        }

        void onExpand(Node node) {
            this.expanded++;
            if (this.startY == Integer.MIN_VALUE) {
                this.startY = node.y; // перший розкритий вузол - стартовий
            }
            int rel = Mth.clamp(node.y - this.startY, -REL_DOWN, REL_UP);
            this.expandedByRel[rel + REL_DOWN]++;
        }

        void onScan() {
            this.scanned++;
        }

        /**
         * Кандидати, що лишились після сканування рівнів (до розподілу буфера сусідів). {@code sizes[dy + downLimit]}.
         */
        void onLevels(int[] sizes, int downLimit) {
            for (int i = 0; i < sizes.length; i++) {
                int dy = i - downLimit;
                if (dy < 0) {
                    int slot = Math.min(-dy, DY_SLOTS - 1);
                    this.candByDy[slot] += sizes[i];
                } else {
                    this.candFlatOrUp += sizes[i];
                }
            }
        }

        /**
         * Кандидат спуску потрапив у граф (з його шкодою й блоком приземлення).
         */
        void onCandidate(BlockPos origin, int dx, int dz, int dy, float damage, BlockState below, BlockState feet, BlockState head) {
            this.landKinds[landingKind(below, feet, head)]++;
            if (damage < this.bestDamage) {
                this.bestDamage = damage;
                this.bestDesc = describeEdge(origin, dx, dz, dy) + " | шкода=" + f1(damage) + " HP | " + describeLanding(below, feet, head);
            }
        }

        /**
         * Приземлення відсічено як смертельне.
         */
        void onLethal(BlockPos origin, int dx, int dz, int dy, float damage, float healthLoss,
                      BlockState below, BlockState feet, BlockState head) {
            int slot = Math.min(-dy, DY_SLOTS - 1);
            this.lethalByDy[slot]++;
            if (healthLoss < this.rejectedBestLoss) {
                this.rejectedBestLoss = healthLoss;
                float left = this.profile == null ? Float.NaN : this.profile.health - healthLoss;
                this.rejectedDesc = describeEdge(origin, dx, dz, dy) + " | шкода=" + f1(damage) + " HP (втрата здоров'я=" + f1(healthLoss)
                        + ", лишилось би " + f1(left) + ", резерв " + (this.profile == null ? "?" : f1(this.profile.reserve)) + ") | "
                        + describeLanding(below, feet, head);
            }
        }

        void flush(int downLimit, float costPerHp) {
            try {
                boolean anyDescent = this.deepTriggered > 0;
                for (int i = 1; i < DY_SLOTS && !anyDescent; i++) {
                    anyDescent = this.candByDy[i] > 0 || this.lethalByDy[i] > 0;
                }
                if (!anyDescent) {
                    return; // рівний паркур / спусків нема - не шумимо
                }
                StringBuilder sb = new StringBuilder(900);
                sb.append("ПОШУК ШЛЯХУ (один пошук): вузлів розкрито=").append(this.expanded).append(", зі скануванням стрибків=")
                        .append(this.scanned).append(" | старт Y=").append(this.startY).append(" | нижній предел=-").append(downLimit)
                        .append(" | вартість HP в A*=").append(f1(costPerHp))
                        .append(" | профіль=").append(this.profile == null ? "НЕМАЄ (стара логіка)" : "HP=" + f1(this.profile.health));

                sb.append("\n    розкрито вузлів за рівнем (Y відносно старту):");
                int lowestRel = Integer.MAX_VALUE;
                for (int i = 0; i < this.expandedByRel.length; i++) {
                    if (this.expandedByRel[i] > 0) {
                        int rel = i - REL_DOWN;
                        sb.append(' ').append(rel > 0 ? "+" : "").append(rel).append(':').append(this.expandedByRel[i]);
                        lowestRel = Math.min(lowestRel, rel);
                    }
                }
                if (lowestRel == Integer.MAX_VALUE) {
                    sb.append(" немає");
                } else {
                    sb.append(" | найнижчий розкритий рівень=").append(lowestRel).append(" (Y=").append(this.startY + lowestRel).append(')');
                    if (lowestRel >= 0) {
                        sb.append("  <-- A* НЕ розкрив жодного вузла нижче за старт: нижню точку відриву він не шукав");
                    }
                }

                sb.append("\n    кандидатів стрибка ВНИЗ за Δy:").append(levelsText(this.candByDy))
                        .append(" | рівних/вгору=").append(this.candFlatOrUp);
                sb.append("\n    відсічено як СМЕРТЕЛЬНІ за Δy:").append(levelsText(this.lethalByDy));
                sb.append("\n    приземлення кандидатів униз за видом: звичайний=").append(this.landKinds[K_NORMAL])
                        .append(" сіно/мед=").append(this.landKinds[K_HAY])
                        .append(" ліжко=").append(this.landKinds[K_BED])
                        .append(" вода=").append(this.landKinds[K_WATER])
                        .append(" павутиння=").append(this.landKinds[K_WEB])
                        .append(" слайм=").append(this.landKinds[K_SLIME])
                        .append(" драбина/ліани(не враховано)=").append(this.landKinds[K_CLIMB]);
                sb.append("\n    глибоке сканування (Δy < -").append(GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS).append("): запущено=")
                        .append(this.deepTriggered).append(" разів; ПРОПУЩЕНО, бо вже було безкоштовне приземлення (у БУДЬ-ЯКОМУ напрямку від вузла)=")
                        .append(this.deepSkippedFree);
                if (this.bestDesc != null) {
                    sb.append("\n    найменша шкода серед кандидатів вниз: ").append(this.bestDesc);
                }
                if (this.rejectedDesc != null) {
                    sb.append("\n    найменш смертельне з відсічених: ").append(this.rejectedDesc);
                }
                DebugLog.log(DebugLog.Cat.FALL, this.mob, sb.toString());
            } catch (RuntimeException ignored) {
                // дебаг не повинен ламати пошук шляху
            }
        }
    }
}
