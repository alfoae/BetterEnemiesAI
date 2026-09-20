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
 * Звідси головне: горизонтальна відстань польоту ЛІНІЙНА за стартовою швидкістю s:
 * {@code distance = s * FLIGHT_FACTOR_TO_LANDING} (≈ 4.917), а не {@code s * 10 * 0.85 = s * 8.5}, як
 * було в старій моделі (та вважала швидкість сталою весь політ і не знала ні про тертя відриву,
 * ні про 0.91 у повітрі — тому переоцінювала дальність майже вдвічі).
 */
final class GapJumpPhysics {

    /** Скільки тіків моб у повітрі на рівному місці (включно з тіком відриву та тіком приземлення). */
    static final int AIRTIME_TICKS;

    static final double JUMP_VERTICAL_VELOCITY = 0.42;
    static final double GRAVITY_PER_TICK = 0.08;
    static final double VERTICAL_DRAG_PER_TICK = 0.98;
    static final double AIR_FRICTION = 0.91;

    /**
     * Тертя блока під ногами (0.6 — усі звичайні блоки). Лід/слайм/мед мають інше — модель тоді
     * трохи неточна, але для звичайних платформ це саме так.
     */
    static final double DEFAULT_BLOCK_FRICTION = 0.6;

    /**
     * Горизонтальний множник тертя на тіку відриву (наземний). Він же зв'язує швидкість і крок за тік.
     */
    static final double GROUND_FRICTION = DEFAULT_BLOCK_FRICTION * AIR_FRICTION;
    /** Σ множників за тіки 1..N — повна горизонтальна дальність польоту до точки приземлення. */
    static final double FLIGHT_FACTOR_TO_LANDING;

    /**
     * Σ горизонтальних множників за тіки 1..N-1 — тобто позиція (в одиницях стартової швидкості),
     * на якій ванільна Y-колізія вирішує, чи приземлиться моб на блок.
     */
    static final double FLIGHT_FACTOR_BEFORE_TOUCHDOWN;
    /**
     * Відстань від центру блока до його межі по осі (для стрибка вздовж осі це і є "передній край").
     */
    static final double EDGE_FRONT = 0.5;

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
     * Запас, щоб не чекати до самого моменту, коли моб уже втратив опору.
     */
    static final double GROUND_LOSS_MARGIN = 0.05;

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
    // КОЛИ ВІДРИВАТИСЬ (геометрія краю) — для БУДЬ-ЯКОГО напрямку стрибка
    // =====================================================================================
    // Позиція моба задається відносно ЦЕНТРУ блока-краю: (relX, relZ). Напрямок стрибка — одиничний
    // вектор (dirX, dirZ) від центру краю до центру приземлення. Стоїть моб на блоці-краю, доки його
    // хітбокс (півширина hw) перекриває клітинку [-0.5, 0.5] по ОБОХ осях.
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

    /** Зворотне до {@link #launchSpeedForDistance}: куди долетить моб зі швидкістю {@code launchSpeed}. */
    static double flightDistance(double launchSpeed) {
        return launchSpeed * FLIGHT_FACTOR_TO_LANDING;
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
     * Наземне прискорення моба за тік при бігу до краю. У ванілі мобу {@code MoveControl} ставить
     * {@code speed = множник * атрибут}, а {@code Mob.setSpeed} кладе те саме число ще й у вхід
     * {@code zza}, тож прискорення = speed * (speed * 0.98) — квадратичне за швидкістю. Це лише ОЦІНКА
     * для передбачення кроку (на сам політ вона не впливає — швидкість відриву ми задаємо явно).
     */
    static double estimateGroundAccel(double runSpeedSetpoint) {
        return runSpeedSetpoint * runSpeedSetpoint * 0.98;
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
}