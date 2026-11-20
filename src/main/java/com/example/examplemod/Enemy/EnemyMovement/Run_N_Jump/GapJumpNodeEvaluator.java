package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Config;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.HopNode;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeAwareNodeEvaluator;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeSettings;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeWalk.Spot;
import com.example.examplemod.debug.DebugLog;
import com.example.examplemod.debug.GraphProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
 * <b>v6 — перестрибування через ряд нестандартних опор (відкриті люки).</b> Платформа на промені закінчується там,
 * де з приземлення на наступну клітинку не можна ступити ПІШКИ ({@link #platformEnd}), а не там, де в наступній
 * клітинці просто немає підлоги. Для повних блоків це одне й те саме, для відкритих люків - ні: опора кожного -
 * смужка 0.19 на краю клітинки з 0.81 порожнього між смужками, а хітбокс моба 0.6, тож ряд люків - це послідовність
 * ОКРЕМИХ опор, а не суцільна платформа. Раніше такий ряд вважався однією платформою: після першого приземлення
 * на люк решта ряду і блок за ним відкидались як "продовження, ногами дійдемо", тож ▣▢▢▢▣ давав стрибок лише через
 * один люк, а далі ще "гоп" на останній люк. Тепер це один стрибок через усі люки - так само, як ▣...▣ зі звичайних
 * блоків. Такі "додаткові" ребра додаються в кінець списку сусідів і лише в залишок буфера
 * ({@link #addExtraJumps}), тож старі ребра вони не витісняють.
 * <p>
 * <b>v7 — найдовші стрибки, а не "люк через люк".</b> Ванільний PathFinder - це A* із евристикою x1.5: він розкриває
 * вузли за f = g + 1.5h і зупиняється на першому, що в межах accuracy від цілі. Якщо вартість стрибка росте з
 * відстанню швидше, ніж 1.5 за блок (було 2.25), довший стрибок має БІЛЬШИЙ f за коротший, і A* ловить ціль
 * ланцюгом коротких, хоч довгий дешевший: у ряді люків (опора на кожній клітинці) виходила суміш стрибків на 2 і 3 і
 * "люк через люк" замість нормального стрибка. Тому в нерегулярній зоні кожне наступне приземлення на промені коштує
 * як перше + {@link #IRREGULAR_SKIP_COST_PER_BLOCK} (менше за 1.5) за кожен блок понад ним: пошук іде найдовшими
 * стрибками, які дозволяє мобу дальність. Промені лише зі звичайних блоків лишились як були.
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
 * <b>v16 - буфер сусідів ділиться між Δy ({@link NeighborBudget}).</b> Ванільний PathFinder дає буфер на 32 вузли. Раніше
 * усі Δy (від -3 до +1) писали в нього підряд, тож велика платформа внизу (десятки посадок з Δy=-1) забирала всі слоти, і
 * кандидати з Δy=0 та Δy=+1 не потрапляли в граф зовсім: з одиночного блока над землею ребро на наступну сходинку
 * {@code ▣▢▢▢▣▢▢▢▣} вгору просто не існувало, A* не бачив продовження, а ланцюжок стрибків обривався на приземленні.
 * Тепер кандидати збираються окремо для кожного Δy й лише потім ділять буфер порівну; поки все вміщується - порядок і
 * склад сусідів такі самі, як були.
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
     * Вартість кожного блока відстані ПОНАД першим приземленням на промені для перестрибувань у нерегулярній зоні
     * (ряд відкритих люків, плити тощо - див. {@link #platformEnd}). Навмисно МЕНША за вагу евристики ванільного
     * PathFinder: він множить відстань до цілі на 1.5, тож для A* довший стрибок - це прогрес (його f = g + 1.5h
     * менший, ніж у коротшого), і пошук іде найдовшими стрибками. З вартістю як у звичайного перестрибування
     * (2.25 за блок) довший стрибок мав би БІЛЬШИЙ f: A* розкривав би ближчі приземлення першими й ловив ціль
     * ланцюгом коротких ("люк через люк"), хоча за повною вартістю довгий таки дешевший - ванільний A* зважений і
     * мінімуму не гарантує. Правило ОДНЕ для всіх перестрибувань у нерегулярній зоні, а не лише для нових: якби
     * старі лишились дорогими (нахил 2.25), A* віддавав би перевагу новим коротшим замість одного довгого.
     * Промені лише зі звичайними блоками рахуються як і раніше ({@link #SKIP_MALUS_PER_BLOCK}).
     */
    private static final float IRREGULAR_SKIP_COST_PER_BLOCK = 1.25F;

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

    /**
     * Результат {@link #platformEnd}: за платформою розриву нема (її продовжує підлога, на яку можна ступити, або стіна).
     */
    private static final byte BEHIND_NO_GAP = 0;
    /** За платформою провалля - справжній розрив (так було завжди). */
    private static final byte BEHIND_VOID = 1;
    /** За платформою є опора, але з приземлення на неї пішки не ступити (ряд відкритих люків) - нова поведінка. */
    private static final byte BEHIND_UNREACHABLE = 2;

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

    /**
     * v20: максимальна дальність променів (блоків) для рівнів глибше старого -3: захист пошуку шляху від розростання вартості.
     */
    private static final int DEEP_LEVEL_MAX_SCAN_GAP = 6;

    // v20: шкода від падіння. fallProfile == null - стара логіка (фіксований нижній предел -3 без штрафів).
    private FallMath.Profile fallProfile;
    private int dynamicDownLimit = GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS;
    private float fallCostPerHp;
    /** Апекс стрибка з поточного вузла (блоків): стрибок додає його до висоти падіння - ванільний лічильник рахує лише рух униз. */
    private double takeoffApex;
    /** Найменша шкода серед вузлів-стрибків, створених у ПОТОЧНОМУ getNeighbors (керує ліниво-глибшим скануванням). */
    private float minJumpDamage;
    /** Штраф за шкоду останнього jumpNode (щоб не рахувати його вдруге в anchorCost перестрибувань). */
    private float lastJumpPenalty;
    /**
     * v21 (дебаг [FALL]): статистика ОДНОГО пошуку для зведення в {@link #done}; null - не збираємо (категорія вимкнена або цей
     * пошук не вибрано: збирається лише один пошук на моба раз на 2 с, щоб не гальмувати A*).
     */
    private FallDebug.SearchStats fallStats;
    private static final int[] NO_SOFT = new int[0];
    private static final int[][] SOFT_NEIGHBORS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /**
     * Ціль має бути хоч на стільки блоків нижче вузла, щоб шукати м'які приземлення (інакше стрибати вниз нема сенсу).
     */
    private static final int SOFT_MIN_TARGET_DROP = 3;
    /**
     * Верхня межа розриву для променів м'яких приземлень (звичайні глибокі рівні обмежені 6).
     */
    private static final int SOFT_MAX_SCAN_GAP = 10;
    /**
     * Скільки м'яких кандидатів додаємо в граф з одного вузла.
     */
    private static final int SOFT_MAX_CANDIDATES = 5;
    private static final int SOFT_BUFFER = 24;
    private static final int SOFT_MIN_RADIUS = 6;
    private static final int SOFT_MAX_RADIUS = 18;
    /**
     * v24: запас індексу навколо вузла понад дальність стрибка - у межах нього той самий індекс годиться й для сусідніх вузлів.
     */
    private static final int SOFT_INDEX_MARGIN = 6;
    /**
     * v24: скільки клітинок рівної опори позаду краю рахуємо як розбіг (більше вже не дає швидкості).
     */
    private static final int SOFT_RUNUP_MAX_CELLS = 5;
    /**
     * v24: до цілих клітинок розбігу додаємо: моб стартує з центру найдальшої клітинки, а відрив - трохи за центром краю.
     */
    private static final double SOFT_RUNUP_BASE = 0.3;
    /**
     * На якій глибині рахується радіус індексу (далі досяжність росте повільно).
     */
    private static final int SOFT_RADIUS_PROBE_DEPTH = 16;
    /**
     * v24: колонки впритик, прочитані в цьому пошуку: ключ (x, верхній рівень, z) -> {вид, Y клітинки ніг} або {@link #NO_SOFT}.
     */
    private final java.util.HashMap<Long, int[]> softColumnCache = new java.util.HashMap<>();
    /** v22: ванільні ребра спуску в цьому пошуку рахуються з урахуванням шкоди (див. {@link #applyDropCosts}). */
    private boolean dropAware;
    // v23: м'які приземлення (вода/сіно/драбини/ліжко...) як цілі RNJ; спуски на сусідню клітинку через RNJ (DropNode)
    private boolean dropViaRnj;

    /**
     * Геометрія готова й точні стрибки дозволені.
     */
    private boolean exactReady() {
        return this.exactJumps && this.source != null && this.dims != null;
    }

    /**
     * v17: чи є серед перших {@code count} сусідів хоч один СПУСК (ноги нижче, ніж у вузла). Лише в напрямку спуску клітинка на
     * рівні старту - справжнє провалля, з якого можливий стрибок; сусіди на тому ж рівні чи вище його не дають.
     */
    private static boolean hasStepDownNeighbor(Node[] nodes, int count, Node node) {
        for (int i = 0; i < count; i++) {
            Node n = nodes[i];
            if (n != null && n.y < node.y) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code Δy:кількість} для кожної групи кандидатів - для рядка про переповнення буфера.
     */
    private static String describeLevels(int[] perLevel, int downLimit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < perLevel.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            int dy = i - downLimit;
            sb.append(dy > 0 ? "+" : "").append(dy).append(':').append(perLevel[i]);
        }
        return sb.toString();
    }
    private boolean softEnabled;

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
        // ...і з неї: за клітинкою є опора, але пішки не дійти (ряд відкритих люків) - випадок, якого раніше не було
        boolean unreachableBehind = false;
        // Відстань і повна вартість (відстань + malus) ребра ПЕРШОГО приземлення на промені: від них відлічується
        // вартість подальших перестрибувань у нерегулярній зоні (див. skipJumpNode)
        double anchorDistance = 0.0;
        double anchorCost = 0.0;
        for (int k = ray.kMin(); k <= ray.kMax(); k++) {
            int dx = ray.stepX() * k;
            int dz = ray.stepZ() * k;
            if (!isStandable(origin, dx, dz, dy)) {
                if (landed) {
                    voidBehind = true; // (це може бути й стіна - коридор наступного кандидата її відсіче)
                    unreachableBehind = false;
                }
                continue;
            }
            // v20: приземлення, яке моб не переживе (з урахуванням здоров'я, ефектів, чарів і блока внизу) - як відсутнє:
            // кандидат НЕ створюється, промінь триває (далі може бути безпечніша платформа чи вода).
            if (dy < 0 && !landingAllowed(origin, dx, dz, dy)) {
                if (this.fallStats != null) {
                    dbgLethal(origin, dx, dz, dy);
                }
                if (landed) {
                    voidBehind = true;
                    unreachableBehind = false;
                }
                continue;
            }
            // Придатна клітинка. Кандидат - якщо це перше приземлення, або перед нею була порожнеча
            // (інакше це просто продовження тієї ж платформи, ногами й так дійдемо).
            if (!landed || voidBehind) {
                // "Додатковий" кандидат існує ЛИШЕ завдяки новому правилу (опора є, але пішки не дійти). Його ребро
                // йде в кінець списку сусідів (див. addExtraJumps) і не може витіснити звичайне: усе, що було до цієї
                // правки, лишається в списку на своїх місцях і в тому ж порядку (змінилась лише вартість
                // перестрибувань у нерегулярній зоні - див. skipJumpNode).
                boolean extra = landed && unreachableBehind;
                if (involvesIrregular(ctx, ray, k, dx, dz, dy)) {
                    // Нестандартна форма (опора чи коридор): рішення приймає геометрія й симуляція польоту,
                    // а не грубі "центр клітинки" / "виступ вище кроку". Відхилений кандидат НЕ закриває
                    // промінь: далі по ньому може бути інша платформа.
                    if (!extra && count >= nodes.length) {
                        return count;
                    }
                    Spot[] from = fromSpotsOf(ctx);
                    boolean ok = from.length == 0
                            ? isCorridorClear(origin, ray.corridor()[k], dy) // немає точок відриву - як раніше
                            : exactJumpOk(ctx, from, dx, dz, dy);
                    if (ok) {
                        // Перше приземлення - як завжди; подальші (перестрибування) в нерегулярній зоні - за єдиним
                        // правилом skipJumpNode, і старі, і нові.
                        Node jump = landed
                                ? skipJumpNode(origin, dx, dz, dy, anchorDistance, anchorCost)
                                : jumpNode(origin, dx, dz, dy, false);
                        if (!landed) {
                            anchorDistance = Math.sqrt((double) dx * dx + (double) dz * dz);
                            anchorCost = anchorDistance + jump.costMalus - this.lastJumpPenalty; // v20: штраф за шкоду кожне приземлення рахує саме
                        }
                        if (extra) {
                            ctx.addExtra(jump);
                        } else {
                            nodes[count++] = jump;
                        }
                        landed = true;
                    }
                } else if (!extra) {
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
                    Node jump = jumpNode(origin, dx, dz, dy, landed);
                    nodes[count++] = jump;
                    if (!landed) {
                        anchorDistance = Math.sqrt((double) dx * dx + (double) dz * dz);
                        anchorCost = anchorDistance + jump.costMalus - this.lastJumpPenalty; // v20: штраф за шкоду кожне приземлення рахує саме
                    }
                    landed = true;
                }
            }
            // Чи закінчується тут платформа? Тільки тоді наступну придатну клітинку на промені можна перестрибнути.
            // Для повних блоків - одразу за нею провалля (на ЇЇ Ж рівні +dy); для нерегулярних форм (ряд відкритих
            // люків) - ще й коли з неї на наступну клітинку НЕ можна ступити пішки, хоч підлога там і є (див.
            // platformEnd). Для променів із кроком >1 клітинки "клітинка за" береться тією самою відносною
            // позицією, що й перша клітинка від краю (лінія періодична).
            byte behind = platformEnd(ctx, ray, dx, dz, dy);
            voidBehind = behind != BEHIND_NO_GAP;
            unreachableBehind = behind == BEHIND_UNREACHABLE;
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
     * Вузол перестрибування в нерегулярній зоні (друге й далі приземлення на промені): повна вартість ребра =
     * вартість першого приземлення + {@link #IRREGULAR_SKIP_COST_PER_BLOCK} за кожен блок відстані ПОНАД ним. Вартість
     * зростає з відстанню повільніше, ніж вага евристики A*, тож пошук віддає перевагу найдовшому стрибку, який
     * можливий. Стрибок ВГОРУ, як і раніше, дорожчий на {@link #UP_JUMP_EXTRA_MALUS_PER_BLOCK} за блок.
     *
     * @param firstDistance відстань першого приземлення на промені
     * @param firstCost     повна вартість (відстань + malus) ребра до нього
     */
    private Node skipJumpNode(BlockPos origin, int dx, int dz, int dy, double firstDistance, double firstCost) {
        BlockPos landing = origin.offset(dx, dy, dz);
        Node jumpNode = new Node(landing.getX(), landing.getY(), landing.getZ());
        jumpNode.type = PathType.WALKABLE;
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
        double ratePerBlock = IRREGULAR_SKIP_COST_PER_BLOCK + (dy > 0 ? UP_JUMP_EXTRA_MALUS_PER_BLOCK : 0.0F);
        double cost = firstCost + (distance - firstDistance) * ratePerBlock;
        jumpNode.costMalus = (float) Math.max(0.0, cost - distance);
        jumpNode.costMalus += fallPenalty(origin, dx, dz, dy); // v20: власна шкода цього приземлення (firstCost її не містить)
        return jumpNode;
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
            return GapJumpUtils.maxJumpGap(this.mob,
                    GapJumpPhysics.Takeoff.of(p.friction(), p.slowdown(), p.jumpFactor()), dy - maxFrac);
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

    /**
     * v20: скан одного рівня Δy (раніше це було тілом циклу в getNeighbors). Повертає кількість знайдених кандидатів.
     */
    private int scanLevel(Node[] nodes, JumpCtx ctx, GapJumpPhysics.Takeoff takeoff, byte[] frontCache, int dy,
                          Node[][] buckets, int[] sizes, int downLimit) {
        // v15: дальність - з балістичної моделі (розбіг + спринт-стрибок + повітряне керування як у гравця), для блока
        // відриву й цього Δy; 0 - недосяжно (напр. вгору з меду).
        int legacyGap = GapJumpUtils.maxJumpGap(this.mob, takeoff, dy);
        int scanGap = legacyGap;
        if (ctx.originIrregular) {
            // З нестандартної опори (плита 0.5, люк...) реальний Δy інший, ніж ціла різниця рівнів: для
            // підйому з плити на блок на клітинку вище це +0.5, а не +1 - дальність відчутно більша. Промені
            // скануємо за найсприятливішою оцінкою; кожного кандидата все одно вирішує точна перевірка.
            scanGap = Math.max(scanGap, irregularScanGap(ctx, dy));
        }
        if (dy < -GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS) {
            // v20: глибокі рівні (нижче старого -3) скануємо з обмеженою дальністю: кількість променів росте квадратично, а
            // сенс глибокого стрибка - зазвичай "зістрибнути на найближчу нижчу платформу/воду", а не перелетіти далеко.
            scanGap = Math.min(scanGap, DEEP_LEVEL_MAX_SCAN_GAP);
        }
        if (scanGap < 1) {
            return 0; // цей Δy з цього блока недосяжний (напр. вгору з меду, чи просто задалеко) - не пробуємо
        }
        Node[] bucket = new Node[nodes.length];
        int found = 0;
        for (GapJumpRays.Ray ray : GapJumpRays.forMaxGap(scanGap)) {
            if (found >= bucket.length) {
                break; // одна група вже заповнила цілий буфер - більше з неї однаково не візьмемо (промені відсортовані: спершу найближчі)
            }
            found = tryRay(bucket, found, ctx, ray, frontCache, dy);
        }
        buckets[dy + downLimit] = bucket;
        sizes[dy + downLimit] = found;
        return found;
    }

    /** v20: шкода (HP, після Опору й чарів, до поглинання) від приземлення на {@code origin + (dx,dy,dz)} стрибком із цього вузла. */
    private float fallDamageOf(BlockPos origin, int dx, int dz, int dy) {
        FallMath.Profile profile = this.fallProfile;
        if (profile == null) {
            return 0.0F;
        }
        double fall = FallMath.fallBlocks(this.takeoffApex, dy);
        if (fall <= profile.safeFall()) {
            return 0.0F; // швидкий вихід: без звернень до світу
        }
        BlockPos landing = origin.offset(dx, dy, dz);
        BlockState below = getBlockStateAt(landing.below());
        BlockState feet = getBlockStateAt(landing);
        BlockState head = getBlockStateAt(landing.above());
        double multiplier = below == null || feet == null || head == null
                ? 1.0 : FallLanding.multiplier(below, feet, head); // v22: + драбини/ліани
        return FallMath.damageAfterReductions(profile, fall, multiplier);
    }

    /** v20: штраф у вартості ребра за шкоду від падіння; заодно запам'ятовує найменшу шкоду (див. getNeighbors). */
    private float fallPenalty(BlockPos origin, int dx, int dz, int dy) {
        float damage = fallDamageOf(origin, dx, dz, dy);
        if (damage < this.minJumpDamage) {
            this.minJumpDamage = damage;
        }
        if (this.fallStats != null && dy < 0) {
            dbgCandidate(origin, dx, dz, dy, damage);
        }
        return damage > 0.0F ? damage * this.fallCostPerHp : 0.0F;
    }
    private double softTargetY;

    // ------------------------------------------------------------------------------------------
    // v23: м'які приземлення (вода/сіно/драбини/ліжко...) як цілі стрибка RNJ
    // ------------------------------------------------------------------------------------------
    private int softMaxDepth;
    private float softPriority;
    private SoftLandings.Index softIndex;
    /**
     * v24: перевіряти розбіг для стрибків у м'яке НЕ впритик ({@code softLandingRunUpCheck}).
     */
    private boolean runUpCheck;
    /**
     * Скільки смертельних спусків відкинуто в цьому пошуку (для повідомлення "моб застряг").
     */
    private int lethalRemoved;
    /**
     * Скільки м'яких приземлень додано в граф у цьому пошуку.
     */
    private int softAdded;
    /**
     * v24: скільки стрибків у м'яке відкинуто через замалий розбіг (для зведення [FALL]).
     */
    private int softRunUpRejected;

    private static int indexOfCell(Node[] nodes, int count, int x, int y, int z) {
        for (int i = 0; i < count; i++) {
            Node c = nodes[i];
            if (c.x == x && c.y == y && c.z == z) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Копія вузла з іншою ціною (спільний вузол пошуку міняти не можна: інші шляхи на ту саму клітинку теж заплатили б).
     */
    private static Node copyWithMalus(Node source, float malus) {
        Node copy = source instanceof DropNode ? new DropNode(source.x, source.y, source.z) : new Node(source.x, source.y, source.z);
        copy.type = source.type;
        copy.costMalus = malus;
        return copy;
    }

    @Override
    public void prepare(PathNavigationRegion level, Mob mob) {
        super.prepare(level, mob);
        this.exactJumps = ShapeSettings.enabledFor(mob);
        // v20: профіль падіння моба на час ЦЬОГО пошуку (здоров'я, золоті серця, Опір, чари, безпечна висота). Динамічний
        // нижній предел = те, що моб переживе на звичайний блок (не менше старого -3, не більше стелі з конфігу).
        this.fallProfile = null;
        this.dynamicDownLimit = GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS;
        // v21 (дебаг [FALL]): що саме порахував цей пошук - див. FallDebug.logProfile
        int dbgCap = -1;
        double dbgApex = 0.0;
        int dbgSurvivable = -1;
        RuntimeException dbgFailure = null;
        if (mob != null && Config.FALL_DAMAGE_AWARE_JUMPS.get()) {
            try {
                FallMath.Profile profile = FallDamage.profileOf(mob);
                int cap = Config.JUMP_DOWN_MAX_BLOCKS.get();
                double apex = FallMath.apexHeight(GapJumpPhysics.JUMP_VERTICAL_VELOCITY, GapJumpPhysics.GRAVITY_PER_TICK,
                        GapJumpPhysics.VERTICAL_DRAG_PER_TICK);
                int survivable = FallMath.maxSurvivableDrop(profile, apex, cap);
                this.fallProfile = profile;
                this.fallCostPerHp = Config.FALL_DAMAGE_COST_PER_HP.get().floatValue();
                this.dynamicDownLimit = Math.max(GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS, Math.min(cap, survivable));
                dbgCap = cap;
                dbgApex = apex;
                dbgSurvivable = survivable;
            } catch (RuntimeException ex) {
                this.fallProfile = null; // будь-яка проблема з читанням моба - стара поведінка, а не зламаний пошук шляху
                dbgFailure = ex;         // v21: раніше цей виняток ковтався мовчки - тепер його видно в [FALL]
            }
        }
        this.dropAware = this.fallProfile != null && Config.DROP_DAMAGE_AWARE.get();
        // v23: налаштування м'яких приземлень цього пошуку
        this.dropViaRnj = Config.DROP_VIA_RNJ.get();
        this.softEnabled = false;
        this.softIndex = null;
        this.softColumnCache.clear();
        this.runUpCheck = Config.SOFT_LANDING_RUNUP_CHECK.get();
        this.softRunUpRejected = 0;
        this.lethalRemoved = 0;
        this.softAdded = 0;
        if (mob != null && Config.SOFT_LANDING_SEARCH.get()) {
            Vec3 chase = PursuitEnemyBehavior.getChasePosition(mob);
            if (chase != null) { // лише переслідувачі: решта мобів м'яких приземлень не шукає
                this.softEnabled = true;
                this.softTargetY = chase.y;
                this.softMaxDepth = Config.SOFT_LANDING_MAX_DEPTH.get();
                this.softPriority = Config.SOFT_LANDING_PRIORITY.get().floatValue();
            }
        }
        this.fallStats = null;
        if (DebugLog.on(DebugLog.Cat.FALL) && FallDebug.relevant(mob)) {
            if (this.fallProfile != null) {
                FallDebug.logProfile(mob, this.fallProfile, dbgCap, dbgApex, dbgSurvivable, this.dynamicDownLimit, this.fallCostPerHp);
            } else if (dbgFailure != null) {
                FallDebug.logProfileFailed(mob, dbgFailure);
            } else {
                FallDebug.logProfileOff(mob);
            }
            if (DebugLog.throttle("fall-search-" + mob.getId(), 2000L)) {
                this.fallStats = new FallDebug.SearchStats(mob, this.fallProfile);
            }
        }
    }

    /**
     * v21 (дебаг [FALL]): пошук закінчено - друкуємо зведення (кандидати вниз, відсічені, глибоке сканування, рівні, які розкрив
     * A*). {@code super.done()} обнуляє моба, тому статистика тримає власне посилання.
     */
    @Override
    public void done() {
        FallDebug.SearchStats stats = this.fallStats;
        this.fallStats = null;
        Mob searched = this.mob; // super.done() обнуляє моба
        super.done();
        if (stats != null) {
            stats.onRunUp(this.softRunUpRejected);
            stats.flush(this.dynamicDownLimit, this.fallCostPerHp, this.dropAware);
        }
        if (searched != null) {
            FallDebug.noteSearch(searched, this.lethalRemoved, this.softAdded); // для повідомлення "моб застряг"
        }
    }

    @Override
    public int getNeighbors(Node[] nodes, Node node) {
        int count = super.getNeighbors(nodes, node);
        if (this.fallStats != null) {
            this.fallStats.onExpand(node); // v21: на яких рівнях Y A* розкриває вузли (чи дивиться він НИЖЧЕ за старт)
        }
        if (this.dropAware) {
            count = applyDropCosts(nodes, count, node); // v22: ванільні ребра зриву з краю теж мають ціну за шкоду
        }
        final int vanillaCount = count;
        boolean probe = isProbeStart(node);
        // v17: "повністю відкрита клітинка" - це 8 сусідів БЕЗ СПУСКІВ (суцільна поверхня на одному рівні): жоден напрямок не має
        // провалля на рівні старту, тож скан дав би порожньо. Але коли ПІД платформою є ще одна поверхня (підлога під паркуром),
        // кожен із 8 напрямків теж "прохідний" - як спуск на 1+ блок, - і рівно 8 сусідів має САМ край платформи, звідки стрибок
        // якраз потрібен. Раніше скан для такого вузла пропускався: ребра на наступну сходинку не було (A* вів моба в обхід),
        // а гоал "підстрибував на місці". Ванільні сусіди не містять уже закритих вузлів, тому рівно 8 зазвичай буває у стартового
        // вузла пошуку (поруч іще нічого не закрито): саме від нього починається кожен свіжий шлях, зокрема ланцюжок стрибків.
        if (count >= ALL_NORMAL_NEIGHBORS && !hasStepDownNeighbor(nodes, count, node)) {
            if (probe) {
                GraphProbe.logText(this.mob, "стрибкові ребра НЕ скануються: клітинка повністю відкрита (" + count + " сусідів, без спусків)");
                GraphProbe.disarm();
            }
            return count; // суцільна поверхня на одному рівні - тут стрибок ніколи не потрібен, не скануємо
        }
        if (probe && count >= ALL_NORMAL_NEIGHBORS) {
            GraphProbe.logText(this.mob, "8 сусідів, але серед них є спуски (під платформою інша поверхня): стрибкові ребра СКАНУЮТЬСЯ");
        }
        BlockPos origin = new BlockPos(node.x, node.y, node.z);
        BlockPos floorPos = origin.below();
        BlockState floor = getBlockStateAt(floorPos);
        if (floor == null || this.mob == null) {
            return count;
        }
        var level = this.mob.level();
        GapJumpPhysics.Takeoff takeoff = GapJumpUtils.takeoffOf(floor, level, floorPos, this.mob);

        // Точна геометрія (ShapeJump) - лише де є нерегулярна форма; на повних блоках лишається старий код.
        JumpCtx ctx = new JumpCtx(node, origin, exactReady() && columnIsIrregular(node.x, node.y, node.z));
        if (this.fallStats != null) {
            this.fallStats.onScan();
        }

        byte[] frontCache = new byte[9]; // стан 8 клітинок перед краєм НА РІВНІ СТАРТУ; ключ (dx+1)*3+(dz+1)
        // Дальність залежить від блока, З ЯКОГО моб відривається (тертя / speedFactor / jumpFactor): лід - далі,
        // слайм, пісок душ, мед - ближче; і від Δy (вгору - вікно коротше, тож і дальність менша, навіть на
        // звичайному блоці; вниз - трохи більша). Тому рахуємо тут, для КОЖНОГО вузла й для КОЖНОГО рівня Δy
        // окремо під час побудови шляху, а не одним числом на всього моба й не перед самим стрибком.
        // ПОВНИЙ діапазон -JUMP_DOWN_LIMIT_BLOCKS..+JUMP_UP_LIMIT_BLOCKS (не лише крайні значення): щоб моб
        // міг приземлитись саме на НАЙБЛИЖЧУ доступну сходинку, а не тільки на найглибшу з можливих (наприклад,
        // сходи в 3 рівні - Δy=-1 на першу, Δy=-2 на другу, Δy=-3 на третю, кожна свій кандидат).
        // v16: кандидати збираємо ОКРЕМО для кожного Δy й лише потім ділимо буфер (NeighborBudget). Раніше всі Δy писали в
        // один буфер за зростанням Δy (-3..+1): велика платформа внизу (десятки посадок з Δy=-1) займала всі 32 слоти, а Δy=0
        // і Δy=+1 лишались без жодного - ребра вгору на наступну сходинку в графі не було (див. NeighborBudget).
        final int legacyDown = GapJumpPhysics.JUMP_DOWN_LIMIT_BLOCKS;
        // v20: нижній предел ДИНАМІЧНИЙ (див. prepare): що моб переживе, але не менше старого -3. Глибші за -3 рівні скануються
        // нижче лише за потреби (scanLevel).
        final int downLimit = Math.max(legacyDown, this.dynamicDownLimit);
        final int levels = downLimit + GapJumpPhysics.JUMP_UP_LIMIT_BLOCKS + 1;
        Node[][] buckets = new Node[levels][];
        int[] sizes = new int[levels];
        this.takeoffApex = this.fallProfile == null ? 0.0
                : FallMath.apexHeight(GapJumpPhysics.JUMP_VERTICAL_VELOCITY * takeoff.jumpFactor(),
                GapJumpPhysics.GRAVITY_PER_TICK, GapJumpPhysics.VERTICAL_DRAG_PER_TICK);
        this.minJumpDamage = Float.MAX_VALUE;
        int shallowCandidates = 0;
        for (int dy = -legacyDown; dy <= GapJumpPhysics.JUMP_UP_LIMIT_BLOCKS; dy++) {
            shallowCandidates += scanLevel(nodes, ctx, takeoff, frontCache, dy, buckets, sizes, downLimit);
        }
        // v20: ЛІНИВО глибші рівні (-4..-downLimit): лише коли мілких приземлень нема зовсім або жодне не БЕЗКОШТОВНЕ (є шкода).
        // Тоді шукаємо місце, з якого можна стрибнути з НАЙМЕНШОЮ шкодою (вода, сіно, нижча платформа), і зупиняємось, щойно
        // знайшли приземлення без шкоди. У звичайному паркурі (є безкоштовна платформа поруч) це нічого не додає до вартості пошуку.
        if (downLimit > legacyDown && (shallowCandidates == 0 || this.minJumpDamage > 0.0F)) {
            if (this.fallStats != null) {
                this.fallStats.deepTriggered++;
            }
            for (int dy = -legacyDown - 1; dy >= -downLimit; dy--) {
                scanLevel(nodes, ctx, takeoff, frontCache, dy, buckets, sizes, downLimit);
                if (this.minJumpDamage <= 0.0F) {
                    break;
                }
            }
        } else if (this.fallStats != null && downLimit > legacyDown) {
            // v21: глибші рівні НЕ скануються, бо хоч де-небудь (навіть у зайвий бік) уже є безкоштовне приземлення
            this.fallStats.deepSkippedFree++;
        }
        int roomForJumps = nodes.length - count;
        int candidates = 0;
        for (int size : sizes) {
            candidates += size;
        }
        if (this.fallStats != null) {
            this.fallStats.onLevels(sizes, downLimit);
        }
        count = NeighborBudget.merge(buckets, sizes, nodes, count);
        if (candidates > roomForJumps) {
            // Буфер не вмістив усіх кандидатів - далі решту відсікає NeighborBudget (порівну між Δy). Показуємо це в лозі: раніше
            // така втрата була мовчазною й виглядала як "моб не бачить наступного стрибка".
            final String overflow = "буфер сусідів (" + nodes.length + ") переповнено у вузлі (" + node.x + "," + node.y + "," + node.z
                    + "): кандидатів на стрибок=" + candidates + " за Δy [" + describeLevels(sizes, downLimit)
                    + "], місця було " + roomForJumps + " -> взято ["
                    + describeLevels(NeighborBudget.share(sizes, roomForJumps), downLimit) + "]";
            if (probe) {
                GraphProbe.logText(this.mob, overflow);
            } else {
                DebugLog.logThrottled(DebugLog.Cat.GRAPH, this.mob, "graph-overflow-" + this.mob.getId(), 3000L, () -> overflow);
            }
        }
        count = addHops(nodes, count, ctx, frontCache);
        count = addExtraJumps(nodes, count, ctx);
        count = addSoftLandings(nodes, count, vanillaCount, node, origin, ctx, takeoff, frontCache); // v23
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
     * v22/v23: ванільні (і shape-aware) ребра СПУСКУ - "зійти з краю й упасти" - раніше коштували як звичайний крок: A* не знав
     * ні HP моба, ні чарів, ні блока внизу, тож кидався вниз одразу (в логу - ПІШКИ-ЗРИВ на 10-15 блоків), замість того щоб
     * спуститись сходами нижче чи влучити у воду/сіно. Для кожного такого ребра (падіння від 2 блоків):
     * <ul>
     *   <li>шкода рахується тією ж моделлю, що й для стрибків ({@link FallMath}, множник блока - {@link FallLanding});</li>
     *   <li>смертельне (не лишає {@code fallDamageReserveHp}) - ребро ВІДКИДАЄТЬСЯ (і рахується в {@link #lethalRemoved});</li>
     *   <li>шкідливе - вузол приземлення замінюється КОПІЄЮ з доданим штрафом {@code шкода * fallDamageCostPerHp}
     *       (спільний вузол міняти не можна: інші шляхи на ту саму клітинку теж заплатили б за падіння);</li>
     *   <li>v23: НЕТРИВІАЛЬНИЙ спуск (більший за безпечну висоту) - копія є {@link DropNode}: у шляху це сегмент RNJ (розбіг/збіг
     *       з краю на потрібній швидкості, повітряне керування в польоті). М'яке приземлення (вода, сіно, драбина...) коштує
     *       як м'яке ({@link SoftMath}), а не як "прохід по воді" (ванільна надбавка 8);</li>
     *   <li>безкоштовне й тривіальне (невелика висота) - лишається як є.</li>
     * </ul>
     * Падіння рахується як висота спуску: з краю моб падає рівно на різницю рівнів (без апекса стрибка).
     *
     * @return нова кількість сусідів (смертельні прибрано)
     */
    private int applyDropCosts(Node[] nodes, int count, Node origin) {
        FallMath.Profile profile = this.fallProfile;
        int safeWalk = (int) Math.floor(profile.safeFall());
        int kept = 0;
        for (int i = 0; i < count; i++) {
            Node n = nodes[i];
            int drop = origin.y - n.y;
            if (drop < 2 || n instanceof HopNode || n instanceof DropNode) {
                nodes[kept++] = n;
                continue;
            }
            BlockPos landing = new BlockPos(n.x, n.y, n.z);
            BlockState below = getBlockStateAt(landing.below());
            BlockState feet = getBlockStateAt(landing);
            BlockState head = getBlockStateAt(landing.above());
            if (below == null || feet == null || head == null) {
                nodes[kept++] = n;
                continue;
            }
            double multiplier = FallLanding.multiplier(below, feet, head);
            float damage = FallMath.damageAfterReductions(profile, drop, multiplier);
            boolean lethal = damage > 0.0F && !FallMath.survives(profile, damage);
            if (this.fallStats != null) {
                dbgDrop(origin, n, drop, damage, lethal, below, feet, head);
            }
            if (lethal) {
                this.lethalRemoved++;
                continue; // з такої висоти на таке приземлення моб не виживе - ребра нема
            }
            boolean viaRnj = drop > safeWalk && this.dropViaRnj;
            if (damage <= 0.0F && !viaRnj) {
                nodes[kept++] = n;
                continue;
            }
            Node copy = viaRnj ? new DropNode(n.x, n.y, n.z) : new Node(n.x, n.y, n.z);
            // v24: ті самі правила "м'якого", що й для індексу й колонок впритик - з перевіркою небезпек поруч (лава, вогонь, кактус)
            SoftLandings.Kind soft = viaRnj && this.softEnabled
                    ? SoftLandings.kindChecked(this::getBlockStateAt, n.x, n.y, n.z) : null;
            if (soft != null) {
                copy.type = PathType.WALKABLE; // планове приземлення в м'яке - не "прохід по воді" (ванільна надбавка 8)
                copy.costMalus = SoftMath.rankPenalty(soft.rank) + SoftMath.tolerancePenalty(softMargin(landing))
                        + (damage > 0.0F ? damage * this.fallCostPerHp : 0.0F);
                if (copy instanceof DropNode softDrop) {
                    softDrop.soft = true;
                }
            } else {
                copy.type = n.type;
                copy.costMalus = n.costMalus + (damage > 0.0F ? damage * this.fallCostPerHp : 0.0F);
            }
            nodes[kept++] = copy;
        }
        return kept;
    }

    /**
     * Скільки з 4 сусідніх клітинок (того ж рівня) теж м'які: допуск на промах, 0 - одиночний блок, 4 - центр басейну 3x3.
     */
    private int softMargin(BlockPos landing) {
        int margin = 0;
        for (int[] d : SOFT_NEIGHBORS) {
            BlockPos c = landing.offset(d[0], 0, d[1]);
            BlockState b = getBlockStateAt(c.below());
            BlockState f = getBlockStateAt(c);
            BlockState h = getBlockStateAt(c.above());
            if (b != null && f != null && h != null && SoftLandings.kindOf(b, f, h) != null) {
                margin++;
            }
        }
        return margin;
    }

    /**
     * Скільки блоків найдальше політ до м'якого приземлення з цього вузла - за ФІЗИКОЮ RNJ на глибині
     * {@link #SOFT_RADIUS_PROBE_DEPTH} (а не вгадується), не більше {@link #SOFT_MAX_SCAN_GAP}; +1 на край клітинки.
     */
    private int softReach(GapJumpPhysics.Takeoff takeoff) {
        int probeDepth = Math.min(this.softMaxDepth, SOFT_RADIUS_PROBE_DEPTH);
        return Math.min(SOFT_MAX_SCAN_GAP, GapJumpUtils.maxJumpGap(this.mob, takeoff, -probeDepth)) + 1;
    }

    /**
     * Індекс м'яких клітинок навколо вузла (кеш на моба ~2 с, до 4 районів). v24: A* розкриває вузли далеко від старту (внизу
     * сходів, на іншій платформі) - для кожного такого вузла береться індекс, що сягає на дальність стрибка навколо НЬОГО, а не
     * лише навколо моба.
     */
    private SoftLandings.Index softIndexFor(BlockPos origin, GapJumpPhysics.Takeoff takeoff) {
        int reach = softReach(takeoff);
        SoftLandings.Index current = this.softIndex;
        if (current != null && current.covers(origin.getX(), origin.getY(), origin.getZ(), reach, this.softMaxDepth)) {
            return current;
        }
        int radius = Math.max(SOFT_MIN_RADIUS, Math.min(SOFT_MAX_RADIUS, reach + SOFT_INDEX_MARGIN));
        this.softIndex = SoftLandings.obtain(this.mob, this::getBlockStateAt, origin.getX(), origin.getY(), origin.getZ(),
                reach, radius, this.softMaxDepth, this.mob.level().getGameTime());
        return this.softIndex;
    }

    /**
     * Вузол м'якого приземлення. Ціна: ранг + штраф за вузьке приземлення + шкода (v24: рахується, а не вважається нульовою: сіно
     * й ліжко пом'якшують падіння, але не скасовують його, а з великої висоти можуть і вбити). БЕЗ штрафу за відстань: м'яке
     * приземлення з поточної платформи має бути дешевшим за спуск пішки.
     *
     * @return вузол або {@code null}, якщо приземлення смертельне для цього моба
     */
    private Node softNode(BlockPos origin, int x, int y, int z, int[] cell, boolean adjacent) {
        SoftLandings.Kind kind = SoftLandings.Kind.values()[cell[0]];
        float damage = 0.0F;
        FallMath.Profile profile = this.fallProfile;
        if (profile != null) {
            damage = adjacent
                    ? walkOffDamage(profile, origin, x, y, z)
                    : fallDamageOf(origin, x - origin.getX(), z - origin.getZ(), y - origin.getY());
            if (damage > 0.0F && !FallMath.survives(profile, damage)) {
                this.lethalRemoved++;
                return null; // навіть м'яке приземлення з такої висоти моб не переживе
            }
        }
        Node n = adjacent && this.dropViaRnj ? new DropNode(x, y, z) : new Node(x, y, z);
        if (n instanceof DropNode softDrop) {
            softDrop.soft = true;
        }
        n.type = PathType.WALKABLE;
        n.costMalus = SoftMath.rankPenalty(kind.rank) + SoftMath.tolerancePenalty(cell[1])
                + (damage > 0.0F ? damage * this.fallCostPerHp : 0.0F);
        return n;
    }

    /**
     * Шкода від збігу з краю (без стрибка: падіння рівно на різницю рівнів) на клітинку {@code (x, y, z)}.
     */
    private float walkOffDamage(FallMath.Profile profile, BlockPos origin, int x, int y, int z) {
        int drop = origin.getY() - y;
        if (drop <= profile.safeFall()) {
            return 0.0F;
        }
        BlockPos landing = new BlockPos(x, y, z);
        BlockState below = getBlockStateAt(landing.below());
        BlockState feet = getBlockStateAt(landing);
        BlockState head = getBlockStateAt(landing.above());
        double multiplier = below == null || feet == null || head == null ? 1.0 : FallLanding.multiplier(below, feet, head);
        return FallMath.damageAfterReductions(profile, drop, multiplier);
    }

    /**
     * v23/v24: додає в сусіди м'які приземлення: сусідні колонки (впритик) - ребром-спуском {@link DropNode}; далі - стрибковими
     * ребрами RNJ по променях, але ЛИШЕ на рівнях, де в індексі є м'які клітинки, і на будь-якій глибині до
     * {@code softLandingMaxDepth} (звичайне сканування глибше за -3 обмежене 6 блоками й нічого не знає про глибоку воду).
     * Досяжність для кожної глибини - {@link GapJumpUtils#maxJumpGap}, коридор - {@link #isCorridorClear}, розбіг - v24
     * ({@link #runUpCells}). Смертельні приземлення (сіно з великої висоти...) відкидаються.
     * <p>
     * ПЕРЕВАГА: коли з вузла досяжне м'яке приземлення, КОНКУРУЮЧІ спуски з нього (кроки вниз, зриви, стрибки на тверде внизу)
     * дорожчають на {@code softLandingPriority} - моб стрибає з поточної платформи, якщо об'їзд не більший за це число.
     * ЯКЩО БУДЕ ЛАГАТИ: індекс будується раз на ~2 с на район (SoftLandings.obtain, до 4 районів на моба), колонки впритик
     * читаються раз на пошук (softColumnCache), а сюди заходять лише вузли-краї при цілі нижче.
     */
    private int addSoftLandings(Node[] nodes, int count, int vanillaCount, Node node, BlockPos origin, JumpCtx ctx,
                                GapJumpPhysics.Takeoff takeoff, byte[] frontCache) {
        if (!this.softEnabled || this.mob == null || origin.getY() - this.softTargetY < SOFT_MIN_TARGET_DROP) {
            return count;
        }
        Node[] found = new Node[SOFT_BUFFER];
        int n = 0;
        // 1) впритик: колонки читаються від рівня САМОГО вузла (індекс сканується від рівня свого центру й для вузла нижче за
        //    центр чи під виступом ховав би воду за першим твердим блоком вище)
        try {
            n = collectAdjacentSoft(found, 0, origin, nodes, count);
        } catch (RuntimeException ex) {
            // м'які приземлення - надбавка: проблема з читанням світу не повинна ламати пошук шляху
        }
        // 2) далі: стрибки по променях на рівні, де є м'які клітинки
        int indexSize = 0;
        SoftLandings.Index index = null;
        try {
            index = softIndexFor(origin, takeoff);
            if (index != null && !index.isEmpty()) {
                indexSize = index.size();
                for (int absY : index.levelsBelow(origin.getY() - 1)) {
                    int dy = absY - origin.getY();
                    if (dy > -2 || dy < -this.softMaxDepth) {
                        continue;
                    }
                    n = scanSoftLevel(found, n, ctx, takeoff, frontCache, dy, index);
                    if (n >= found.length) {
                        break;
                    }
                }
            }
        } catch (RuntimeException ex) {
            // те саме: індекс - надбавка
        }

        boolean softPresent = false; // м'яке вже є серед сусідів (ванільні ребра спуску, перетворені на DropNode)
        for (int i = 0; i < count && !softPresent; i++) {
            Node c = nodes[i];
            softPresent = c.y < origin.getY() && c instanceof DropNode d && d.soft;
        }

        int added = 0;
        java.util.Set<Node> softSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (n > 0) {
            final Node from = node;
            java.util.Arrays.sort(found, 0, n,
                    (a, b) -> Float.compare(from.distanceTo(a) + a.costMalus, from.distanceTo(b) + b.costMalus));
            int take = Math.min(n, SOFT_MAX_CANDIDATES);
            for (int i = 0; i < take; i++) {
                Node c = found[i];
                int dup = indexOfCell(nodes, count, c.x, c.y, c.z);
                if (dup >= 0) {
                    Node ex = nodes[dup];
                    boolean fresh = ex instanceof DropNode || Math.hypot(ex.x - node.x, ex.z - node.z) > 1.5;
                    if (fresh && c.costMalus < ex.costMalus) {
                        // те саме приземлення, але з пріоритетом м'якого; спільний вузол не міняємо - ставимо копію
                        Node copy = copyWithMalus(ex, c.costMalus);
                        if (copy instanceof DropNode softDrop) {
                            softDrop.soft = true;
                        }
                        nodes[dup] = copy;
                        softSet.add(copy);
                    }
                    continue;
                }
                if (count >= nodes.length) {
                    if (count - 1 < vanillaCount) {
                        break;
                    }
                    count--; // м'яке має пріоритет над найдальшим регулярним стрибком
                }
                nodes[count++] = c;
                softSet.add(c);
                added++;
            }
        }
        this.softAdded += added;
        if (this.fallStats != null) {
            this.fallStats.onSoft(n, added, softPresent, indexSize);
        }

        if (this.softPriority > 0.0F && (added > 0 || softPresent)) {
            for (int i = 0; i < count; i++) {
                Node c = nodes[i];
                if (c.y >= origin.getY() || c instanceof HopNode || isSoftTarget(c, index, softSet)) {
                    continue; // не спуск, гоп, або сам м'який вузол
                }
                nodes[i] = copyWithMalus(c, c.costMalus + this.softPriority); // конкуруючий спуск: м'яке приземлення має перевагу
            }
        }
        return count;
    }

    /**
     * Чи сам вузол - м'яке приземлення (його не штрафуємо як «конкуруючий спуск»): позначений DropNode.soft, доданий цим викликом,
     * є в індексі, або (звичайний стрибок/крок на воду чи сіно, яких індекс не покриває) за блоками під/в/над клітинкою.
     */
    private boolean isSoftTarget(Node c, SoftLandings.Index index, java.util.Set<Node> softSet) {
        if ((c instanceof DropNode d && d.soft) || softSet.contains(c)) {
            return true;
        }
        if (index != null && index.cellAt(c.x, c.y, c.z) != null) {
            return true;
        }
        return SoftLandings.kindChecked(this::getBlockStateAt, c.x, c.y, c.z) != null;
    }

    /**
     * Колонка {@code (x, z)}: м'яка клітинка шукається зверху вниз від рівня {@code yTop} (раз на пошук). {@code null} - нема.
     */
    private int[] softColumn(int x, int z, int yTop) {
        long key = BlockPos.asLong(x, yTop, z);
        int[] hit = this.softColumnCache.get(key);
        if (hit == null) {
            hit = SoftLandings.probeColumn(this::getBlockStateAt, x, z, yTop, yTop - this.softMaxDepth);
            this.softColumnCache.put(key, hit == null ? NO_SOFT : hit);
        }
        return hit == NO_SOFT ? null : hit;
    }

    /**
     * М'які клітинки в 8 сусідніх колонках (впритик) - ребра-спуски, яких ванільний пошук не дав (глибше за getMaxFallDistance).
     */
    private int collectAdjacentSoft(Node[] found, int n, BlockPos origin, Node[] nodes, int count) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx == 0 && dz == 0) || n >= found.length) {
                    continue;
                }
                if (classifyFront(origin, dx, dz, 0) != VOID) {
                    continue; // перед нами не провал
                }
                if (dx != 0 && dz != 0 && (classifyFront(origin, dx, 0, 0) == BLOCKED || classifyFront(origin, 0, dz, 0) == BLOCKED)) {
                    continue; // діагональ без зрізання кутів
                }
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                int[] hit = softColumn(x, z, origin.getY() - 1);
                if (hit == null) {
                    continue;
                }
                int y = hit[1];
                int dy = y - origin.getY();
                if (dy > -2 || dy < -this.softMaxDepth) {
                    continue;
                }
                if (indexOfCell(nodes, count, x, y, z) >= 0) {
                    continue; // вже є (ванільне ребро спуску)
                }
                Node soft = softNode(origin, x, y, z, new int[]{hit[0], softMargin(new BlockPos(x, y, z))}, true);
                if (soft != null) {
                    found[n++] = soft;
                }
            }
        }
        return n;
    }

    /**
     * Промені стрибка на рівень {@code dy}: беруться лише клітинки, що є в індексі м'яких.
     */
    private int scanSoftLevel(Node[] found, int n, JumpCtx ctx, GapJumpPhysics.Takeoff takeoff, byte[] frontCache, int dy,
                              SoftLandings.Index index) {
        int gap = Math.min(GapJumpUtils.maxJumpGap(this.mob, takeoff, dy), SOFT_MAX_SCAN_GAP);
        if (gap < 1) {
            return n;
        }
        for (GapJumpRays.Ray ray : GapJumpRays.forMaxGap(gap)) {
            if (n >= found.length) {
                break;
            }
            n = trySoftRay(found, n, ctx, ray, frontCache, dy, index, takeoff);
        }
        return n;
    }

    /**
     * v24: розбіг - скільки клітинок рівної опори є ПОЗАДУ краю вздовж променя (до {@link #SOFT_RUNUP_MAX_CELLS}); моб розганяється
     * з нуля до ~90% швидкості за ~2.3 блока, тож на коротшій платформі стрибок коротший ({@link GapJumpPhysics#velocityAfterRunUp}).
     */
    private int runUpCells(BlockPos origin, GapJumpRays.Ray ray) {
        int m = Math.max(Math.abs(ray.stepX()), Math.abs(ray.stepZ()));
        if (m == 0) {
            return 0;
        }
        int cells = 0;
        for (int j = 1; j <= SOFT_RUNUP_MAX_CELLS; j++) {
            int bx = -Math.round((float) (j * ray.stepX()) / m);
            int bz = -Math.round((float) (j * ray.stepZ()) / m);
            if (!hasFloorAt(origin, bx, bz, 0)) {
                break;
            }
            cells = j;
        }
        return cells;
    }

    private int trySoftRay(Node[] found, int n, JumpCtx ctx, GapJumpRays.Ray ray, byte[] frontCache, int dy,
                           SoftLandings.Index index, GapJumpPhysics.Takeoff takeoff) {
        BlockPos origin = ctx.origin;
        int frontIndex = (ray.frontX() + 1) * 3 + (ray.frontZ() + 1);
        byte front = frontCache[frontIndex];
        if (front == UNKNOWN) {
            front = classifyStartFront(ctx, ray.frontX(), ray.frontZ());
            frontCache[frontIndex] = front;
        }
        if (front != VOID) {
            return n; // у цьому напрямку нема краю
        }
        int runUp = -1; // рахуємо ліниво: лише коли на промені справді є м'яка клітинка
        for (int k = ray.kMin(); k <= ray.kMax(); k++) {
            int x = origin.getX() + ray.stepX() * k;
            int z = origin.getZ() + ray.stepZ() * k;
            int y = origin.getY() + dy;
            int[] cell = index.cellAt(x, y, z);
            if (cell == null) {
                continue;
            }
            if (!isCorridorClear(origin, ray.corridor()[k], dy)) {
                continue;
            }
            if (this.runUpCheck) {
                if (runUp < 0) {
                    runUp = runUpCells(origin, ray);
                }
                int gap = GapJumpUtils.maxJumpGap(this.mob, takeoff, dy, runUp + SOFT_RUNUP_BASE);
                if (GapJumpRays.flightDistance(ray.stepX() * k, ray.stepZ() * k) > GapJumpRays.maxFlight(gap)) {
                    this.softRunUpRejected++;
                    break; // далі по променю - ще далі, розбігу не вистачить і поготів
                }
            }
            if (n >= found.length) {
                return n;
            }
            Node soft = softNode(origin, x, y, z, cell, false);
            if (soft != null) {
                found[n++] = soft;
            }
        }
        return n;
    }

    /** v22 (дебаг [FALL]): ребро спуску розглянуто - для зведення пошуку. Ніколи не ламає пошук шляху. */
    private void dbgDrop(Node origin, Node to, int drop, float damage, boolean lethal,
                         BlockState below, BlockState feet, BlockState head) {
        try {
            FallMath.Profile profile = this.fallProfile;
            float loss = profile == null ? damage : FallMath.healthLoss(profile, damage);
            this.fallStats.onDrop(origin, to, drop, damage, loss, lethal, below, feet, head);
        } catch (RuntimeException ignored) {
            // тільки дебаг
        }
    }

    /** v21 (дебаг [FALL]): кандидат стрибка вниз потрапив у граф - для зведення пошуку. Ніколи не ламає пошук шляху. */
    private void dbgCandidate(BlockPos origin, int dx, int dz, int dy, float damage) {
        try {
            BlockPos landing = origin.offset(dx, dy, dz);
            BlockState below = getBlockStateAt(landing.below());
            BlockState feet = getBlockStateAt(landing);
            BlockState head = getBlockStateAt(landing.above());
            if (below != null && feet != null && head != null) {
                this.fallStats.onCandidate(origin, dx, dz, dy, damage, below, feet, head);
            }
        } catch (RuntimeException ignored) {
            // тільки дебаг
        }
    }

    /** v21 (дебаг [FALL]): приземлення відсічено як смертельне - для зведення пошуку. Ніколи не ламає пошук шляху. */
    private void dbgLethal(BlockPos origin, int dx, int dz, int dy) {
        try {
            float damage = fallDamageOf(origin, dx, dz, dy);
            FallMath.Profile profile = this.fallProfile;
            float loss = profile == null ? damage : FallMath.healthLoss(profile, damage);
            BlockPos landing = origin.offset(dx, dy, dz);
            BlockState below = getBlockStateAt(landing.below());
            BlockState feet = getBlockStateAt(landing);
            BlockState head = getBlockStateAt(landing.above());
            if (below != null && feet != null && head != null) {
                this.fallStats.onLethal(origin, dx, dz, dy, damage, loss, below, feet, head);
            }
        } catch (RuntimeException ignored) {
            // тільки дебаг
        }
    }

    /** v20: чи переживе моб це приземлення (за вимогою конфігу лишивши {@code fallDamageReserveHp} HP). */
    private boolean landingAllowed(BlockPos origin, int dx, int dz, int dy) {
        FallMath.Profile profile = this.fallProfile;
        if (profile == null || dy >= 0) {
            return true;
        }
        return FallMath.survives(profile, fallDamageOf(origin, dx, dz, dy));
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
        // v20: шкода від падіння - у вартість ребра: безпечне приземлення завжди вигідніше за шкідливе
        this.lastJumpPenalty = fallPenalty(origin, dx, dz, dy);
        jumpNode.costMalus += this.lastJumpPenalty;
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
     * Що відомо про вузол на час одного {@link #getNeighbors}: нерегулярність колонки, придатні точки відриву
     * й модель фізики (рахуються ліниво - на повних блоках вони взагалі не потрібні).
     */
    private static final class JumpCtx {
        final Node node;
        final BlockPos origin;
        final boolean originIrregular;
        Spot[] from;
        ShapeJump.Model model;
        /**
         * Додаткові перестрибування (див. {@link #addExtraJumps}); створюється ліниво - здебільшого порожній.
         */
        List<Node> extra;

        JumpCtx(Node node, BlockPos origin, boolean originIrregular) {
            this.node = node;
            this.origin = origin;
            this.originIrregular = originIrregular;
        }

        void addExtra(Node jump) {
            if (this.extra == null) {
                this.extra = new ArrayList<>(4);
            }
            this.extra.add(jump);
        }
    }

    /**
     * Чи закінчується платформа на клітинці {@code (dx, dz)} променя: чи можна наступну придатну клітинку на промені
     * вважати ОКРЕМОЮ платформою, яку варто перестрибнути, а не продовженням тієї, на яку вже стрибаємо.
     * <p>
     * Для повних блоків "наступна клітинка має підлогу" означає "ногами дійдемо" ({@link #classifyFront}). Для
     * нерегулярних форм - ні: відкриті люки - це смужки по 0.19 на краю клітинки з 0.81 порожнього між ними, а
     * хітбокс моба лише 0.6, тож між сусідніми люками пішки не пройти, хоча підлога в кожній клітинці "є". Раніше
     * через це ряд люків вважався однією суцільною платформою: після першого приземлення решта ряду й блок за ним
     * відкидались, і ▣▢▢▢▣ давав стрибок через один люк та "гоп" на останній. Тому в нерегулярній зоні платформа
     * триває лише якщо з точок стояння на приземленні на наступну клітинку справді можна пройти
     * ({@link #anyMove}) - ті самі правила, за якими будуються звичайні кроки ({@code ShapeAwareNodeEvaluator}) і
     * перша клітинка від краю ({@link #classifyStartFront}).
     * <p>
     * Для повних блоків і при вимкнених точних стрибках - рівно як і раніше ({@code classifyFront == VOID}).
     *
     * @return {@link #BEHIND_VOID} - за клітинкою провалля (як і раніше); {@link #BEHIND_UNREACHABLE} - за нею клітинка
     * з опорою, на яку звідси не ступити (нове); {@link #BEHIND_NO_GAP} - платформа триває (або там стіна)
     */
    private byte platformEnd(JumpCtx ctx, GapJumpRays.Ray ray, int dx, int dz, int dy) {
        BlockPos origin = ctx.origin;
        int nextDx = dx + ray.frontX();
        int nextDz = dz + ray.frontZ();
        byte next = classifyFront(origin, nextDx, nextDz, dy);
        if (next == VOID) {
            return BEHIND_VOID;
        }
        if (next != WALKABLE || !exactReady()) {
            return BEHIND_NO_GAP; // стіна або (без точної геометрії) підлога: як і раніше
        }
        int y = origin.getY() + dy;
        int landX = origin.getX() + dx;
        int landZ = origin.getZ() + dz;
        int nextX = origin.getX() + nextDx;
        int nextZ = origin.getZ() + nextDz;
        if (!columnIsIrregular(landX, y, landZ) && !columnIsIrregular(nextX, y, nextZ)) {
            return BEHIND_NO_GAP; // повні блоки: ногами дійдемо, як і було
        }
        Spot[] landing = geoSpotsAll(landX, y, landZ);
        if (landing.length == 0) {
            return BEHIND_NO_GAP;
        }
        return anyMove(landing, geoSpotsEarly(nextX, y, nextZ)) ? BEHIND_NO_GAP : BEHIND_UNREACHABLE;
    }

    /**
     * Додаткові перестрибування ({@link #platformEnd} = {@link #BEHIND_UNREACHABLE}) додаються ПІСЛЯ всіх звичайних
     * ребер і гопів і лише в залишок буфера сусідів. Буфер ванільного PathFinder - 32 вузли; щільна нерегулярна сцена
     * (поле люків) може дати більше, і нове ребро не має витісняти звичайне: у гіршому випадку виходить те саме, що
     * було до цієї правки.
     */
    private int addExtraJumps(Node[] nodes, int count, JumpCtx ctx) {
        if (ctx.extra == null) {
            return count;
        }
        for (Node jump : ctx.extra) {
            if (count >= nodes.length) {
                break;
            }
            nodes[count++] = jump;
        }
        return count;
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