package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

/**
 * Чиста математика стрибка через розрив — БЕЗ жодних Minecraft-типів (тому її можна тестувати
 * окремо від гри). Модель — це буквально ванільні {@code LivingEntity.jumpFromGround()} +
 * {@code LivingEntity.travel()}, звірені з незалежною реалізацією ванільної фізики
 * (prismarine-physics) з точністю до 4-го знака.
 * <p>
 * ЯК ЛЕТИТЬ МОБ (вісь Y і горизонталь), тік за тіком:
 * <ul>
 *   <li><b>Тік 1 (тік відриву).</b> {@code jumpFromGround()} ставить vy=0.42, а {@code travel()} у цей
 *       самий тік ще вважає, що моб стоїть на землі: тому горизонтальний зсув = стартова швидкість
 *       (deltaMovement), а тертя після зсуву береться НАЗЕМНЕ — 0.6*0.91 = 0.546.</li>
 *   <li><b>Тіки 2..N (повітря).</b> Кожного тіку: зсув на поточну швидкість, потім швидкість *= 0.91
 *       (повітряне тертя). Керування в повітрі в нас вимкнене (moveControl гасимо), тож більше нічого
 *       горизонтальну швидкість не змінює.</li>
 *   <li>Політ на рівному місці триває N = 12 тіків. Y-колізія приземлення на N-му тіку рахується
 *       ПЕРШОЮ і по позиції, яка була ПІСЛЯ (N-1)-го тіку — тому для приземлення важлива саме вона.</li>
 * </ul>
 * <b>v9:</b> ціль більше не покладається на цю балістичну модель для ВІДРИВУ (див. розділ "ПРОФІЛЬ ПОЛЬОТУ"
 * нижче: горизонтальний крок щотіку = відстань / тіків до приземлення). {@link #launchSpeedForDistance},
 * {@link #flightDistance}, {@link #remainingAirFactor} та константи {@code FLIGHT_FACTOR_*} лишені як
 * опис ванільної фізики (на них зведені тести й звірка з еталоном); вертикаль (гравітація, опір, тривалість
 * 12 тіків) і {@link #GROUND_FRICTION} використовуються й далі.
 * <p>
 * Звідси головне: горизонтальна відстань польоту ЛІНІЙНА за стартовою швидкістю s:
 * {@code distance = s * FLIGHT_FACTOR_TO_LANDING} (≈ 4.917), а не {@code s * 10 * 0.85 = s * 8.5}, як
 * було в старій моделі (та вважала швидкість сталою весь політ і не знала ні про тертя відриву,
 * ні про 0.91 у повітрі — тому переоцінювала дальність майже вдвічі).
 */
final class GapJumpPhysics {

    /**
     * Скільки тіків моб у повітрі на рівному місці (включно з тіком відриву та тіком приземлення).
     */
    static final int AIRTIME_TICKS;

    static final double JUMP_VERTICAL_VELOCITY = 0.42;
    static final double GRAVITY_PER_TICK = 0.08;
    static final double VERTICAL_DRAG_PER_TICK = 0.98;
    static final double AIR_FRICTION = 0.91;

    /**
     * Тертя ЗВИЧАЙНОГО блока під ногами (0.6). Константи польоту нижче ({@link #GROUND_FRICTION},
     * {@code FLIGHT_FACTOR_*}) описують саме його. Як на ДАЛЬНІСТЬ ПЛАНУВАННЯ впливають лід/слайм/мед та
     * блоки з інших модів (тертя, speedFactor, jumpFactor) - див. {@link #blockRangeFactor}.
     */
    static final double DEFAULT_BLOCK_FRICTION = 0.6;

    /**
     * Горизонтальний множник тертя на тіку відриву (наземний). Він же зв'язує швидкість і крок за тік.
     */
    static final double GROUND_FRICTION = DEFAULT_BLOCK_FRICTION * AIR_FRICTION;
    /**
     * Σ множників за тіки 1..N — повна горизонтальна дальність польоту до точки приземлення.
     */
    static final double FLIGHT_FACTOR_TO_LANDING;

    /**
     * Σ горизонтальних множників за тіки 1..N-1 — тобто позиція (в одиницях стартової швидкості),
     * на якій ванільна Y-колізія вирішує, чи приземлиться моб на блок.
     */
    static final double FLIGHT_FACTOR_BEFORE_TOUCHDOWN;
    /**
     * Найкоротший політ, який плануємо (тіків разом із тіком відриву): нижчу дугу за ~0.43 блока вже не робимо.
     */
    static final int MIN_PLANNED_AIRTIME = 5;

    static {
        double y = 0.0;
        double vy = JUMP_VERTICAL_VELOCITY;
        double factor = 1.0;      // яку частку стартової швидкості моб проходить по горизонталі цього тіку
        double sumBefore = 0.0;
        double sumTotal = 0.0;
        int tick = 0;
        while (tick < 60) {       // запобіжник
            tick++;
            y += vy;              // вертикальний рух цього тіку (у тіку відриву - вгору на 0.42)
            sumBefore = sumTotal;
            sumTotal += factor;
            factor *= (tick == 1 ? GROUND_FRICTION : AIR_FRICTION);
            vy = (vy - GRAVITY_PER_TICK) * VERTICAL_DRAG_PER_TICK;
            if (tick > 1 && y <= 0.0) {
                break;
            }
        }
        AIRTIME_TICKS = tick;
        FLIGHT_FACTOR_BEFORE_TOUCHDOWN = sumBefore;
        FLIGHT_FACTOR_TO_LANDING = sumTotal;
    }

    /**
     * Стартова горизонтальна швидкість (deltaMovement перед тіком відриву, БЕЗ sprint-поштовху),
     * яка приведе моба рівно на {@code horizontalDistance} блоків від поточної точки — тобто на
     * центр блока приземлення, якщо міряти до нього.
     */
    static double launchSpeedForDistance(double horizontalDistance) {
        return horizontalDistance / FLIGHT_FACTOR_TO_LANDING;
    }
    /**
     * Запас до краю зони опори блока приземлення (блоків): див. {@link #maxFlightStep}.
     */
    static final double LANDING_STEP_MARGIN = 0.10;

    /**
     * Σ горизонтальних множників на решту польоту з ПОТОЧНОГО стану: {@code v * factor} = скільки ще
     * пролетить моб по горизонталі, якщо зараз (на початку тіку) його горизонтальна швидкість = v.
     * Рахується з реальних висоти й вертикальної швидкості, тож не залежить від того, що було на
     * тіку відриву (тертя блока під ногами, чи виконався стрибок у той самий тік тощо) — у польоті
     * діє лише повітряне тертя 0.91.
     *
     * @param heightAboveLanding висота ніг моба над рівнем приземлення
     * @param verticalVelocity   поточна вертикальна швидкість (deltaMovement.y)
     */
    static double remainingAirFactor(double heightAboveLanding, double verticalVelocity) {
        double y = heightAboveLanding;
        double vy = verticalVelocity;
        double factor = 1.0;
        double sum = 0.0;
        for (int tick = 0; tick < 60; tick++) {
            y += vy;
            sum += factor;
            factor *= AIR_FRICTION;
            vy = (vy - GRAVITY_PER_TICK) * VERTICAL_DRAG_PER_TICK;
            if (y <= 0.0) {
                break;
            }
        }
        return sum;
    }

    // =====================================================================================
    // ПРОФІЛЬ ПОЛЬОТУ: рівномірна швидкість замість "стрибок-поштовх + затухання"
    // =====================================================================================
    // Стара модель ("задати швидкість відриву й летіти балістично") приземляла моба ТОЧНО, але політ
    // виглядав так: перший тік ривок (0.7-0.8 бл/тік), далі швидкість падає на x0.91 за тік і на
    // приземленні лишається 0.06 бл/тік (~1 бл/с) - моб "повзе" й майже зупиняється перед наступним
    // стрибком. Тепер горизонтальна швидкість виставляється ЩОТІКУ як
    // {@code відстань_до_центру / тіків_до_приземлення}: моб летить рівномірно й приземляється на
    // крейсерській швидкості. Точність та сама (замкнений контур перераховує щотіку).
    /** Відстань від центру блока до його межі по осі (для стрибка вздовж осі це і є "передній край"). */
    static final double EDGE_FRONT = 0.5;
    /**
     * Запас, щоб не чекати до самого моменту, коли моб уже втратив опору.
     */
    static final double GROUND_LOSS_MARGIN = 0.05;
    /**
     * Запас на прискорення моба (реальний крок за наступний тік трохи більший за попередній).
     */
    static final double STEP_LOOKAHEAD_FACTOR = 1.3;
    /**
     * Мінімальний очікуваний крок за тік. Захист від випадку "моб стоїть майже на краю і різко
     * розганяється": за швидкістю (яка ще майже нуль) наступний крок не передбачити, а моб може
     * за один тік проскочити всю зону опори і впасти БЕЗ стрибка.
     */
    static final double MIN_PREDICTED_STEP = 0.3;

    private GapJumpPhysics() {
    }

    /**
     * Зворотне до {@link #launchSpeedForDistance}: куди долетить моб зі швидкістю {@code launchSpeed}.
     */
    static double flightDistance(double launchSpeed) {
        return launchSpeed * FLIGHT_FACTOR_TO_LANDING;
    }

    /**
     * Скільки тіків лишилось до торкання рівня приземлення з поточного стану (включно з поточним
     * тіком). Ті самі формули, що й {@link #remainingAirFactor}: рух, потім гравітація й опір.
     */
    static int remainingAirTicks(double heightAboveLanding, double verticalVelocity) {
        double y = heightAboveLanding;
        double vy = verticalVelocity;
        for (int tick = 1; tick <= 60; tick++) {
            y += vy;
            vy = (vy - GRAVITY_PER_TICK) * VERTICAL_DRAG_PER_TICK;
            if (y <= 0.0) {
                return tick;
            }
        }
        return 60;
    }

    // =====================================================================================
    // КОЛИ ВІДРИВАТИСЬ (геометрія краю) — для БУДЬ-ЯКОГО напрямку стрибка
    // =====================================================================================
    // Позиція моба задається відносно ЦЕНТРУ блока-краю: (relX, relZ). Напрямок стрибка — одиничний
    // вектор (dirX, dirZ) від центру краю до центру приземлення. Стоїть моб на блоці-краю, доки його
    // хітбокс (півширина hw) перекриває клітинку [-0.5, 0.5] по ОБОХ осях.

    /**
     * Найбільший зсув за тік, при якому моб ще встигає приземлитись НА блок. Ванільна Y-колізія
     * приземлення на N-му тіку рахується ПЕРШОЮ й по позиції, що була після (N-1)-го тіку, тобто ДО
     * останнього горизонтального зсуву. Хітбокс має вже перекривати блок приземлення на тій позиції, а
     * вона лежить за один крок до центру: крок має бути меншим за радіус опори {@code 0.5 + halfWidth}.
     * (Стара модель із затуханням цього не потребувала: останній крок був ~0.15. При РІВНОМІРНОМУ польоті
     * малий зомбі з кроком 0.8 приземлявся б у порожнечу перед блоком і бився об його стінку.)
     */
    static double maxFlightStep(double halfWidth) {
        return EDGE_FRONT + halfWidth - LANDING_STEP_MARGIN;
    }

    /**
     * Передня межа блока-краю ВЗДОВЖ напрямку стрибка: осьовий стрибок - 0.5, діагональний - 0.707
     * (кут блока), косий - між ними ({@code 0.5 / max(|dirX|,|dirZ|)}).
     */
    static double frontBorder(double dirX, double dirZ) {
        double k = Math.max(Math.abs(dirX), Math.abs(dirZ));
        return k < 1.0E-6 ? EDGE_FRONT : EDGE_FRONT / k;
    }

    /**
     * Скільки тіків летіти, щоб швидкість польоту була ~крейсерською (швидкістю бігу): відстань / крок,
     * але не менше, ніж потрібно, щоб крок не перевищив {@code maxStep} ({@link #maxFlightStep}).
     * Обмежено знизу {@link #MIN_PLANNED_AIRTIME} (нижча дуга) і зверху природним польотом
     * {@link #AIRTIME_TICKS} (вища й довша - неможлива без зайвої висоти).
     */
    static int plannedAirtime(double distance, double cruiseStepPerTick, double maxStep) {
        double step = Math.min(Math.max(cruiseStepPerTick, 1.0E-3), Math.max(maxStep, 1.0E-3));
        long ticks = Math.round(distance / step);
        long minTicks = (long) Math.ceil(distance / Math.max(maxStep, 1.0E-3) - 1.0E-9);
        return (int) Math.max(MIN_PLANNED_AIRTIME, Math.min(AIRTIME_TICKS, Math.max(ticks, minTicks)));
    }

    /**
     * Найменша вертикальна швидкість (не нижча за поточну), при якій до торкання лишиться щонайменше
     * {@code targetTicks} тіків. Потрібна, коли контуру доводиться подовжити політ ("зависнути"), щоб
     * крок не перевищив {@link #maxFlightStep}. {@code currentVy}, якщо й так вистачає тіків.
     */
    static double verticalVelocityForRemainingTicks(double heightAboveLanding, double currentVy, int targetTicks) {
        for (double vy = currentVy; vy <= 0.5; vy += 0.005) {
            if (remainingAirTicks(heightAboveLanding, vy) >= targetTicks) {
                return vy;
            }
        }
        return 0.5;
    }

    /**
     * Вертикальна швидкість, яку треба виставити на ДРУГОМУ тіку польоту (1-й тік - ванільний підйом
     * 0.42, його не чіпаємо), щоб політ тривав рівно {@code totalTicks} (разом із тіком відриву). Це
     * найвища дуга з тих, що дають таку тривалість. {@code NaN} - природна фізика (політ не скорочуємо).
     */
    static double verticalVelocityForAirtime(int totalTicks) {
        if (totalTicks >= AIRTIME_TICKS) {
            return Double.NaN;
        }
        double heightAfterTakeoffTick = JUMP_VERTICAL_VELOCITY;
        double natural = (JUMP_VERTICAL_VELOCITY - GRAVITY_PER_TICK) * VERTICAL_DRAG_PER_TICK;
        for (double vy = natural; vy > -0.6; vy -= 0.002) {
            if (1 + remainingAirTicks(heightAfterTakeoffTick, vy) <= totalTicks) {
                return vy;
            }
        }
        return Double.NaN;
    }

    /**
     * Наземне прискорення моба за тік при бігу до краю. У ванілі мобу {@code MoveControl} ставить
     * {@code speed = множник * атрибут}, а {@code Mob.setSpeed} кладе те саме число ще й у вхід
     * {@code zza}, тож прискорення = speed * (speed * 0.98) — квадратичне за швидкістю. Це лише ОЦІНКА
     * для передбачення кроку (на сам політ вона не впливає — швидкість відриву ми задаємо явно).
     */
    static double estimateGroundAccel(double runSpeedSetpoint) {
        return runSpeedSetpoint * runSpeedSetpoint * 0.98;
    }

    /**
     * Усталений зсув моба за тік при бігу (блоків): на землі швидкість встановлюється там, де щотіку
     * прибавка {@code a} дорівнює втраті на терті, тобто {@code v = 0.546 * (v + a)} і зсув за тік
     * {@code v + a = a / (1 - 0.546)}. Природна швидкість бігу моба - вище за неї він сам не біжить, тож
     * і політ не має бути швидшим (інакше ланцюжок стрибків розганяється сам від себе).
     */
    static double steadyRunStep(double runSpeedSetpoint) {
        return estimateGroundAccel(runSpeedSetpoint) / (1.0 - GROUND_FRICTION);
    }

    /**
     * Чи пора стрибати ЦЬОГО тіку.
     * <p>
     * Найпізніша точка відриву — центр моба на передній межі блока вздовж стрибка
     * ({@link #frontBorder}). Зазвичай правило спрацьовує трохи раніше (запас на прискорення):
     * хітбокс тоді вже на самому краї, як у гравця при стрибку "з краю". Це навмисно НЕ те, що було
     * раніше: старий код ловив моба в колі R=0.7 навколо ЦЕНТРУ блока, тобто відривав його ще на 0.2
     * блока ДО блока-краю (більш ніж за 1.2 блока до реального краю).
     * <p>
     * Крім того відрив примусовий, якщо за наступний тік моб уже втратив би опору по будь-якій із
     * осей, куди він рухається (це і є "проскочити край за один тік").
     *
     * @param relX, relZ    позиція моба відносно центру блока-краю
     * @param dirX, dirZ    одиничний напрямок стрибка (від центру краю до центру приземлення)
     * @param speedAlong    поточна швидкість моба вздовж напрямку (deltaMovement, вже після тертя)
     * @param halfWidth     половина ширини хітбокса моба
     * @param groundAccel   оцінка наземного прискорення моба за тік, див. {@link #estimateGroundAccel}
     */
    static boolean shouldTakeOff(double relX, double relZ, double dirX, double dirZ,
                                 double speedAlong, double halfWidth, double groundAccel) {
        double along = relX * dirX + relZ * dirZ;
        if (along >= frontBorder(dirX, dirZ)) {
            return true;
        }
        double v = Math.max(0.0, speedAlong);
        // Зсув за наступний тік: на усталеній швидкості = v / 0.546 (deltaMovement = зсув * тертя),
        // а поки моб ще розганяється - v + прискорення. Беремо більше з двох + нижня межа.
        double stepSteady = v / GROUND_FRICTION;
        double stepAccelerating = v + Math.max(0.0, groundAccel);
        double predictedStep = Math.max(MIN_PREDICTED_STEP, Math.max(stepSteady, stepAccelerating));
        double look = predictedStep * STEP_LOOKAHEAD_FACTOR;
        double nextX = relX + dirX * look;
        double nextZ = relZ + dirZ * look;
        // Моб стоїть на землі, доки його хітбокс хоч трохи перекриває блок: центр < 0.5 + halfWidth.
        double limit = EDGE_FRONT + halfWidth - GROUND_LOSS_MARGIN;
        boolean loseX = (dirX > 1.0E-6 && nextX >= limit) || (dirX < -1.0E-6 && -nextX >= limit);
        boolean loseZ = (dirZ > 1.0E-6 && nextZ >= limit) || (dirZ < -1.0E-6 && -nextZ >= limit);
        return loseX || loseZ;
    }

    // =====================================================================================
    // БЛОК ПІД НОГАМИ: як тертя / speedFactor / jumpFactor міняють ДАЛЬНІСТЬ стрибка
    // =====================================================================================
    // Оцінка дальності (GapJumpUtils.estimateMaxJumpRangeBlocks) раніше була однаковою для будь-якого блока
    // відриву - як для звичайного. Тепер вона множиться на blockRangeFactor: це відношення "скільки блоків
    // пролетить моб, що біжить на повній швидкості ПО ЦЬОМУ блоку й відривається з нього" до того ж самого
    // для ЗВИЧАЙНОГО блока (тертя 0.6, speedFactor 1, jumpFactor 1). Для звичайного блока множник РІВНО 1.0,
    // тож там усе лишається як було. Фізика польоту й висота стрибка НЕ змінюються - це лише оцінка для
    // ПОБУДОВИ ШЛЯХУ (див. GapJumpNodeEvaluator.getNeighbors), а не втручання в рух моба.
    //
    // Ванільні формули (LivingEntity.travel / getFrictionInfluencedSpeed / Entity.move), f = тертя блока:
    //   * прискорення бігу за тік на землі:   a ~ 0.216 / f^3   (слизький блок - розганяє повільніше)
    //   * швидкість після тіку лишається:     F = f * 0.91      (слизький блок - довше тримає швидкість)
    //   * гальмо блока s (speedFactor, слайм): горизонтальна швидкість множиться на s щотіку на землі
    //   * усталений зсув за тік при бігу:     d = a / (1 - s * F)
    //   * політ: тік відриву ще НАЗЕМНИЙ (x F), далі повітря x 0.91; тривалість польоту задає вертикальна
    //     швидкість 0.42 * jumpFactor (мед 0.5 -> 7 тіків замість 12)
    //   * дальність = d * (сума горизонтальних множників польоту)
    // Спрощення: гальмо блока в самому польоті не враховується (моб на відриві вже за межею блока), а розбіг
    // вважається достатнім для усталеної швидкості - як і в "теоретичному максимумі" оцінки дальності.
    /**
     * Стеля множника: навіть найслизькіший блок не дає більше x2 до дальності.
     */
    static final double MAX_BLOCK_RANGE_FACTOR = 2.0;
    /**
     * Довіра до тертя блока: захист від сміття з чужих модів (0, NaN, >1 тощо).
     */
    private static final double MIN_BLOCK_FRICTION = 0.1;
    private static final double MAX_BLOCK_FRICTION = 1.0;
    /**
     * Гальмо блока (speedFactor) вище 1 теж можливе (блок-прискорювач із моду), але не безмежне.
     */
    private static final double MAX_RUN_SLOWDOWN = 2.0;
    private static final double MAX_JUMP_FACTOR = 4.0;
    /**
     * Захист від ділення на ~0, якщо {@code s * F} наближається до 1.
     */
    private static final double MIN_RUN_DENOMINATOR = 0.02;
    /**
     * Допуск, у межах якого блок вважається ЗВИЧАЙНИМ (float 0.6F != 0.6 у double).
     */
    private static final double NEUTRAL_EPSILON = 1.0E-4;
    /**
     * Дальність для звичайного блока в тих самих відносних одиницях - знаменник множника.
     */
    private static final double ORDINARY_BLOCK_RANGE = blockJumpRange(DEFAULT_BLOCK_FRICTION, 1.0, 1.0);

    /**
     * Множник до оцінки дальності стрибка для моба, що відривається з блока з такими властивостями.
     * {@code 1.0} - звичайний блок (оцінка не змінюється), {@code <1} - блок ближчий (слайм, пісок душ, мед),
     * {@code >1} - далі (лід). Значення береться з САМОГО блока, тож працює й для блоків з інших модів.
     *
     * @param blockFriction тертя блока ({@code BlockState.getFriction}); 0.6 - звичайний, 0.98 - лід
     * @param runSlowdown   гальмо ходьби по блоку за тік: {@code speedFactor} (пісок душ / мед = 0.4),
     *                      для слайма ще й {@code SlimeBlock.stepOn} (x0.4); 1.0 - без гальма
     * @param jumpFactor    {@code Block.getJumpFactor()}: мед = 0.5, інакше 1.0
     */
    static double blockRangeFactor(double blockFriction, double runSlowdown, double jumpFactor) {
        if (!Double.isFinite(blockFriction) || !Double.isFinite(runSlowdown) || !Double.isFinite(jumpFactor)
                || blockFriction <= 0.0) {
            return 1.0; // некоректні значення з чужого моду - поводимось як зі звичайним блоком
        }
        if (Math.abs(blockFriction - DEFAULT_BLOCK_FRICTION) < NEUTRAL_EPSILON
                && Math.abs(runSlowdown - 1.0) < NEUTRAL_EPSILON
                && Math.abs(jumpFactor - 1.0) < NEUTRAL_EPSILON) {
            return 1.0; // звичайний блок: нічого не міняємо, без жодних обчислень
        }
        double friction = clamp(blockFriction, MIN_BLOCK_FRICTION, MAX_BLOCK_FRICTION);
        double slowdown = clamp(runSlowdown, 0.0, MAX_RUN_SLOWDOWN);
        double jump = clamp(jumpFactor, 0.0, MAX_JUMP_FACTOR);
        return clamp(blockJumpRange(friction, slowdown, jump) / ORDINARY_BLOCK_RANGE, 0.0, MAX_BLOCK_RANGE_FACTOR);
    }

    /**
     * Відносна дальність (одиниця - прискорення звичайного блока): усталений зсув за тік на блоці, помножений
     * на суму горизонтальних множників польоту після відриву з нього.
     */
    private static double blockJumpRange(double friction, double runSlowdown, double jumpFactor) {
        double retention = friction * AIR_FRICTION;                        // F: що лишається від швидкості за тік
        double accel = Math.pow(DEFAULT_BLOCK_FRICTION / friction, 3.0);   // a відносно звичайного (0.216 / f^3)
        double denominator = Math.max(1.0 - runSlowdown * retention, MIN_RUN_DENOMINATOR);
        double steadyStep = accel / denominator;                           // d = a / (1 - s * F)
        return steadyStep * flightSum(retention, JUMP_VERTICAL_VELOCITY * jumpFactor);
    }

    /**
     * Сума горизонтальних множників польоту: тік відриву = 1, після нього швидкість множиться на
     * {@code takeoffRetention} (тертя блока відриву), далі на 0.91 за тік, доки моб не торкнеться рівня відриву.
     * Той самий цикл, що й у статичному блоці цього класу, тільки з довільною вертикальною швидкістю відриву.
     */
    private static double flightSum(double takeoffRetention, double jumpVelocity) {
        double y = 0.0;
        double vy = jumpVelocity;
        double factor = 1.0;
        double sum = 0.0;
        for (int tick = 1; tick <= 60; tick++) {
            y += vy;
            sum += factor;
            factor *= (tick == 1 ? takeoffRetention : AIR_FRICTION);
            vy = (vy - GRAVITY_PER_TICK) * VERTICAL_DRAG_PER_TICK;
            if (tick > 1 && y <= 0.0) {
                break;
            }
        }
        return sum;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}