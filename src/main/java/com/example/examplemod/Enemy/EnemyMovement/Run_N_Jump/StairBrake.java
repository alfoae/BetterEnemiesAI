package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

/**
 * v24: чиста логіка гальмування мобів на СХОДАХ (без типів Minecraft - перевіряється окремо).
 * <p>
 * Навіщо. Заагрений моб біжить зі спринтом: зомбі в цьому моді робить ~0.78 бл/тік (атрибут 0.33 * 1.3 * множник бігу 1.4).
 * Сходинка 1x1 завглибшки в 1 блок: падіння на блок триває ~4.4 тіки, за які на такій швидкості моб пролітає 2.5-3 блоки, а
 * будь-який бічний поштовх (розворот, штовханина) за цей політ виносить його за межі сходів завширшки 1 (у дебаг-логу: 7 з 7
 * запусків зі сходів падали на 10-14 блоків). Тож поки попереду щонайменше два спуски по 1 блоку підряд, усталений зсув моба
 * за тік обмежується {@link #DEFAULT_MAX_STEP}: він іде сходинка за сходинкою, а не летить над ними.
 */
public final class StairBrake {

    /**
     * Найбільший зсув за тік на сходах за замовчуванням (бл/тік; 0.20 = 4 бл/с, повільніше за гравця в спринті 5.6).
     */
    static final double DEFAULT_MAX_STEP = 0.20;
    /** З якої відстані (по горизонталі) до першого спуску сходів починаємо гальмувати: моб скидає 0.78 -> 0.22 бл/тік за ~0.7 блока. */
    public static final double LOOKAHEAD_DIST = 2.5;
    /** Спуск "сходинкою": від 0.5 до 1.5 блока вниз (глибше - це падіння, його веде RNJ/DropAssist). */
    static final double MIN_STEP_DROP = 0.5;
    static final double MAX_STEP_DROP = 1.5;
    /** Вхід моба в {@code travel()}: zza множиться на 0.98 (ванільний aiStep). */
    private static final double INPUT_SCALE = 0.98;

    private StairBrake() {
    }

    /** {@code true}, якщо різниця висот {@code from - to} - сходинка вниз (не підйом, не падіння). */
    static boolean isStepDown(double from, double to) {
        double drop = from - to;
        return drop >= MIN_STEP_DROP && drop <= MAX_STEP_DROP;
    }

    /**
     * Чи є у висотах {@code ys} (по порядку: ноги моба, потім вузли шляху попереду) ДВА спуски-сходинки ПІДРЯД - тобто сходи,
     * а не одинокий уступ (з уступу швидкий моб просто зістрибує й летить далі - це не катастрофа).
     *
     * @return індекс {@code i}, з якого починаються два спуски підряд ({@code ys[i] -> ys[i+1] -> ys[i+2]}), або -1
     */
    public static int staircaseStart(double[] ys) {
        for (int i = 0; i + 2 < ys.length; i++) {
            if (isStepDown(ys[i], ys[i + 1]) && isStepDown(ys[i + 1], ys[i + 2])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Множник швидкості для {@code MoveControl}, щоб зсув моба за НАСТУПНИЙ тік не перевищив {@code maxStep}.
     * <p>
     * Зсув за тік = швидкість з початку тіку {@code v} (вже після тертя) + прискорення {@code a}, де {@code a = s * s * 0.98},
     * {@code s = множник * атрибут} (див. {@link GapJumpPhysics#estimateGroundAccel}). Тож треба {@code a <= maxStep - v}: якщо
     * моб уже швидший - гасимо тягу зовсім (0, лишається тертя 0.546 за тік), інакше беремо рівно потрібне прискорення.
     * Ніколи не більше {@code navModifier}: гальмо не може розігнати повільного моба.
     *
     * @param attribute   поточне значення атрибуту швидкості моба (зі спринтом, якщо він увімкнений)
     * @param speedNow    горизонтальна швидкість моба зараз (deltaMovement.horizontalDistance)
     * @param maxStep     найбільший дозволений зсув за тік
     * @param navModifier множник, який виставив навігатор (те, що було б без гальма)
     */
    public static double speedModifier(double attribute, double speedNow, double maxStep, double navModifier) {
        if (!(attribute > 1.0E-6) || !(navModifier > 0.0)) {
            return navModifier;
        }
        double room = maxStep - Math.max(0.0, speedNow);
        if (room <= 0.0) {
            return 0.0;
        }
        double s = Math.sqrt(room / INPUT_SCALE);
        return Math.min(navModifier, s / attribute);
    }
}
