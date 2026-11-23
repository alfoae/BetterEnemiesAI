package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Config;
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
import net.minecraft.world.entity.player.Player;
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
     * Лічильники м'яких приземлень: влучив у м'яку клітинку чи ні.
     */
    private static final Map<Mob, int[]> SOFT_COUNTS = new WeakHashMap<>();

    // ------------------------------------------------------------------------------------------
    // Форматування
    // ------------------------------------------------------------------------------------------

    /**
     * Лише моби, що когось переслідують/шукають або саме виконують стрибок: пошук шляху йде в усіх наземних мобів світу, і без цього
     * фільтра лог заливали б корови з вівцями.
     */
    static boolean relevant(Mob mob) {
        return mob != null && (mob.getTarget() != null || PursuitEnemyBehavior.getChasePosition(mob) != null
                || GapJumpAssistGoal.isActive(mob));
    }

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
        if (feet.is(BlockTags.CLIMBABLE)) {
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
            case K_CLIMB -> "драбина/ліани (0, гра скидає падіння)";
            default -> "звичайний (x1.0)";
        };
    }
    /**
     * Підсумок останнього пошуку шляху моба: {tick, відкинуто смертельних спусків, додано м'яких приземлень}.
     */
    private static final Map<Mob, int[]> SEARCH_HINT = new WeakHashMap<>();
    private static int softHits;
    private static int softMisses;

    // ------------------------------------------------------------------------------------------
    // 1. Профіль падіння (початок пошуку шляху)
    // ------------------------------------------------------------------------------------------

    /** Профіль побудовано: HP/чари/ефекти, скільки моб переживе, нижній предел і таблиця шкоди за глибиною. */
    static void logProfile(Mob mob, FallMath.Profile p, int cap, double apex, int survivable, int downLimit, float costPerHp) {
        try {
            String sig = f1(p.health()) + "|" + f1(p.absorption()) + "|" + f1(p.reserve()) + "|" + f2(p.safeFall()) + "|" + f2(p.fallMultiplier())
                    + "|" + p.resistanceLevel() + "|" + f1(p.protection()) + "|" + p.immune() + "|" + downLimit + "|" + mob.getMaxFallDistance();
            boolean changed = !sig.equals(LAST_PROFILE_SIG.get(mob));
            // той самий профіль - не частіше разу на 5 с; змінився (HP міняється від кожного удару) - не частіше разу на 1 с
            if (!DebugLog.throttle("fall-profile-" + mob.getId(), changed ? 1000L : 5000L)) {
                return;
            }
            LAST_PROFILE_SIG.put(mob, sig);

            StringBuilder sb = new StringBuilder(1100);
            sb.append("ПРОФІЛЬ ПАДІННЯ (початок пошуку шляху)").append(changed ? " [ЗМІНИВСЯ]" : "");
            sb.append("\n    HP=").append(f1(p.health())).append('/').append(f1(mob.getMaxHealth()))
                    .append(" +поглинання=").append(f1(p.absorption()))
                    .append(" | резерв після падіння=").append(f1(p.reserve()))
                    .append(" | безпечна висота=").append(f2(p.safeFall()))
                    .append(" | множник шкоди=").append(f2(p.fallMultiplier()))
                    .append(" | Опір=").append(p.resistanceLevel() == 0 ? "нема" : "рівень " + p.resistanceLevel())
                    .append(" | захист чарами (Невагомість/Захист)=").append(f1(p.protection()))
                    .append(" | імунітет=").append(p.immune());
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
                    .append(Config.DROP_DAMAGE_AWARE.get()
                            ? " - ним обмежені ПІШІ ребра спуску (збіг з краю); A* рахує для них шкоду (HP, чари, блок унизу): dropDamageAware=true"
                            : " - ним обмежені ПІШІ ребра спуску (збіг з краю): вони НЕ рахують ні HP, ні чари, ні блок унизу (dropDamageAware=false)");

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

    /** Профіль не будується, бо вимкнено в конфігу ({@code fallDamageAwareJumps=false}). */
    static void logProfileOff(Mob mob) {
        if (!DebugLog.throttle("fall-profile-off-" + mob.getId(), 10000L)) {
            return;
        }
        DebugLog.log(DebugLog.Cat.FALL, mob, "ПРОФІЛЬ ПАДІННЯ ВИМКНЕНО: fallDamageAwareJumps=false -> стара логіка: фіксований нижній предел -"
                + GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS + ", без штрафів за шкоду й без відсікання смертельних приземлень");
    }

    /** Профіль НЕ побудовано через виняток (раніше це мовчки перемикало пошук на стару логіку). */
    static void logProfileFailed(Mob mob, RuntimeException failure) {
        if (!DebugLog.throttle("fall-profile-fail-" + mob.getId(), 5000L)) {
            return;
        }
        StackTraceElement[] trace = failure == null ? new StackTraceElement[0] : failure.getStackTrace();
        DebugLog.log(DebugLog.Cat.FALL, mob, "!!! ПРОФІЛЬ ПАДІННЯ НЕ ПОБУДОВАНО - виняток у FallDamage.profileOf; пошук іде за СТАРОЮ логікою"
                + " (нижній предел -" + GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS + ", без штрафів і без відсікання смертельних): "
                + failure + (trace.length > 0 ? " @ " + trace[0] : ""));
    }

    // ------------------------------------------------------------------------------------------
    // 2. Статистика одного пошуку шляху (GapJumpNodeEvaluator)
    // ------------------------------------------------------------------------------------------

    /**
     * Що під/в клітинці приземлення й який множник шкоди з цього виходить за {@link FallDamage#landingMultiplier}.
     */
    static String describeLanding(BlockState below, BlockState feet, BlockState head) {
        double multiplier = FallLanding.multiplier(below, feet, head);
        int kind = landingKind(below, feet, head);
        String sb = "блоки: під=" + blockName(below) + " ноги=" + blockName(feet) +
                " голова=" + blockName(head) + " | вид=" + kindName(kind) +
                " | множник у моделі=" + f2(multiplier);
        return sb;
    }

    // ------------------------------------------------------------------------------------------
    // 3. Спуски в побудованому шляху
    // ------------------------------------------------------------------------------------------

    private static String dmg(FallMath.Profile p, double fall, double multiplier) {
        float damage = FallMath.damageAfterReductions(p, fall, multiplier);
        return f1(damage) + (FallMath.survives(p, damage) ? "" : "✗");
    }

    // ------------------------------------------------------------------------------------------
    // 4. Виконавець (GapJumpAssistGoal)
    // ------------------------------------------------------------------------------------------

    private static double normalApex() {
        return FallMath.apexHeight(GapJumpPhysics.JUMP_VERTICAL_VELOCITY, GapJumpPhysics.GRAVITY_PER_TICK,
                GapJumpPhysics.VERTICAL_DRAG_PER_TICK);
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
                boolean rnj = b instanceof DropNode; // v23: спуск на сусідню клітинку під керуванням RNJ
                boolean jump = b instanceof HopNode || rnj || dx * dx + dz * dz > thr;
                if (jump ? dy >= 0 : dy > -2) {
                    continue; // нас цікавлять лише падіння: стрибки вниз і зриви від 2 блоків (сходинка в 1 блок - не падіння)
                }
                descents++;
                if (!jump) {
                    walkOffs++;
                }
                double fall = jump && !rnj ? FallMath.fallBlocks(apex, dy) : -dy;
                BlockPos landing = new BlockPos(b.x, b.y, b.z);
                BlockState below = level.getBlockState(landing.below());
                BlockState feet = level.getBlockState(landing);
                BlockState head = level.getBlockState(landing.above());
                double multiplier = FallLanding.multiplier(below, feet, head);
                float damage = FallMath.damageAfterReductions(p, fall, multiplier);
                totalLoss += FallMath.healthLoss(p, damage);
                if (firstY == Integer.MIN_VALUE) {
                    firstY = a.y;
                }
                edges.append("\n    ребро #").append(i).append(": (").append(a.x).append(',').append(a.y).append(',').append(a.z)
                        .append(") -> (").append(b.x).append(',').append(b.y).append(',').append(b.z).append(") Δy=").append(dy)
                        .append(rnj ? " СПУСК-RNJ (DropNode: збіг з краю під керуванням RNJ, граф рахував шкоду)"
                                : jump ? " СТРИБОК (граф рахував шкоду)"
                                : Config.DROP_DAMAGE_AWARE.get() ? " ПІШКИ-ЗРИВ (ребро спуску: A* рахує шкоду, dropDamageAware=true)"
                                : " ПІШКИ-ЗРИВ (ванільне ребро: A* шкоду НЕ рахував, dropDamageAware=false)")
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
                    " | сумарна очікувана втрата≈" + f1(totalLoss) + " HP при HP=" + f1(p.health()) +
                    "+" + f1(p.absorption()) + " | ванільний mob.getMaxFallDistance()=" + mob.getMaxFallDistance() +
                    " | вузлів=" + nodes + " canReach=" + path.canReach() +
                    edges;
            DebugLog.log(DebugLog.Cat.FALL, mob, sb);
        } catch (RuntimeException ignored) {
            // дебаг не повинен ламати пошук шляху
        }
    }

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
            double multiplier = FallLanding.multiplier(below, feet, head);
            float damage = FallMath.damageAfterReductions(p, fall, multiplier);
            float loss = FallMath.healthLoss(p, damage);
            float hp = p.health() + p.absorption();
            String sb = (runOff ? "ЗБІГ З КРАЮ (без стрибка)" : "ВІДРИВ (стрибок)") + ": край=" + cell(edge) +
                    " -> приземлення=(" + f2(landing.x) + ',' + f2(landing.y) + ',' + f2(landing.z) +
                    ") клітинка=" + cell(land) + " | Δy=" + f2(segDeltaY) +
                    "\n    падіння≈" + f2(fall) + " бл (апекс=" + f2(apex) + " + глибина " + f2(-segDeltaY) +
                    "; безпечно до " + f2(p.safeFall()) + ')' +
                    " | " + describeLanding(below, feet, head) +
                    "\n    шкода≈" + f1(damage) + " HP (втрата здоров'я=" + f1(loss) + ") | HP(+погл.) " +
                    f1(hp) + " -> " + f1(hp - damage) + " | виживе=" + FallMath.survives(p, damage) +
                    " (резерв " + f1(p.reserve()) + ')' +
                    "\n    для порівняння ТІЄЇ Ж глибини: на звичайний блок=" + dmg(p, fall, 1.0) +
                    " | сіно/мед=" + dmg(p, fall, 0.2) + " | вода/павутиння/слайм=" + dmg(p, fall, 0.0) +
                    " | ванільний mob.getMaxFallDistance()=" + mob.getMaxFallDistance();
            DebugLog.log(DebugLog.Cat.FALL, mob, sb);
            return new Prediction(fall, damage, multiplier < 1.0, landing.x, landing.z);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Моб зараз У м'якій клітинці (вода, драбина) або стоїть на сіні/ліжку/меду/сліймі - тобто приземлення вдалось.
     */
    private static boolean softContactNow(Mob mob) {
        if (mob.isInWater() || mob.onClimbable()) {
            return true;
        }
        BlockPos pos = mob.blockPosition();
        Level level = mob.level();
        return FallLanding.multiplier(level.getBlockState(pos.below()), level.getBlockState(pos), level.getBlockState(pos.above())) < 1.0;
    }

    /**
     * Результат: фактичне падіння, втрата HP й відхилення від центру проти прогнозу {@link #logSegment}; лічильники влучань.
     */
    static void logLanding(Mob mob, double airFallMax, float hpAtLaunch, Prediction predicted) {
        if (mob == null || predicted == null || !DebugLog.on(DebugLog.Cat.FALL)) {
            return;
        }
        try {
            float hpNow = mob.getHealth() + mob.getAbsorptionAmount();
            boolean haveLaunch = !Float.isNaN(hpAtLaunch);
            float lost = haveLaunch ? hpAtLaunch - hpNow : Float.NaN;
            double miss = Math.hypot(mob.getX() - predicted.landX(), mob.getZ() - predicted.landZ());
            StringBuilder sb = new StringBuilder(500);
            sb.append("ПРИЗЕМЛЕННЯ (результат): фактичне падіння (макс. mob.fallDistance у польоті)=").append(f2(airFallMax))
                    .append(" бл, прогноз=").append(f2(predicted.fallBlocks()))
                    .append(" | HP(+погл.) при відриві=").append(haveLaunch ? f1(hpAtLaunch) : "н/д")
                    .append(" -> зараз=").append(f1(hpNow))
                    .append(" (втрата=").append(haveLaunch ? f1(lost) : "н/д").append(", прогноз шкоди=").append(f1(predicted.damage())).append(')')
                    .append(" | відхилення від ЦЕНТРА запланованого приземлення=").append(f2(miss)).append(" бл");
            if (haveLaunch && Math.abs(lost - predicted.damage()) > 1.0F) {
                sb.append("  <-- прогноз НЕ збігся з фактом (перевір блок приземлення, чари, ефекти, регенерацію, чи не було іншої шкоди)");
            }
            if (predicted.soft()) {
                boolean hit = softContactNow(mob);
                int[] counts = SOFT_COUNTS.computeIfAbsent(mob, k -> new int[2]);
                counts[hit ? 0 : 1]++;
                if (hit) {
                    softHits++;
                } else {
                    softMisses++;
                }
                sb.append("\n    М'ЯКЕ ПРИЗЕМЛЕННЯ: ").append(hit ? "ВЛУЧИВ" : "ПРОМАХНУВСЯ (приземлився не в м'яку клітинку)")
                        .append(" | цей моб: влучань=").append(counts[0]).append(", промахів=").append(counts[1])
                        .append(" | усього: влучань=").append(softHits).append(", промахів=").append(softMisses);
            }
            DebugLog.log(DebugLog.Cat.FALL, mob, sb.toString());
        } catch (RuntimeException ignored) {
            // тільки дебаг
        }
    }

    /**
     * Підсумок пошуку шляху (викликається з evaluator.done()) - щоб потім сказати, чи моб застряг через смертельні спуски.
     */
    static void noteSearch(Mob mob, int lethalRemoved, int softAdded) {
        if (mob != null) {
            SEARCH_HINT.put(mob, new int[]{mob.tickCount, lethalRemoved, softAdded});
        }
    }

    /**
     * ЗАСТРЯГ: ціль нижче, шлях недосяжний, а останній пошук відкинув смертельні спуски й не знайшов жодного м'якого приземлення -
     * безпечного шляху вниз нема. Повідомляємо в чат гравцеві й у лог; моб при цьому лишається в агро (пам'ять переслідування
     * не скидається). v26: "будувати спуск" уже є - спуск виступами на стіні ({@link LedgeDescent}); тож це повідомлення тепер
     * означає, що й виступи неможливі. TODO: чекати регенерації чи йти довшим обхідним шляхом; поки лише повідомлення (за домовленістю).
     */
    public static void reportStuckIfLethal(Mob mob, Path path) {
        if (!DebugLog.on(DebugLog.Cat.FALL) || !relevant(mob) || (path != null && path.canReach())) {
            return;
        }
        int[] hint = SEARCH_HINT.get(mob);
        if (hint == null || mob.tickCount - hint[0] > 60 || hint[1] <= 0 || hint[2] > 0) {
            return;
        }
        if (!DebugLog.throttle("fall-stuck-" + mob.getId(), 15000L)) {
            return;
        }
        try {
            String msg = "[УВАГА] моб застряг: усі спуски до гравця СМЕРТЕЛЬНІ при HP=" + f1(mob.getHealth()) + "+" + f1(mob.getAbsorptionAmount())
                    + " (відкинуто смертельних ребер=" + hint[1] + "), м'якого приземлення поблизу нема, а спуск виступами (v26) неможливий: нема стіни"
                    + " біля краю чи нема куди сходити, вимкнено (ledgeDescent / enableMobTerraforming / mobGriefing) або моб ширший за 0.9."
                    + " Моб лишається в агро. TODO: чекати регенерації/обхід";
            DebugLog.log(DebugLog.Cat.FALL, mob, msg);
            Player player = PursuitEnemyBehavior.getTrackedPlayer(mob);
            if (player != null) {
                PursuitEnemyBehavior.sendChatOnly(player, "[DEBUG FALL] " + msg);
            }
        } catch (RuntimeException ignored) {
            // тільки дебаг
        }
    }

    /**
     * Де моб відносно шляху: найближчий вузол і "наступний" - щоб бачити, чи зійшов він зі шляху (зліт зі сходів і т.п.).
     */
    private static String pathDeviation(Mob mob) {
        Path p = mob.getNavigation().getPath();
        if (p == null || p.getNodeCount() == 0) {
            return "шляху нема";
        }
        double best = Double.MAX_VALUE;
        int bestIndex = -1;
        for (int i = 0; i < p.getNodeCount(); i++) {
            Node n = p.getNode(i);
            double dx = n.x + 0.5 - mob.getX();
            double dy = n.y - mob.getY();
            double dz = n.z + 0.5 - mob.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) {
                best = d;
                bestIndex = i;
            }
        }
        int next = Math.min(p.getNextNodeIndex(), p.getNodeCount() - 1);
        Node nn = p.getNode(next);
        double dn = Math.sqrt((nn.x + 0.5 - mob.getX()) * (nn.x + 0.5 - mob.getX()) + (nn.y - mob.getY()) * (nn.y - mob.getY())
                + (nn.z + 0.5 - mob.getZ()) * (nn.z + 0.5 - mob.getZ()));
        double db = Math.sqrt(best);
        return "шлях: найближчий вузол [" + bestIndex + "] на " + f2(db) + " бл, \"наступний\" [" + next + "] на " + f2(dn) + " бл"
                + (db > 1.5 ? "  <-- моб ПОЗА шляхом" : "");
    }

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
                    + " | де=" + cell(mob.blockPosition()) + " | GapJumpAssistGoal активна=" + GapJumpAssistGoal.isActive(mob)
                    + " | " + pathDeviation(mob));
        } catch (RuntimeException ignored) {
            // тільки дебаг
        }
    }

    /**
     * Прогноз падіння/шкоди для сегмента, який збирається виконати {@code GapJumpAssistGoal} (потім порівнюється з фактом).
     */
    record Prediction(double fallBlocks, float damage, boolean soft, double landX, double landZ) {
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
        private int startY = Integer.MIN_VALUE;
        private int expanded;
        private int scanned;
        private final int[] expandedByRel = new int[REL_DOWN + REL_UP + 1];
        private final int[] candByDy = new int[DY_SLOTS];
        private final int[] lethalByDy = new int[DY_SLOTS];
        private final int[] landKinds = new int[7];
        private final int[] dropKinds = new int[7];
        private int candFlatOrUp;
        int deepTriggered;
        private float bestDamage = Float.MAX_VALUE;
        private String bestDesc;
        private float rejectedBestLoss = Float.MAX_VALUE;
        private String rejectedDesc;
        // v22: ванільні ребра спуску (зрив з краю від 2 блоків)
        private int dropsSeen;
        private int dropsFree;
        private int dropsPenalized;
        private int dropsRemoved;
        int deepSkippedFree;
        private float cheapestDropDamage = Float.MAX_VALUE;
        private String cheapestDropDesc;
        private float removedBestLoss = Float.MAX_VALUE;
        private String removedDropDesc;
        // v23: м'які приземлення (RNJ)
        private int softSearches;
        private int softCandidates;
        private int softAdded;
        private boolean softPresent;
        private int softIndexSize;
        private int softRunUpRejected;
        private int hardAdded;
        private int hardLethal;
        private boolean hardOn;

        SearchStats(Mob mob, FallMath.Profile profile) {
            this.mob = mob;
            this.profile = profile;
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

        /** Кандидати, що лишились після сканування рівнів (до розподілу буфера сусідів). {@code sizes[dy + downLimit]}. */
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

        /** Кандидат спуску потрапив у граф (з його шкодою й блоком приземлення). */
        void onCandidate(BlockPos origin, int dx, int dz, int dy, float damage, BlockState below, BlockState feet, BlockState head) {
            this.landKinds[landingKind(below, feet, head)]++;
            if (damage < this.bestDamage) {
                this.bestDamage = damage;
                this.bestDesc = describeEdge(origin, dx, dz, dy) + " | шкода=" + f1(damage) + " HP | " + describeLanding(below, feet, head);
            }
        }

        /** Приземлення відсічено як смертельне. */
        void onLethal(BlockPos origin, int dx, int dz, int dy, float damage, float healthLoss,
                      BlockState below, BlockState feet, BlockState head) {
            int slot = Math.min(-dy, DY_SLOTS - 1);
            this.lethalByDy[slot]++;
            if (healthLoss < this.rejectedBestLoss) {
                this.rejectedBestLoss = healthLoss;
                float left = this.profile == null ? Float.NaN : this.profile.health() - healthLoss;
                this.rejectedDesc = describeEdge(origin, dx, dz, dy) + " | шкода=" + f1(damage) + " HP (втрата здоров'я=" + f1(healthLoss)
                        + ", лишилось би " + f1(left) + ", резерв " + (this.profile == null ? "?" : f1(this.profile.reserve())) + ") | "
                        + describeLanding(below, feet, head);
            }
        }

        /** Вузол звернувся до індексу м'яких приземлень: скільки кандидатів знайдено, скільки додано в граф. */
        void onSoft(int candidates, int added, boolean present, int indexSize) {
            this.softSearches++;
            this.softCandidates += candidates;
            this.softAdded += added;
            this.softPresent |= present;
            this.softIndexSize = indexSize;
        }

        /** v24: скільки стрибків у м'яке відкинуто через замалий розбіг (коротка платформа позаду краю). */
        void onRunUp(int rejected) {
            this.softRunUpRejected = rejected;
        }

        /** v25: твердих спусків впритик (в т.ч. кутових) додано в граф і скільки відкинуто як смертельні. */
        void onHard(boolean enabled, int added, int lethal) {
            this.hardOn = enabled;
            this.hardAdded = added;
            this.hardLethal = lethal;
        }

        /** Ребро спуску (зрив з краю) розглянуто: безкоштовне, зі штрафом чи відкинуте як смертельне. */
        void onDrop(Node from, Node to, int drop, float damage, float healthLoss, boolean lethal,
                    BlockState below, BlockState feet, BlockState head) {
            this.dropsSeen++;
            this.dropKinds[landingKind(below, feet, head)]++;
            String desc = "(" + from.x + "," + from.y + "," + from.z + ") -> (" + to.x + "," + to.y + "," + to.z + ") спуск=" + drop
                    + " бл | шкода=" + f1(damage) + " HP | " + describeLanding(below, feet, head);
            if (lethal) {
                this.dropsRemoved++;
                if (healthLoss < this.removedBestLoss) {
                    this.removedBestLoss = healthLoss;
                    this.removedDropDesc = desc + " | втрата здоров'я=" + f1(healthLoss);
                }
            } else if (damage <= 0.0F) {
                this.dropsFree++;
            } else {
                this.dropsPenalized++;
            }
            if (!lethal && damage < this.cheapestDropDamage) {
                this.cheapestDropDamage = damage;
                this.cheapestDropDesc = desc;
            }
        }

        void flush(int downLimit, float costPerHp, boolean dropAware) {
            try {
                boolean anyDescent = this.deepTriggered > 0 || this.dropsSeen > 0 || this.softSearches > 0;
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
                        .append(" | спуски-ребра з урахуванням шкоди=").append(dropAware)
                        .append(" | профіль=").append(this.profile == null ? "НЕМАЄ (стара логіка)" : "HP=" + f1(this.profile.health()));

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
                        .append(" драбина/ліани=").append(this.landKinds[K_CLIMB]);
                sb.append("\n    глибоке сканування (Δy < -").append(GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS).append("): запущено=")
                        .append(this.deepTriggered).append(" разів; ПРОПУЩЕНО, бо вже було безкоштовне приземлення (у БУДЬ-ЯКОМУ напрямку від вузла)=")
                        .append(this.deepSkippedFree);
                if (this.bestDesc != null) {
                    sb.append("\n    найменша шкода серед кандидатів вниз: ").append(this.bestDesc);
                }
                if (this.rejectedDesc != null) {
                    sb.append("\n    найменш смертельне з відсічених: ").append(this.rejectedDesc);
                }
                if (this.dropsSeen > 0) {
                    sb.append("\n    ПІШКИ-спуски (ванільні ребра зриву з краю, від 2 блоків): розглянуто=").append(this.dropsSeen)
                            .append(" | безкоштовні=").append(this.dropsFree)
                            .append(" | зі штрафом=").append(this.dropsPenalized)
                            .append(" | ВІДКИНУТО смертельних=").append(this.dropsRemoved)
                            .append(" | за видом приземлення: звичайний=").append(this.dropKinds[K_NORMAL])
                            .append(" сіно/мед=").append(this.dropKinds[K_HAY])
                            .append(" ліжко=").append(this.dropKinds[K_BED])
                            .append(" вода=").append(this.dropKinds[K_WATER])
                            .append(" павутиння=").append(this.dropKinds[K_WEB])
                            .append(" слайм=").append(this.dropKinds[K_SLIME])
                            .append(" драбина/ліани=").append(this.dropKinds[K_CLIMB]);
                    if (this.cheapestDropDesc != null) {
                        sb.append("\n    найдешевший спуск: ").append(this.cheapestDropDesc);
                    }
                    if (this.removedDropDesc != null) {
                        sb.append("\n    найменш смертельний із відкинутих спусків: ").append(this.removedDropDesc);
                    }
                }
                if (this.softSearches > 0) {
                    sb.append("\n    М'ЯКІ приземлення (v23): вузлів із пошуком=").append(this.softSearches)
                            .append(" | клітинок в індексі=").append(this.softIndexSize)
                            .append(" | кандидатів стрибка=").append(this.softCandidates)
                            .append(" | додано в граф=").append(this.softAdded)
                            .append(" | серед ребер спуску вже було м'яке=").append(this.softPresent)
                            .append(" | відсічено розбігом=").append(this.softRunUpRejected);
                }
                if (this.hardOn) {
                    sb.append("\n    ТВЕРДІ спуски впритик (v25, незалежно від getMaxFallDistance): додано в граф=").append(this.hardAdded)
                            .append(" | відкинуто як смертельні=").append(this.hardLethal);
                }
                DebugLog.log(DebugLog.Cat.FALL, this.mob, sb.toString());
            } catch (RuntimeException ignored) {
                // дебаг не повинен ламати пошук шляху
            }
        }
    }
}
