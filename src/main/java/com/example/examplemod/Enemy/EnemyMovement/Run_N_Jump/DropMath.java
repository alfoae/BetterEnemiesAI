package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

/**
 * v22: чиста математика керування падінням над м'яким приземленням (без типів Minecraft - перевіряється окремо).
 * <p>
 * Моб сходить з краю з горизонтальною інерцією; без корекції (повітряний опір 0.91/тік) вона зносить його на 1-3 блоки від
 * центру клітинки приземлення - повз воду в 1 блок. Тут - "м'яка пружина" до центру: бажана швидкість пропорційна відстані,
 * а зміна швидкості за тік обмежена, тож це не телепорт, а повітряне керування на рівні гравця.
 */
final class DropMath {

    /**
     * Найбільша горизонтальна швидкість (блоків/тік), з якою моб тягнеться до центру приземлення.
     */
    static final double MAX_AIR_SPEED = 0.25;
    /** Бажана швидкість = відстань до центру * AIR_PULL (чим ближче - тим повільніше). */
    static final double AIR_PULL = 0.15;
    /** Найбільша зміна горизонтальної швидкості за тік - ТА САМА, що в RNJ ({@code GapJumpPhysics.MAX_AIR_ACCEL}, "як у гравця"). */
    static final double MAX_AIR_ACCEL = GapJumpPhysics.MAX_AIR_ACCEL;

    private DropMath() {
    }

    /**
     * Нова горизонтальна швидкість {@code {vx, vz}} моба в польоті.
     *
     * @param vx поточна швидкість по X;
     * @param vz поточна швидкість по Z;
     * @param dx X-складова вектора від моба до центру приземлення;
     * @param dz Z-складова вектора від моба до центру приземлення
     */
    static double[] steer(double vx, double vz, double dx, double dz) {
        double wantX = dx * AIR_PULL;
        double wantZ = dz * AIR_PULL;
        double wantLen = Math.hypot(wantX, wantZ);
        if (wantLen > MAX_AIR_SPEED) {
            wantX *= MAX_AIR_SPEED / wantLen;
            wantZ *= MAX_AIR_SPEED / wantLen;
        }
        double ax = wantX - vx;
        double az = wantZ - vz;
        double len = Math.hypot(ax, az);
        if (len > MAX_AIR_ACCEL) {
            ax *= MAX_AIR_ACCEL / len;
            az *= MAX_AIR_ACCEL / len;
        }
        return new double[]{vx + ax, vz + az};
    }
}
