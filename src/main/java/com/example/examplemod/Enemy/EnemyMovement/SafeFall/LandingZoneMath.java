package com.example.examplemod.Enemy.EnemyMovement.SafeFall;

/**
 * v27: чиста математика ЗОНИ ПРИЗЕМЛЕННЯ (без Minecraft-типів, як {@link FallMath}/{@link DropMath}): які клітинки беруться в зону і як
 * оцінюється, яка з них краща ціль. Саму зону (читання світу) будує {@link LandingZone}.
 * <p>
 * Усі відстані - у блоках: вздовж осі спуску (від центру краю до центру клітинки спуску) і вбік від неї.
 */
public final class LandingZoneMath {

    /**
     * Допуск убік від осі біля клітинки спуску: клітинка спуску + «хитання» хітбокса.
     */
    public static final double BASE_LATERAL = 0.75;

    /**
     * На скільки росте допуск убік на кожен блок віддалення від клітинки спуску (конус ~ +-22 градуси). Повітряне керування (сила гравця)
     * довертає моба вбік, але не на півкарти: різкий поворот шляху в зону не потрапляє.
     */
    public static final double LATERAL_SLOPE = 0.4;

    /**
     * Штраф за відхилення клітинки від променя ходу шляху (блоків оцінки на блок відхилення): ціль тримається біля шляху.
     */
    public static final double DEVIATION_PENALTY = 0.35;

    /**
     * Надбавка клітинці, яка сама є вузлом шляху (а не просто сусідкою вузла): шлях явно веде саме туди.
     */
    public static final double PATH_BONUS = 0.15;

    private LandingZoneMath() {
    }

    /**
     * Проєкція вектора {@code (relX, relZ)} на одиничну вісь {@code (dirX, dirZ)}.
     */
    public static double along(double relX, double relZ, double dirX, double dirZ) {
        return relX * dirX + relZ * dirZ;
    }

    /**
     * Відстань від осі {@code (dirX, dirZ)} (одиничної) до точки {@code (relX, relZ)}.
     */
    public static double lateral(double relX, double relZ, double dirX, double dirZ) {
        return Math.abs(relX * dirZ - relZ * dirX);
    }

    /**
     * Допустимий бічний зсув клітинки на відстані {@code along} вздовж осі (клітинка спуску - на {@code axisLength}).
     */
    public static double lateralLimit(double along, double axisLength) {
        return BASE_LATERAL + LATERAL_SLOPE * Math.max(0.0, along - axisLength);
    }

    /**
     * Чи лежить клітинка в конусі зони: не ближче клітинки спуску (ближче - під стіною уступу), не далі {@code maxReach} від центру краю і
     * не далі від осі, ніж дозволяє {@link #lateralLimit}.
     *
     * @param along      відстань вздовж осі від центру краю
     * @param lateral    відстань убік від осі
     * @param axisLength відстань вздовж осі до клітинки спуску
     * @param maxReach   межа зони вздовж осі ({@code Config.LANDING_ZONE_MAX_REACH})
     */
    public static boolean inCone(double along, double lateral, double axisLength, double maxReach) {
        return along >= axisLength - 0.01 && along <= maxReach && lateral <= lateralLimit(along, axisLength);
    }

    /**
     * Наскільки клітинка добра як ціль приземлення: просування від клітинки спуску вздовж ходу шляху (heading, одиничний) мінус штраф
     * за відхилення від променя ходу, плюс надбавка, якщо клітинка - вузол шляху. Клітинка спуску має 0 (+ надбавку).
     *
     * @param relX,relZ вектор від центру клітинки спуску до центру клітинки
     */
    public static double score(double relX, double relZ, double headX, double headZ, boolean onPath) {
        double progress = relX * headX + relZ * headZ;
        double deviation = Math.abs(relX * headZ - relZ * headX);
        return progress - DEVIATION_PENALTY * deviation + (onPath ? PATH_BONUS : 0.0);
    }
}
