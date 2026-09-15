package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Розширює звичайний {@code WalkNodeEvaluator} додатковими "стрибковими" ребрами графа: якщо
 * від вузла в одному з 4 кардинальних напрямків одразу починається провалля, а десь у межах
 * теоретичної дальності стрибка моба ({@link GapJumpUtils#estimateMaxJumpRangeBlocks}) знову є
 * тверда підлога — додає ЦЮ віддалену позицію як сусіда просто в {@code getNeighbors}, а не
 * тільки сусідні по одному кроку клітинки, як робить ванільний {@code WalkNodeEvaluator}.
 * <p>
 * Навмисно НЕ заводить окремий {@code PathType} (типу "PARKOUR_JUMP") — це вимагало б
 * NeoForge-механізму enum extensions (окремий JSON + запис у neoforge.mods.toml) заради самого
 * лише маркування. Замість цього "цей сегмент - стрибок" визначається геометрично: у
 * {@link GapJumpUtils#findUpcomingJumpSegment} просто дивляться на відстань між сусідніми
 * вузлами готового Path. Простіше і не залежить від зайвої інфраструктури.
 * <p>
 * ПРО ПРОДУКТИВНІСТЬ: getNeighbors викликається на КОЖЕН вузол під час КОЖНОГО пошуку шляху, тож
 * додатковий скан не повинен бути безумовним. Тому пробуємо шукати стрибок лише коли звичайних
 * сусідів від super() вийшло менше 4 — це і є сигнал "тут щось не так з підлогою навколо", а не
 * відкрита рівна місцевість, де стрибок все одно ніколи не знадобиться.
 * <p>
 * ЧЕСНО, найменш перевірений шматок у всій справі: {@code this.mob} і {@code this.level} тут —
 * поля, успадковані від базового {@code NodeEvaluator} (виставляються в {@code prepare(...)}
 * перед пошуком) — я не зміг звірити компіляцією (нема Minecraft-бібліотек у пісочниці), лише
 * пошуком у документації. Якщо назви трохи інші - це перше, що варто перевірити автокомплітом.
 */
public class GapJumpNodeEvaluator extends WalkNodeEvaluator {

    private static final int MIN_NORMAL_NEIGHBORS_TO_SKIP_SCAN = 4;
    private static final int[][] CARDINAL_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public int getNeighbors(Node[] nodes, Node node) {
        int count = super.getNeighbors(nodes, node);
        if (count >= MIN_NORMAL_NEIGHBORS_TO_SKIP_SCAN) {
            return count; // повністю відкрита клітинка - тут стрибок ніколи не потрібен, не скануємо
        }
        int maxGap = GapJumpUtils.estimateMaxJumpRangeBlocks(this.mob);
        if (maxGap < 1) {
            return count;
        }
        for (int[] dir : CARDINAL_DIRS) {
            if (count >= nodes.length) {
                break; // буфер сусідів повний - більше нема куди писати
            }
            count = tryAddJumpNeighbor(nodes, count, node, dir[0], dir[1], maxGap);
        }
        return count;
    }

    private int tryAddJumpNeighbor(Node[] nodes, int count, Node node, int dx, int dz, int maxGap) {
        BlockPos origin = new BlockPos(node.x, node.y, node.z);
        if (hasFloorAt(origin, dx, dz, 1)) {
            return count; // тут і так є нормальна опора - звичайний крок з цього вже впорається
        }
        for (int step = 2; step <= maxGap + 1; step++) {
            if (hasFloorAt(origin, dx, dz, step)) {
                BlockPos landing = origin.offset(dx * step, 0, dz * step);
                if (landing.getY() - origin.getY() > 1) {
                    return count; // приземлення суттєво вище - climb-кейс, не наш
                }
                Node jumpNode = new Node(landing.getX(), landing.getY(), landing.getZ());
                jumpNode.type = PathType.WALKABLE;
                jumpNode.costMalus = step * 1.5F; // дорожче за звичайний крок - A* бере тільки якщо це реально коротший шлях
                nodes[count] = jumpNode;
                return count + 1;
            }
        }
        return count;
    }

    private boolean hasFloorAt(BlockPos origin, int dx, int dz, int step) {
        BlockPos column = origin.offset(dx * step, 0, dz * step);
        BlockPos floorPos = column.below();

        BlockState floorState = getBlockStateAt(floorPos);
        BlockState feetState = getBlockStateAt(column);

        if (floorState == null || feetState == null) {
            return false;
        }

        // Перевіряємо, що підлога блокирует рух (тверда), а на рівні ніг — вільно
        return floorState.blocksMotion() && !feetState.blocksMotion();
    }

    /**
     * Отримує BlockState з контексту навігації (Fast/Thread-safe),
     * а якщо він не ініціалізований — робить фолбек на прямий доступ до світу моба.
     */
    private BlockState getBlockStateAt(BlockPos pos) {
        if (this.currentContext != null) {
            return this.currentContext.getBlockState(pos);
        }
        if (this.mob != null) {
            return this.mob.level().getBlockState(pos);
        }
        return null;
    }
}
