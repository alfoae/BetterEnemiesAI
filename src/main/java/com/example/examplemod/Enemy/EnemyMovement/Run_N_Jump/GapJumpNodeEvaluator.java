package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Config;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
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
 * теж має підлогу) перестрибуванням НЕ вважається — ногами й так дійдемо. Працює так само й для
 * похилих променів (див. v4 нижче) — проміжна платформа тоді теж на висоті {@code dy}.
 * <p>
 * Навмисно НЕ заводить окремий {@code PathType} (типу "PARKOUR_JUMP") — це вимагало б
 * NeoForge-механізму enum extensions (окремий JSON + запис у neoforge.mods.toml) заради самого
 * лише маркування. Замість цього "цей сегмент - стрибок" визначається геометрично: у
 * {@link GapJumpUtils#findUpcomingJumpSegment} просто дивляться на відстань між сусідніми
 * вузлами готового Path (лише по X/Z — {@code v4} нижче навмисно нічого тут не міняє, бо гарного
 * маркера "це похилий стрибок" однаково нема, а сама подія "це стрибок" від Δy не залежить).
 * Зауваж: клітинка через кут, (1,1), стрибком не вважається (відстань 1.41 < 1.5) — це ванільний
 * діагональний крок; 0.6-ширний хітбокс моба перекриває обидва блоки біля спільного кута, тож там
 * і не потрібно стрибати.
 * <p>
 * <b>v3 — дальність залежить від блока відриву.</b> Максимальний розрив рахується для КОЖНОГО вузла окремо за
 * властивостями блока під ним ({@link GapJumpUtils#blockRangeFactor}: тертя, speedFactor, jumpFactor): з льоду моб
 * планує стрибки далі, зі слайма / піску душ / меду - ближче (повільному мобу з меду стрибків може не лишитись
 * зовсім). Висота й фізика стрибка не змінюються - це лише те, які ребра потрапляють у граф. На звичайних
 * блоках усе як раніше.
 * <p>
 * <b>v4 — сходинки (Δy).</b> Тепер для кожного вузла пробуємо не лише приземлення на своєму рівні (Δy=0), а й на
 * {@code +1} (вгору) та {@code -1} (вниз) — {@link GapJumpPhysics#JUMP_UP_LIMIT_BLOCKS} /
 * {@code JUMP_DOWN_LIMIT_BLOCKS}, свій ліміт на кожен бік (легко підняти чи опустити пізніше, формули physics
 * узагальнені під будь-який цілий Δy - див. {@link GapJumpPhysics#naturalAirtime}). У БУДЬ-ЯКОМУ напрямку
 * (діагоналі, "коні" - той самий набір променів {@link GapJumpRays}, що й для рівних стрибків), з
 * перестрибуванням проміжної платформи так само, як для рівних. Дальність для похилого стрибка - ОКРЕМА від
 * рівної навіть на звичайному блоці (фізика: вікно на підйом коротше за рівний політ, тож і дальність менша;
 * вниз - трохи більша), і теж враховує блок відриву ({@link GapJumpUtils#blockRangeFactor} тепер бере Δy як
 * четвертий параметр) - з дуже липких/низькострибучих блоків стрибок вгору може бути взагалі недосяжний
 * (дальність виходить 0, промені для цього напрямку просто не пробуємо - той самий шлях, що вже спрацював
 * для v3: рахуємо ЄДИНОЮ формулою, без окремого "чи вистачить висоти" гейта, а мед і подібне самі відсіюються
 * нулем).
 * <p>
 * Перевірка "тут дійсно провалля" (для кешу {@code frontCache}) завжди на рівні СТАРТУ (Δy=0 відносно
 * вузла) незалежно від того, який Δy зараз пробуємо — інакше "лесенку" з порожнечею на обох поверхах
 * evaluator не розпізнав би як провалля. Коридор польоту для похилого стрибка — на 3 клітинки заввишки
 * замість 2 (від рівня ніг на старті до рівня голови на приземленні чи навпаки): проста, консервативна
 * оцінка без точного розрахунку, де саме на дузі моб опиниться в кожній проміжній точці.
 * <p>
 * НЕ займає (лишається як TODO, разом із неповними блоками приземлення нижче): похилий стрибок, де
 * приземлення не на повний блок (плита, сходинка тощо) - для нього й рівний Δy=0 вже неточний, а
 * для похилого додається ще й питання "від якого фактичного рівня рахувати сам Δy".
 * <p>
 * <b>TODO(Y) — висота блока, з якого стрибаємо / на який приземляємось, для НЕПОВНИХ блоків.</b> Висота підлоги
 * зараз завжди береться як ЦІЛА координата вузла ({@code node.y}). Для неповних і нестандартних блоків варто
 * колись рахувати РЕАЛЬНУ висоту, на якій стоїть моб:
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
 * що починаються з прохідної клітинки. Три проходи (рівно/вгору/вниз) діляться ОДНИМ frontCache
 * (перевірка на рівні старту від Δy не залежить), тож зайвих запитів до світу через це не додається.
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

    /**
     * Стрибок ВГОРУ додатково дорожчий за рівний (той самий дистанційний множник, зверху): вузьке вікно на
     * підйом (див. {@link GapJumpPhysics#naturalAirtime}) робить його ризикованішим, тож A* бере його лише
     * коли рівного шляху справді немає, а не як рівноцінну альтернативу. Вниз - не дорожчий (там більше
     * запасу за часом, а не менше): звичайного {@link #JUMP_MALUS_PER_BLOCK} досить.
     */
    private static final float UP_JUMP_EXTRA_MALUS_PER_BLOCK = 0.5F;

    /** Стан клітинки перед краєм (для кешу на вузол). */
    private static final byte UNKNOWN = 0;
    /** Є підлога й вільно на рівні ніг: моб просто зробить крок, стрибок звідси в цей бік не потрібен. */
    private static final byte WALKABLE = 1;
    /** Немає підлоги й на рівні ніг вільно: справжнє провалля - лише крізь нього має сенс стрибати. */
    private static final byte VOID = 2;
    /** На рівні ніг стіна/блок (або світ недоступний): крізь неї стрибок не йде. */
    private static final byte BLOCKED = 3;

    /**
     * DEBUG-хелпер (тимчасово - див. чат): читабельна назва замість сирого byte.
     */
    private static String classificationName(byte value) {
        return switch (value) {
            case WALKABLE -> "WALKABLE";
            case VOID -> "VOID";
            case BLOCKED -> "BLOCKED";
            default -> "UNKNOWN(" + value + ")";
        };
    }

    @Override
    public int getNeighbors(Node[] nodes, Node node) {
        int count = super.getNeighbors(nodes, node);
        BlockPos origin = new BlockPos(node.x, node.y, node.z);
        byte[] frontCache = new byte[9]; // стан 8 клітинок перед краєм НА РІВНІ СТАРТУ; ключ (dx+1)*3+(dz+1)

        // v11 - НЕ довіряти "count" ваніли наосліп. Люк, відкритий рівно під ногами моба, ванілья могла
        // порахувати звичайним кроком НА ТОМУ Ж РІВНІ (getFloorLevel повертає ЯКЕСЬ число - який саме
        // контракт "нема підлоги" очікує виклик ВСЕРЕДИНІ WalkNodeEvaluator, чесно не підтверджено,
        // про це написано в самому мексині). Якщо просто повірити count>=8, граф "крокує" через
        // порожнечу, а стрибковий скан нижче НАВІТЬ НЕ ЗАПУСКАЄТЬСЯ - точно баг "падає і навіть не
        // пригне". Тому за наявності 8 сусідів звіряємось іще раз ВЛАСНОЮ (перевіреною тестами)
        // класифікацією - дешево для звичайного ландшафту (у classifyFront швидкий шлях на
        // blocksMotion()), і лише коли є розбіжність - не виходимо рано.
        if (count >= ALL_NORMAL_NEIGHBORS && !anySuspectDirection(origin, frontCache)) {
            return count; // справді відкрита клітинка з усіх боків - стрибок тут не потрібен, не скануємо
        }
        BlockPos floorPos = origin.below();
        BlockState floor = getBlockStateAt(floorPos);
        if (floor == null || this.mob == null) {
            return count;
        }
        var level = this.mob.level();
        // Дальність залежить від блока, З ЯКОГО моб відривається (тертя / speedFactor / jumpFactor): лід - далі,
        // слайм, пісок душ, мед - ближче; і від Δy (вгору - вікно коротше, тож і дальність менша, навіть на
        // звичайному блоці; вниз - трохи більша). Тому рахуємо тут, для КОЖНОГО вузла й для КОЖНОГО рівня Δy
        // окремо під час побудови шляху, а не одним числом на всього моба й не перед самим стрибком.
        // ПОВНИЙ діапазон -JUMP_DOWN_LIMIT_BLOCKS..+JUMP_UP_LIMIT_BLOCKS (не лише крайні значення): щоб моб
        // міг приземлитись саме на НАЙБЛИЖЧУ доступну сходинку, а не тільки на найглибшу з можливих (наприклад,
        // сходи в 3 рівні - Δy=-1 на першу, Δy=-2 на другу, Δy=-3 на третю, кожна свій кандидат).
        for (int dy = -GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS; dy <= GapJumpPhysics.JUMP_UP_LIMIT_BLOCKS; dy++) {
            double factor = GapJumpUtils.blockRangeFactor(floor, level, floorPos, this.mob, dy);
            int maxGap = GapJumpUtils.estimateMaxJumpRangeBlocks(this.mob, factor);
            if (maxGap < 1) {
                continue; // цей Δy з цього блока недосяжний (напр. вгору з меду, чи просто задалеко) - не пробуємо
            }
            for (GapJumpRays.Ray ray : GapJumpRays.forMaxGap(maxGap)) {
                if (count >= nodes.length) {
                    break; // буфер сусідів повний - більше нема куди писати (промені відсортовані: спершу найближчі)
                }
                count = tryRay(nodes, count, origin, ray, frontCache, dy);
            }
        }
        return count;
    }

    /**
     * Чи є серед 8 напрямків такий, де ВЛАСНА класифікація {@link #classifyFront} НЕ погоджується з
     * тим, що ванілья вважає звичайним прохідним кроком (WALKABLE) — тобто провалля (люк) чи стовпчик,
     * який ванілья порахувала відкритим простором. Заповнює {@code frontCache} по дорозі — далі в
     * {@link #tryRay} та сама перевірка на {@code UNKNOWN} не дасть це перерахувати вдруге.
     */
    private boolean anySuspectDirection(BlockPos origin, byte[] frontCache) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int idx = (dx + 1) * 3 + (dz + 1);
                frontCache[idx] = classifyFront(origin, dx, dz, 0);
                if (frontCache[idx] != WALKABLE) {
                    // DEBUG (тимчасово - див. чат): звіряю ваніли й свою класифікацію розійшлись.
                    System.out.println("[DEBUG GapJumpNodeEvaluator] anySuspectDirection: розбіжність з "
                            + "ваниллю (count вже було 8) origin=" + origin + " dx=" + dx + " dz=" + dz
                            + " myClass=" + classificationName(frontCache[idx]));
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @param dy landing.y - origin.y: {@code 0} - рівний стрибок, {@code >0} - вгору, {@code <0} - вниз
     *           (у межах {@code -JUMP_DOWN_LIMIT_BLOCKS..+JUMP_UP_LIMIT_BLOCKS} - {@link #getNeighbors})
     */
    private int tryRay(Node[] nodes, int count, BlockPos origin, GapJumpRays.Ray ray, byte[] frontCache, int dy) {
        // 1. Край у цьому напрямку? Перша клітинка має бути справжнім проваллям, ЗАВЖДИ на рівні СТАРТУ
        //    (dy тут НЕ підставляємо - "лесенка", де порожньо на обох поверхах, інакше не розпізнається як
        //    провалля). Якщо вона прохідна - звичайний крок з цього вже впорається (а стрибок згенерується
        //    з наступної клітинки); якщо там стіна - крізь неї не стрибаємо.
        int frontIndex = (ray.frontX() + 1) * 3 + (ray.frontZ() + 1);
        if (frontCache[frontIndex] == UNKNOWN) {
            frontCache[frontIndex] = classifyFront(origin, ray.frontX(), ray.frontZ(), 0);
        }
        if (frontCache[frontIndex] != VOID) {
            return count;
        }
        // DEBUG (тимчасово - див. чат): підтверджує, що провалля ВЗАГАЛІ побачене й скан по променю почався.
        System.out.println("[DEBUG GapJumpNodeEvaluator] tryRay: VOID виявлено, скануємо. origin=" + origin
                + " front=(" + ray.frontX() + "," + ray.frontZ() + ") dy=" + dy
                + " kMin=" + ray.kMin() + " kMax=" + ray.kMax());

        // 2. Приземлення вздовж променя, на рівні origin.y + dy. ПЕРШЕ придатне - звичайний стрибок. Далі
        //    сканування ТРИВАЄ: якщо за цією платформою знову провалля (на ЇЇ рівні, тобто теж +dy), а ще
        //    далі (у межах дальності) - нова платформа, то це ПЕРЕСТРИБУВАННЯ: один довгий стрибок НАД
        //    проміжною платформою замість двох коротких (▣▢▣▢▣ - з 1-го блока одразу на 3-й).
        boolean landed = false;      // на цьому промені вже додано хоча б одне приземлення
        boolean voidBehind = false;  // після останньої придатної клітинки на промені була порожнеча
        for (int k = ray.kMin(); k <= ray.kMax(); k++) {
            int dx = ray.stepX() * k;
            int dz = ray.stepZ() * k;
            if (!isStandable(origin, dx, dz, dy)) {
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
                if (!isCorridorClear(origin, ray.corridor()[k], dy)) {
                    return count;
                }
                if (count >= nodes.length) {
                    return count;
                }
                nodes[count++] = jumpNode(origin, dx, dz, dy, landed);
                landed = true;
            }
            // Чи закінчується тут платформа (одразу за нею провалля, на ЇЇ Ж рівні +dy)? Тільки тоді наступну
            // придатну клітинку на промені можна перестрибнути. Для променів із кроком >1 клітинки "клітинка
            // за" береться тією самою відносною позицією, що й перша клітинка від краю (лінія періодична).
            voidBehind = classifyFront(origin, dx + ray.frontX(), dz + ray.frontZ(), dy) == VOID;
        }
        // DEBUG (тимчасово - див. чат): landed=false тут означає "провалля побачили, але НІЯКОГО
        // придатного приземлення на всьому промені не знайшли" - варто звірити з дальністю (maxGap).
        System.out.println("[DEBUG GapJumpNodeEvaluator] tryRay: підсумок landed=" + landed
                + " origin=" + origin + " front=(" + ray.frontX() + "," + ray.frontZ() + ") dy=" + dy
                + " countAfter=" + count);
        return count;
    }

    private Node jumpNode(BlockPos origin, int dx, int dz, int dy, boolean skip) {
        BlockPos landing = origin.offset(dx, dy, dz);
        Node jumpNode = new Node(landing.getX(), landing.getY(), landing.getZ());
        jumpNode.type = PathType.WALKABLE;
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
        float malusPerBlock = skip ? SKIP_MALUS_PER_BLOCK : JUMP_MALUS_PER_BLOCK;
        if (dy > 0) {
            malusPerBlock += UP_JUMP_EXTRA_MALUS_PER_BLOCK;
        }
        jumpNode.costMalus = (float) (distance * malusPerBlock);
        return jumpNode;
    }

    /**
     * Завжди на рівні {@code origin.y + dy} - викликається або з {@code dy=0} (перевірка "тут провалля" на
     * рівні старту), або з поточним Δy променя (перевірка "проміжна платформа скінчилась" - на ЇЇ рівні).
     * <p>
     * <b>v6 — форма замість {@code blocksMotion()}.</b> Перемикається {@link Config#ENABLE_SHAPE_AWARE_PATHING}
     * (типово увімкнено): замість булевого прапорця "≈повний куб" питає РЕАЛЬНУ форму колізії через
     * {@link ShapeProbe} (розділ {@code TerrainShape}, спільний з ванільним мексином на
     * {@code WalkNodeEvaluator} — та сама геометрія, той самий поріг покриття). Горщик, килим, нижня/
     * верхня плита більше не VOID і не BLOCKED — WALKABLE на своїй справжній висоті (закриває частину
     * TODO(Y) вище: саму КЛАСИФІКАЦІЮ; точна висота Δy для фізики польоту — окремий, ще не зроблений
     * крок). Відкритий люк (тонка ВЕРТИКАЛЬНА панель при стінці клітинки, не горизонтальна поличка) —
     * VOID, а не WALKABLE, як було: {@code ShapeProbe.floorSupport} семплить опору по центру клітинки,
     * де панель узагалі не лежить. Стара {@code blocksMotion()}-логіка лишена поруч
     * ({@code classifyFrontLegacy}) на випадок, якщо десь знадобиться відкат.
     */
    private byte classifyFront(BlockPos origin, int dx, int dz, int dy) {
        if (Config.ENABLE_SHAPE_AWARE_PATHING.get()) {
            return classifyCellShapeAware(origin.offset(dx, dy, dz));
        }
        return classifyFrontLegacy(origin, dx, dz, dy);
    }

    private byte classifyFrontLegacy(BlockPos origin, int dx, int dz, int dy) {
        BlockPos column = origin.offset(dx, dy, dz);
        BlockState feetState = getBlockStateAt(column);
        BlockState floorState = getBlockStateAt(column.below());
        if (feetState == null || floorState == null || feetState.blocksMotion()) {
            return BLOCKED;
        }
        return floorState.blocksMotion() ? WALKABLE : VOID;
    }

    /**
     * Реальна опора {@code column} — {@link ShapeProbe#floorSupport} рахує форму САМОЇ клітинки
     * (нуб горщика/килима/нижньої плити ВСЕРЕДИНІ неї) РАЗОМ із формою клітинки під нею (звичайна
     * підлога чи верхня плита впритул до межі) ОДНІЄЮ геометричною задачею — findSupport сам бере
     * вищу з двох поверхонь, без жодного спеціального розбору "чи це плита/горщик/килим/люк", тому
     * працює так само й для блоків з інших модів. Нема реальної опори під заданим {@code footprint}-ом
     * узагалі (відкритий люк при стінці, справжня порожнеча) — VOID; є опора, але вона впирається в
     * стелю клітинки (суцільний блок від низу до верху, не тонкий виступ) — BLOCKED; інакше WALKABLE.
     * <p>
     * Перевірка "стовпчика" ({@link ShapeProbe#isPillarObstruction}) — ЗАВЖДИ перша, ще до
     * {@code floorSupport}: паркан (колізія 1.5) ВИЩИЙ за стелю клітинки (1.0), тому
     * {@code floorSupport} його власний верх у принципі не побачить і піде шукати опору в клітинці
     * НИЖЧЕ (там зазвичай суцільна земля) — без цієї окремої перевірки клітинка з парканом
     * помилково вийшла б WALKABLE (опора "знайдена" у сусідній клітинці, а сам стовпчик просто
     * випав з розрахунку).
     */
    private byte classifyCellShapeAware(BlockPos column) {
        BlockGetter level = levelReader();
        ShapeGeometry.Footprint footprint = ShapeProbe.centeredFootprint(column, mobFootprintWidth());
        if (ShapeProbe.isPillarObstruction(level, column, footprint)) {
            return BLOCKED;
        }
        ShapeGeometry.Support support = ShapeProbe.floorSupport(level, column, footprint);
        if (support.coverage() < ShapeProbe.MIN_FLOOR_COVERAGE) {
            return VOID;
        }
        double clearance = (column.getY() + 1.0) - support.surfaceY();
        return clearance >= ShapeProbe.MIN_STANDING_CLEARANCE ? WALKABLE : BLOCKED;
    }

    /**
     * Коридор польоту: клітинки на шляху не мають блокувати рух. Для рівного стрибка (dy=0) - 2 клітинки
     * заввишки (ноги/голова на рівні старту = рівні приземлення), як і раніше. Для похилого - 3 заввишки
     * (від рівня ніг на нижчому кінці до рівня голови на вищому): моб фізично проходить проміжні висоти
     * дуги десь між рівнем відриву й рівнем приземлення, а не по прямій лінії, тож перевіряємо весь
     * можливий діапазон одразу - простіша й безпечніша оцінка, ніж рахувати точну висоту в кожній точці.
     */
    private boolean isCorridorClear(BlockPos origin, int[][] cells, int dy) {
        for (int[] cell : cells) {
            BlockPos column = origin.offset(cell[0], 0, cell[1]);
            if (blocksBody(column, dy)) {
                return false;
            }
        }
        return true;
    }

    /** Клітинки в діапазоні висот {@code [min(0,dy), max(1,dy+1)]} не мають бути суцільними. */
    private boolean blocksBody(BlockPos originColumn, int dy) {
        if (Config.ENABLE_SHAPE_AWARE_PATHING.get()) {
            return blocksBodyShapeAware(originColumn, dy);
        }
        return blocksBodyLegacy(originColumn, dy);
    }

    private boolean blocksBodyLegacy(BlockPos originColumn, int dy) {
        int low = Math.min(0, dy);
        int high = Math.max(1, dy + 1);
        for (int y = low; y <= high; y++) {
            BlockState state = getBlockStateAt(originColumn.offset(0, y, 0));
            if (state == null || state.blocksMotion()) {
                return true; // світ недоступний - обережно вважаємо заблокованим
            }
        }
        return false;
    }

    /**
     * Те саме, форма замість {@code blocksMotion()}: тонкий виступ чи панель скраю клітинки (край
     * відкритого люка, стовпчик паркану не по центру променя) більше не гасять увесь коридор польоту,
     * якщо мобу реально є куди пролетіти повз них — {@link ShapeProbe#blocksBody} рахує перекриту
     * частку сліду мобу, а не саму лише наявність будь-якої геометрії в клітинці.
     */
    private boolean blocksBodyShapeAware(BlockPos originColumn, int dy) {
        BlockGetter level = levelReader();
        double width = mobFootprintWidth();
        int low = Math.min(0, dy);
        int high = Math.max(1, dy + 1);
        for (int y = low; y <= high; y++) {
            BlockPos cell = originColumn.offset(0, y, 0);
            if (ShapeProbe.blocksBody(level, cell, ShapeProbe.centeredFootprint(cell, width))) {
                return true;
            }
        }
        return false;
    }

    /** Підлога тверда, а на рівні ніг вільно - на рівні {@code origin.y + dy}. */
    private boolean hasFloorAt(BlockPos origin, int dx, int dz, int dy) {
        if (Config.ENABLE_SHAPE_AWARE_PATHING.get()) {
            return classifyCellShapeAware(origin.offset(dx, dy, dz)) == WALKABLE;
        }
        return hasFloorAtLegacy(origin, dx, dz, dy);
    }

    private boolean hasFloorAtLegacy(BlockPos origin, int dx, int dz, int dy) {
        BlockPos column = origin.offset(dx, dy, dz);
        BlockPos floorPos = column.below();

        BlockState floorState = getBlockStateAt(floorPos);
        BlockState feetState = getBlockStateAt(column);

        if (floorState == null || feetState == null) {
            return false;
        }

        // Перевіряємо, що підлога блокирует рух (тверда), а на рівні ніг — вільно
        return floorState.blocksMotion() && !feetState.blocksMotion();
    }

    /** Придатне для приземлення: підлога, а над нею вільно і на рівні ніг, і на рівні голови - на {@code origin.y + dy}. */
    private boolean isStandable(BlockPos origin, int dx, int dz, int dy) {
        if (!hasFloorAt(origin, dx, dz, dy)) {
            return false;
        }
        BlockPos head = origin.offset(dx, dy + 1, dz);
        if (Config.ENABLE_SHAPE_AWARE_PATHING.get()) {
            BlockGetter level = levelReader();
            return !ShapeProbe.blocksBody(level, head, ShapeProbe.centeredFootprint(head, mobFootprintWidth()));
        }
        BlockState headState = getBlockStateAt(head);
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

    /**
     * {@link BlockGetter} для {@link ShapeProbe} — той самий пріоритет джерела, що й
     * {@link #getBlockStateAt}: контекст пошуку (Fast/Thread-safe), інакше фолбек на живий світ моба.
     */
    private BlockGetter levelReader() {
        if (this.currentContext != null) {
            return this.currentContext.level();
        }
        if (this.mob != null) {
            return this.mob.level();
        }
        return null;
    }

    /**
     * Реальна ширина ЦЬОГО моба — точніша за {@link ShapeProbe#DEFAULT_MOB_WIDTH} (типове значення
     * для загального ванільного мексину, де конкретного {@code Mob} під рукою нема).
     */
    private double mobFootprintWidth() {
        return this.mob != null ? this.mob.getBbWidth() : ShapeProbe.DEFAULT_MOB_WIDTH;
    }
}