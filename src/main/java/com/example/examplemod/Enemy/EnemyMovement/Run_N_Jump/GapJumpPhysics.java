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
 * <b>v14 (варіант B):</b> керований політ ({@link #flightCommand}) більше не використовується виконавцем. Моб відривається
 * і летить за ванільною фізикою, а модель із цього файлу (розділ «v14» унизу: {@link #forecastFlight}, {@link #predict},
 * {@link #chooseRunPlan}, {@link #airAccel}) лише ПЕРЕДБАЧАЄ політ - щоб обрати швидкість розбігу й граничну дальність
 * для графа шляхів. Старі функції польоту лишені як опис і для звірки.
 * <p>
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
 * <p>
 * <b>Δy (сходинки).</b> Усе вище - опис приземлення НА ТОМУ САМОМУ рівні (Δy=0, N=12). Для приземлення
 * вище/нижче рівня відриву формули узагальнені під довільний цілий Δy: {@link #naturalAirtime} (кількість
 * тіків), {@link #remainingAirTicks} (те саме, але з живих координат під час польоту) і
 * {@link #blockRangeFactor} (дальність). Вгору - тіків МЕНШЕ (вузьке вікно, ловимо момент, коли моб ще
 * ПІДНІМАЄТЬСЯ до потрібної висоти, а не спускається до неї), і недосяжно, якщо апекс стрибка (≈1.25 блока
 * при jumpFactor=1, менше з нижчим) нижчий за Δy. Вниз - тіків більше (довше падати). {@link #AIRTIME_TICKS}
 * і {@code FLIGHT_FACTOR_*} лишаються як були - це просто {@code naturalAirtime(0, 1.0)} і супутні суми.
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

    // =====================================================================================
    // Δy: СХОДИНКИ (стрибок одночасно вгору/вниз і через розрив)
    // =====================================================================================
    // Ліміт наскільки поточна версія готова ПРОПОНУВАТИ стрибки в графі шляхів (не плутати з тим, ЩО ВМІЄ
    // порахувати фізика нижче - вона генералізована під БУДЬ-ЯКИЙ цілий Δy). Підняти WALKUP пізніше (зілля
    // стрибка вище) - зміна одного числа, більше нічого чіпати не треба.
    static final int JUMP_UP_LIMIT_BLOCKS = 1;
    /**
     * Ванільне правило: падіння на 3 блоки чи менше не завдає шкоди (шкода = {@code (fallDistance-3)*0.2}
     * за блок понад поріг). Стрибок додає невеликий підйом ПЕРЕД падінням (апекс ~0.2-1.25 блока залежно
     * від jumpFactor блока відриву), але це загальновідомо НЕ підвищує шкоду понад звичайний "збіг з
     * виступу і впав на N блоків" - гравці постійно перестрибують провалля з розбігу й падають на кілька
     * блоків нижче без жодної шкоди навіть на самій межі. Тому ліміт - рівно 3, а не 3 мінус висота
     * підйому: {@code Δy=-3} лишається безпечним.
     */
    static final int JUMP_DOWN_LIMIT_BLOCKS = 3;
    /** Стеля множника: навіть найслизькіший блок не дає більше x2 до дальності. */
    static final double MAX_BLOCK_RANGE_FACTOR = 2.0;

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
    /** Довіра до тертя блока: захист від сміття з чужих модів (0, NaN, >1 тощо). */
    private static final double MIN_BLOCK_FRICTION = 0.1;

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

    /**
     * Найбільший горизонтальний крок за тік, який дозволяє профіль із коротким останнім кроком
     * ({@link #flightCommand}, {@code shortLastStep}): швидкість спринту зомбі ~0.44, вище - це вже ривок.
     */
    static final double MAX_SHORT_LAST_STEP_SPEED = 0.5;

    /**
     * Висоти блоків кратні 1/16, тож 1/256 не губить нічого, але прибирає шум double (64.00000000000001).
     */
    static double quantizeDeltaY(double deltaY) {
        return Math.rint(deltaY * 256.0) / 256.0;
    }

    /**
     * Той самий критерій відриву, але для опори НЕСТАНДАРТНОЇ форми (див. {@link ShapeJump}): замість меж
     * повного блока ({@link #frontBorder}, {@code 0.5 + halfWidth}) беруться реальні межі опори вздовж стрибка.
     * Для повного блока дає той самий результат, що й {@link #shouldTakeOff} при русі вздовж осі.
     *
     * @param relX, relZ  позиція моба відносно ТОЧКИ ВІДРИВУ
     * @param front       {@code along}, з якого відриваємось (центр моба над краєм поверхні)
     * @param lose        {@code along}, де хітбокс повністю сходить з опори (далі onGround=false)
     */
    static boolean shouldTakeOffAlong(double relX, double relZ, double dirX, double dirZ,
                                      double speedAlong, double groundAccel, double front, double lose) {
        double along = relX * dirX + relZ * dirZ;
        if (along >= front) {
            return true;
        }
        double v = Math.max(0.0, speedAlong);
        double stepSteady = v / GROUND_FRICTION;
        double stepAccelerating = v + Math.max(0.0, groundAccel);
        double predictedStep = Math.max(MIN_PREDICTED_STEP, Math.max(stepSteady, stepAccelerating));
        double look = predictedStep * STEP_LOOKAHEAD_FACTOR;
        return along + look >= lose - GROUND_LOSS_MARGIN;
    }

    /**
     * Команда польоту на один тік: нова deltaMovement (vx, vy, vz).
     */
    record FlightCommand(double vx, double vy, double vz) {
    }

    /**
     * Один тік замкненого контуру польоту: горизонтальна швидкість = відстань до точки приземлення / тіків до
     * торкання її висоти. Це ТОЙ САМИЙ розрахунок, що раніше жив у {@code GapJumpAssistGoal.controlFlight};
     * винесений сюди, щоб Goal і симуляція {@link ShapeJump} користувались ОДНИМ кодом.
     * <p>
     * Крок за тік не має перевищувати {@code maxStep} (радіус опори приземлення, див. {@link #maxFlightStep}):
     * <ul>
     *   <li>{@code shortLastStep=false} (повні блоки, як і було): якщо крок вийшов би більшим - політ
     *       подовжується (вертикаль піднімається, «зависання»), доки крок не вкладеться;</li>
     *   <li>{@code shortLastStep=true} (вузька опора - див. {@link ShapeJump}): політ лишається ванільним, а
     *       обмеженим робиться лише ОСТАННІЙ крок - решту дистанції моб долає трохи швидше
     *       ({@code (D - maxStep) / (n - 1)}), на останньому тіку йде коротким кроком {@code maxStep}. Хітбокс
     *       тоді на тіку торкання вже над опорою, а політ не перетворюється на «зависання» на 3 блоки вгору.
     *       Якщо потрібна для цього швидкість зависока ({@link #MAX_SHORT_LAST_STEP_SPEED}) - тоді все ж
     *       зависання, як раніше.</li>
     * </ul>
     *
     * @param vy      вертикальна швидкість з deltaMovement на початку тіку (вже після гравітації минулого тіку)
     * @param planCap стеля плану: відстань сегмента + запас (захист від абсурдної швидкості)
     * @return команду, або {@code null}, якщо рівня приземлення вже не досягти (керувати нічим)
     */
    static FlightCommand flightCommand(double mobX, double mobY, double mobZ, double vy,
                                       double landX, double landY, double landZ,
                                       double planCap, double maxStep, boolean shortLastStep) {
        double heightAbove = mobY - landY;
        double dx = landX - mobX;
        double dz = landZ - mobZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-6) {
            return null;
        }
        int ticks = remainingAirTicks(heightAbove, vy);
        if (ticks <= 0) {
            return null;
        }
        double planDist = Math.min(dist, planCap);
        if (planDist / ticks <= maxStep + 1.0E-9) {
            double perTick = planDist / ticks;
            return new FlightCommand(dx / dist * perTick, vy, dz / dist * perTick);
        }
        if (shortLastStep && ticks >= 2) {
            double perTick = (planDist - maxStep) / (ticks - 1);
            if (perTick <= MAX_SHORT_LAST_STEP_SPEED) {
                return new FlightCommand(dx / dist * perTick, vy, dz / dist * perTick);
            }
        }
        int needed = (int) Math.ceil(planDist / maxStep - 1.0E-9);
        if (needed > ticks) {
            double extendedVy = verticalVelocityForRemainingTicks(heightAbove, vy, needed);
            int extendedTicks = remainingAirTicks(heightAbove, extendedVy);
            if (extendedTicks > 0) {
                vy = extendedVy;
                ticks = extendedTicks;
            }
        }
        double perTick = planDist / ticks;
        return new FlightCommand(dx / dist * perTick, vy, dz / dist * perTick);
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
    /** Гальмо блока (speedFactor) вище 1 теж можливе (блок-прискорювач із моду), але не безмежне. */
    private static final double MAX_RUN_SLOWDOWN = 2.0;
    private static final double MAX_BLOCK_FRICTION = 1.0;
    private static final double MAX_JUMP_FACTOR = 4.0;

    /**
     * Скільки тіків триває ПРИРОДНИЙ (ванільний, підйом {@code 0.42*jumpFactor}) політ до перетину висоти
     * {@code deltaY} відносно точки відриву. {@code deltaY<=0} - рівно чи вниз: перший тік, коли моб
     * ОПУСТИВСЯ до потрібної висоти (як і раніше). {@code deltaY>0} - вгору: тут важливо НЕ ловити перший
     * дотик знизу (це і був баг - див. коментар нижче), а чекати, поки моб СПЕРШУ підніметься НАД цю
     * висоту (апекс стрибка ~1.25 блока при jumpFactor=1 - завжди вище повного блока, якщо взагалі
     * досяжно), а тоді опуститься назад до неї - так само, як звичайне приземлення, просто після пролітання
     * НАД цільовим блоком.
     * <p>
     * <b>Чому не "перший дотик знизу".</b> Так було спочатку, і це виглядало логічно ("злови момент,
     * коли нога опинилась на потрібній висоті") - але на практиці ламало сам стрибок. Вертикаль для Δy=+1
     * перетинає позначку 1.0 вже на 3-му тіку (апекс - на ~5-6-му), тож "перший дотик" дає ДУЖЕ короткий
     * політ (3 тіки замість 12 на рівному). Рівномірний горизонтальний крок (відстань/тіків) на такому
     * короткому вікні виходить занадто швидким: за перші 1-2 тіки моб долає більшість дистанції, а ноги
     * в цей момент ще НИЖЧЕ верху цільового блока - тобто він не "заскакує" на блок, а буквально в'їжджає
     * в нього збоку, як у стіну (ванільна колізія гасить горизонтальну швидкість до нуля - саме це видно
     * в дебаг-лозі як "ЗОВНІШНІЙ ІМПУЛЬС", хоча це не удар, а зіткнення зі стіною через зарано прикладену
     * горизонтальну швидкість). Чекаючи, поки моб пролетить НАД блоком і опуститься на нього зверху (як
     * при звичайному приземленні), вікно виходить набагато довшим (і майже завжди довшим за рівне - апекс
     * уже "витрачений" на висоту, а не на дальність, але це і так соразмерно повільніший рух вперед,
     * тому колізії зі стіною більше нема) і безпечним - той самий принцип, що вже давно працює для рівного
     * й для вниз.
     * <p>
     * Тертя/speedFactor блока відриву тут НЕ беруть участі - вертикаль від них не залежить, лише від
     * {@code jumpFactor} (початкова вертикальна швидкість) і ванільних гравітації/опору (фіксовані).
     *
     * @return тіків, або {@code -1}, якщо апекс нижчий за {@code deltaY} (мед і подібні дуже низькі
     *     стрибки з дуже похилими jumpFactor - вгору на повний блок фізично не дістати)
     */
    static int naturalAirtime(double deltaY, double jumpFactor) {
        double y = 0.0;
        double vy = JUMP_VERTICAL_VELOCITY * jumpFactor;
        boolean everAboveOrAt = deltaY <= 0.0; // рівно/вниз: як і завжди, з тіку 1; вгору: чекаємо, поки побуває
        for (int tick = 1; tick <= 60; tick++) {
            y += vy;
            vy = (vy - GRAVITY_PER_TICK) * VERTICAL_DRAG_PER_TICK;
            if (y >= deltaY) {
                everAboveOrAt = true;
            }
            boolean crossed = everAboveOrAt && (tick > 1 || deltaY <= 0.0) && y <= deltaY;
            if (crossed) {
                return tick;
            }
        }
        return -1;
    }
    /** Захист від ділення на ~0, якщо {@code s * F} наближається до 1. */
    private static final double MIN_RUN_DENOMINATOR = 0.02;
    /** Допуск, у межах якого блок вважається ЗВИЧАЙНИМ (float 0.6F != 0.6 у double). */
    private static final double NEUTRAL_EPSILON = 1.0E-4;

    /**
     * Скільки тіків лишилось до торкання рівня приземлення з поточного стану (включно з поточним
     * тіком). Ті самі формули, що й {@link #remainingAirFactor}: рух, потім гравітація й опір.
     * <p>
     * <b>Δy: вгору й вниз.</b> {@code heightAboveLanding} — це "наскільки моб ЗАРАЗ вище за рівень
     * приземлення" (від'ємне — ціль ще ВИЩЕ моба, тобто це стрибок ВГОРУ і потрібного рівня ще не
     * досягнуто). Напрямок пошуку визначає ЗНАК на вході: {@code < 0} — чекаємо, поки моб підніметься
     * ДО цілі ({@code y >= 0}, як гравець застрибує на вищий блок — приземлення ловиться в перший
     * момент дотику знизу); {@code >= 0} — як і раніше, чекаємо, поки моб ОПУСТИТЬСЯ до цілі
     * ({@code y <= 0}: рівний стрибок чи стрибок ВНИЗ). Не залежить від того, ЯКИЙ це стрибок за
     * планом — лише від того, де моб ЗАРАЗ відносно landing, тож коректно обробляє і "проскочив повз".
     * <p>
     * <b>Вгору: НЕ перший дотик знизу.</b> Як і в {@link #naturalAirtime} — щойно {@code y} вперше
     * піднявся до {@code 0} чи вище, чекаємо продовження польоту (апекс дуги ще попереду) і ловимо
     * приземлення лише коли моб ОПУСТИТЬСЯ назад до {@code y<=0}, після того, як побував на цій висоті
     * чи вище. Перший дотик знизу давав занадто короткий, ризикований політ (моб в'їжджав у бік
     * цільового блока, а не заскакував на нього зверху) — саме це й було причиною бага.
     *
     * @return тіків до приземлення, або {@code -1}, якщо в межах природної дуги (60 тіків - явний
     * запобіжник) моб рівня приземлення не досягає (найчастіше — стрибок ВГОРУ, для якого забракло
     * висоти: низький {@code jumpFactor} блока відриву чи Δy більший за апекс стрибка).
     */
    static int remainingAirTicks(double heightAboveLanding, double verticalVelocity) {
        double y = heightAboveLanding;
        double vy = verticalVelocity;
        boolean everAboveOrAt = y >= 0.0;
        for (int tick = 1; tick <= 60; tick++) {
            y += vy;
            vy = (vy - GRAVITY_PER_TICK) * VERTICAL_DRAG_PER_TICK;
            if (y >= 0.0) {
                everAboveOrAt = true;
            }
            if (everAboveOrAt && y <= 0.0) {
                return tick;
            }
        }
        return -1;
    }

    /**
     * Дальність для звичайного блока НА РІВНОМУ (Δy=0) в тих самих відносних одиницях - знаменник УСІХ множників,
     * і рівних, і вгору/вниз: усе завжди відносно "звичайний блок, рівний стрибок" = 1.0.
     */
    private static final double ORDINARY_BLOCK_RANGE = blockJumpRange(DEFAULT_BLOCK_FRICTION, 1.0, 1.0, 0.0);

    /**
     * Множник до оцінки дальності стрибка для моба, що відривається з блока з такими властивостями, ТАКОГО Δy
     * (вгору/вниз/рівно). {@code 1.0} - звичайний блок І рівний стрибок (нічого не змінюється), {@code <1} -
     * ближче (слайм/пісок душ/мед; вгору - ЗАВЖДИ менше 1, навіть на звичайному блоці - фізика: вікно для
     * підйому коротше за рівний політ), {@code >1} - далі (лід; вниз - трохи далі навіть на звичайному блоці).
     * Значення блока беруться з САМОГО блока, тож працює й для блоків з інших модів.
     *
     * @param blockFriction тертя блока ({@code BlockState.getFriction}); 0.6 - звичайний, 0.98 - лід
     * @param runSlowdown   гальмо ходьби по блоку за тік: {@code speedFactor} (пісок душ / мед = 0.4),
     *                      для слайма ще й {@code SlimeBlock.stepOn} (x0.4); 1.0 - без гальма
     * @param jumpFactor    {@code Block.getJumpFactor()}: мед = 0.5, інакше 1.0
     * @param deltaY        landing.y - edge.y: {@code 0} рівно, {@code >0} вгору, {@code <0} вниз
     * @return множник, або {@code 0.0}, якщо з цього блока на таку висоту не піднятись (низький jumpFactor,
     *     Δy більший за апекс стрибка - наприклад, мед вгору)
     */
    static double blockRangeFactor(double blockFriction, double runSlowdown, double jumpFactor, double deltaY) {
        if (!Double.isFinite(blockFriction) || !Double.isFinite(runSlowdown) || !Double.isFinite(jumpFactor)
                || !Double.isFinite(deltaY) || blockFriction <= 0.0) {
            return deltaY == 0.0 ? 1.0 : 0.0; // некоректні дані з чужого моду: рівно - як звичайний, вгору/вниз - обережно 0
        }
        if (deltaY == 0.0
                && Math.abs(blockFriction - DEFAULT_BLOCK_FRICTION) < NEUTRAL_EPSILON
                && Math.abs(runSlowdown - 1.0) < NEUTRAL_EPSILON
                && Math.abs(jumpFactor - 1.0) < NEUTRAL_EPSILON) {
            return 1.0; // звичайний блок І рівний стрибок: нічого не міняємо, без жодних обчислень
        }
        double friction = clamp(blockFriction, MIN_BLOCK_FRICTION, MAX_BLOCK_FRICTION);
        double slowdown = clamp(runSlowdown, 0.0, MAX_RUN_SLOWDOWN);
        double jump = clamp(jumpFactor, 0.0, MAX_JUMP_FACTOR);
        return clamp(blockJumpRange(friction, slowdown, jump, deltaY) / ORDINARY_BLOCK_RANGE, 0.0, MAX_BLOCK_RANGE_FACTOR);
    }

    /**
     * Відносна дальність (одиниця - прискорення звичайного блока): усталений зсув за тік на блоці, помножений
     * на суму горизонтальних множників польоту після відриву з нього, для приземлення на висоті {@code deltaY}.
     */
    private static double blockJumpRange(double friction, double runSlowdown, double jumpFactor, double deltaY) {
        double retention = friction * AIR_FRICTION;                        // F: що лишається від швидкості за тік
        double accel = Math.pow(DEFAULT_BLOCK_FRICTION / friction, 3.0);   // a відносно звичайного (0.216 / f^3)
        double denominator = Math.max(1.0 - runSlowdown * retention, MIN_RUN_DENOMINATOR);
        double steadyStep = accel / denominator;                           // d = a / (1 - s * F)
        return steadyStep * flightSum(deltaY, retention, JUMP_VERTICAL_VELOCITY * jumpFactor);
    }

    /**
     * Сума горизонтальних множників польоту ДО ПЕРЕТИНУ висоти {@code deltaY}: тік відриву = 1, після нього
     * швидкість множиться на {@code takeoffRetention} (тертя блока відриву), далі на 0.91 за тік. {@code deltaY}:
     * {@code 0} чи {@code <0} - як і раніше, чекаємо, поки моб ОПУСТИТЬСЯ до цієї висоти (рівний/спадний стрибок);
     * {@code >0} - чекаємо, поки моб СПЕРШУ підніметься НАД неї (апекс дуги), а тоді опуститься назад (як і
     * {@link #naturalAirtime} - не перший дотик знизу, той короткий і ризикований). Той самий цикл, що й у
     * статичному блоці класу й у {@link #naturalAirtime}, тільки тут ще й накопичуємо горизонтальну суму.
     * Пакетна (не {@code private}): використовується й у {@code GapJumpAssistGoal} для перевірки "чи долетить
     * моб до landing просто збігши з краю, без стрибка" ({@code jumpVelocity=0}).
     *
     * @return суму множників, або {@code 0.0}, якщо висота {@code deltaY} за 60 тіків не досягається
     *     (недосяжно - наприклад, дуже низький {@code jumpFactor} і Δy>0)
     */
    static double flightSum(double deltaY, double takeoffRetention, double jumpVelocity) {
        double y = 0.0;
        double vy = jumpVelocity;
        double factor = 1.0;
        double sum = 0.0;
        boolean everAboveOrAt = deltaY <= 0.0;
        for (int tick = 1; tick <= 60; tick++) {
            y += vy;
            sum += factor;
            factor *= (tick == 1 ? takeoffRetention : AIR_FRICTION);
            vy = (vy - GRAVITY_PER_TICK) * VERTICAL_DRAG_PER_TICK;
            if (y >= deltaY) {
                everAboveOrAt = true;
            }
            boolean crossed = everAboveOrAt && (tick > 1 || deltaY <= 0.0) && y <= deltaY;
            if (crossed) {
                return sum;
            }
        }
        return 0.0; // недосяжно
    }

    // =====================================================================================
    // v14 (ВАРІАНТ B): ПРИРОДНИЙ СТРИБОК «ЯК У ГРАВЦЯ»
    // =====================================================================================
    // Раніше (v9-v13) ціль щотіку ПЕРЕЗАПИСУВАЛА швидкість моба ({@link #flightCommand}) і так вела його по дузі
    // до центру приземлення. Тепер усе, що відбувається з мобом у стрибку, робить ванільна фізика:
    //   * розбіг  - звичайний MoveControl (швидкість = уставка * атрибут); ціль лише ОБИРАЄ уставку;
    //   * відрив  - звичайне jumpControl.jump() зі спринтом: vy = 0.42 * jumpFactor, плюс +0.2 уздовж погляду;
    //   * політ   - ванільний travel(): гравітація й опір 0.91 за тік. Керування в повітрі - лише те, що має
    //               гравець, який тримає W/A/S/D при спринті: прискорення 0.026 * 0.98 бл/тік^2, не більше.
    // Усі функції нижче - чиста математика (без Minecraft-типів), 1D уздовж осі стрибка; бічну складову
    // й саме прискорення в повітрі рахує {@link #airAccel}.

    /**
     * Поштовх ванільного {@code LivingEntity.jumpFromGround()} при спринті: +0.2 бл/тік уздовж погляду.
     */
    static final double SPRINT_JUMP_BOOST = 0.2;

    /**
     * Повітряне прискорення спринтуючого гравця: {@code Player.getFlyingSpeed()} = 0.026 при спринті, вхід
     * уперед = 1.0 * 0.98. Ванільний моб у повітрі має близько 0.008 (0.02 * вхід ~0.4): втричі менше.
     */
    static final double PLAYER_AIR_ACCEL = 0.026 * 0.98;

    /**
     * Скільки від сили повітряного керування гравця дозволено мобу: {@code 1.0} - як у гравця,
     * {@code 0.0} - суто балістичний політ без жодного керування (тоді зомбі летить рівно так, як вийшов із розбігу).
     */
    static final double AIR_CONTROL_SCALE = 1.0;
    static final double MAX_AIR_ACCEL = PLAYER_AIR_ACCEL * AIR_CONTROL_SCALE;

    /**
     * «Номінальний тиск на W» при розрахунку розбігу: частка {@link #MAX_AIR_ACCEL}, на яку цілимось, щоб у
     * польоті лишався запас корекції в обидва боки (і додати, і зменшити).
     */
    static final double NOMINAL_AIR_FRACTION = 0.5;

    /**
     * Запас дальності ПЛАНУВАННЯ (блоки польоту) - щоб граф не пропонував стрибки на самій межі можливого:
     * відрив трапляється трохи раніше за передню межу (правило {@link #shouldTakeOff} має запас на прискорення),
     * а розбіг не завжди встигає вийти на повну швидкість.
     */
    static final double PLAN_RANGE_MARGIN = 0.30;

    /**
     * Далі за стільки блоків до краю моб просто біжить на повній швидкості й зі спринтом - регулювання лише поблизу.
     */
    static final double RUN_REGULATION_DISTANCE = 2.0;

    /**
     * Найнижча уставка розбігу при регулюванні: частка від повної. 0.5 для зомбі - це його ванільна швидкість ходьби
     * (~2.3 бл/с): повільніше моб уже не «підповзає» до краю, а надто короткі стрибки дає не повільність, а відпущений
     * спринт (див. {@link #chooseRunPlan}).
     */
    static final double MIN_RUN_SETPOINT_FRACTION = 0.5;

    /**
     * Гістерезис вибору «зі спринтом / без»: щоб на межі моб не перемикав спринт щотіку, відпускаємо спринт лише коли
     * без нього дальності вистачає з запасом {@code RELEASE_SPRINT_MARGIN}, а повертаємо - коли не вистачає понад
     * {@code KEEP_NO_SPRINT_SHORTFALL} (решту підтягне повітряне керування).
     */
    static final double RELEASE_SPRINT_MARGIN = 0.25;
    static final double KEEP_NO_SPRINT_SHORTFALL = 0.15;

    /**
     * Ванільне {@code LivingEntity.aiStep}: {@code zza *= 0.98}.
     */
    static final double INPUT_SCALE = 0.98;

    /**
     * Ванільне {@code LivingEntity.getFrictionInfluencedSpeed}: на землі швидкість * 0.216 / тертя^3.
     */
    static final double GROUND_SPEED_CONSTANT = 0.21600002;

    /**
     * Скільки тіків уперед рахує прогноз розбігу (запобіжник від «моб ніколи не дійде до краю»).
     */
    static final int MAX_PREDICT_TICKS = 80;
    static final Takeoff ORDINARY_TAKEOFF = new Takeoff(DEFAULT_BLOCK_FRICTION, 1.0, 1.0);
    /**
     * Допуск у {@link #reachableWithoutJump}: політ вважається достатнім, якщо коротший за потрібний не більше ніж на стільки.
     */
    static final double NO_JUMP_TOLERANCE = 0.25;
    private static final double[] AIR_CARRY = new double[61];
    private static final double[] AIR_PUSH = new double[61];

    static {
        double pow = 1.0;
        double carry = 0.0;
        double push = 0.0;
        for (int n = 1; n <= 60; n++) {
            carry += pow;                                   // 0.91^(n-1)
            pow *= AIR_FRICTION;                            // 0.91^n
            push += (1.0 - pow) / (1.0 - AIR_FRICTION);
            AIR_CARRY[n] = carry;
            AIR_PUSH[n] = push;
        }
    }

    /**
     * Наземне прискорення моба за тік при бігу з уставкою {@code setpoint}. У ванілі {@code MoveControl} ставить
     * {@code speed = множник * атрибут}, а {@code Mob.setSpeed} кладе те саме число ще й у вхід {@code zza}, тож
     * прискорення = {@code speed * 0.216/тертя^3 * (speed * 0.98)} - квадратичне за уставкою. Для звичайного блока
     * (тертя 0.6) множник 0.216/тертя^3 = 1.
     */
    static double groundAccel(double setpoint, double friction) {
        double f = clamp(friction, MIN_BLOCK_FRICTION, MAX_BLOCK_FRICTION);
        double input = Math.min(1.0, Math.max(0.0, setpoint) * INPUT_SCALE);
        return Math.max(0.0, setpoint) * (GROUND_SPEED_CONSTANT / (f * f * f)) * input;
    }

    /**
     * Усталений зсув за тік при бігу з уставкою {@code setpoint} по блоку {@code t}: {@code a / (1 - гальмо * F)}.
     */
    static double steadyStep(double setpoint, Takeoff t) {
        return groundAccel(setpoint, t.friction()) / Math.max(1.0 - t.retention(), MIN_RUN_DENOMINATOR);
    }

    /**
     * deltaMovement (вздовж осі) на початку тіку при усталеному бігу: {@code зсув * тертя}.
     */
    static double steadyVelocity(double setpoint, Takeoff t) {
        return steadyStep(setpoint, t) * t.retention();
    }

    /**
     * Ванільний політ тік за тіком, починаючи з тіку відриву.
     * <ul>
     *   <li>тік 1: {@code jumpFromGround()} дає поштовх спринту ({@code boost}), а {@code travel()} ще вважає, що моб
     *       на землі, тож додає наземне прискорення {@code groundAccel}, і тертя після зсуву - НАЗЕМНЕ;</li>
     *   <li>тіки 2..N: прискорення в повітрі {@code airAccel} (рівне на весь політ), зсув, опір 0.91.</li>
     * </ul>
     *
     * @param v0          deltaMovement уздовж осі на початку тіку відриву (після тертя минулого тіку)
     * @param groundAccel наземне прискорення цього тіку ({@link #groundAccel})
     * @param boost       чи діє поштовх спринту (ігнорується, якщо {@code noJump})
     * @param noJump      моб просто збігає з краю, без вертикального імпульсу
     * @return політ, або {@code null}, якщо висоти {@code deltaY} із цього стрибка не досягти
     */
    static Forecast forecastFlight(double v0, double groundAccel, Takeoff t, boolean boost, double airAccel,
                                   double deltaY, boolean noJump) {
        int n = naturalAirtime(deltaY, noJump ? 0.0 : t.jumpFactor());
        if (n <= 0) {
            return null;
        }
        double step = v0 + (boost && !noJump ? SPRINT_JUMP_BOOST : 0.0) + groundAccel;
        double x = step;
        double v = step * t.retention();
        double before = 0.0;
        for (int tick = 2; tick <= n; tick++) {
            before = x;
            v += airAccel;
            x += v;
            v *= AIR_FRICTION;
        }
        return new Forecast(x, before, n, v);
    }

    /**
     * Найбільша дальність польоту (від позиції на початку тіку відриву) для моба, що біжить по блоку {@code t} на
     * повній швидкості й тримає «W» у повітрі на повну силу: це стеля для ПЛАНУВАННЯ графа шляхів.
     */
    static double maxReach(double setpointMax, Takeoff t, double deltaY, boolean sprint) {
        double a = groundAccel(setpointMax, t.friction());
        Forecast f = forecastFlight(steadyVelocity(setpointMax, t), a, t, sprint, MAX_AIR_ACCEL, deltaY, false);
        return f == null ? 0.0 : f.reach();
    }

    /**
     * Скільки блоків розриву (як {@code maxGap} у {@link GapJumpRays}) вважати прохідними для цього моба, цього блока
     * відриву й такого Δy. Рахується з фізики ({@link #maxReach} мінус {@link #PLAN_RANGE_MARGIN}), а не з калібрувального
     * коефіцієнта. {@code 0} - стрибок (чи така висота) недосяжні.
     */
    static int maxGapBlocks(double setpointMax, Takeoff t, double deltaY) {
        double reach = maxReach(setpointMax, t, deltaY, true);
        if (reach <= 0.0) {
            return 0;
        }
        double allowed = reach - PLAN_RANGE_MARGIN;
        return Math.max(0, (int) Math.floor(allowed - EDGE_FRONT - GapJumpRays.FLIGHT_TOLERANCE + 1.0E-9));
    }

    /**
     * Зсув за тік відриву ("крок 1"), після якого природний політ із постійним прискоренням {@code airAccel} у повітрі
     * долітає рівно на {@code dist}. Із виразу {@code reach = step * (1 + F * S1(m)) + airAccel * S2(m)}, де {@code m} - тіків
     * у повітрі після тіку відриву, {@code F} - наземне тертя тіку відриву.
     */
    static double takeoffStepForReach(double dist, Takeoff t, int airTicks, double airAccel) {
        int m = Math.max(0, Math.min(airTicks - 1, 60));
        double carry = m == 0 ? 0.0 : AIR_CARRY[m];
        double push = m == 0 ? 0.0 : AIR_PUSH[m];
        return (dist - airAccel * push) / (1.0 + t.retention() * carry);
    }

    /**
     * Горизонтальна швидкість (deltaMovement) у повітрі, після якої політ ще на {@code ticks} тіків із постійним
     * прискоренням {@code airAccel} долітає на {@code dist}. Потрібна лише рятувальному стрибку.
     */
    static double airSpeedForReach(double dist, int ticks, double airAccel) {
        int n = Math.max(1, Math.min(ticks, 60));
        return (dist - airAccel * AIR_PUSH[n]) / AIR_CARRY[n];
    }

    /**
     * Чи долетить моб до приземлення (на {@code flight} блоків від краю, нижче на {@code -deltaY}) просто збігши з краю, без
     * стрибка, - так, як гравець просто збігає з виступу, а не підстрибує. Лише для спуску ({@code deltaY < 0}): без
     * стрибка вгору чи рівно нікуди не долетіти. Рахується на повній швидкості бігу з номінальним тиском на W.
     */
    static boolean reachableWithoutJump(Takeoff t, double setpointMax, double deltaY, double flight) {
        if (!(deltaY < 0.0)) {
            return false;
        }
        double a = groundAccel(setpointMax, t.friction());
        Forecast f = forecastFlight(steadyVelocity(setpointMax, t), a, t, false,
                MAX_AIR_ACCEL * NOMINAL_AIR_FRACTION, deltaY, true);
        return f != null && f.reach() + NO_JUMP_TOLERANCE >= flight;
    }

    /**
     * Межа, де хітбокс повного блока сходить з опори вздовж стрибка (для повного блока: {@code (0.5 + hw) / max(|dir|)}).
     */
    static double plainLoseAlong(double dirX, double dirZ, double halfWidth) {
        double k = Math.max(Math.abs(dirX), Math.abs(dirZ));
        return (EDGE_FRONT + halfWidth) / (k < 1.0E-6 ? 1.0 : k);
    }

    /**
     * Прогноз ВСЬОГО стрибка з поточного стану: моб на {@code along} уздовж осі, його deltaMovement уздовж осі = {@code v};
     * з цього тіку він біжить із уставкою {@code setpoint}, відривається за ТИМ САМИМ правилом, що й виконавець
     * ({@link #shouldTakeOffAlong}), а в повітрі тримає постійне прискорення {@code airAccel}.
     *
     * @return прогноз; {@code null}, якщо моб не відірветься за {@link #MAX_PREDICT_TICKS} (не дійде до краю) або
     * висота недосяжна
     */
    static Prediction predict(double along, double v, double setpoint, Takeoff t, Geometry g, double airAccel) {
        double a = groundAccel(setpoint, t.friction());
        double ret = t.retention();
        double startSpeed = v;
        for (int tick = 0; tick < MAX_PREDICT_TICKS; tick++) {
            boolean takeoff = g.noJump()
                    // збіг без стрибка: «відрив» - це тік, у якому центр моба перейде межу, де хітбокс сходить з опори
                    ? along + v + a >= g.lose()
                    : shouldTakeOffAlong(along, 0.0, 1.0, 0.0, v, a, g.front(), g.lose());
            if (takeoff) {
                Forecast f = forecastFlight(v, a, t, g.boost(), airAccel, g.deltaY(), g.noJump());
                if (f == null) {
                    return null;
                }
                return new Prediction(along + f.reach(), along, tick, startSpeed);
            }
            double step = v + a;
            along += step;
            v = step * ret;
        }
        return null;
    }

    /**
     * Те саме, що {@link #predict}, але лише точка приземлення; {@code -Infinity}, якщо прогнозу нема (щоб рахувати як «замало»).
     */
    static double predictTouchdown(double along, double v, double setpoint, Takeoff t, Geometry g, double airAccel) {
        Prediction p = predict(along, v, setpoint, t, g, airAccel);
        return p == null ? Double.NEGATIVE_INFINITY : p.touchdown();
    }

    /**
     * ГОЛОВНЕ РЕГУЛЮВАННЯ БЕЗ «ВЕДЕННЯ»: що робить моб на цьому тіку розбігу, щоб природний політ (ванільний відрив +
     * номінальний тиск на W у повітрі) приземлив його в центр. Це рівно ті два «важелі», що є в гравця:
     * <ul>
     *   <li><b>спринт</b>: зі спринтом ванільний стрибок дає поштовх +0.2 (це ~1 блок дальності), без нього - ні. Для
     *       короткого стрибка спринт відпускається - як гравець, що відпускає його перед маленьким розривом;</li>
     *   <li><b>швидкість</b>: уставка MoveControl від ходьби ({@link #MIN_RUN_SETPOINT_FRACTION}) до повної.</li>
     * </ul>
     * Нічого, окрім цих двох, не змінюється: швидкість ніхто не виставляє. Прогноз монотонний за уставкою (швидше - далі),
     * тож уставку шукаємо діленням навпіл. Далеко від краю ({@link #RUN_REGULATION_DISTANCE}) - повна швидкість і спринт.
     * Якщо навіть повна швидкість зі спринтом не дотягує - повна (докручує повітряне керування в межах сили гравця); якщо
     * навіть найнижча перелітає - найнижча (повітряне керування підгальмує).
     *
     * @param g             геометрія стрибка; поле {@code boost} тут ігнорується (пробуються обидва варіанти)
     * @param sMax          повна уставка бігу ({@code GapJumpUtils.runSpeedSetpoint})
     * @param nominal       «номінальний тиск на W» у повітрі, на який цілимось
     * @param previousBoost чи був спринт на минулому тіку (для гістерезису)
     */
    static RunPlan chooseRunPlan(double along, double v, Takeoff t, Geometry g, double sMax, double nominal,
                                 boolean previousBoost) {
        if (!(sMax > 0.0) || g.front() - along > RUN_REGULATION_DISTANCE) {
            return new RunPlan(sMax, true);
        }
        double sMin = sMax * MIN_RUN_SETPOINT_FRACTION;
        double target = g.length();
        if (g.noJump()) {
            // збіг без стрибка: поштовху нема, регулюємо лише швидкість
            return new RunPlan(solveSetpoint(along, v, t, g, sMin, sMax, nominal, target), true);
        }
        Geometry plain = new Geometry(g.front(), g.lose(), g.length(), g.deltaY(), false, false);
        Geometry boosted = new Geometry(g.front(), g.lose(), g.length(), g.deltaY(), true, false);
        double noSprintAtMax = predictTouchdown(along, v, sMax, t, plain, nominal) - target;
        boolean releaseSprint = previousBoost ? noSprintAtMax >= RELEASE_SPRINT_MARGIN
                : noSprintAtMax >= -KEEP_NO_SPRINT_SHORTFALL;
        if (releaseSprint) {
            return new RunPlan(solveSetpoint(along, v, t, plain, sMin, sMax, nominal, target), false);
        }
        return new RunPlan(solveSetpoint(along, v, t, boosted, sMin, sMax, nominal, target), true);
    }

    /**
     * Уставка зі {@code [sMin, sMax]}, при якій прогноз приземлення найближчий до {@code target} (див. {@link #chooseRunPlan}).
     */
    private static double solveSetpoint(double along, double v, Takeoff t, Geometry g, double sMin, double sMax,
                                        double nominal, double target) {
        double eMax = predictTouchdown(along, v, sMax, t, g, nominal) - target;
        if (!(eMax > 0.0)) {
            return sMax;
        }
        double eMin = predictTouchdown(along, v, sMin, t, g, nominal) - target;
        if (eMin >= 0.0) {
            return sMin;
        }
        double lo = sMin;
        double hi = sMax;
        double eLo = eMin;
        double eHi = eMax;
        for (int i = 0; i < 20; i++) {
            double mid = 0.5 * (lo + hi);
            double e = predictTouchdown(along, v, mid, t, g, nominal) - target;
            if (e < 0.0) {
                lo = mid;
                eLo = e;
            } else {
                hi = mid;
                eHi = e;
            }
        }
        return -eLo <= eHi ? lo : hi; // обираємо бік із меншою помилкою (на розриві прогноз стрибає на один тік)
    }

    /**
     * Прискорення, яке моб додає ЦЬОГО тіку в повітрі (до того, як ванільний {@code travel()} зсуне його й помножить швидкість
     * на 0.91). Це рівно те саме, що робить {@code moveRelative} гравця, який тримає клавішу: просто додає до deltaMovement.
     * Прискорення - постійне до кінця польоту й вибране так, щоб приземлитись у ціль; його довжина обмежена
     * {@code maxAccel} (сила гравця), тож велику помилку воно не виправить - лише підправить малу.
     * <p>
     * Виведення: якщо зараз швидкість {@code V}, лишилось {@code n} тіків (разом із цим), а прискорення {@code A} постійне, то
     * зсув до торкання = {@code V * S1(n) + A * S2(n)}, де {@code S1 = sum 0.91^(t-1)}, {@code S2 = sum (1 - 0.91^t)/0.09}.
     *
     * @param ticks скільки тіків лишилось до торкання, включно з цим ({@link #remainingAirTicks})
     */
    static AirAccel airAccel(double posX, double posZ, double velX, double velZ, int ticks,
                             double targetX, double targetZ, double maxAccel) {
        if (ticks <= 0 || !(maxAccel > 0.0)) {
            return AirAccel.NONE;
        }
        int n = Math.min(ticks, 60);
        double needX = targetX - posX - velX * AIR_CARRY[n];
        double needZ = targetZ - posZ - velZ * AIR_CARRY[n];
        double ax = needX / AIR_PUSH[n];
        double az = needZ / AIR_PUSH[n];
        double len = Math.sqrt(ax * ax + az * az);
        if (len > maxAccel) {
            double k = maxAccel / len;
            ax *= k;
            az *= k;
        }
        return new AirAccel(ax, az);
    }

    /**
     * Властивості опори, з якої моб відривається: тертя блока, гальмо ходьби ({@code speedFactor}, для слайма ще
     * {@code x0.4}) і {@code jumpFactor}. Для звичайного блока - {@link #ORDINARY_TAKEOFF}.
     */
    record Takeoff(double friction, double slowdown, double jumpFactor) {

        /**
         * Те саме, але з захистом від сміття з чужих модів (0, NaN, >1 тощо).
         */
        static Takeoff of(double friction, double slowdown, double jumpFactor) {
            double f = Double.isFinite(friction) ? clamp(friction, MIN_BLOCK_FRICTION, MAX_BLOCK_FRICTION) : DEFAULT_BLOCK_FRICTION;
            double s = Double.isFinite(slowdown) ? clamp(slowdown, 0.0, MAX_RUN_SLOWDOWN) : 1.0;
            double j = Double.isFinite(jumpFactor) ? clamp(jumpFactor, 0.0, MAX_JUMP_FACTOR) : 1.0;
            return new Takeoff(f, s, j);
        }

        /**
         * Яка частка горизонтальної швидкості лишається після ОДНОГО наземного тіку: тертя * 0.91 * гальмо.
         */
        double retention() {
            return this.friction * AIR_FRICTION * this.slowdown;
        }
    }

    // ---- керування в повітрі: що може гравець (W/A/S/D + поворот), і не більше ----

    /**
     * Політ від початку тіку відриву.
     *
     * @param reach           горизонтальний зсув від позиції на початку тіку відриву до торкання землі (включно з
     *                        останнім кроком)
     * @param reachBeforeLast те саме, але до останнього кроку: саме ця позиція вирішує, чи хітбокс уже над опорою
     *                        приземлення (ванільна Y-колізія рахується першою)
     * @param ticks           тіків польоту (разом із тіком відриву)
     * @param endSpeed        deltaMovement уздовж осі після торкання (тертя 0.91 уже застосоване)
     */
    record Forecast(double reach, double reachBeforeLast, int ticks, double endSpeed) {
    }

    /**
     * Геометрія стрибка вздовж його осі (початок відліку - точка відриву, тобто центр блока-краю або точка стояння
     * на нестандартній опорі).
     *
     * @param front  {@code along}, із якого відриваємось (центр моба над краєм поверхні)
     * @param lose   {@code along}, де хітбокс повністю сходить з опори
     * @param length відстань від точки відриву до центру приземлення (сюди цілимось)
     * @param deltaY висота приземлення мінус висота відриву
     * @param boost  чи увімкнений спринт (поштовх +0.2 у момент відриву)
     * @param noJump моб не стрибає, а збігає з краю
     */
    record Geometry(double front, double lose, double length, double deltaY, boolean boost, boolean noJump) {
    }

    /**
     * Результат {@link #predict}: де приземлиться моб і де він при цьому відірвався.
     */
    record Prediction(double touchdown, double takeoffAlong, int groundTicks, double startSpeed) {
    }

    /**
     * Рішення про розбіг на цей тік: уставка швидкості для MoveControl і чи тримати спринт (поштовх +0.2 при відриві).
     */
    record RunPlan(double setpoint, boolean boost) {
    }

    /**
     * Прискорення в повітрі на цей тік.
     */
    record AirAccel(double ax, double az) {
        static final AirAccel NONE = new AirAccel(0.0, 0.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}