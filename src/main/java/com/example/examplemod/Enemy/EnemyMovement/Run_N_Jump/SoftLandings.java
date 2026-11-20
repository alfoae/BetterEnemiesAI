package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * v23: м'які приземлення - клітинки, де падіння не б'є (або б'є слабо): вода, сіно, драбини/ліани/риштування, ліжко; запасні -
 * мед, слайм, павутиння. Тут: розпізнавання виду клітинки ({@link #kindOf}) і ІНДЕКС м'яких клітинок навколо моба
 * ({@link Index}), щоб A* не сканував усі рівні вниз, а лише ті, де вони справді є.
 * <p>
 * Виключено: порошковий сніг, ягідні кущі і все, біля чого (у кубі 3x3x3) лава, вогонь, багаття, кактус, магма, сталагміт.
 * <p>
 * ПРОДУКТИВНІСТЬ (якщо буде лагати - спрощувати тут): побудова індексу - одне проходження по колонках (2R+1)^2, у кожній вниз до
 * першої не-порожньої клітинки; кеш на моба ~2 с ({@link #CACHE_TTL_TICKS}); на кожну записану клітинку ще 27 читань блоків
 * (перевірка небезпек) - тому {@link #MAX_CELLS} обмежує розмір.
 */
final class SoftLandings {

    /** Скільки м'яких клітинок максимум в одному індексі (великі озера не повинні роздувати пошук). */
    private static final int MAX_CELLS = 400;
    /** Індекс покриває вузол, якщо той не далі за стільки блоків по висоті від центру індексу. */
    private static final int COVER_VERTICAL_TOLERANCE = 6;

    /**
     * v24: до {@link #MAX_INDEXES_PER_MOB} індексів на моба (A* розкриває вузли далеко від старту - кожен район має свій індекс).
     */
    private static final Map<Mob, List<Index>> CACHE = new WeakHashMap<>();
    private static final int CACHE_TTL_TICKS = 40;
    private static final int MAX_INDEXES_PER_MOB = 4;

    /**
     * v24: м'яка клітинка в колонці {@code (x, z)}, шукаючи ЗВЕРХУ ВНИЗ від рівня {@code yTop} до {@code yBottom} - те саме правило,
     * що й в індексі, але від довільного рівня й без індексу. Індекс скануються від рівня свого центру, тому для вузла НИЖЧЕ за
     * центр (або під виступом) вода внизу лишалась би схованою за першим твердим блоком вище; для приземлення впритик читаємо
     * колонку від рівня самого вузла.
     *
     * @return {вид (ordinal {@link Kind}), Y клітинки ніг} або {@code null}
     */
    static int[] probeColumn(Reader r, int x, int z, int yTop, int yBottom) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = yTop; y >= yBottom; y--) {
            BlockState state = r.get(p.set(x, y, z));
            if (state == null) {
                return null;
            }
            if (state.isAir()) {
                continue;
            }
            if (isHazardBlock(state)) {
                return null; // лава, порошковий сніг, кущ...: за ними воду не шукаємо
            }
            if (state.getFluidState().is(FluidTags.WATER) || state.is(BlockTags.CLIMBABLE) || state.is(Blocks.COBWEB)) {
                return probeCell(r, p, x, y, z);
            }
            if (state.isSolid()) {
                return probeCell(r, p, x, y + 1, z);
            }
        }
        return null;
    }

    /**
     * v25: ПІДЛОГА колонки {@code (x, z)} зверху вниз від {@code yTop} до {@code yBottom}: перший твердий блок (або вода/драбина/
     * павутиння). Те саме, що {@link #probeColumn}, але повертає й ТВЕРДУ підлогу (для спусків на сходинки), а не лише м'яку.
     *
     * @return {вид м'якого (ordinal {@link Kind}) або -1 для твердої (чи небезпечної для м'якого) підлоги, Y клітинки ніг} або
     * {@code null}, якщо підлоги нема, вона небезпечна (лава, кактус, магма...) або колонку не прочитано
     */
    static int[] probeFloor(Reader r, int x, int z, int yTop, int yBottom) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = yTop; y >= yBottom; y--) {
            BlockState state = r.get(p.set(x, y, z));
            if (state == null) {
                return null;
            }
            if (state.isAir()) {
                continue;
            }
            if (isHazardBlock(state)) {
                return null;
            }
            if (state.getFluidState().is(FluidTags.WATER) || state.is(BlockTags.CLIMBABLE) || state.is(Blocks.COBWEB)) {
                Kind kind = kindChecked(r, p, x, y, z);
                return kind == null ? null : new int[]{kind.ordinal(), y};
            }
            if (state.isSolid()) {
                int feetY = y + 1;
                Kind kind = kindChecked(r, p, x, feetY, z); // сіно, мед, слайм, ліжко - тверді, але м'які
                return new int[]{kind == null ? -1 : kind.ordinal(), feetY};
            }
        }
        return null;
    }

    private SoftLandings() {
    }

    /**
     * Вид приземлення в клітинку ніг {@code feet} (під нею {@code below}, над нею {@code head}) або {@code null}, якщо клітинка
     * не м'яка або в неї не вміщується тіло.
     */
    static Kind kindOf(BlockState below, BlockState feet, BlockState head) {
        if (head.isSolid()) {
            return null; // тіло не вміщується
        }
        if (feet.getFluidState().is(FluidTags.WATER)) {
            return Kind.WATER;
        }
        if (feet.is(BlockTags.CLIMBABLE)) {
            return Kind.CLIMB;
        }
        if (feet.is(Blocks.COBWEB)) {
            return Kind.WEB;
        }
        if (feet.isSolid()) {
            return null;
        }
        if (below.is(Blocks.HAY_BLOCK)) {
            return Kind.HAY;
        }
        if (below.is(BlockTags.BEDS)) {
            return Kind.BED;
        }
        if (below.is(Blocks.HONEY_BLOCK)) {
            return Kind.HONEY;
        }
        if (below.is(Blocks.SLIME_BLOCK)) {
            return Kind.SLIME;
        }
        return null;
    }

    /** Колонка зверху вниз до першої не-порожньої клітинки: вона й вирішує, чи є тут м'яке приземлення. */
    private static void scanColumn(Reader r, BlockPos.MutableBlockPos p, Index index, int x, int z, int yTop, int yBottom) {
        for (int y = yTop; y >= yBottom; y--) {
            BlockState state = r.get(p.set(x, y, z));
            if (state == null) {
                return;
            }
            if (state.isAir()) {
                continue;
            }
            if (state.getFluidState().is(FluidTags.WATER) || state.is(BlockTags.CLIMBABLE) || state.is(Blocks.COBWEB)) {
                tryRecord(r, p, index, x, y, z); // вода - її ПОВЕРХНЯ (перша клітинка води згори)
                return;
            }
            if (state.isSolid()) {
                tryRecord(r, p, index, x, y + 1, z); // тверда опора: м'яка лише якщо це сіно/ліжко/мед/слайм - клітинка НАД нею
                return;
            }
            // решта (трава, сніг-шар, плита без колізії) - проходимо далі вниз
        }
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /**
     * Індекс для вузла {@code (cx, cy, cz)} із запасом {@code reach} блоків навколо: з кешу моба (до ~2 с), якщо котрийсь
     * наявний його покриває, інакше побудований заново з радіусом {@code radius} (і доданий у кеш, до
     * {@link #MAX_INDEXES_PER_MOB} штук - найстаріші витісняються).
     */
    static Index obtain(Mob mob, Reader reader, int cx, int cy, int cz, int reach, int radius, int maxDepth, long tick) {
        List<Index> list = CACHE.computeIfAbsent(mob, k -> new ArrayList<>(MAX_INDEXES_PER_MOB));
        list.removeIf(i -> tick < i.tick || tick - i.tick > CACHE_TTL_TICKS);
        for (Index cached : list) {
            if (cached.covers(cx, cy, cz, reach, maxDepth)) {
                return cached;
            }
        }
        Index fresh = build(reader, cx, cy, cz, Math.max(radius, reach), maxDepth, tick);
        list.add(fresh);
        while (list.size() > MAX_INDEXES_PER_MOB) {
            list.remove(0);
        }
        return fresh;
    }

    private static Index build(Reader reader, int cx, int cy, int cz, int radius, int maxDepth, long tick) {
        Index index = new Index(cx, cy, cz, radius, maxDepth, tick);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // v24: КІЛЬЦЯМИ від центру (Чебишов): якщо ліміт клітинок вичерпається на великому озері, найближчі до моба клітинки
        // вже в індексі, а не залишаться недосканованими через те, що перебір ішов рядками.
        for (int r = 0; r <= radius && index.size() < MAX_CELLS; r++) {
            for (int dx = -r; dx <= r && index.size() < MAX_CELLS; dx++) {
                if (r == 0 || Math.abs(dx) == r) {
                    for (int dz = -r; dz <= r && index.size() < MAX_CELLS; dz++) {
                        index.columnsScanned++;
                        scanColumn(reader, p, index, cx + dx, cz + dz, cy - 1, cy - maxDepth);
                    }
                } else {
                    index.columnsScanned += 2;
                    scanColumn(reader, p, index, cx + dx, cz - r, cy - 1, cy - maxDepth);
                    if (index.size() < MAX_CELLS) {
                        scanColumn(reader, p, index, cx + dx, cz + r, cy - 1, cy - maxDepth);
                    }
                }
            }
        }
        index.computeMargins();
        return index;
    }

    /** Лава, вогонь, багаття, кактус, магма, ягідний кущ, порошковий сніг, сталагміт, іссохла троянда в кубі 3x3x3 навколо клітинки. */
    private static boolean hazardNear(Reader r, BlockPos.MutableBlockPos p, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockState s = r.get(p.set(x + dx, y + dy, z + dz));
                    if (s != null && isHazardBlock(s)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * v25: блок, на який/в який падати не можна (для куба навколо м'якого приземлення й для підлоги твердого приземлення).
     */
    static boolean isHazardBlock(BlockState s) {
        return s.getFluidState().is(FluidTags.LAVA) || s.is(BlockTags.FIRE) || s.is(BlockTags.CAMPFIRES)
                || s.is(Blocks.CACTUS) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.SWEET_BERRY_BUSH)
                || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.POINTED_DRIPSTONE) || s.is(Blocks.WITHER_ROSE);
    }

    private static int[] probeCell(Reader r, BlockPos.MutableBlockPos p, int x, int y, int z) {
        Kind kind = kindChecked(r, p, x, y, z);
        return kind == null ? null : new int[]{kind.ordinal(), y};
    }

    /**
     * v24: вид приземлення в клітинку ніг {@code (x, y, z)} З перевіркою небезпек поруч (лава, вогонь, кактус...): одне правило для
     * індексу, для колонок впритик і для ванільних ребер спуску ({@code applyDropCosts}) - щоб вода біля лави не була "м'якою"
     * в одному місці й "небезпечною" в іншому.
     */
    static Kind kindChecked(Reader r, BlockPos.MutableBlockPos p, int x, int y, int z) {
        BlockState below = r.get(p.set(x, y - 1, z));
        BlockState feet = r.get(p.set(x, y, z));
        BlockState head = r.get(p.set(x, y + 1, z));
        if (below == null || feet == null || head == null) {
            return null;
        }
        Kind kind = kindOf(below, feet, head);
        if (kind == null || hazardNear(r, p, x, y, z)) {
            return null;
        }
        return kind;
    }

    static Kind kindChecked(Reader r, int x, int y, int z) {
        return kindChecked(r, new BlockPos.MutableBlockPos(), x, y, z);
    }

    enum Kind {
        WATER(0, "вода"),
        HAY(0, "сіно"),
        CLIMB(0, "драбина/ліани/риштування"),
        BED(0, "ліжко"),
        HONEY(1, "мед (запасне)"),
        SLIME(1, "слайм (запасне)"),
        WEB(1, "павутиння (запасне)");

        final int rank;
        final String label;

        Kind(int rank, String label) {
            this.rank = rank;
            this.label = label;
        }
    }

    private static void tryRecord(Reader r, BlockPos.MutableBlockPos p, Index index, int x, int y, int z) {
        BlockState below = r.get(p.set(x, y - 1, z));
        BlockState feet = r.get(p.set(x, y, z));
        BlockState head = r.get(p.set(x, y + 1, z));
        if (below == null || feet == null || head == null) {
            return;
        }
        Kind kind = kindOf(below, feet, head);
        if (kind == null || hazardNear(r, p, x, y, z)) {
            return;
        }
        index.record(x, y, z, kind);
    }

    @FunctionalInterface
    interface Reader {
        BlockState get(BlockPos pos);
    }

    /**
     * Індекс м'яких клітинок у кубі навколо центру (ключ - {@link BlockPos#asLong}).
     */
    static final class Index {
        final int cx;
        final int cy;
        final int cz;
        final int radius;
        final int maxDepth;
        final long tick;
        private final HashMap<Long, int[]> cells = new HashMap<>();
        private final HashMap<Long, Integer> columns = new HashMap<>();
        private final TreeSet<Integer> levels = new TreeSet<>();
        int columnsScanned;

        Index(int cx, int cy, int cz, int radius, int maxDepth, long tick) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.radius = radius;
            this.maxDepth = maxDepth;
            this.tick = tick;
        }

        boolean isEmpty() {
            return this.cells.isEmpty();
        }

        /**
         * v24: чи достатньо цього індексу для вузла {@code (x, y, z)}: індекс має сягати від вузла на {@code reach} блоків усіма
         * боками (інакше м'які клітинки в межах стрибка від вузла лишились би поза індексом) і не бути надто вище за вузол.
         */
        boolean covers(int x, int y, int z, int reach, int maxDepth) {
            return this.maxDepth >= maxDepth
                    && Math.abs(x - this.cx) + reach <= this.radius
                    && Math.abs(z - this.cz) + reach <= this.radius
                    && y <= this.cy + COVER_VERTICAL_TOLERANCE
                    && y >= this.cy - COVER_VERTICAL_TOLERANCE;
        }

        int size() {
            return this.cells.size();
        }

        /** {вид (ordinal {@link Kind}), margin 0..4} або {@code null}. */
        int[] cellAt(int x, int y, int z) {
            return this.cells.get(BlockPos.asLong(x, y, z));
        }

        /** Y клітинки ніг м'якого приземлення в колонці (x, z) або {@code null}. */
        Integer softYAt(int x, int z) {
            return this.columns.get(columnKey(x, z));
        }

        /** Абсолютні Y рівнів, де є м'які клітинки, строго нижчі за {@code y}, від вищих до нижчих. */
        int[] levelsBelow(int y) {
            NavigableSet<Integer> below = this.levels.headSet(y, false).descendingSet();
            int[] out = new int[below.size()];
            int i = 0;
            for (int v : below) {
                out[i++] = v;
            }
            return out;
        }

        private void record(int x, int y, int z, Kind kind) {
            this.cells.put(BlockPos.asLong(x, y, z), new int[]{kind.ordinal(), 0});
            this.columns.put(columnKey(x, z), y);
            this.levels.add(y);
        }

        private void computeMargins() {
            for (Map.Entry<Long, int[]> e : this.cells.entrySet()) {
                long key = e.getKey();
                int x = BlockPos.getX(key);
                int y = BlockPos.getY(key);
                int z = BlockPos.getZ(key);
                int margin = 0;
                if (this.cells.containsKey(BlockPos.asLong(x + 1, y, z))) {
                    margin++;
                }
                if (this.cells.containsKey(BlockPos.asLong(x - 1, y, z))) {
                    margin++;
                }
                if (this.cells.containsKey(BlockPos.asLong(x, y, z + 1))) {
                    margin++;
                }
                if (this.cells.containsKey(BlockPos.asLong(x, y, z - 1))) {
                    margin++;
                }
                e.getValue()[1] = margin;
            }
        }
    }
}
