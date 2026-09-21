package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import java.util.*;

/**
 * Таблиця напрямків стрибка (променів) — БЕЗ Minecraft-типів, рахується один раз і кешується.
 * <p>
 * РАНІШЕ {@link GapJumpNodeEvaluator} шукав стрибки лише вздовж 4 осей (вліво/вправо/вгору/вниз).
 * Тепер напрямок — будь-який: кожна цілочисельна пара (dx, dz) — потенційне приземлення відносно
 * блока-краю. Пари групуються в ПРОМЕНІ за "примітивним" кроком (p, q), gcd(|p|,|q|)=1: наприклад
 * (2,2), (3,3) — це той самий промінь (1,1), а (2,1) і (4,2) — промінь (2,1). Вздовж променя перше
 * придатне приземлення — звичайний стрибок; далі, якщо платформа закінчується проваллям, а за ним є
 * наступна в межах дальності, додається ще й ПЕРЕСТРИБУВАННЯ (див. {@link GapJumpNodeEvaluator}).
 * <p>
 * <b>Межа дальності однакова у всіх напрямках.</b> Складність стрибка визначає не відстань між
 * ЦЕНТРАМИ блоків, а відстань польоту від точки відриву (передня межа блока-краю ВЗДОВЖ стрибка)
 * до центру приземлення. Вздовж діагоналі передня межа — це кут блока (0.707 від центру, а не 0.5),
 * тож діагональний стрибок (3,3) [центри за 4.24] за складністю рівний осьовому через 3 блоки
 * (політ 3.54 проти 3.50). Формула: {@code flight = d * (1 - 0.5 / max(|dx|,|dz|))}, де d — відстань
 * між центрами. Дозволяємо {@code flight <= maxGap + 0.5 + FLIGHT_TOLERANCE}: для осей це рівно
 * старі {@code step <= maxGap + 1}.
 * <p>
 * Для кожного променя й кожного k заздалегідь порахований "коридор польоту" — клітинки, які
 * зачепить хітбокс моба (товщина {@link #CORRIDOR_HALF_WIDTH} * 2) на прямій від центру краю до центру
 * приземлення. Раніше коридор не перевірявся зовсім (стрибок "крізь" стовп чи стіну вважався
 * прохідним); з 8+ напрямками це стало б помітно частіше.
 */
final class GapJumpRays {

    private GapJumpRays() {
    }

    /**
     * Допуск до межі дальності (блоки польоту). Дає рівно ті самі осьові стрибки, що й раніше
     * (step <= maxGap + 1), і пропускає діагональ (3,3) та (4,1) при maxGap = 3.
     */
    static final double FLIGHT_TOLERANCE = 0.15;

    /**
     * Мінімальна відстань між центрами, з якої це вже "стрибок", а не звичайний крок. МАЄ збігатись з
     * {@code GapJumpUtils.JUMP_SEGMENT_THRESHOLD}, за яким Goal розпізнає стрибковий сегмент у Path.
     * Клітинки ближче (1,0), (1,1) — це ванільні кроки, ними займається WalkNodeEvaluator.
     */
    static final double MIN_JUMP_DISTANCE = 1.5;

    /**
     * Півширина хітбокса, з якою рахується коридор (зомбі = 0.3).
     */
    private static final double CORRIDOR_HALF_WIDTH = 0.3;

    private static final double CORRIDOR_SAMPLE_STEP = 0.2;

    private static final int MAX_CACHED_GAP = 12;

    private static final Ray[][] CACHE = new Ray[MAX_CACHED_GAP + 1][];

    /** Промені для моба з цим "теоретичним максимумом розриву" ({@code estimateMaxJumpRangeBlocks}). */
    static synchronized Ray[] forMaxGap(int maxGap) {
        int gap = Math.max(1, Math.min(maxGap, MAX_CACHED_GAP));
        if (CACHE[gap] == null) {
            CACHE[gap] = build(gap);
        }
        return CACHE[gap];
    }

    /** Складність стрибка: відстань польоту від передньої межі блока-краю до центру приземлення. */
    static double flightDistance(int dx, int dz) {
        int m = Math.max(Math.abs(dx), Math.abs(dz));
        if (m == 0) {
            return 0.0;
        }
        double d = Math.sqrt((double) dx * dx + (double) dz * dz);
        return d * (1.0 - 0.5 / m);
    }

    /**
     * Один промінь: примітивний крок, перша клітинка на шляху (вона ж перевіряється на "чи є тут
     * край"), діапазон k придатних приземлень і коридори польоту.
     *
     * @param frontX          Перша клітинка, у яку заходить центральна лінія, відносно блока-краю.
     * @param corridor        corridor[k] — клітинки {dx, dz} коридору для приземлення k*(stepX, stepZ); індекси kMin..kMax.
     * @param nearestDistance Відстань між центрами для найближчого придатного приземлення (для сортування).
     */
        record Ray(int stepX, int stepZ, int frontX, int frontZ, int kMin, int kMax, int[][][] corridor,
                   double nearestDistance) {
    }

    static double maxFlight(int maxGap) {
        return maxGap + 0.5 + FLIGHT_TOLERANCE;
    }

    private static Ray[] build(int maxGap) {
        double limit = maxFlight(maxGap);
        int bound = maxGap + 2;
        List<Ray> rays = new ArrayList<>();

        for (int p = -bound; p <= bound; p++) {
            for (int q = -bound; q <= bound; q++) {
                if ((p == 0 && q == 0) || gcd(Math.abs(p), Math.abs(q)) != 1) {
                    continue;
                }
                int kMax = 0;
                for (int k = 1; k <= bound + 1; k++) {
                    if (flightDistance(k * p, k * q) > limit) {
                        break;
                    }
                    kMax = k;
                }
                if (kMax == 0) {
                    continue;
                }
                int kMin = -1;
                for (int k = 1; k <= kMax; k++) {
                    double d = Math.sqrt((double) (k * p) * (k * p) + (double) (k * q) * (k * q));
                    if (d > MIN_JUMP_DISTANCE) {
                        kMin = k;
                        break;
                    }
                }
                if (kMin < 0) {
                    continue;
                }

                int[][][] corridor = new int[kMax + 1][][];
                for (int k = 1; k <= kMax; k++) {
                    corridor[k] = corridorCells(k * p, k * q);
                }
                int frontX;
                int frontZ;
                int ap = Math.abs(p);
                int aq = Math.abs(q);
                if (ap > aq) {
                    frontX = Integer.signum(p);
                    frontZ = 0;
                } else if (aq > ap) {
                    frontX = 0;
                    frontZ = Integer.signum(q);
                } else {
                    frontX = Integer.signum(p);   // строга діагональ: лінія йде через кут блока
                    frontZ = Integer.signum(q);
                }
                double nearest = Math.sqrt((double) (kMin * p) * (kMin * p) + (double) (kMin * q) * (kMin * q));
                rays.add(new Ray(p, q, frontX, frontZ, kMin, kMax, corridor, nearest));
            }
        }
        rays.sort(Comparator.comparingDouble((Ray r) -> r.nearestDistance)
                .thenComparingInt(r -> r.stepX)
                .thenComparingInt(r -> r.stepZ));
        return rays.toArray(new Ray[0]);
    }

    /**
     * Клітинки (відносно блока-краю), які зачіпає хітбокс моба на прямій від центру краю до центру
     * приземлення (dx, dz), КРІМ самого краю та самого приземлення.
     */
    private static int[][] corridorCells(int dx, int dz) {
        double x0 = 0.5;
        double z0 = 0.5;
        double x1 = 0.5 + dx;
        double z1 = 0.5 + dz;
        double length = Math.sqrt((double) dx * dx + (double) dz * dz);
        int samples = Math.max(2, (int) Math.ceil(length / CORRIDOR_SAMPLE_STEP));

        Set<Long> cells = new HashSet<>();
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double sx = x0 + (x1 - x0) * t;
            double sz = z0 + (z1 - z0) * t;
            int minX = (int) Math.floor(sx - CORRIDOR_HALF_WIDTH + 1.0E-9);
            int maxX = (int) Math.floor(sx + CORRIDOR_HALF_WIDTH - 1.0E-9);
            int minZ = (int) Math.floor(sz - CORRIDOR_HALF_WIDTH + 1.0E-9);
            int maxZ = (int) Math.floor(sz + CORRIDOR_HALF_WIDTH - 1.0E-9);
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    cells.add(pack(cx, cz));
                }
            }
        }
        cells.remove(pack(0, 0));
        cells.remove(pack(dx, dz));

        List<int[]> list = new ArrayList<>();
        for (long key : cells) {
            list.add(new int[]{unpackX(key), unpackZ(key)});
        }
        // ближчі до краю - першими (раніше знайдемо перешкоду)
        list.sort(Comparator.comparingInt((int[] c) -> c[0] * c[0] + c[1] * c[1])
                .thenComparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
        return list.toArray(new int[0][]);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}