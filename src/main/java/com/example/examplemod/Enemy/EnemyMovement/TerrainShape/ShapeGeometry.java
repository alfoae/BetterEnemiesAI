package com.example.examplemod.Enemy.EnemyMovement.TerrainShape;

import java.util.List;

/**
 * Чиста геометрія неповних блоків — БЕЗ жодних Minecraft-типів (тому тестується окремо від гри,
 * так само, як {@code GapJumpPhysics}). Замінює булеве "блокує/не блокує" ({@code blocksMotion()})
 * трьома реальними числами, порахованими з розкладеної на окремі коробки форми колізії:
 * <ul>
 *   <li><b>surfaceY</b> — на якій РЕАЛЬНІЙ висоті лежить опора (0.1875 для горщика, 0.5 для нижньої
 *       плити, 1.0 для звичайного блока — не завжди ціле число);</li>
 *   <li><b>coverage</b> — яка частка "сліду" мобу (footprint) реально лежить НАД суцільною
 *       геометрією на цій висоті, а не висить над порожнечею (відкритий люк — coverage≈0, навіть
 *       якщо в клітинці Є якась форма);</li>
 *   <li><b>headroom</b> — скільки вільного місця вертикально над точкою опори до першої перешкоди.</li>
 * </ul>
 * <p>
 * Джерело коробок — {@code VoxelShape.toAabbs()} з ADAPTER-шару ({@code ShapeProbe}, окремий файл,
 * там уже є Minecraft-типи). Тут — лише арифметика над списком коробок.
 */
public final class ShapeGeometry {

    /**
     * Допуск на похибку double-порівнянь висот/меж.
     */
    public static final double EPS = 1.0E-5;

    private ShapeGeometry() {
    }

    /**
     * Найвища опорна поверхня НЕ вище {@code ceilingY}, під якою фактично лежить footprint, і яка
     * частка footprint-у на ній реально тримається.
     * <p>
     * Спершу шукаємо {@code bestTop} — максимальний {@code maxY} серед коробок, що перетинають
     * footprint по X/Z і не вищі за стелю. Тоді підсумовуємо площу ВСІХ коробок на цій самій висоті
     * (у межах {@link #EPS}) — не тільки першої знайденої: горизонтальна поверхня може складатись із
     * кількох сусідніх коробок (наприклад, стовпчик паркану + рука до сусіда).
     *
     * @param boxes     коробки колізії (можуть бути з кількох клітинок одразу — адаптер сам вирішує,
     *                  які саме клітинки дають кандидатів)
     * @param footprint ділянка вибірки (слід мобу) у світових X/Z
     * @param ceilingY  найвища висота, яку ще приймаємо як "тут" (звичайно — верх клітинки, що
     *                  запитується, щоб не підхопити щось із клітинки НАД нею)
     */
    public static Support findSupport(List<Box> boxes, Footprint footprint, double ceilingY) {
        double area = footprint.area();
        if (area <= 0.0) {
            return Support.NONE;
        }
        double bestTop = Double.NEGATIVE_INFINITY;
        for (Box b : boxes) {
            if (b.maxY() <= ceilingY + EPS && b.maxY() > bestTop && b.overlapsXZ(footprint)) {
                bestTop = b.maxY();
            }
        }
        if (bestTop == Double.NEGATIVE_INFINITY) {
            return Support.NONE;
        }
        double covered = 0.0;
        for (Box b : boxes) {
            if (Math.abs(b.maxY() - bestTop) <= EPS && b.overlapsXZ(footprint)) {
                covered += b.intersectionAreaXZ(footprint);
            }
        }
        double coverage = Math.min(1.0, covered / area);
        return new Support(bestTop, coverage);
    }

    /**
     * Яка частка footprint-у перекрита БУДЬ-ЯКОЮ коробкою, незалежно від висоти (чи заважає ця
     * геометрія тілу мобу пройти тут узагалі — на відміну від {@link #findSupport}, тут не важливо,
     * "верхня" це поверхня чи ні). Використовується для "чи блокує ця клітинка".
     */
    public static double blockedFraction(List<Box> boxes, Footprint footprint) {
        double area = footprint.area();
        if (area <= 0.0) {
            return 0.0;
        }
        double covered = 0.0;
        for (Box b : boxes) {
            if (b.overlapsXZ(footprint)) {
                covered += b.intersectionAreaXZ(footprint);
            }
        }
        return Math.min(1.0, covered / area);
    }

    /**
     * Чи є в списку коробка, яка перетинає footprint по X/Z і тягнеться вертикально від {@code lowY}
     * (чи нижче) щонайменше до {@code highY} — тобто "стовпчик"/"панель", що фізично перекриває ввесь
     * цей вертикальний діапазон, а не тонкий виступ лише знизу (горщик, килим, нижня плита). Саме
     * цим паркан (0..1.5, вище за стелю звичайної клітинки 0..1.0) відрізняється від горщика
     * (0..0.1875): {@link #findSupport} із верхньою межею на рівні стелі клітинки паркан у принципі
     * НЕ побачить (його верх вище за cei­ling) — тому "чи заважає тілу" перевіряється ЦИМ окремим
     * запитом, а не висновком із findSupport.
     */
    public static boolean spansRange(List<Box> boxes, Footprint footprint, double lowY, double highY) {
        for (Box b : boxes) {
            if (b.minY() <= lowY + EPS && b.maxY() >= highY - EPS && b.overlapsXZ(footprint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Вільний простір вертикально від {@code fromY} до першої перешкоди над footprint-ом, не більше
     * {@code maxCheck}.
     */
    public static double headroom(List<Box> boxes, Footprint footprint, double fromY, double maxCheck) {
        double best = maxCheck;
        for (Box b : boxes) {
            double gap = b.minY() - fromY;
            if (gap > EPS && gap < best && b.overlapsXZ(footprint)) {
                best = gap;
            }
        }
        return best;
    }

    /**
     * Одна коробка колізії в АБСОЛЮТНИХ світових координатах (вже зі зміщенням на позицію блока —
     * адаптер це робить, тут координати завжди "живі", не відносні 0..1 клітинки).
     */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

        public boolean overlapsXZ(Footprint f) {
            return maxX > f.minX() && minX < f.maxX() && maxZ > f.minZ() && minZ < f.maxZ();
        }

        /**
         * Площа перетину горизонтальної проекції коробки з footprint-ом (0, якщо не перетинаються).
         */
        public double intersectionAreaXZ(Footprint f) {
            double dx = Math.min(maxX, f.maxX()) - Math.max(minX, f.minX());
            double dz = Math.min(maxZ, f.maxZ()) - Math.max(minZ, f.minZ());
            return Math.max(0.0, dx) * Math.max(0.0, dz);
        }
    }

    /**
     * Горизонтальний "слід" мобу (чи довільна ділянка вибірки) у світових координатах X/Z.
     */
    public record Footprint(double minX, double minZ, double maxX, double maxZ) {

        /**
         * Квадрат заданої ширини, відцентрований у (cx, cz).
         */
        public static Footprint centered(double cx, double cz, double width) {
            double h = width / 2.0;
            return new Footprint(cx - h, cz - h, cx + h, cz + h);
        }

        public double area() {
            return Math.max(0.0, maxX - minX) * Math.max(0.0, maxZ - minZ);
        }
    }

    /**
     * Результат пошуку опори: висота поверхні й яка частка footprint-у реально на ній лежить.
     * {@link #NONE} — опори взагалі нема (справжня порожнеча в межах footprint-у).
     */
    public record Support(double surfaceY, double coverage) {

        public static final Support NONE = new Support(Double.NaN, 0.0);

        public boolean isPresent() {
            return coverage > 0.0;
        }
    }
}
