package com.example.examplemod.Enemy.EnemyMovement.SafeFall;

/**
 * v23: чиста логіка (без типів Minecraft - перевіряється окремо): ціни м'яких приземлень і вибір вузла для ресинхронізації шляху.
 */
final class SoftMath {

    /**
     * Надбавка до ціни "запасних" м'яких приземлень (мед, слайм, павутиння) - щоб їх брали лише коли кращого нема.
     */
    static final float RESERVE_RANK_PENALTY = 10.0F;

    /** Ресинхронізація шляху: моб ближче за цю відстань (квадрат, 2 блоки) до "наступного" вузла - ванільна логіка справляється. */
    static final double RESYNC_MIN_DIST_SQ = 4.0;
    /** Вузол попереду вважається "тим, біля якого моб стоїть", якщо ближче за 1 блок (квадрат)... */
    static final double RESYNC_NEAR_DIST_SQ = 1.0;
    /** ...і не вище/нижче за цю різницю висот. */
    static final double RESYNC_MAX_DY = 1.2;
    /** Моб поза шляхом, якщо він далі за 3 блоки (квадрат 9) і від попереднього, і від наступного вузла. */
    static final double OFF_PATH_DIST_SQ = 9.0;

    static final int RESYNC_NONE = -1;
    static final int RESYNC_REFRESH = -2;

    private SoftMath() {
    }

    /** Надбавка за ранг: 0 - вода, сіно, драбина, ліжко; запасні - {@link #RESERVE_RANK_PENALTY}. */
    static float rankPenalty(int rank) {
        return rank <= 0 ? 0.0F : RESERVE_RANK_PENALTY;
    }

    /**
     * Штраф за вузьке приземлення: чим більше сусідніх м'яких клітинок (margin 0..4), тим більший допуск на промах - центр басейну
     * 3x3 (margin 4) краще за одиночний блок (margin 0). Лише додатний: негативний malus у вузлі означає "прохід заборонено".
     */
    static float tolerancePenalty(int margin) {
        return switch (Math.max(0, Math.min(4, margin))) {
            case 0 -> 1.5F;
            case 1 -> 0.9F;
            case 2 -> 0.45F;
            case 3 -> 0.15F;
            default -> 0.0F;
        };
    }

    /**
     * Що робити зі шляхом, коли моб на землі й "наступний" вузол далеко.
     *
     * @param dNextSq     відстань (квадрат) до поточного "наступного" вузла;
     * @param dPrevSq     відстань (квадрат) до попереднього вузла (якщо його нема - те саме, що {@code dNextSq});
     * @param aheadDistSq відстані (квадрат) до вузлів next+1, next+2, ... по порядку;
     * @param aheadAbsDy  |Δy| до тих самих вузлів
     * @return зсув (&gt;= 1) від next до вузла, біля якого моб фізично стоїть (переставити "наступним" його) - перший такий;
     *         {@link #RESYNC_REFRESH} - поблизу жодного вузла, моб поза шляхом (треба свіжий шлях);
     *         {@link #RESYNC_NONE} - нічого робити
     */
    static int pickResync(double dNextSq, double dPrevSq, double[] aheadDistSq, double[] aheadAbsDy) {
        if (dNextSq <= RESYNC_MIN_DIST_SQ) {
            return RESYNC_NONE;
        }
        for (int i = 0; i < aheadDistSq.length; i++) {
            if (aheadAbsDy[i] < RESYNC_MAX_DY && aheadDistSq[i] <= RESYNC_NEAR_DIST_SQ) {
                return i + 1;
            }
        }
        if (Math.min(dNextSq, dPrevSq) > OFF_PATH_DIST_SQ) {
            return RESYNC_REFRESH;
        }
        return RESYNC_NONE;
    }
}
