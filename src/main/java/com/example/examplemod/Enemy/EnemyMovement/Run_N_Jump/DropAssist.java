package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Config;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.HopNode;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapePathAccess;
import com.example.examplemod.debug.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

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
    /**
     * Ребро спуску "сусідня колонка" (стрибки на далі - справа GapJumpAssistGoal): квадрат 1.5.
     */
    private static final double MAX_ADJACENT_SQ = 2.25;
    /**
     * З якої відстані до вузла краю моб починає повільний підхід: квадрат 2.5.
     */
    private static final double EDGE_APPROACH_DIST_SQ = 6.25;
    /**
     * Множник швидкості повільного підходу (ходьба зі спринту ~1.3 -> повзе ~0.4).
     */
    private static final double SLOW_SPEED_MODIFIER = 0.4;
    /**
     * Не ближче/далі за стільки блоків по горизонталі вузол приземлення вважається "тією колонкою": квадрат 4.
     */
    private static final double AIR_TARGET_MAX_DIST_SQ = 16.0;

    /**
     * Ресинхронізація: коли моб далі за 2 блоки від поточного вузла (квадрат 4)...
     */
    private static final double RESYNC_MIN_DIST_SQ = 4.0;
    /**
     * ...і майже стоїть на одному з наступних вузлів (квадрат 1), дивимось не далі ніж на 3 вперед.
     */
    private static final double RESYNC_NEAR_DIST_SQ = 1.0;
    private static final int RESYNC_LOOKAHEAD = 3;

    private DropAssist() {
    }

    // ------------------------------------------------------------------------------------------
    // Підхід до краю й корекція в польоті
    // ------------------------------------------------------------------------------------------

    /**
     * Кликається з {@code PathNavigation#tick} (TAIL) кожен тік; для не-переслідувачів і твердих приземлень нічого не робить.
     */
    public static void onNavigationTick(Mob mob, Path path) {
        if (mob == null || path == null || path.isDone() || mob.level().isClientSide()) {
            return;
        }
        if (!Config.DROP_AIR_STEERING.get()) {
            return;
        }
        if (mob.isInWater() || mob.isInLava() || mob.onClimbable() || mob.isPassenger()) {
            return;
        }
        if (GapJumpAssistGoal.isActive(mob) || PursuitEnemyBehavior.getChasePosition(mob) == null) {
            return; // стрибками керує свій гоал; не переслідувачів не чіпаємо
        }
        try {
            if (mob.onGround()) {
                approachEdge(mob, path);
            } else {
                steerInAir(mob, path);
            }
        } catch (RuntimeException ignored) {
            // допоміжна механіка ніколи не повинна ламати навігацію
        }
    }

    private static void approachEdge(Mob mob, Path path) {
        int next = path.getNextNodeIndex();
        int count = path.getNodeCount();
        Level level = mob.level();
        for (int j = Math.max(1, next); j <= Math.min(next + 1, count - 1); j++) {
            Node edge = path.getNode(j - 1);
            Node landing = path.getNode(j);
            if (edge.y - landing.y < MIN_DROP_BLOCKS || landing instanceof HopNode) {
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
            mob.getMoveControl().setWantedPosition(target.x, target.y, target.z, SLOW_SPEED_MODIFIER);
            DebugLog.logThrottled(DebugLog.Cat.FALL, mob, "drop-approach|" + mob.getId(), 700L,
                    () -> "ПІДХІД ДО КРАЮ (повільно, без спринту): м'яке приземлення " + cell(landing) + " на " + (edge.y - landing.y)
                            + " бл нижче | до краю " + cell(edge) + " лишилось=" + f2(Math.sqrt(dx * dx + dz * dz))
                            + " бл | швидкість-множник=" + SLOW_SPEED_MODIFIER);
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

    /**
     * Приземлення в цю клітинку НЕ завдає повної шкоди (вода, сіно, мед, ліжко, слайм, павутиння, драбина, ліани).
     */
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

    /**
     * Кликається з {@code PathNavigation#followThePath} (HEAD), коли моб на землі.
     */
    public static void resync(Mob mob, Path path, Vec3 mobPos) {
        if (mob == null || path == null || path.isDone() || mobPos == null || !mob.onGround()) {
            return;
        }
        if (!Config.PATH_RESYNC.get() || PursuitEnemyBehavior.getChasePosition(mob) == null) {
            return;
        }
        Object holder = path;
        if (holder instanceof ShapePathAccess access && access.betterEnemies$spots() != null) {
            return; // шлях із точними точками - у нього власний механізм проходу вузлів
        }
        try {
            int next = path.getNextNodeIndex();
            int count = path.getNodeCount();
            if (next < 0 || next >= count - 1) {
                return;
            }
            double dNext = mobPos.distanceToSqr(path.getEntityPosAtNode(mob, next));
            if (dNext <= RESYNC_MIN_DIST_SQ) {
                return; // ванільна логіка ще справляється
            }
            int best = -1;
            double bestD = dNext;
            for (int i = next + 1; i <= Math.min(next + RESYNC_LOOKAHEAD, count - 1); i++) {
                if (path.getNode(i) instanceof HopNode) {
                    break; // стрибок/гоп - не перескакуємо
                }
                Vec3 pi = path.getEntityPosAtNode(mob, i);
                double d = mobPos.distanceToSqr(pi);
                if (Math.abs(pi.y - mobPos.y) < 1.2 && d <= RESYNC_NEAR_DIST_SQ && d < bestD) {
                    best = i;
                    bestD = d;
                }
            }
            if (best > next) {
                path.setNextNodeIndex(best);
                final int from = next;
                final int to = best;
                final double was = Math.sqrt(dNext);
                DebugLog.logThrottled(DebugLog.Cat.FALL, mob, "path-resync|" + mob.getId(), 500L,
                        () -> "РЕСИНХРОНІЗАЦІЯ ШЛЯХУ: моб уже біля вузла [" + to + "], а \"наступним\" лишався [" + from + "] за "
                                + f2(was) + " бл (ваніль із такої відстані вузол не пропускає) -> індекс переставлено");
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
