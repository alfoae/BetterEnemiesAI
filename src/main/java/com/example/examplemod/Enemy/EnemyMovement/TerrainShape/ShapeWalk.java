package com.example.examplemod.Enemy.EnemyMovement.TerrainShape;

import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry.Box;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry.Footprint;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry.Support;

import java.util.ArrayList;
import java.util.List;

/**
 * Чиста (без Minecraft-типів) логіка "де моб МОЖЕ стояти в цій клітинці і чи можна пройти звідти
 * туди". Працює над {@link BoxSource} — тому тестується окремо від гри.
 * <p>
 * Система координат вузла — ванільна: вузол {@code (x,y,z)} — це НИЖНІЙ кут області
 * {@code nw × nw} клітинок ({@code nw = floor(width+1)}: 1 для вузьких мобів, 2 для широких…), а
 * центр моба стоїть у {@code (x+nw/2, z+nw/2)}. Висота вузла {@code y} — клітинка ніг: опора мусить
 * лежати на висоті {@code [y, y+1)}.
 */
public final class ShapeWalk {

    /**
     * Мінімальна частка footprint-у на суцільній геометрії, щоб рахувати це опорою.
     */
    public static final double MIN_FLOOR_COVERAGE = 0.05;
    /**
     * "Шкіра": footprint трохи вужчий за хітбокс, щоб дотик до стіни/межі не рахувався перетином.
     */
    public static final double SKIN = 1.0E-3;
    /**
     * Допуск у порівняннях висот.
     */
    private static final double LOW = 1.0E-3;
    /**
     * Крок вибірки вздовж відрізка між двома точками стояння (блоків).
     */
    private static final double SAMPLE_STEP = 0.1;

    private ShapeWalk() {
    }

    private static Footprint footprint(double cx, double cz, BodyDims d) {
        return Footprint.centered(cx, cz, Math.max(0.01, d.width() - 2.0 * SKIN));
    }

    /**
     * Чи може моб ШИРИНИ {@code d.width()} стояти з центром у {@code (cx, cz)} на висоті ніг
     * {@code [y, y+1)}: є опора (покриття ≥ {@link #MIN_FLOOR_COVERAGE}) і над нею вільно на всю
     * висоту тіла. {@code null} — ні.
     */
    public static Spot standAt(BoxSource src, double cx, double cz, int y, BodyDims d) {
        Footprint fp = footprint(cx, cz, d);
        List<Box> boxes = new ArrayList<>(8);
        src.collect(fp.minX(), y - 1.0, fp.minZ(), fp.maxX(), y + 1.0 + d.height(), fp.maxZ(), boxes);
        Support s = ShapeGeometry.findSupport(boxes, fp, y + 1.0 - LOW, MIN_FLOOR_COVERAGE);
        if (!s.isPresent() || s.surfaceY() < y - LOW) {
            return null;
        }
        double top = s.surfaceY();
        double upper = top + d.height();
        for (Box b : boxes) {
            if (b.maxY() > top + ShapeGeometry.EPS && b.minY() < upper - ShapeGeometry.EPS && b.overlapsXZ(fp)) {
                return null;
            }
        }
        return new Spot(cx, cz, top, s.coverage(), false, false);
    }

    /**
     * 9 позицій у межах області вузла: центр, 4 до стінок, 4 у кути.
     */
    private static double[][] offsets(int nw, BodyDims d) {
        double m = Math.max(0.0, (nw - d.width()) / 2.0);
        if (m < 0.01) {
            return new double[][]{{0, 0}};
        }
        return new double[][]{{0, 0}, {0, -m}, {0, m}, {-m, 0}, {m, 0}, {-m, -m}, {m, -m}, {-m, m}, {m, m}};
    }

    /**
     * Придатні точки стояння у вузлі. Якщо центр клітинки стоїть повністю на суцільній опорі
     * (звичайний блок, плита) — повертається ТІЛЬКИ центр (будь-яка інша позиція в клітинці тут не
     * кращa). Інакше — усі придатні з 9 позицій (центр першим). Порожній список — стояти тут не можна.
     */
    public static List<Spot> candidates(BoxSource src, int x, int y, int z, int nw, BodyDims d) {
        return gather(src, x, y, z, nw, d, true);
    }

    /**
     * Усі придатні з 9 позицій (без раннього виходу) — для вирівнювання перед/після складної клітинки.
     */
    public static List<Spot> candidatesAll(BoxSource src, int x, int y, int z, int nw, BodyDims d) {
        return gather(src, x, y, z, nw, d, false);
    }

    private static List<Spot> gather(BoxSource src, int x, int y, int z, int nw, BodyDims d, boolean earlyExit) {
        double cx = x + nw / 2.0;
        double cz = z + nw / 2.0;
        List<Spot> out = new ArrayList<>(4);
        double[][] offs = offsets(nw, d);
        for (int i = 0; i < offs.length; i++) {
            Spot s = standAt(src, cx + offs[i][0], cz + offs[i][1], y, d);
            if (s == null) {
                continue;
            }
            if (i == 0) {
                Spot c = new Spot(s.x(), s.z(), s.surfaceY(), s.coverage(), true, false);
                out.add(c);
                if (earlyExit && s.coverage() >= 0.999) {
                    return out;
                }
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Чи може моб пройти з точки {@code a} у точку {@code b}: хітбокс, протягнутий уздовж відрізка
     * кроком {@link #SAMPLE_STEP}, у КОЖНІЙ точці має мати опору (покриття ≥ мінімуму) і вільне тіло.
     * <ul>
     *   <li>підйом ≤ {@code stepUp} — звичайний крок; ≤ {@code jumpUp} — стрибок; більше — ні;</li>
     *   <li>спуск довше за {@code stepUp} — це падіння з краю: дозволене до {@code maxFall};</li>
     *   <li>опора, що провалилась глибше за ціль (діра посеред шляху) — шлях відхиляється.</li>
     * </ul>
     */
    public static boolean traverse(BoxSource src, Spot a, Spot b, BodyDims d) {
        double rise = b.surfaceY() - a.surfaceY();
        if (rise > d.jumpUp() + LOW || -rise > d.maxFall() + LOW) {
            return false;
        }
        boolean up = rise > d.stepUp() + 1.0E-6;
        boolean down = rise < -d.stepUp() - 1.0E-6;
        boolean jumpLike = up || down;
        double topRef = Math.max(a.surfaceY(), b.surfaceY());
        double lowAllowed = Math.min(a.surfaceY(), b.surfaceY()) - (down ? 0.05 : d.stepUp());

        double half = Math.max(0.01, d.width() - 2.0 * SKIN) / 2.0;
        List<Box> boxes = new ArrayList<>(16);
        src.collect(Math.min(a.x(), b.x()) - half, Math.min(a.surfaceY(), b.surfaceY()) - 1.0,
                Math.min(a.z(), b.z()) - half, Math.max(a.x(), b.x()) + half,
                topRef + d.height() + 1.0, Math.max(a.z(), b.z()) + half, boxes);

        double dist = Math.hypot(b.x() - a.x(), b.z() - a.z());
        int n = Math.max(1, (int) Math.ceil(dist / SAMPLE_STEP));
        double cur = a.surfaceY();
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            Footprint fp = footprint(a.x() + (b.x() - a.x()) * t, a.z() + (b.z() - a.z()) * t, d);
            double ceiling = cur + (up ? d.jumpUp() : d.stepUp()) + 1.0E-6;
            Support s = ShapeGeometry.findSupport(boxes, fp, ceiling, MIN_FLOOR_COVERAGE);
            if (!s.isPresent()) {
                return false;
            }
            double ns = s.surfaceY();
            if (ns < lowAllowed - ShapeGeometry.EPS) {
                return false;
            }
            // Нижні виступи (≤ stepUp над опорою) моб просто переступає; ціль стрибка/падіння — теж не перешкода.
            double limit = Math.max(ns + d.stepUp(), jumpLike ? topRef : Double.NEGATIVE_INFINITY) + ShapeGeometry.EPS;
            double upper = (jumpLike ? Math.max(ns, topRef) : ns) + d.height();
            for (Box bx : boxes) {
                if (bx.maxY() > limit && bx.minY() < upper - ShapeGeometry.EPS && bx.overlapsXZ(fp)) {
                    return false;
                }
            }
            cur = ns;
        }
        return true;
    }

    /**
     * Найкраща точка стояння серед кандидатів вузла: номінальний центр, якщо він придатний (він завжди
     * першим у списку), інакше та, що має найбільше покриття опорою. {@code null} — кандидатів нема.
     */
    public static Spot bestSpot(List<Spot> spots) {
        Spot best = null;
        for (Spot s : spots) {
            if (s.center()) {
                return s;
            }
            if (best == null || s.coverage() > best.coverage()) {
                best = s;
            }
        }
        return best;
    }

    public static Spot bestSpot(Spot[] spots) {
        return bestSpot(java.util.Arrays.asList(spots));
    }

    /**
     * РЕАЛЬНА висота ніг (поверхні опори) у вузлі: {@code surfaceY} найкращої точки стояння, або
     * {@code fallback} (звичайно — ціле {@code y} вузла), якщо стояти там не можна. Для повного блока це
     * рівно {@code y}; для плити {@code y+0.5}, для піску душ {@code y+0.875}, для килима
     * {@code y+0.0625} тощо — вузол завжди має {@code y == floor(surfaceY)}.
     */
    public static double surfaceYOf(List<Spot> spots, double fallback) {
        Spot s = bestSpot(spots);
        return s == null ? fallback : s.surfaceY();
    }

    public static double surfaceYOf(Spot[] spots, double fallback) {
        return surfaceYOf(java.util.Arrays.asList(spots), fallback);
    }

    /**
     * Розміри й можливості конкретного моба.
     *
     * @param stepUp  скільки моб піднімається просто йдучи (атрибут step height, ~0.6)
     * @param jumpUp  скільки моб піднімається стрибком (1.0 — один блок)
     * @param maxFall максимальне падіння, яке path-finder ще вважає прийнятним
     */
    public record BodyDims(double width, double height, double stepUp, double jumpUp, double maxFall) {
    }

    /**
     * Точка стояння моба: центр хітбокса в X/Z і реальна висота ніг.
     *
     * @param center true — це номінальний центр клітинки (інакше моб "притиснутий" до краю/кута)
     * @param check  true — для переходів із/в цю точку обов'язкова перевірка {@link #traverse}
     *               (клітинка нерегулярної форми); false — звичайний куб, ванільна логіка
     */
    public record Spot(double x, double z, double surfaceY, double coverage, boolean center, boolean check) {

        public Spot withCheck(boolean value) {
            return value == check ? this : new Spot(x, z, surfaceY, coverage, center, value);
        }
    }
}
