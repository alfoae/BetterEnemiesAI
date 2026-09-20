package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Розширює звичайний {@code WalkNodeEvaluator} додатковими "стрибковими" ребрами графа: якщо від
 * вузла в якомусь напрямку одразу починається провалля, а десь у межах теоретичної дальності
 * стрибка моба ({@link GapJumpUtils#estimateMaxJumpRangeBlocks}) знову є тверда підлога — додає ЦЮ
 * віддалену позицію як сусіда просто в {@code getNeighbors}, а не тільки сусідні по одному кроку
 * клітинки, як робить ванільний {@code WalkNodeEvaluator}.
 * <p>
 * <b>v2 — напрямок стрибка БУДЬ-ЯКИЙ.</b> Раніше скан ішов лише вздовж 4 осей
 * ({@code CARDINAL_DIRS}) — тому моб стрибав лише вліво/вправо/вгору/вниз. Тепер кожна цілочисельна
 * пара (dx, dz) — потенційне приземлення: діагоналі, "коні" (2,1), (3,2) тощо, тобто фактично
 * по колу. Пари згруповані в промені й заздалегідь пораховані в {@link GapJumpRays} (там же
 * пояснено, чому межа дальності однакова у всіх напрямках). Що перевіряється для кожного променя:
 * <ol>
 *   <li><b>Тут край саме в цьому напрямку:</b> перша клітинка на шляху НЕ прохідна (нема підлоги).
 *       Інакше моб просто зробить звичайний крок, а стрибок згенерується вже з наступної клітинки
 *       (з реального краю) — як і раніше робив старий код для кожної осі.</li>
 *   <li><b>Перше придатне приземлення вздовж променя</b> — підлога під ним тверда, на рівні ніг і
 *       голови вільно.</li>
 *   <li><b>Коридор польоту вільний:</b> клітинки, які зачепить хітбокс моба на шляху, на рівні ніг
 *       і голови не блокують рух. Раніше цього не перевірялось — стрибок "крізь" стовп вважався
 *       прохідним.</li>
 * </ol>
 * Навмисно НЕ заводить окремий {@code PathType} (типу "PARKOUR_JUMP") — це вимагало б
 * NeoForge-механізму enum extensions (окремий JSON + запис у neoforge.mods.toml) заради самого
 * лише маркування. Замість цього "цей сегмент - стрибок" визначається геометрично: у
 * {@link GapJumpUtils#findUpcomingJumpSegment} просто дивляться на відстань між сусідніми
 * вузлами готового Path. Простіше і не залежить від зайвої інфраструктури. Зауваж: клітинка через
 * кут, (1,1), стрибком не вважається (відстань 1.41 < 1.5) — це ванільний діагональний крок; 0.6-ширний
 * хітбокс моба перекриває обидва блоки біля спільного кута, тож там і не потрібно стрибати.
 * <p>
 * ПРО ПРОДУКТИВНІСТЬ: getNeighbors викликається на КОЖЕН вузол під час КОЖНОГО пошуку шляху, тож
 * додатковий скан не повинен бути безумовним. Ванільний WalkNodeEvaluator дає максимум 8 сусідів
 * (4 сторони + 4 діагоналі); якщо вийшли всі 8 — довкола суцільна підлога, стрибок звідси ніколи
 * не знадобиться, не скануємо. Раніше поріг був 4, і через це вузол на прямому краї широкої
 * платформи (5 звичайних сусідів) не отримував стрибків узагалі — тільки вузькі платформи.
 * Далі для 9 сусідніх клітинок один раз питаємо світ, чи є там підлога, і відкидаємо всі промені,
 * що починаються з прохідної клітинки.
 * <p>
 * ЧЕСНО, найменш перевірений шматок у всій справі: {@code this.mob} і {@code this.currentContext} тут —
 * поля, успадковані від базового {@code NodeEvaluator} (виставляються в {@code prepare(...)}
 * перед пошуком) — самі назви полів і API світу лишились ТІ САМІ, що були в попередній версії цього файлу
 * (нових викликів Minecraft тут немає).
 */
public class GapJumpNodeEvaluator extends WalkNodeEvaluator {

    /**
     * Ванільний getNeighbors дає максимум 8 сусідів: 4 сторони + 4 діагоналі.
     */
    private static final int ALL_NORMAL_NEIGHBORS = 8;

    /**
     * Стан клітинки перед краєм (для кешу на вузол).
     */
    private static final byte UNKNOWN = 0;
    /**
     * Є підлога й вільно на рівні ніг: моб просто зробить крок, стрибок звідси в цей бік не потрібен.
     */
    private static final byte WALKABLE = 1;
    /**
     * Немає підлоги й на рівні ніг вільно: справжнє провалля - лише крізь нього має сенс стрибати.
     */
    private static final byte VOID = 2;
    /**
     * На рівні ніг стіна/блок (або світ недоступний): крізь неї стрибок не йде.
     */
    private static final byte BLOCKED = 3;

    @Override
    public int getNeighbors(Node[] nodes, Node node) {
        int count = super.getNeighbors(nodes, node);
        if (count >= ALL_NORMAL_NEIGHBORS) {
            return count; // повністю відкрита клітинка - тут стрибок ніколи не потрібен, не скануємо
        }
        int maxGap = GapJumpUtils.estimateMaxJumpRangeBlocks(this.mob);
        if (maxGap < 1) {
            return count;
        }

        BlockPos origin = new BlockPos(node.x, node.y, node.z);
        byte[] frontCache = new byte[9]; // стан 8 клітинок перед краєм (прохідна/провалля/заблокована); ключ (dx+1)*3+(dz+1)
        for (GapJumpRays.Ray ray : GapJumpRays.forMaxGap(maxGap)) {
            if (count >= nodes.length) {
                break; // буфер сусідів повний - більше нема куди писати (промені відсортовані: спершу найближчі)
            }
            count = tryRay(nodes, count, origin, ray, frontCache);
        }
        return count;
    }

    private int tryRay(Node[] nodes, int count, BlockPos origin, GapJumpRays.Ray ray, byte[] frontCache) {
        // 1. Край у цьому напрямку? Перша клітинка має бути справжнім проваллям. Якщо вона прохідна -
        //    звичайний крок з цього вже впорається (а стрибок згенерується з наступної клітинки); якщо
        //    там стіна - крізь неї не стрибаємо (раніше стіна теж вважалась "не прохідною" і скан ішов далі).
        int frontIndex = (ray.frontX() + 1) * 3 + (ray.frontZ() + 1);
        if (frontCache[frontIndex] == UNKNOWN) {
            frontCache[frontIndex] = classifyFront(origin, ray.frontX(), ray.frontZ());
        }
        if (frontCache[frontIndex] != VOID) {
            return count;
        }

        // 2. Перше придатне приземлення вздовж променя.
        for (int k = ray.kMin(); k <= ray.kMax(); k++) {
            int dx = ray.stepX() * k;
            int dz = ray.stepZ() * k;
            if (!isStandable(origin, dx, dz)) {
                continue;
            }
            // 3. Приземлення є - коридор польоту має бути вільний. Якщо ні, то й далі по променю заблоковано.
            if (!isCorridorClear(origin, ray.corridor()[k])) {
                return count;
            }
            BlockPos landing = origin.offset(dx, 0, dz);
            Node jumpNode = new Node(landing.getX(), landing.getY(), landing.getZ());
            jumpNode.type = PathType.WALKABLE;
            double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
            jumpNode.costMalus = (float) (distance * 1.5); // дорожче за звичайний крок - A* бере тільки якщо це реально коротший шлях
            nodes[count] = jumpNode;
            return count + 1;
        }
        return count;
    }

    private byte classifyFront(BlockPos origin, int dx, int dz) {
        BlockPos column = origin.offset(dx, 0, dz);
        BlockState feetState = getBlockStateAt(column);
        BlockState floorState = getBlockStateAt(column.below());
        if (feetState == null || floorState == null || feetState.blocksMotion()) {
            return BLOCKED;
        }
        return floorState.blocksMotion() ? WALKABLE : VOID;
    }

    private boolean isCorridorClear(BlockPos origin, int[][] cells) {
        for (int[] cell : cells) {
            BlockPos column = origin.offset(cell[0], 0, cell[1]);
            if (blocksBody(column)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Клітинка на рівні ніг чи голови моба не має бути суцільною.
     */
    private boolean blocksBody(BlockPos feet) {
        BlockState feetState = getBlockStateAt(feet);
        BlockState headState = getBlockStateAt(feet.above());
        if (feetState == null || headState == null) {
            return true; // світ недоступний - обережно вважаємо заблокованим
        }
        return feetState.blocksMotion() || headState.blocksMotion();
    }

    /**
     * Підлога тверда, а на рівні ніг вільно.
     */
    private boolean hasFloorAt(BlockPos origin, int dx, int dz) {
        BlockPos column = origin.offset(dx, 0, dz);
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
     * Придатне для приземлення: підлога, а над нею вільно і на рівні ніг, і на рівні голови.
     */
    private boolean isStandable(BlockPos origin, int dx, int dz) {
        if (!hasFloorAt(origin, dx, dz)) {
            return false;
        }
        BlockState headState = getBlockStateAt(origin.offset(dx, 1, dz));
        return headState != null && !headState.blocksMotion();
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