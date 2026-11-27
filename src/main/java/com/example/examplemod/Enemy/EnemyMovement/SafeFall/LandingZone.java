package com.example.examplemod.Enemy.EnemyMovement.SafeFall;

import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v27: ЗОНА ПРИЗЕМЛЕННЯ спуску збігом - клітинка спуску ({@code DropNode}) і ті клітинки навколо шляху за нею, на які моб може приземлитись,
 * не втрачаючи хід. Раніше ціль RNJ була лише центром клітинки спуску (0.5-1 бл від краю): розбіг підбирав найменшу швидкість, що туди
 * влучає (моб майже ставав на краю), а повітряне керування все падіння гасило горизонтальний рух, щоб зависнути над нею. Тепер розбіг
 * цілиться в найкращу клітинку зони ({@link #planAlong}), а в польоті щотіку обирається та з них, яка найкраще просуває вздовж ходу
 * шляху і до якої ще вистачає сили гравця ({@code GapJumpPhysics#pickZoneTarget}).
 * <p>
 * Зона - «коридор»: клітинка спуску, вузли шляху після неї ({@code GapJumpUtils#collectFollow}) і їхні 8 сусідок, у конусі від осі
 * спуску ({@link LandingZoneMath#inCone}). Шлях після спуску рідко йде строго по осі (A* бере діагоналі), тому коридор, а не пряма.
 * Кожна клітинка перевіряється окремо і має бути ТОЮ САМОЮ підлогою, що під клітинкою спуску (висота падіння, а отже й шкода, та сама):
 * <ul>
 *   <li>під нею тверда безпечна підлога, а в ній і над нею - повітря від ніг до верху моба на рівні краю (крізь цю колону моб летить
 *       униз), без рідин, павутиння, вогню тощо ({@link LedgePlanner.World#floor}/{@link LedgePlanner.World#free});</li>
 *   <li>вузли шляху беруться підряд, доки проходять перевірку (на першому, що ні, ланцюжок обривається); діагональний крок між ними не
 *       зачіпає кута перепони (обидві ортогональні клітинки вільні).</li>
 * </ul>
 * Лише клітинка спуску - зони нема ({@code null}): тоді все як у v26.
 */
public final class LandingZone {

    private final double[] xs;
    private final double[] zs;
    private final double[] scores;
    private final double[] along;
    private final int best;
    private final double headingX;
    private final double headingZ;

    private LandingZone(double[] xs, double[] zs, double[] scores, double[] along, int best, double headingX, double headingZ) {
        this.xs = xs;
        this.zs = zs;
        this.scores = scores;
        this.along = along;
        this.best = best;
        this.headingX = headingX;
        this.headingZ = headingZ;
    }

    /**
     * Будує зону для спуску {@code jump} або повертає {@code null} (зони нема: вузлів після клітинки спуску немає, моб широкий, жодна
     * клітинка за клітинкою спуску не підійшла).
     *
     * @param maxReach найбільша відстань вздовж осі від центру краю ({@code Config.LANDING_ZONE_MAX_REACH})
     */
    public static LandingZone build(Mob mob, GapJumpUtils.GapJump jump, double maxReach) {
        int[] follow = jump.follow(); // пари {x, z} вузлів шляху після клітинки спуску (висота - та сама, що в клітинки спуску)
        if (follow == null || follow.length < 2 || mob.getBbWidth() > LedgeDescent.MAX_MOB_WIDTH) {
            return null;
        }
        Level level = mob.level();
        LedgePlanner.World world = LedgeDescent.worldOf(level::getBlockState);
        BlockPos edge = jump.edge();
        Vec3 landing = jump.landing();
        Vec3 edgeCenter = Vec3.atBottomCenterOf(edge);
        double axisX = landing.x - edgeCenter.x;
        double axisZ = landing.z - edgeCenter.z;
        double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);
        if (axisLength < 1.0E-6) {
            return null;
        }
        double dirX = axisX / axisLength;
        double dirZ = axisZ / axisLength;
        int footY = (int) Math.floor(landing.y + 1.0E-6);
        int edgeY = edge.getY();
        int height = Math.max(1, (int) Math.ceil(mob.getBbHeight() - 0.05));
        int landingX = (int) Math.floor(landing.x);
        int landingZ = (int) Math.floor(landing.z);

        // 1. Вузли шляху після клітинки спуску - підряд, доки це клітинка в конусі, що проходить перевірку (ланцюжок).
        List<int[]> chain = new ArrayList<>();
        int previousX = landingX;
        int previousZ = landingZ;
        for (int i = 0; i + 1 < follow.length; i += 2) {
            int cellX = follow[i];
            int cellZ = follow[i + 1];
            double relX = cellX + 0.5 - edgeCenter.x;
            double relZ = cellZ + 0.5 - edgeCenter.z;
            if (!LandingZoneMath.inCone(LandingZoneMath.along(relX, relZ, dirX, dirZ), LandingZoneMath.lateral(relX, relZ, dirX, dirZ),
                    axisLength, maxReach)) {
                break;
            }
            if (!standable(world, cellX, cellZ, footY, edgeY, height)) {
                break;
            }
            if (!cornerClear(world, previousX, previousZ, cellX, cellZ, footY, height)) {
                break;
            }
            chain.add(new int[]{cellX, cellZ});
            previousX = cellX;
            previousZ = cellZ;
        }

        // 2. Напрямок ходу шляху: від клітинки спуску до найдальшого вузла ланцюжка (без нього - вісь спуску).
        double headX = dirX;
        double headZ = dirZ;
        if (!chain.isEmpty()) {
            int[] far = chain.get(chain.size() - 1);
            double hx = far[0] - landingX;
            double hz = far[1] - landingZ;
            double length = Math.sqrt(hx * hx + hz * hz);
            if (length > 1.0E-6) {
                headX = hx / length;
                headZ = hz / length;
            }
        }

        // 3. Кандидати: клітинка спуску, вузли ланцюжка і їхні сусідки в конусі. Клітинка спуску й вузли ланцюжка вже перевірені
        //    (першій довіряє A*), сусідки перевіряються окремо.
        Candidates candidates = new Candidates(world, landing, edgeCenter, dirX, dirZ, headX, headZ, axisLength, maxReach, footY, edgeY, height);
        candidates.add(landingX, landingZ, true, false);
        for (int[] cell : chain) {
            candidates.add(cell[0], cell[1], true, false);
        }
        for (int[] cell : chain) {
            candidates.addNeighbours(cell[0], cell[1]);
        }
        candidates.addNeighbours(landingX, landingZ);
        int count = candidates.cells.size();
        if (count < 2) {
            return null;
        }
        double[] xs = new double[count];
        double[] zs = new double[count];
        double[] scores = new double[count];
        double[] along = new double[count];
        int best = 0;
        for (int k = 0; k < count; k++) {
            double[] c = candidates.cells.get(k);
            xs[k] = c[0];
            zs[k] = c[1];
            scores[k] = c[2];
            along[k] = c[3];
            if (scores[k] > scores[best]) {
                best = k;
            }
        }
        if (best == 0) {
            return null; // кращої за клітинку спуску цілі нема - зона нічого не дає
        }
        return new LandingZone(xs, zs, scores, along, best, headX, headZ);
    }

    /**
     * Крок із клітинки {@code (fromX, fromZ)} у сусідню {@code (toX, toZ)} не зачіпає кута: для діагонального кроку обидві ортогональні
     * клітинки (між ними) вільні на всю висоту тіла; для прямого кроку кута нема. Інакше політ над зламом шляху чіпляв би стовп чи
     * виступ на розі.
     */
    private static boolean cornerClear(LedgePlanner.World world, int fromX, int fromZ, int toX, int toZ, int footY, int height) {
        if (fromX == toX || fromZ == toZ) {
            return true;
        }
        for (int h = 0; h < height; h++) {
            if (!world.free(toX, footY + h, fromZ) || !world.free(fromX, footY + h, toZ)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Клітинка колони {@code (x, z)} годиться для приземлення: під ногами ({@code footY - 1}) безпечна підлога, а від ніг до верху
     * моба на рівні краю ({@code edgeY + height - 1}) - вільно: крізь цю колону моб летить униз, і зависла в ній перепона збила б політ.
     */
    private static boolean standable(LedgePlanner.World world, int x, int z, int footY, int edgeY, int height) {
        if (!world.floor(x, footY - 1, z)) {
            return false;
        }
        int top = Math.max(footY + height - 1, edgeY + height - 1);
        for (int y = footY; y <= top; y++) {
            if (!world.free(x, y, z)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Скільки клітинок у зоні (перша, з індексом 0, - клітинка спуску). Завжди не менше 2.
     */
    public int size() {
        return this.xs.length;
    }

    /**
     * Центри клітинок зони (світові X). Масив не змінювати.
     */
    public double[] xs() {
        return this.xs;
    }

    /**
     * Центри клітинок зони (світові Z). Масив не змінювати.
     */
    public double[] zs() {
        return this.zs;
    }

    /**
     * Оцінка кожної клітинки як цілі ({@link LandingZoneMath#score}). Масив не змінювати.
     */
    public double[] scores() {
        return this.scores;
    }

    /**
     * Відстань вздовж осі спуску від центру краю до найкращої клітинки зони: сюди цілиться розбіг.
     */
    public double planAlong() {
        return this.along[this.best];
    }

    /**
     * Одиничний напрямок ходу шляху за клітинкою спуску (вісь спуску, якщо вузлів шляху в зоні нема): так біжить моб після приземлення.
     */
    public double headingX() {
        return this.headingX;
    }

    public double headingZ() {
        return this.headingZ;
    }

    /**
     * Кандидати в зону з перевіркою конуса, повторів і (для сусідок) підлоги й повітря.
     */
    private static final class Candidates {
        final List<double[]> cells = new ArrayList<>(); // {центр X, центр Z, оцінка, вздовж осі}
        private final Set<Long> seen = new HashSet<>();
        private final LedgePlanner.World world;
        private final Vec3 landing;
        private final Vec3 edgeCenter;
        private final double dirX;
        private final double dirZ;
        private final double headX;
        private final double headZ;
        private final double axisLength;
        private final double maxReach;
        private final int footY;
        private final int edgeY;
        private final int height;

        Candidates(LedgePlanner.World world, Vec3 landing, Vec3 edgeCenter, double dirX, double dirZ, double headX, double headZ,
                   double axisLength, double maxReach, int footY, int edgeY, int height) {
            this.world = world;
            this.landing = landing;
            this.edgeCenter = edgeCenter;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.headX = headX;
            this.headZ = headZ;
            this.axisLength = axisLength;
            this.maxReach = maxReach;
            this.footY = footY;
            this.edgeY = edgeY;
            this.height = height;
        }

        /**
         * 8 сусідок клітинки {@code (x, z)}: кожна лише в конусі, з вільним кутом (діагональні) і після перевірки підлоги/повітря.
         */
        void addNeighbours(int x, int z) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && cornerClear(this.world, x, z, x + dx, z + dz, this.footY, this.height)) {
                        add(x + dx, z + dz, false, true);
                    }
                }
            }
        }

        /**
         * @param onPath клітинка - вузол шляху (дає {@link LandingZoneMath#PATH_BONUS}), а не сусідка
         * @param verify перевірити підлогу й повітря (для вузлів шляху це вже зроблено)
         */
        void add(int cellX, int cellZ, boolean onPath, boolean verify) {
            long key = ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
            if (!this.seen.add(key)) {
                return;
            }
            double centerX = cellX + 0.5;
            double centerZ = cellZ + 0.5;
            double relX = centerX - this.edgeCenter.x;
            double relZ = centerZ - this.edgeCenter.z;
            double along = LandingZoneMath.along(relX, relZ, this.dirX, this.dirZ);
            if (!LandingZoneMath.inCone(along, LandingZoneMath.lateral(relX, relZ, this.dirX, this.dirZ), this.axisLength, this.maxReach)) {
                return;
            }
            if (verify && !standable(this.world, cellX, cellZ, this.footY, this.edgeY, this.height)) {
                return;
            }
            double score = LandingZoneMath.score(centerX - this.landing.x, centerZ - this.landing.z, this.headX, this.headZ, onPath);
            this.cells.add(new double[]{centerX, centerZ, score, along});
        }
    }
}
