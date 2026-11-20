package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Config;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.EnemyBreak_N_BuildUtils;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.HopNode;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapePathAccess;
import com.example.examplemod.debug.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * v22: виконання ПЛАНОВИХ спусків і вирівнювання вузлів шляху - викликається з {@code PathNavigationMixin}, лише для мобів, які
 * переслідують гравця ({@link PursuitEnemyBehavior#getChasePosition}).
 * <ul>
 *   <li><b>Підхід до краю</b> ({@link #onNavigationTick}, моб на землі): якщо наступне ребро шляху - спуск від 2 блоків на МЯКЕ
 *       приземлення (вода, сіно, мед, ліжко, драбина, ліани) у сусідню колонку, моб підходить до краю без спринту й повільно -
 *       зі спринту він знімається з краю з інерцією ~0.3 блока/тік і за ~16 тіків падіння промахується на 3-4 блоки.</li>
 *   <li><b>Корекція в польоті</b> (той самий метод, моб у повітрі): падаючи до такого приземлення, моб м'яко тягнеться до центру
 *       клітинки ({@link DropMath#steer}) - вода в 1 блок має шанс.</li>
 *   <li><b>Ресинхронізація шляху</b> ({@link #resync}): якщо моб фізично пройшов кілька вузлів (збіг по сходах), а індекс
 *       "наступного" лишився позаду й задалеко (&gt; 2 блоків) - ванільний {@code shouldTargetNextNodeInDirection} уже не
 *       спрацьовує, і моб тупцює під сходинкою, поки шлях не перерахується (в логу це "ЗАВИСАННЯ" на ~10 с).</li>
 * </ul>
 * Звичайні падіння на тверде приземлення не чіпаються - це лишається ванільним рухом; вибір ЧИ падати вирішує A*
 * ({@code GapJumpNodeEvaluator#applyDropCosts}).
 */
public final class DropAssist {

    /**
     * Від скількох блоків висоти ребро вважається спуском.
     */
    private static final double MIN_DROP_BLOCKS = 2.0;
    /** Ребро спуску "сусідня колонка" (стрибки на далі - справа GapJumpAssistGoal): квадрат 1.5. */
    private static final double MAX_ADJACENT_SQ = 2.25;
    /** З якої відстані до вузла краю моб починає повільний підхід: квадрат 2.5. */
    private static final double EDGE_APPROACH_DIST_SQ = 6.25;
    /** Множник швидкості повільного підходу (ходьба зі спринту ~1.3 -> повзе ~0.4). */
    private static final double SLOW_SPEED_MODIFIER = 0.4;
    /** Не ближче/далі за стільки блоків по горизонталі вузол приземлення вважається "тією колонкою": квадрат 4. */
    private static final double AIR_TARGET_MAX_DIST_SQ = 16.0;

    /** Ресинхронізація: коли моб далі за 2 блоки від поточного вузла (квадрат 4)... */
    private static final double RESYNC_MIN_DIST_SQ = 4.0;
    /** ...і майже стоїть на одному з наступних вузлів (квадрат 1), дивимось не далі ніж на 3 вперед. */
    private static final double RESYNC_NEAR_DIST_SQ = 1.0;
    private static final int RESYNC_LOOKAHEAD = 3;

    private DropAssist() {
    }

    // ------------------------------------------------------------------------------------------
    // Підхід до краю й корекція в польоті
    // ------------------------------------------------------------------------------------------

    /**
     * v25: скільки тіків поспіль моб чекає на старт RNJ (далі їде звичайно - на випадок, якщо RNJ чомусь не стартує).
     */
    private static final int HANDOFF_HOLD_MAX_TICKS = 3;
    /**
     * v25: на якій відстані (по горизонталі) від точки відриву чекаємо на старт RNJ, а не біжимо до краю з розбігом.
     */
    private static final double HANDOFF_NEAR_EDGE = 1.5;
    /**
     * Для кожного моба: {позиція краю (BlockPos.asLong), скільки тіків уже чекав саме на цьому краї}.
     */
    private static final Map<Mob, long[]> HANDOFF_HELD = new WeakHashMap<>();
    /**
     * Скільки вузлів шляху попереду дивимось у пошуку сходів (разом із висотою ніг моба - до 6 значень).
     */
    private static final int STAIR_LOOKAHEAD_NODES = 5;
    private static final Map<Mob, Integer> LAST_REFRESH = new WeakHashMap<>();
    /** Не частіше за стільки тіків просимо свіжий шлях, коли моб поза шляхом. */
    private static final int REFRESH_INTERVAL_TICKS = 20;

    /**
     * v24: на СХОДАХ (щонайменше два спуски по 1 блоку підряд у найближчих вузлах шляху) швидкість моба обмежується, щоб він ішов
     * сходинка за сходинкою, а не летів над ними: у логу всі 7 запусків зі сходів завширшки 1 зривалися вбік на 2-й сходинці
     * (швидкість ~0.6-0.8 бл/тік, а на падіння з сходинки на сходинку потрібно ~4.4 тіки). Гальмувати починаємо за
     * {@link StairBrake#LOOKAHEAD_DIST} блока до першого спуску; після останньої сходинки обмеження знімається саме (спуск
     * одинокого уступа не рахується сходами). Одинокі падіння від 2 блоків (збіг з краю) - справа RNJ/{@link #approachEdge}.
     *
     * @return true, якщо швидкість цього тіку обмежено
     */
    private static boolean brakeOnStairs(Mob mob, Path path) {
        int next = path.getNextNodeIndex();
        int count = path.getNodeCount();
        if (next < 0 || next >= count) {
            return false;
        }
        double[] ys = new double[STAIR_LOOKAHEAD_NODES + 1];
        int n = 0;
        ys[n++] = mob.getY();
        for (int i = 0; i < STAIR_LOOKAHEAD_NODES && next + i < count; i++) {
            Node node = path.getNode(next + i);
            if (node instanceof DropNode || node instanceof HopNode) {
                break; // стрибок/збіг через RNJ - далі не сходи
            }
            ys[n++] = node.y;
        }
        double[] heights = java.util.Arrays.copyOf(ys, n);
        int start = StairBrake.staircaseStart(heights);
        if (start < 0) {
            return false;
        }
        // Від моба до верхнього вузла першого спуску (для start=0 це сам моб): далі за LOOKAHEAD_DIST ще не гальмуємо.
        if (start > 0) {
            Vec3 top = path.getEntityPosAtNode(mob, next + start - 1);
            double dx = top.x - mob.getX();
            double dz = top.z - mob.getZ();
            if (dx * dx + dz * dz > StairBrake.LOOKAHEAD_DIST * StairBrake.LOOKAHEAD_DIST) {
                return false;
            }
        }
        double navModifier = mob.getMoveControl().getSpeedModifier();
        double speedNow = mob.getDeltaMovement().horizontalDistance();
        double attribute = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        double limited = StairBrake.speedModifier(attribute, speedNow, Config.STAIR_DESCENT_MAX_SPEED.get(), navModifier);
        if (limited >= navModifier - 1.0E-6) {
            return false; // моб і так не швидший за ліміт
        }
        // Ціль руху лишається ванільною (куди вів навігатор) - міняється лише множник швидкості.
        mob.getMoveControl().setWantedPosition(mob.getMoveControl().getWantedX(), mob.getMoveControl().getWantedY(),
                mob.getMoveControl().getWantedZ(), limited);
        final double shown = limited;
        final double was = speedNow;
        DebugLog.logThrottled(DebugLog.Cat.FALL, mob, "stair-brake|" + mob.getId(), 700L,
                () -> "ГАЛЬМО НА СХОДАХ: швидкість " + f2(was) + " бл/тік -> множник " + f2(shown) + " (навігатор хотів "
                        + f2(navModifier) + ", ліміт зсуву " + f2(Config.STAIR_DESCENT_MAX_SPEED.get()) + " бл/тік)");
        return true;
    }

    private static void approachEdge(Mob mob, Path path) {
        int next = path.getNextNodeIndex();
        int count = path.getNodeCount();
        Level level = mob.level();
        for (int j = Math.max(1, next); j <= Math.min(next + 1, count - 1); j++) {
            Node edge = path.getNode(j - 1);
            Node landing = path.getNode(j);
            if (edge.y - landing.y < MIN_DROP_BLOCKS || landing instanceof HopNode || landing instanceof DropNode) {
                // DropNode - спуск, який веде RNJ (dropViaRnj); це запасний механізм лише для звичайних вузлів
                continue;
            }
            double abx = landing.x - edge.x;
            double abz = landing.z - edge.z;
            if (abx * abx + abz * abz > MAX_ADJACENT_SQ) {
                continue; // далеке приземлення - це стрибок, його веде GapJumpAssistGoal
            }
            if (!isSoftLanding(level, landing)) {
                return; // тверде приземлення - ванільний рух
            }
            Vec3 edgePos = path.getEntityPosAtNode(mob, j - 1);
            double dx = edgePos.x - mob.getX();
            double dz = edgePos.z - mob.getZ();
            if (dx * dx + dz * dz > EDGE_APPROACH_DIST_SQ) {
                return; // ще далеко від краю
            }
            if (mob.isSprinting()) {
                mob.setSprinting(false);
            }
            Vec3 target = path.getNextEntityPos(mob);
            double distToEdge = Math.sqrt(dx * dx + dz * dz);
            double speed = SLOW_SPEED_MODIFIER + 0.36 * Math.min(1.0, distToEdge / 2.5); // плавно: 0.4 на краю -> 0.76 за 2.5 бл
            mob.getMoveControl().setWantedPosition(target.x, target.y, target.z, speed);
            DebugLog.logThrottled(DebugLog.Cat.FALL, mob, "drop-approach|" + mob.getId(), 700L,
                    () -> "ПІДХІД ДО КРАЮ (повільно, без спринту): м'яке приземлення " + cell(landing) + " на " + (edge.y - landing.y)
                            + " бл нижче | до краю " + cell(edge) + " лишилось=" + f2(Math.sqrt(dx * dx + dz * dz))
                            + " бл | швидкість-множник=" + f2(speed));
            return;
        }
    }

    private static void steerInAir(Mob mob, Path path) {
        Vec3 v = mob.getDeltaMovement();
        if (v.y > -0.1 || mob.fallDistance < 1.0F) {
            return; // не падіння (стрибок угору, початок польоту)
        }
        int next = path.getNextNodeIndex();
        int count = path.getNodeCount();
        Level level = mob.level();
        for (int j = Math.max(0, next - 1); j <= Math.min(next + 1, count - 1); j++) {
            Node landing = path.getNode(j);
            if (landing instanceof DropNode) {
                return; // спуск веде RNJ
            }
            if (landing.y >= mob.getY() - 1.0) {
                continue; // це не посадковий вузол: він ще не нижче за моба
            }
            Vec3 pos = path.getEntityPosAtNode(mob, j);
            double dx = pos.x - mob.getX();
            double dz = pos.z - mob.getZ();
            if (dx * dx + dz * dz > AIR_TARGET_MAX_DIST_SQ || !isSoftLanding(level, landing)) {
                return;
            }
            double[] nv = DropMath.steer(v.x, v.z, dx, dz);
            mob.setDeltaMovement(nv[0], v.y, nv[1]);
            DebugLog.logThrottled(DebugLog.Cat.FALL, mob, "drop-steer|" + mob.getId(), 400L,
                    () -> "КОРЕКЦІЯ В ПОЛЬОТІ до м'якого приземлення " + cell(landing) + ": до центру=" + f2(Math.sqrt(dx * dx + dz * dz))
                            + " бл, швидкість (" + f2(v.x) + "," + f2(v.z) + ") -> (" + f2(nv[0]) + "," + f2(nv[1]) + ") | падіння="
                            + f2(mob.fallDistance) + " бл");
            return;
        }
    }

    /** Приземлення в цю клітинку НЕ завдає повної шкоди (вода, сіно, мед, ліжко, слайм, павутиння, драбина, ліани). */
    private static boolean isSoftLanding(Level level, Node node) {
        BlockPos pos = new BlockPos(node.x, node.y, node.z);
        BlockState below = level.getBlockState(pos.below());
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        return FallLanding.multiplier(below, feet, head) < 1.0;
    }

    // ------------------------------------------------------------------------------------------
    // Ресинхронізація вузлів шляху
    // ------------------------------------------------------------------------------------------

    /** Кликається з {@code PathNavigation#tick} (TAIL) кожен тік; для не-переслідувачів і твердих приземлень нічого не робить. */
    public static void onNavigationTick(Mob mob, Path path) {
        if (mob == null || path == null || path.isDone() || mob.level().isClientSide()) {
            return;
        }
        // v24: при dropViaRnj (за замовчуванням) усі нетривіальні спуски й м'які приземлення веде RNJ (DropNode), а повільний
        // "підхід до краю" з v22 - це милиця, що давала вигляд сповільненого часу: тепер він лише запасний (коли RNJ-спуски вимкнено).
        boolean steering = Config.DROP_AIR_STEERING.get() && !Config.DROP_VIA_RNJ.get();
        boolean stairBrake = Config.STAIR_DESCENT_BRAKE.get();
        if (!steering && !stairBrake) {
            return;
        }
        if (mob.isInWater() || mob.isInLava() || mob.onClimbable() || mob.isPassenger()) {
            return;
        }
        if (GapJumpAssistGoal.isActive(mob) || PursuitEnemyBehavior.getChasePosition(mob) == null) {
            return; // стрибками керує свій гоал; не переслідувачів не чіпаємо
        }
        try {
            if (mob.onGround() && holdForHandoff(mob)) {
                return; // v25: RNJ ось-ось стартує - не біжимо до краю самі
            }
            if (mob.onGround()) {
                if (stairBrake) {
                    brakeOnStairs(mob, path); // v24: на сходах швидкість обмежується (див. StairBrake)
                }
                if (steering) {
                    approachEdge(mob, path); // запасний механізм для м'яких приземлень не через RNJ (повільніший підхід, якщо діє)
                }
            } else if (steering) {
                steerInAir(mob, path);
            }
        } catch (RuntimeException ignored) {
            // допоміжна механіка ніколи не повинна ламати навігацію
        }
    }

    /**
     * v25: у шляху вже є ГОТОВИЙ стрибок/збіг з краю, на якому моб стоїть, але гоал RNJ ще не стартував: GoalSelector вибирає нові
     * цілі раз на 2 тіки, і Pursuit за цей час розганяв моба до краю на повній швидкості (в логу: «ЗАПІЗНІЛЕ КЕРУВАННЯ» - моб за
     * краєм на +0.9 бл зі швидкістю 0.3 бл/тік ще до першого тіку цілі; зі стовпа/сходинки 1x1 це різниця між влученням і промахом).
     * Тому до старту RNJ моб СТОЇТЬ - не довше {@link #HANDOFF_HOLD_MAX_TICKS} тіків поспіль.
     *
     * @return true, якщо рух цього тіку скасовано
     */
    private static boolean holdForHandoff(Mob mob) {
        if (!Config.RNJ_HANDOFF_HOLD.get()) {
            return false;
        }
        GapJumpUtils.GapJump jump = GapJumpUtils.findUpcomingJumpSegment(mob);
        if (jump == null) {
            HANDOFF_HELD.remove(mob);
            return false;
        }
        Vec3 takeoff = jump.takeoffPoint();
        double dx = takeoff.x - mob.getX();
        double dz = takeoff.z - mob.getZ();
        if (dx * dx + dz * dz > HANDOFF_NEAR_EDGE * HANDOFF_NEAR_EDGE) {
            return false; // далеко від краю: RNJ сам поведе на розбіг
        }
        long edgeKey = jump.edge().asLong();
        long[] state = HANDOFF_HELD.get(mob);
        if (state == null || state[0] != edgeKey) {
            state = new long[]{edgeKey, 0L}; // інший край (інший сегмент) - рахуємо спочатку
            HANDOFF_HELD.put(mob, state);
        }
        final int held = (int) state[1];
        if (held >= HANDOFF_HOLD_MAX_TICKS) {
            return false;
        }
        state[1] = held + 1L;
        // ціль руху = поточна позиція: MoveControl обнуляє вхід (zza), моб не розганяється, лишається лише тертя
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0);
        DebugLog.logThrottled(DebugLog.Cat.FALL, mob, "handoff-hold|" + mob.getId(), 700L,
                () -> "ПЕРЕДАЧА КЕРУВАННЯ RNJ: моб стоїть до старту гоала (тік " + (held + 1) + " з " + HANDOFF_HOLD_MAX_TICKS + ")");
        return true;
    }

    /**
     * Кликається з {@code PathNavigation#followThePath} (HEAD), коли моб на землі. v23: шукає по ВСЬОМУ шляху попереду (раніше
     * лише 3 вузли) і, якщо моб поза шляхом (упав зі сходів, нокбек), просить СВІЖИЙ шлях одразу, а не чекає ~10 с
     * (в логу це "ЗАВИСАННЯ"/"залагав": індекс "наступного" стояв угорі сходів, а моб бігав унизу).
     */
    public static void resync(Mob mob, Path path, Vec3 mobPos) {
        if (mob == null || path == null || path.isDone() || mobPos == null || !mob.onGround()) {
            return;
        }
        if (!Config.PATH_RESYNC.get() || PursuitEnemyBehavior.getChasePosition(mob) == null || GapJumpAssistGoal.isActive(mob)) {
            return;
        }
        Object holder = path;
        if (holder instanceof ShapePathAccess access && access.betterEnemies$spots() != null) {
            return; // шлях із точними точками - у нього власний механізм проходу вузлів
        }
        try {
            int next = path.getNextNodeIndex();
            int count = path.getNodeCount();
            if (next < 0 || next >= count) {
                return;
            }
            double dNext = mobPos.distanceToSqr(path.getEntityPosAtNode(mob, next));
            if (dNext <= SoftMath.RESYNC_MIN_DIST_SQ) {
                return; // ванільна логіка ще справляється
            }
            double dPrev = next >= 1 ? mobPos.distanceToSqr(path.getEntityPosAtNode(mob, next - 1)) : dNext;
            int ahead = Math.max(0, count - 1 - next);
            double[] dist = new double[ahead];
            double[] dy = new double[ahead];
            for (int i = 0; i < ahead; i++) {
                Vec3 pi = path.getEntityPosAtNode(mob, next + 1 + i);
                dist[i] = mobPos.distanceToSqr(pi);
                dy[i] = Math.abs(pi.y - mobPos.y);
            }
            int pick = SoftMath.pickResync(dNext, dPrev, dist, dy);
            final double was = Math.sqrt(dNext);
            if (pick >= 1) {
                path.setNextNodeIndex(next + pick);
                final int from = next;
                final int to = next + pick;
                DebugLog.logThrottled(DebugLog.Cat.FALL, mob, "path-resync|" + mob.getId(), 500L,
                        () -> "РЕСИНХРОНІЗАЦІЯ ШЛЯХУ: моб уже біля вузла [" + to + "], а \"наступним\" лишався [" + from + "] за "
                                + f2(was) + " бл (ваніль із такої відстані вузол не пропускає) -> індекс переставлено");
            } else if (pick == SoftMath.RESYNC_REFRESH) {
                Integer last = LAST_REFRESH.get(mob);
                if (last == null || mob.tickCount - last >= REFRESH_INTERVAL_TICKS) {
                    LAST_REFRESH.put(mob, mob.tickCount);
                    EnemyBreak_N_BuildUtils.requestFreshPath(mob);
                    final int idx = next;
                    DebugLog.logThrottled(DebugLog.Cat.FALL, mob, "path-offpath|" + mob.getId(), 1000L,
                            () -> "МОБ ПОЗА ШЛЯХОМ: далі за " + f2(Math.sqrt(SoftMath.OFF_PATH_DIST_SQ)) + " бл і від вузла [" + (idx - 1)
                                    + "], і від [" + idx + "] (відстань до наступного " + f2(was) + " бл), поряд жодного вузла -> прошу СВІЖИЙ шлях");
                }
            }
        } catch (RuntimeException ignored) {
            // тільки допоміжне
        }
    }

    private static String cell(Node n) {
        return "(" + n.x + "," + n.y + "," + n.z + ")";
    }

    private static String f2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
