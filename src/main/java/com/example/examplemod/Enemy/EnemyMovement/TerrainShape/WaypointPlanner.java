package com.example.examplemod.Enemy.EnemyMovement.TerrainShape;

import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeWalk.Spot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Чиста (без Minecraft) динамічна програма, що з готового ланцюжка вузлів і кандидатних точок
 * стояння в кожному вибирає ТОЧНІ точки, якими моб піде. Вузол може дати одну точку або дві (зайти
 * в клітинку в одному місці, а вийти з неї в іншому — наприклад, спершу притиснутись до краю ще на
 * суцільній підлозі, а вже потім іти вздовж ребра відкритого люка).
 * <p>
 * Ціна: кожна точка маршруту — 100, плюс невелика надбавка за точку не в центрі й за слабке
 * покриття. Тому, поки можна йти центрами — йдемо центрами (поведінка як у ванілі).
 */
public final class WaypointPlanner {

    private static final double WAYPOINT_COST = 100.0;

    private WaypointPlanner() {
    }

    private static double penalty(Spot s) {
        return (s.center() ? 0.0 : 1.0) + (1.0 - Math.min(1.0, s.coverage()));
    }

    /**
     * @param cands    кандидати кожного вузла (непорожні); для вузла 0 кандидат №0 — ФІКСОВАНА точка входу
     *                 (де моб стоїть зараз), решта — де з цієї клітинки можна вийти
     * @param walkEdge {@code walkEdge[i]} — перехід i→i+1 пішки (потрібна перевірка);
     *                 false — стрибок/пропуск, перевірка не потрібна
     * @return точки маршруту по порядку або {@code null}, якщо ланцюжок неможливий
     */
    public static List<Waypoint> plan(Spot[][] cands, boolean[] walkEdge, Mover mover) {
        int n = cands.length;
        if (n == 0) {
            return new ArrayList<>();
        }
        double[][] cost = new double[n][];
        int[][] predDep = new int[n][];
        int[][] predArr = new int[n][];
        for (int i = 0; i < n; i++) {
            cost[i] = new double[cands[i].length];
            predDep[i] = new int[cands[i].length];
            predArr[i] = new int[cands[i].length];
            java.util.Arrays.fill(cost[i], Double.POSITIVE_INFINITY);
            java.util.Arrays.fill(predDep[i], -1);
            java.util.Arrays.fill(predArr[i], -1);
        }
        // Вузол 0: вхід фіксований (кандидат 0), вихід — будь-який досяжний.
        Spot[] c0 = cands[0];
        for (int j = 0; j < c0.length; j++) {
            if (j == 0) {
                cost[0][0] = WAYPOINT_COST;
                predArr[0][0] = 0;
            } else if (mover.canMove(c0[0], c0[j])) {
                cost[0][j] = 2 * WAYPOINT_COST + penalty(c0[j]);
                predArr[0][j] = 0;
            }
        }
        for (int i = 1; i < n; i++) {
            Spot[] prev = cands[i - 1];
            Spot[] cur = cands[i];
            for (int p = 0; p < prev.length; p++) {
                if (Double.isInfinite(cost[i - 1][p])) {
                    continue;
                }
                for (int k = 0; k < cur.length; k++) {
                    if (walkEdge[i - 1] && !mover.canMove(prev[p], cur[k])) {
                        continue;
                    }
                    for (int j = 0; j < cur.length; j++) {
                        double c;
                        if (j == k) {
                            c = cost[i - 1][p] + WAYPOINT_COST + penalty(cur[k]);
                        } else if (mover.canMove(cur[k], cur[j])) {
                            c = cost[i - 1][p] + 2 * WAYPOINT_COST + penalty(cur[k]) + penalty(cur[j]);
                        } else {
                            continue;
                        }
                        if (c < cost[i][j]) {
                            cost[i][j] = c;
                            predDep[i][j] = p;
                            predArr[i][j] = k;
                        }
                    }
                }
            }
        }
        int best = -1;
        double bestCost = Double.POSITIVE_INFINITY;
        for (int j = 0; j < cands[n - 1].length; j++) {
            if (cost[n - 1][j] < bestCost) {
                bestCost = cost[n - 1][j];
                best = j;
            }
        }
        if (best < 0) {
            return null;
        }
        List<Waypoint> rev = new ArrayList<>();
        int dep = best;
        for (int i = n - 1; i >= 0; i--) {
            int arr = predArr[i][dep];
            rev.add(new Waypoint(i, cands[i][dep]));
            if (arr != dep) {
                rev.add(new Waypoint(i, cands[i][arr]));
            }
            dep = predDep[i][dep];
        }
        Collections.reverse(rev);
        return rev;
    }

    /**
     * Чи може моб пройти з {@code a} в {@code b}.
     */
    public interface Mover {
        boolean canMove(Spot a, Spot b);
    }

    /**
     * Одна точка маршруту, що належить вузлу з індексом {@code nodeIndex}.
     */
    public record Waypoint(int nodeIndex, Spot spot) {
    }
}
