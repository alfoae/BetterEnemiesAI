package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.HopNode;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeAwareNodeEvaluator;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeSettings;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeWalk.Spot;
import com.example.examplemod.debug.GraphProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;

import java.util.Arrays;

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
 * <b>v5 — нестандартні ("криві") блоки: плити, краї відкритих люків, стовпчики, килими, блоки з модів.</b>
 * Раніше стрибок цілився в ЦЕНТР клітинки на ЦІЛІЙ висоті вузла, а грубий коридор ({@link #isCorridorClear})
 * дивився лише центр кожної клітинки. Тепер, коли в стрибку бере участь нерегулярна форма (колонка вузла,
 * колонка приземлення чи будь-яка клітинка коридору - {@link #involvesIrregular}), рішення приймає
 * {@link ShapeJump#choose}: із придатних точок стояння відриву й приземлення ({@code ShapeWalk.candidates} -
 * ті самі, що й у побудові шляху) обирається пара, для якої {@link ShapeJump#simulate} (ванільна фізика +
 * колізія хітбокса з реальними коробками) доводить, що моб долетить, нічого не зачепить і приземлиться
 * саме на цю поверхню. Δy береться з РЕАЛЬНИХ висот поверхонь (плита 0.5, килим 0.0625...), блок для тертя й
 * jumpFactor - за ванільним правилом для точки стояння ({@link GapJumpUtils#takeoffProps}). Для повних
 * блоків (центр, повне покриття, цілий рівень, регулярний коридор) лишається СТАРИЙ код без жодних змін.
 * <p>
 * Друге: клітинка перед краєм могла мати поверхню (ребро люка в дальньому куті), але з краю на неї не
 * ступити; раніше вона вважалась прохідною, крок не існував, а стрибок не генерувався - глухий кут. Тепер
 * така клітинка прохідною вважається лише якщо справді існує геометричний перехід ({@link #classifyStartFront}).
 * <p>
 * НЕ займає: стрибок між СУСІДНІМИ клітинками (відстань до 1.41), навіть якщо між опорами є розрив менший
 * за клітинку (дві свічки поруч): це за визначенням {@code GapJumpUtils.JUMP_SEGMENT_THRESHOLD} крок, а не стрибок.
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
public class GapJumpNodeEvaluator extends ShapeAwareNodeEvaluator {

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
    /**
     * Штраф за "гоп" на сусідню клітинку (в одиницях вартості A*). Навмисно великий: коли можна пройти звичайними
     * стрибками "через одну" (перевірені, відстань 2), шлях має обирати їх, а гоп лишається для випадків, де інакше
     * ніяк (кінець доріжки з люків, перший блок мосту одразу за люком).
     */
    private static final float HOP_MALUS = 4.0F;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static final byte UNKNOWN = 0;
    /** Є підлога й вільно на рівні ніг: моб просто зробить крок, стрибок звідси в цей бік не потрібен. */
    private static final byte WALKABLE = 1;
    /** Немає підлоги й на рівні ніг вільно: справжнє провалля - лише крізь нього має сенс стрибати. */
    private static final byte VOID = 2;
    /** На рівні ніг стіна/блок (або світ недоступний): крізь неї стрибок не йде. */
    private static final byte BLOCKED = 3;

    /** Чи є хоч одна пара точок (відрив у клітинці вузла, приземлення в цільовій), для якої політ проходить. */
    private boolean exactJumpOk(JumpCtx ctx, Spot[] from, int dx, int dz, int dy) {
        // Виняток тут обірвав би пошук шляху (а з ним і тік моба/сервера) - гірше, ніж відхилений стрибок.
        try {
            BlockPos land = ctx.origin.offset(dx, dy, dz);
            Spot[] to = geoSpotsEarly(land.getX(), land.getY(), land.getZ());
            if (to.length == 0) {
                return false;
            }
            return ShapeJump.choose(this.source, this.dims, Arrays.asList(from), Arrays.asList(to), modelOf(ctx)) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Точні стрибки по нестандартних блоках увімкнені тим самим перемикачем, що й форм-орієнтована ходьба
     * ({@link ShapeSettings#enabledFor}: {@code enableShapeAwareWalking} + чорний список мобів). Вимкнено - граф
     * і виконавець працюють старим кодом (центр клітинки, цілі висоти), як до цієї зміни.
     */
    private boolean exactJumps = true;

    @Override
    public void prepare(PathNavigationRegion level, Mob mob) {
        super.prepare(level, mob);
        this.exactJumps = ShapeSettings.enabledFor(mob);
    }

    /**
     * Геометрія готова й точні стрибки дозволені.
     */
    private boolean exactReady() {
        return this.exactJumps && this.source != null && this.dims != null;
    }

    @Override
    public int getNeighbors(Node[] nodes, Node node) {
        int count = super.getNeighbors(nodes, node);
        boolean probe = isProbeStart(node);
        if (count >= ALL_NORMAL_NEIGHBORS) {
            if (probe) {
                GraphProbe.logText(this.mob, "стрибкові ребра НЕ скануються: клітинка повністю відкрита (" + count + " сусідів)");
                GraphProbe.disarm();
            }
            return count; // повністю відкрита клітинка - тут стрибок ніколи не потрібен, не скануємо
        }
        BlockPos origin = new BlockPos(node.x, node.y, node.z);
        BlockPos floorPos = origin.below();
        BlockState floor = getBlockStateAt(floorPos);
        if (floor == null || this.mob == null) {
            return count;
        }
        var level = this.mob.level();

        // Точна геометрія (ShapeJump) - лише де є нерегулярна форма; на повних блоках лишається старий код.
        JumpCtx ctx = new JumpCtx(node, origin, exactReady() && columnIsIrregular(node.x, node.y, node.z));

        byte[] frontCache = new byte[9]; // стан 8 клітинок перед краєм НА РІВНІ СТАРТУ; ключ (dx+1)*3+(dz+1)
        // Дальність залежить від блока, З ЯКОГО моб відривається (тертя / speedFactor / jumpFactor): лід - далі,
        // слайм, пісок душ, мед - ближче; і від Δy (вгору - вікно коротше, тож і дальність менша, навіть на
        // звичайному блоці; вниз - трохи більша). Тому рахуємо тут, для КОЖНОГО вузла й для КОЖНОГО рівня Δy
        // окремо під час побудови шляху, а не одним числом на всього моба й не перед самим стрибком.
        // ПОВНИЙ діапазон -JUMP_DOWN_LIMIT_BLOCKS..+JUMP_UP_LIMIT_BLOCKS (не лише крайні значення): щоб моб
        // міг приземлитись саме на НАЙБЛИЖЧУ доступну сходинку, а не тільки на найглибшу з можливих (наприклад,
        // сходи в 3 рівні - Δy=-1 на першу, Δy=-2 на другу, Δy=-3 на третю, кожна свій кандидат).
        for (int dy = -GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS; dy <= GapJumpPhysics.JUMP_UP_LIMIT_BLOCKS; dy++) {
            double factor = GapJumpUtils.blockRangeFactor(floor, level, floorPos, this.mob, dy);
            int legacyGap = GapJumpUtils.estimateMaxJumpRangeBlocks(this.mob, factor);
            int scanGap = legacyGap;
            if (ctx.originIrregular) {
                // З нестандартної опори (плита 0.5, люк...) реальний Δy інший, ніж ціла різниця рівнів: для
                // підйому з плити на блок на клітинку вище це +0.5, а не +1 - дальність відчутно більша. Промені
                // скануємо за найсприятливішою оцінкою; кожного кандидата все одно вирішує точна перевірка.
                scanGap = Math.max(scanGap, irregularScanGap(ctx, dy));
            }
            if (scanGap < 1) {
                continue; // цей Δy з цього блока недосяжний (напр. вгору з меду, чи просто задалеко) - не пробуємо
            }
            for (GapJumpRays.Ray ray : GapJumpRays.forMaxGap(scanGap)) {
                if (count >= nodes.length) {
                    break; // буфер сусідів повний - більше нема куди писати (промені відсортовані: спершу найближчі)
                }
                count = tryRay(nodes, count, ctx, ray, frontCache, dy);
            }
        }
        count = addHops(nodes, count, ctx, frontCache);
        if (probe) {
            GraphProbe.logNodes(this.mob, "ПІСЛЯ стрибкових ребер і гопів (фінальний список для A*)", node, nodes, count);
            StringBuilder fronts = new StringBuilder("стан 'першої клітинки перед краєм' на рівні старту (VOID = вважається проваллям -> породжує стрибок; originIrregular=")
                    .append(ctx.originIrregular).append("):");
            for (int k = 0; k < frontCache.length; k++) {
                if (frontCache[k] == UNKNOWN) {
                    continue;
                }
                String st = frontCache[k] == WALKABLE ? "WALKABLE" : frontCache[k] == VOID ? "VOID" : "BLOCKED";
                fronts.append(" (").append(k / 3 - 1).append(',').append(k % 3 - 1).append(")=").append(st);
            }
            GraphProbe.logText(this.mob, fronts.toString());
            GraphProbe.disarm();
        }
        return count;
    }

    /**
     * @param dy          landing.y - origin.y: {@code 0} - рівний стрибок, {@code >0} - вгору, {@code <0} - вниз
     *                    (у межах {@code -JUMP_DOWN_LIMIT_BLOCKS..+JUMP_UP_LIMIT_BLOCKS} - {@link #getNeighbors})
     */
    private int tryRay(Node[] nodes, int count, JumpCtx ctx, GapJumpRays.Ray ray, byte[] frontCache, int dy) {
        BlockPos origin = ctx.origin;
        // 1. Край у цьому напрямку? Перша клітинка має бути справжнім проваллям, ЗАВЖДИ на рівні СТАРТУ
        //    (dy тут НЕ підставляємо - "лесенка", де порожньо на обох поверхах, інакше не розпізнається як
        //    провалля). Якщо вона прохідна - звичайний крок з цього вже впорається (а стрибок згенерується
        //    з наступної клітинки); якщо там стіна - крізь неї не стрибаємо.
        int frontIndex = (ray.frontX() + 1) * 3 + (ray.frontZ() + 1);
        if (frontCache[frontIndex] == UNKNOWN) {
            frontCache[frontIndex] = classifyStartFront(ctx, ray.frontX(), ray.frontZ());
        }
        if (frontCache[frontIndex] != VOID) {
            return count;
        }

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
                if (involvesIrregular(ctx, ray, k, dx, dz, dy)) {
                    // Нестандартна форма (опора чи коридор): рішення приймає геометрія й симуляція польоту,
                    // а не грубі "центр клітинки" / "виступ вище кроку". Відхилений кандидат НЕ закриває
                    // промінь: далі по ньому може бути інша платформа.
                    if (count >= nodes.length) {
                        return count;
                    }
                    Spot[] from = fromSpotsOf(ctx);
                    boolean ok = from.length == 0
                            ? isCorridorClear(origin, ray.corridor()[k], dy) // немає точок відриву - як раніше
                            : exactJumpOk(ctx, from, dx, dz, dy);
                    if (ok) {
                        nodes[count++] = jumpNode(origin, dx, dz, dy, landed);
                        landed = true;
                    }
                } else {
                    // Звичайні блоки - старий код. (Сюди не потрапляємо з нестандартної опори відриву: там
                    // involvesIrregular завжди true, а промені для звичайної опори якраз у межах дальності.)
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
            }
            // Чи закінчується тут платформа (одразу за нею провалля, на ЇЇ Ж рівні +dy)? Тільки тоді наступну
            // придатну клітинку на промені можна перестрибнути. Для променів із кроком >1 клітинки "клітинка
            // за" береться тією самою відносною позицією, що й перша клітинка від краю (лінія періодична).
            voidBehind = classifyFront(origin, dx + ray.frontX(), dz + ray.frontZ(), dy) == VOID;
        }
        return count;
    }

    // -------------------------------------------------------------------------------------
    // Точна геометрія (нестандартні блоки)
    // -------------------------------------------------------------------------------------

    /**
     * "ГОП": ребро на СУСІДНЮ клітинку (відстань 1), яка придатна для стояння, але ПІШКИ недосяжна (classifyStartFront
     * дав VOID саме через провал опори під хітбоксом, а не через порожнечу). Ребро додається лише якщо точний
     * планувальник ({@link ShapeJump#choose}) знаходить для нього реальний стрибок - тобто рівно за тими ж правилами,
     * що й звичайні стрибки на нерегулярній опорі. Не знайшов - ребра нема, як і було раніше.
     */
    private int addHops(Node[] nodes, int count, JumpCtx ctx, byte[] frontCache) {
        if (!exactReady()) {
            return count;
        }
        BlockPos origin = ctx.origin;
        for (int[] c : CARDINALS) {
            if (count >= nodes.length) {
                break;
            }
            int fx = c[0];
            int fz = c[1];
            int idx = (fx + 1) * 3 + (fz + 1);
            if (frontCache[idx] == UNKNOWN) {
                frontCache[idx] = classifyStartFront(ctx, fx, fz);
            }
            if (frontCache[idx] != VOID) {
                continue;
            }
            BlockPos front = origin.offset(fx, 0, fz);
            if (!isStandableCell(front.getX(), front.getY(), front.getZ())) {
                continue; // справжня порожнеча - це справа звичайних стрибків (від 2 клітин), не гопа
            }
            Spot[] from = fromSpotsOf(ctx);
            if (from.length == 0 || !exactJumpOk(ctx, from, fx, fz, 0)) {
                continue;
            }
            Node hop = new HopNode(front.getX(), front.getY(), front.getZ());
            hop.type = PathType.WALKABLE;
            hop.costMalus = HOP_MALUS;
            nodes[count++] = hop;
        }
        return count;
    }

    private Spot[] fromSpotsOf(JumpCtx ctx) {
        if (ctx.from == null) {
            ctx.from = !exactReady()
                    ? new Spot[0]
                    : geoSpotsEarly(ctx.origin.getX(), ctx.origin.getY(), ctx.origin.getZ());
        }
        return ctx.from;
    }

    private ShapeJump.Model modelOf(JumpCtx ctx) {
        if (ctx.model == null) {
            ctx.model = GapJumpUtils.jumpModel(this.mob.level(), this.mob, GapJumpUtils.runSpeedSetpoint(this.mob));
        }
        return ctx.model;
    }

    /**
     * Чи бере участь у стрибку нерегулярна форма: колонка відриву, колонка приземлення або клітинка коридору
     * (на рівні старту й на рівні приземлення). Лише тоді діє точна перевірка; інакше - старий код.
     */
    private boolean involvesIrregular(JumpCtx ctx, GapJumpRays.Ray ray, int k, int dx, int dz, int dy) {
        if (!exactReady()) {
            return false;
        }
        if (ctx.originIrregular) {
            return true;
        }
        BlockPos o = ctx.origin;
        if (columnIsIrregular(o.getX() + dx, o.getY() + dy, o.getZ() + dz)) {
            return true;
        }
        for (int[] c : ray.corridor()[k]) {
            if (columnIsIrregular(o.getX() + c[0], o.getY(), o.getZ() + c[1])
                    || (dy != 0 && columnIsIrregular(o.getX() + c[0], o.getY() + dy, o.getZ() + c[1]))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Що відомо про вузол на час одного {@link #getNeighbors}: нерегулярність колонки, придатні точки відриву
     * й модель фізики (рахуються ліниво - на повних блоках вони взагалі не потрібні).
     */
    private static final class JumpCtx {
        final Node node;
        final BlockPos origin;
        final boolean originIrregular;
        Spot[] from;
        ShapeJump.Model model;

        JumpCtx(Node node, BlockPos origin, boolean originIrregular) {
            this.node = node;
            this.origin = origin;
            this.originIrregular = originIrregular;
        }
    }

    /**
     * Кількість блоків розриву, яку варто сканувати з нестандартної опори для рівня {@code dy}: дальність за
     * НАЙСПРИЯТЛИВІШОЮ реальною різницею висот (поверхня відриву може бути вище за ціле {@code origin.y}).
     */
    private int irregularScanGap(JumpCtx ctx, int dy) {
        try {
            Spot[] from = fromSpotsOf(ctx);
            if (from.length == 0) {
                return 0;
            }
            double maxFrac = 0.0;
            for (Spot s : from) {
                maxFrac = Math.max(maxFrac, s.surfaceY() - ctx.origin.getY());
            }
            GapJumpUtils.TakeoffProps p = GapJumpUtils.takeoffProps(
                    this.mob.level(), this.mob, from[0].x(), from[0].surfaceY(), from[0].z());
            double factor = GapJumpPhysics.blockRangeFactor(p.friction(), p.slowdown(), p.jumpFactor(), dy - maxFrac);
            return GapJumpUtils.estimateMaxJumpRangeBlocks(this.mob, factor);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Перша клітинка променя на рівні старту. Як {@link #classifyFront}, але в нерегулярній зоні клітинка, на
     * якій Є опора, вважається прохідною ({@link #WALKABLE}) лише якщо з вузла на неї реально можна ступити:
     * інакше (ребро люка в дальньому куті клітинки, стовпчик за проваллям менше клітинки) це «провалля з
     * опорою за ним» - тобто те, через що стрибають.
     */
    private byte classifyStartFront(JumpCtx ctx, int fx, int fz) {
        BlockPos column = ctx.origin.offset(fx, 0, fz);
        if (isStandableCell(column.getX(), column.getY(), column.getZ())) {
            if (exactReady()
                    && (ctx.originIrregular || columnIsIrregular(column.getX(), column.getY(), column.getZ()))
                    && !anyMove(fromSpots(ctx.node), geoSpotsEarly(column.getX(), column.getY(), column.getZ()))) {
                return VOID;
            }
            return WALKABLE;
        }
        return isBodyZoneClear(column.getX(), column.getY(), column.getZ()) ? VOID : BLOCKED;
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
     */
    private byte classifyFront(BlockPos origin, int dx, int dz, int dy) {
        BlockPos column = origin.offset(dx, dy, dz);
        // Реальна форма (ShapeProbe/ShapeWalk), а не blocksMotion(): плита, килим, край люка — це опора.
        if (isStandableCell(column.getX(), column.getY(), column.getZ())) {
            return WALKABLE;
        }
        return isBodyZoneClear(column.getX(), column.getY(), column.getZ()) ? VOID : BLOCKED;
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

    /**
     * Клітинки в діапазоні висот {@code [min(0,dy), max(1,dy+1)]} не мають мати виступів вище за крок підйому.
     */
    private boolean blocksBody(BlockPos originColumn, int dy) {
        int low = Math.min(0, dy);
        int high = Math.max(1, dy + 1);
        for (int y = low; y <= high; y++) {
            if (blocksFlightAt(originColumn.getX(), originColumn.getY() + y, originColumn.getZ())) {
                return true; // у т.ч. якщо світ недоступний - обережно вважаємо заблокованим
            }
        }
        return false;
    }

    /** Є де стати (реальна опора за формою колізії) і вільне тіло - на рівні {@code origin.y + dy}. */
    private boolean hasFloorAt(BlockPos origin, int dx, int dz, int dy) {
        BlockPos column = origin.offset(dx, dy, dz);
        return isStandableCell(column.getX(), column.getY(), column.getZ());
    }

    /** Придатне для приземлення: опора + вільне тіло на повну висоту моба - на {@code origin.y + dy}. */
    private boolean isStandable(BlockPos origin, int dx, int dz, int dy) {
        return hasFloorAt(origin, dx, dz, dy);
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