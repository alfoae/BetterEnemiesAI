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
 *   <li><b>Придатне приземлення вздовж променя</b> — підлога під ним тверда, на рівні ніг і
 *       голови вільно. Перше таке — звичайний стрибок; далі промінь скануємо ще: якщо платформа
 *       закінчується проваллям і ще далі (у межах дальності) є нова — додається ПЕРЕСТРИБУВАННЯ
 *       (див. нижче).</li>
 *   <li><b>Коридор польоту вільний:</b> клітинки, які зачепить хітбокс моба на шляху, на рівні ніг
 *       і голови не блокують рух. Раніше цього не перевірялось — стрибок "крізь" стовп вважався
 *       прохідним.</li>
 * </ol>
 * <b>Перестрибування проміжних платформ.</b> {@code ▣▢▣▢▣}: з 1-го блока моб тепер може стрибнути не на 2-й, а
 * одразу на 3-й, пролетівши НАД 2-м. Якщо на промені після першого приземлення знову провалля, а за ним
 * ще одна платформа в межах тієї ж дальності, вона теж стає сусідом вузла — з дешевшою вартістю за блок
 * ({@link #SKIP_MALUS_PER_BLOCK}), щоб A* віддавав перевагу одному довгому стрибку над двома короткими.
 * Політ над проміжним блоком безпечний: після тіку відриву ноги моба вже вище 0.42 над верхом платформи,
 * а коридор польоту (клітинки на рівні ніг і голови, у тому числі над самою проміжною платформою)
 * перевіряється так само, як для звичайного стрибка. Продовження тієї ж платформи (наступна клітинка
 * теж має підлогу) перестрибуванням НЕ вважається — ногами й так дійдемо.
 * <p>
 * Навмисно НЕ заводить окремий {@code PathType} (типу "PARKOUR_JUMP") — це вимагало б
 * NeoForge-механізму enum extensions (окремий JSON + запис у neoforge.mods.toml) заради самого
 * лише маркування. Замість цього "цей сегмент - стрибок" визначається геометрично: у
 * {@link GapJumpUtils#findUpcomingJumpSegment} просто дивляться на відстань між сусідніми
 * вузлами готового Path. Простіше і не залежить від зайвої інфраструктури. Зауваж: клітинка через
 * кут, (1,1), стрибком не вважається (відстань 1.41 < 1.5) — це ванільний діагональний крок; 0.6-ширний
 * хітбокс моба перекриває обидва блоки біля спільного кута, тож там і не потрібно стрибати.
 * <p>
 * <b>v3 — дальність залежить від блока відриву.</b> Максимальний розрив рахується для КОЖНОГО вузла окремо за
 * властивостями блока під ним ({@link GapJumpUtils#blockRangeFactor}: тертя, speedFactor, jumpFactor): з льоду моб
 * планує стрибки далі, зі слайма / піску душ / меду - ближче (повільному мобу з меду стрибків може не лишитись
 * зовсім). Висота й фізика стрибка не змінюються - це лише те, які ребра потрапляють у граф. На звичайних
 * блоках усе як раніше.
 * <p>
 * <b>TODO(Y) — висота блока, з якого стрибаємо / на який приземляємось.</b> Зараз моб стрибає лише по X/Z, а
 * висота підлоги береться як ЦІЛА координата вузла ({@code node.y}). Коли перейдемо до Y (перепад ±1 блок,
 * зілля стрибка вище), для неповних і нестандартних блоків треба рахувати РЕАЛЬНУ висоту, на якій стоїть моб:
 * <ul>
 *   <li>пісок душ (14/16), мед (15/16), плити (0.5; верхня плита - 1.0), килими, шари снігу, платівки, горщики,
 *       яйця тощо;</li>
 *   <li>сходинки: залежно від повороту й {@code half} (низ/верх). Якщо моб стоїть на "ребрі" сходинки, відрив і
 *       приземлення мають бути на НИЖНЬОМУ краї;</li>
 *   <li>паркани, стіни, хвіртки (колізія 1.5), двері й люки (залежно від open / half / facing) і т.д.</li>
 * </ul>
 * Загальне правило - брати {@code getCollisionShape(...).max(Axis.Y)} у тій точці, де моб стоїть/приземляється.
 * Куди це вплине: {@code landing.y} у сегменті ({@link GapJumpUtils.GapJump}), {@code heightAbove} у
 * {@code GapJumpAssistGoal.controlFlight/tryRescueJump}, а тут - {@link #hasFloorAt} / {@link #isStandable}.
 * Поки що похибка невелика (пісок душ дає ~0.05 переліту) і стрибок виходить; для плит/сходинок вона більша.
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
     * Вартість звичайного стрибка за блок відстані: дорожче за крок - A* бере лише коли це реально коротший шлях.
     */
    private static final float JUMP_MALUS_PER_BLOCK = 1.5F;

    /**
     * Вартість ПЕРЕСТРИБУВАННЯ за блок відстані (див. {@link #tryRay}). Один довгий стрибок над проміжною
     * платформою має бути СТРОГО дешевшим за ланцюг коротких: ланцюг із двох стрибків на сумарну відстань D
     * коштує D + 1.5D = 2.5D, а перестрибування - D + 1.25D = 2.25D. Без знижки при однаковій відстані
     * вони були б у нічиїй (вартість лінійна), і A* обирав би той чи інший випадково.
     */
    private static final float SKIP_MALUS_PER_BLOCK = 1.25F;

    /** Стан клітинки перед краєм (для кешу на вузол). */
    private static final byte UNKNOWN = 0;
    /** Є підлога й вільно на рівні ніг: моб просто зробить крок, стрибок звідси в цей бік не потрібен. */
    private static final byte WALKABLE = 1;
    /** Немає підлоги й на рівні ніг вільно: справжнє провалля - лише крізь нього має сенс стрибати. */
    private static final byte VOID = 2;
    /** На рівні ніг стіна/блок (або світ недоступний): крізь неї стрибок не йде. */
    private static final byte BLOCKED = 3;

    @Override
    public int getNeighbors(Node[] nodes, Node node) {
        int count = super.getNeighbors(nodes, node);
        if (count >= ALL_NORMAL_NEIGHBORS) {
            return count; // повністю відкрита клітинка - тут стрибок ніколи не потрібен, не скануємо
        }
        BlockPos origin = new BlockPos(node.x, node.y, node.z);
        // Дальність залежить від блока, З ЯКОГО моб відривається (тертя / speedFactor / jumpFactor): лід - далі,
        // слайм, пісок душ, мед - ближче. Тому рахуємо її тут, для КОЖНОГО вузла під час побудови шляху, а не
        // одним числом на всього моба й не перед самим стрибком.
        int maxGap = estimateMaxGapFrom(origin);
        if (maxGap < 1) {
            return count; // з цього блока (дуже липкого для цього моба) стрибнути не вдасться - стрибкових ребер звідси нема
        }

        byte[] frontCache = new byte[9]; // стан 8 клітинок перед краєм (прохідна/провалля/заблокована); ключ (dx+1)*3+(dz+1)
        for (GapJumpRays.Ray ray : GapJumpRays.forMaxGap(maxGap)) {
            if (count >= nodes.length) {
                break; // буфер сусідів повний - більше нема куди писати (промені відсортовані: спершу найближчі)
            }
            count = tryRay(nodes, count, origin, ray, frontCache);
        }
        return count;
    }

    /**
     * Скільки блоків розриву моб здатен перестрибнути, відриваючись саме з цього вузла. Блок відриву - підлога
     * ПІД вузлом (той самий, на якому моб стоятиме на краю); його коефіцієнти беруться з самого блока (тертя через
     * хук NeoForge, тож і для блоків з інших модів), див. {@link GapJumpUtils#blockRangeFactor}. Для звичайного
     * блока множник рівно 1.0 - результат такий самий, як був.
     */
    private int estimateMaxGapFrom(BlockPos origin) {
        double blockFactor = 1.0;
        BlockPos floorPos = origin.below();
        BlockState floor = getBlockStateAt(floorPos);
        if (floor != null && this.mob != null) {
            blockFactor = GapJumpUtils.blockRangeFactor(floor, this.mob.level(), floorPos, this.mob);
        }
        return GapJumpUtils.estimateMaxJumpRangeBlocks(this.mob, blockFactor);
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

        // 2. Приземлення вздовж променя. ПЕРШЕ придатне - звичайний стрибок. Далі сканування ТРИВАЄ: якщо
        //    за цією платформою знову провалля, а ще далі (у межах дальності) - нова платформа, то це
        //    ПЕРЕСТРИБУВАННЯ: один довгий стрибок НАД проміжною платформою замість двох коротких
        //    (▣▢▣▢▣ - з 1-го блока одразу на 3-й). Раніше цикл робив return на першому ж приземленні, і
        //    проміжну платформу перестрибнути було неможливо.
        boolean landed = false;      // на цьому промені вже додано хоча б одне приземлення
        boolean voidBehind = false;  // після останньої придатної клітинки на промені була порожнеча
        for (int k = ray.kMin(); k <= ray.kMax(); k++) {
            int dx = ray.stepX() * k;
            int dz = ray.stepZ() * k;
            if (!isStandable(origin, dx, dz)) {
                if (landed) {
                    voidBehind = true; // (це може бути й стіна - коридор наступного кандидата її відсіче)
                }
                continue;
            }
            // Придатна клітинка. Кандидат - якщо це перше приземлення, або перед нею була порожнеча
            // (інакше це просто продовження тієї ж платформи, ногами й так дійдемо).
            if (!landed || voidBehind) {
                // Коридор польоту (разом із проміжною платформою й проваллями між) має бути вільний.
                // Якщо ні - то й далі по променю заблоковано.
                if (!isCorridorClear(origin, ray.corridor()[k])) {
                    return count;
                }
                if (count >= nodes.length) {
                    return count;
                }
                nodes[count++] = jumpNode(origin, dx, dz, landed);
                landed = true;
            }
            // Чи закінчується тут платформа (одразу за нею провалля)? Тільки тоді наступну придатну
            // клітинку на промені можна перестрибнути. Для променів із кроком >1 клітинки "клітинка за"
            // береться тією самою відносною позицією, що й перша клітинка від краю (лінія періодична).
            voidBehind = classifyFront(origin, dx + ray.frontX(), dz + ray.frontZ()) == VOID;
        }
        return count;
    }

    private Node jumpNode(BlockPos origin, int dx, int dz, boolean skip) {
        BlockPos landing = origin.offset(dx, 0, dz);
        Node jumpNode = new Node(landing.getX(), landing.getY(), landing.getZ());
        jumpNode.type = PathType.WALKABLE;
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
        jumpNode.costMalus = (float) (distance * (skip ? SKIP_MALUS_PER_BLOCK : JUMP_MALUS_PER_BLOCK));
        return jumpNode;
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

    /** Клітинка на рівні ніг чи голови моба не має бути суцільною. */
    private boolean blocksBody(BlockPos feet) {
        BlockState feetState = getBlockStateAt(feet);
        BlockState headState = getBlockStateAt(feet.above());
        if (feetState == null || headState == null) {
            return true; // світ недоступний - обережно вважаємо заблокованим
        }
        return feetState.blocksMotion() || headState.blocksMotion();
    }

    /** Підлога тверда, а на рівні ніг вільно. */
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

    /** Придатне для приземлення: підлога, а над нею вільно і на рівні ніг, і на рівні голови. */
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