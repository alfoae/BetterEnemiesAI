package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v26: ПЛАНУВАЛЬНИК СПУСКУ ВИСТУПАМИ НА СТІНІ. Чиста геометрія без типів Minecraft (як {@code FallMath}/{@code DropMath}):
 * світ читається через {@link World}, тож алгоритм перевіряється окремо від гри.
 * <p>
 * Задача: моб стоїть на краю високої споруди (стовп, урвище, дах), а до підлоги значно більше за безпечну висоту падіння.
 * Спуск розбивається на СТУПЕНІ не вище {@link Params#stage()} блоків (за замовчуванням 3 - безпечна висота, шкоди нема).
 * Кожен ступінь: моб сходить з поточної опори вбік (на сусідню колонку або ЗА КУТ), у польоті ставить блок-ВИСТУП у цій
 * колонці й приземляється на нього. Звідти - наступний ступінь, і так до підлоги. Крок:
 * <ul>
 *   <li>{@link Kind#STEP} - збіг на сусідню колонку (ортогональну або діагональну/кутову) + виступ на {@code fall} блоків нижче;</li>
 *   <li>{@link Kind#FINAL} - останній збіг: до справжньої підлоги не більше {@code stage} блоків, блок не потрібен;</li>
 *   <li>{@link Kind#BREAK_STEP}/{@link Kind#BREAK_FINAL} - КРАЙНІЙ ВИПАДОК (лише коли збігом убік іти нікуди, наприклад
 *       шахта 1x1): зламати виступ під собою й падати далі тією ж колонкою. Пробується тільки якщо без нього плану нема.</li>
 * </ul>
 * Виступ ставиться ЛИШЕ в клітинку, в якої збоку є справжній суцільний блок ({@link World#solid}) - тобто до стіни, а не в
 * повітрі. Виступи, які сам план поставить раніше, стіною не рахуються, але враховуються як перешкоди: моб може "пригнути
 * під блок, який сам поставив" (колонка під попереднім виступом вільна, якщо вистачає висоти).
 * <p>
 * Виступи йдуть через 3+ блоки по висоті один від одного (не по -1 Y), чергуючись між сусідніми колонками біля стіни
 * (зигзаг) або обходячи стовп по колу (кутові збіги).
 */
public final class LedgePlanner {

    private LedgePlanner() {
    }

    /**
     * {@link Step#ledgeY} для кроку без нового виступу.
     */
    public static final int NO_LEDGE = Integer.MIN_VALUE;
    /**
     * 8 напрямків: спершу ортогональні, потім діагональні.
     */
    public static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * Плани для КОЖНОГО першого напрямку збігу (різні - різні підлоги внизу), без повторів за точкою приземлення.
     * Лише плани, у яких є хоч один виступ (інакше це звичайний спуск, ним займається решта графа).
     */
    public static List<Plan> planAll(World world, int sx, int sy, int sz, Params params) {
        return planAll(world, sx, sy, sz, params, null);
    }

    /**
     * Те саме, але з ЗАГАЛЬНИМ бюджетом вузлів {@code budget[0]} (зменшується в процесі пошуку): граф A* викликає планувальник з
     * багатьох країв за один пошук шляху, тож час треба обмежити сумарно (довге урвище над лавою інакше змусило б перебрати все).
     * {@code null} - без загального ліміту (діє лише {@link Params#maxNodes()} на один пошук).
     */
    public static List<Plan> planAll(World world, int sx, int sy, int sz, Params params, int[] budget) {
        List<Plan> out = new ArrayList<>(4);
        Set<Long> seen = new HashSet<>();
        for (int[] d : DIRS) {
            Plan plan = plan(world, sx, sy, sz, d[0], d[1], params, budget);
            if (plan != null && seen.add(key(plan.landX(), plan.landY(), plan.landZ()))) {
                out.add(plan);
            }
        }
        return out;
    }

    /**
     * План спуску з клітинки ніг {@code (sx, sy, sz)}, перший збіг - у напрямку {@code (firstDx, firstDz)} (обов'язково
     * ненульовий). Спершу пошук БЕЗ ламання, і лише якщо його нема й {@link Params#allowBreak()} - з ламанням.
     * Детермінований: ті самі світ і параметри дають той самий план (виконавець перераховує його, не зберігаючи).
     *
     * @return план або {@code null}
     */
    public static Plan plan(World world, int sx, int sy, int sz, int firstDx, int firstDz, Params params) {
        return plan(world, sx, sy, sz, firstDx, firstDz, params, null);
    }

    /**
     * Те саме із загальним бюджетом вузлів (див. {@link #planAll(World, int, int, int, Params, int[])}).
     */
    public static Plan plan(World world, int sx, int sy, int sz, int firstDx, int firstDz, Params params, int[] budget) {
        if (firstDx == 0 && firstDz == 0) {
            return null;
        }
        Plan plan = new Run(world, params.withBreak(false), sx, sz, firstDx, firstDz, budget).run(sx, sy, sz);
        if (plan == null && params.allowBreak() && (budget == null || budget[0] > 0)) {
            plan = new Run(world, params.withBreak(true), sx, sz, firstDx, firstDz, budget).run(sx, sy, sz);
        }
        return plan;
    }

    /**
     * Ключ клітинки (x, z - по 26 біт, y - 12 біт зі зсувом).
     */
    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) ((y + 2048) & 0xFFF);
    }

    public enum Kind {
        STEP, FINAL, BREAK_STEP, BREAK_FINAL
    }

    /**
     * Що планувальник знає про світ. Усі методи - для клітинки {@code (x, y, z)}; невідоме (незавантажене) = "ні" в усьому.
     */
    public interface World {
        /** Є колізія: блок - перешкода для тіла і може бути "стіною", до якої приліплюється виступ. */
        boolean solid(int x, int y, int z);

        /** Зверху на цьому блоці можна БЕЗПЕЧНО стати (повний блок, не шкодить): підлога внизу. */
        boolean floor(int x, int y, int z);

        /** У клітинці може бути тіло моба: повітря/трава, без рідини, павутини, вогню та іншого, що шкодить чи тримає. */
        boolean free(int x, int y, int z);

        /** У клітинку можна поставити блок (повітря чи замінюване, без рідини й небезпек). */
        boolean placeable(int x, int y, int z);
    }

    /**
     * @param stage       висота одного ступеня, блоків (не більше безпечної висоти падіння моба)
     * @param height      висота моба в клітинках (зомбі = 2)
     * @param maxStages   найбільше кроків у плані (запобіжник)
     * @param allowBreak  дозволити КРАЙНІЙ випадок - ламати виступ під собою
     * @param maxNodes    ліміт вузлів пошуку (запобіжник часу)
     */
    public record Params(int stage, int height, int maxStages, boolean allowBreak, int maxNodes) {
        public Params {
            stage = Math.max(1, stage);
            height = Math.max(1, height);
            maxStages = Math.max(1, maxStages);
            maxNodes = Math.max(16, maxNodes);
        }

        public Params withBreak(boolean value) {
            return new Params(this.stage, this.height, this.maxStages, value, this.maxNodes);
        }
    }

    /**
     * Один крок плану.
     *
     * @param fromX,fromY,fromZ клітинка ніг моба на початку кроку (стоїть на опорі під нею)
     * @param toX,toZ           колонка, у яку моб потрапляє (для BREAK-кроків збігається з {@code from})
     * @param exitX,exitZ       кутовий збіг: відкрита ортогональна сторона виходу з краю; {@code (0,0)} - звичайний
     * @param ledgeY            Y клітинки нового виступу в колонці {@code (toX, toZ)} або {@link #NO_LEDGE}
     * @param fall              скільки блоків моб падає за цей крок (від ніг на початку до ніг на приземленні)
     */
    public record Step(Kind kind, int fromX, int fromY, int fromZ, int toX, int toZ, int exitX, int exitZ,
                       int ledgeY, int fall) {
        public boolean placesLedge() {
            return this.ledgeY != NO_LEDGE;
        }

        public boolean breaksLedge() {
            return this.kind == Kind.BREAK_STEP || this.kind == Kind.BREAK_FINAL;
        }

        public boolean isFinal() {
            return this.kind == Kind.FINAL || this.kind == Kind.BREAK_FINAL;
        }

        /** Рівень ніг після кроку. */
        public int landingY() {
            return this.fromY - this.fall;
        }
    }

    /** Готовий план: кроки й клітинка ніг на справжній підлозі наприкінці. */
    public record Plan(List<Step> steps, int landX, int landY, int landZ) {
        /** Скільки виступів доведеться поставити. */
        public int ledges() {
            int n = 0;
            for (Step s : this.steps) {
                if (s.placesLedge()) {
                    n++;
                }
            }
            return n;
        }

        public int breaks() {
            int n = 0;
            for (Step s : this.steps) {
                if (s.breaksLedge()) {
                    n++;
                }
            }
            return n;
        }

        public boolean usesBreak() {
            return breaks() > 0;
        }
    }

    /** Колонка, у яку моб заходить з поточної опори. */
    private record Entry(int tx, int tz, int exitX, int exitZ) {
    }

    private static final class Run {
        final World w;
        final Params p;
        final int originX;
        final int originZ;
        final int firstDx;
        final int firstDz;
        /** Загальний бюджет вузлів на кілька пошуків (null - без нього). */
        final int[] budget;
        /** Виступи, поставлені в цьому ланцюжку (віртуальні: у світі їх ще нема). */
        final Set<Long> ledges = new HashSet<>();
        /** Стани, з яких продовження не знайдено (мемо провалів). */
        final Set<Long> dead = new HashSet<>();
        final ArrayList<Step> chain = new ArrayList<>();
        int nodes;
        int landX;
        int landY;
        int landZ;

        Run(World w, Params p, int originX, int originZ, int firstDx, int firstDz, int[] budget) {
            this.w = w;
            this.p = p;
            this.originX = originX;
            this.originZ = originZ;
            this.firstDx = firstDx;
            this.firstDz = firstDz;
            this.budget = budget;
        }

        Plan run(int sx, int sy, int sz) {
            if (!solve(sx, sy, sz, false, 0)) {
                return null;
            }
            return new Plan(List.copyOf(this.chain), this.landX, this.landY, this.landZ);
        }

        // ---- запити зі "віртуальними" виступами ----

        boolean isLedge(int x, int y, int z) {
            return !this.ledges.isEmpty() && this.ledges.contains(key(x, y, z));
        }

        static int sq(int v) {
            return v * v;
        }

        boolean solidV(int x, int y, int z) {
            return this.w.solid(x, y, z) || isLedge(x, y, z);
        }

        /** Клітинка вільна для тіла й не зайнята виступом плану. */
        boolean freeV(int x, int y, int z) {
            return this.w.free(x, y, z) && !isLedge(x, y, z);
        }

        /** Усі клітинки тіла на рівні {@code y} вільні. */
        boolean bodyFree(int x, int y, int z) {
            for (int i = 0; i < this.p.height(); i++) {
                if (!freeV(x, y + i, z)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Чи може моб зійти з опори {@code (sx, sy, sz)} у колонку {@code (sx+dx, sz+dz)} на рівні ніг {@code sy}: тіло там
         * вільне, а підлоги на цьому рівні нема (інакше це ходьба, не збіг). Діагональ: обидва боки закриті - ні; один
         * закритий - КУТОВИЙ збіг відкритим боком (він теж без підлоги); обидва відкриті - звичайна діагональ.
         */
        Entry entry(int sx, int sy, int sz, int dx, int dz) {
            int tx = sx + dx;
            int tz = sz + dz;
            if (!bodyFree(tx, sy, tz) || solidV(tx, sy - 1, tz)) {
                return null;
            }
            int ex = 0;
            int ez = 0;
            if (dx != 0 && dz != 0) {
                boolean aOpen = bodyFree(sx + dx, sy, sz);
                boolean bOpen = bodyFree(sx, sy, sz + dz);
                if (!aOpen && !bOpen) {
                    return null;
                }
                if (aOpen && !bOpen) {
                    if (solidV(sx + dx, sy - 1, sz)) {
                        return null;
                    }
                    ex = dx;
                } else if (!aOpen) {
                    if (solidV(sx, sy - 1, sz + dz)) {
                        return null;
                    }
                    ez = dz;
                }
            }
            return new Entry(tx, tz, ex, ez);
        }

        /** Біля клітинки збоку є СПРАВЖНІЙ суцільний блок (не виступ плану): це "стіна", до якої приліплюється виступ. */
        boolean wallSupport(int x, int y, int z) {
            return (this.w.solid(x + 1, y, z) && !isLedge(x + 1, y, z))
                    || (this.w.solid(x - 1, y, z) && !isLedge(x - 1, y, z))
                    || (this.w.solid(x, y, z + 1) && !isLedge(x, y, z + 1))
                    || (this.w.solid(x, y, z - 1) && !isLedge(x, y, z - 1));
        }

        /**
         * Скільки блоків до справжньої підлоги в колонці входу, якщо вона в межах {@code stage} (без шкоди): 1..stage, інакше 0.
         * Приземлення - на справжній підлозі (не на виступі плану).
         */
        int floorWithin(Entry e, int sx, int sy, int sz) {
            for (int d = 1; d <= this.p.stage(); d++) {
                if (!freeV(e.tx, sy - d, e.tz)) {
                    return 0; // колонка падіння перекрита (чи небезпека) раніше за підлогу
                }
                if (this.w.floor(e.tx, sy - d - 1, e.tz) && !isLedge(e.tx, sy - d - 1, e.tz)) {
                    return fallClear(e, sx, sy, sz, d) && bodyFree(e.tx, sy - d, e.tz) ? d : 0;
                }
                if (this.w.solid(e.tx, sy - d - 1, e.tz) || isLedge(e.tx, sy - d - 1, e.tz)) {
                    return 0; // тверде, але небезпечне/не підлога (магма, кактус) або виступ плану
                }
            }
            return 0;
        }

        /** Колонка падіння (і колонка виходу для кутового збігу) вільна на {@code fall} блоків униз від рівня ніг {@code sy}. */
        boolean fallClear(Entry e, int sx, int sy, int sz, int fall) {
            for (int y = sy - fall; y < sy; y++) {
                if (!freeV(e.tx, y, e.tz)) {
                    return false;
                }
                if ((e.exitX != 0 || e.exitZ != 0) && !freeV(sx + e.exitX, y, sz + e.exitZ)) {
                    return false;
                }
            }
            return true;
        }

        /** Напрямки в порядку спроби: ближчі до початкової колонки першими (зигзаг біля стіни), ортогональні перед діагоналлю. */
        int[][] ordered(int sx, int sz, int depth) {
            if (depth == 0) {
                return new int[][]{{this.firstDx, this.firstDz}};
            }
            int[][] order = DIRS.clone();
            final int ox = this.originX;
            final int oz = this.originZ;
            java.util.Arrays.sort(order, (a, b) -> {
                int da = sq(sx + a[0] - ox) + sq(sz + a[1] - oz);
                int db = sq(sx + b[0] - ox) + sq(sz + b[1] - oz);
                if (da != db) {
                    return Integer.compare(da, db);
                }
                int oa = (a[0] != 0 && a[1] != 0) ? 1 : 0;
                int ob = (b[0] != 0 && b[1] != 0) ? 1 : 0;
                return Integer.compare(oa, ob);
            });
            return order;
        }

        boolean solve(int sx, int sy, int sz, boolean onLedge, int depth) {
            if (++this.nodes > this.p.maxNodes() || depth >= this.p.maxStages()) {
                return false;
            }
            if (this.budget != null) {
                if (this.budget[0] <= 0) {
                    return false;
                }
                this.budget[0]--;
            }
            long stateKey = key(sx, sy, sz);
            if (this.dead.contains(stateKey)) {
                return false;
            }
            int[][] order = ordered(sx, sz, depth);

            // 1) ОСТАННІЙ збіг на справжню підлогу (не на першому кроці: там це звичайний спуск без виступів)
            if (depth > 0) {
                for (int[] d : order) {
                    Entry e = entry(sx, sy, sz, d[0], d[1]);
                    if (e == null) {
                        continue;
                    }
                    int fall = floorWithin(e, sx, sy, sz);
                    if (fall > 0) {
                        this.chain.add(new Step(Kind.FINAL, sx, sy, sz, e.tx, e.tz, e.exitX, e.exitZ, NO_LEDGE, fall));
                        this.landX = e.tx;
                        this.landY = sy - fall;
                        this.landZ = e.tz;
                        return true;
                    }
                }
            }

            // 2) Збіг убік + виступ біля стіни
            for (int[] d : order) {
                Entry e = entry(sx, sy, sz, d[0], d[1]);
                if (e == null) {
                    continue;
                }
                for (int s = this.p.stage(); s >= 1; s--) {
                    int ly = sy - s - 1;
                    if (!fallClear(e, sx, sy, sz, s) || !this.w.placeable(e.tx, ly, e.tz) || isLedge(e.tx, ly, e.tz)
                            || !wallSupport(e.tx, ly, e.tz)) {
                        continue;
                    }
                    long ledgeKey = key(e.tx, ly, e.tz);
                    this.ledges.add(ledgeKey);
                    this.chain.add(new Step(Kind.STEP, sx, sy, sz, e.tx, e.tz, e.exitX, e.exitZ, ly, s));
                    if (solve(e.tx, sy - s, e.tz, true, depth + 1)) {
                        return true;
                    }
                    this.chain.remove(this.chain.size() - 1);
                    this.ledges.remove(ledgeKey);
                }
            }

            // 3) КРАЙНІЙ ВИПАДОК: зламати виступ під собою й падати тією ж колонкою
            if (this.p.allowBreak() && onLedge && depth > 0 && breakMoves(sx, sy, sz, depth)) {
                return true;
            }
            this.dead.add(stateKey);
            return false;
        }

        boolean breakMoves(int sx, int sy, int sz, int depth) {
            long under = key(sx, sy - 1, sz);
            if (!this.ledges.contains(under)) {
                return false; // під ногами не наш виступ - ламати нічого
            }
            this.ledges.remove(under);
            try {
                // до справжньої підлоги в межах ступеня
                for (int d = 1; d <= this.p.stage(); d++) {
                    if (!freeV(sx, sy - d, sz)) {
                        break;
                    }
                    if (this.w.floor(sx, sy - d - 1, sz) && !isLedge(sx, sy - d - 1, sz)) {
                        this.chain.add(new Step(Kind.BREAK_FINAL, sx, sy, sz, sx, sz, 0, 0, NO_LEDGE, d));
                        this.landX = sx;
                        this.landY = sy - d;
                        this.landZ = sz;
                        return true;
                    }
                    if (this.w.solid(sx, sy - d - 1, sz) || isLedge(sx, sy - d - 1, sz)) {
                        break;
                    }
                }
                // новий виступ нижче в тій самій колонці
                for (int s = this.p.stage(); s >= 1; s--) {
                    int ly = sy - s - 1;
                    boolean clear = true;
                    for (int y = sy - s; y < sy; y++) {
                        if (!freeV(sx, y, sz)) {
                            clear = false;
                            break;
                        }
                    }
                    if (!clear || !this.w.placeable(sx, ly, sz) || isLedge(sx, ly, sz) || !wallSupport(sx, ly, sz)) {
                        continue;
                    }
                    long ledgeKey = key(sx, ly, sz);
                    this.ledges.add(ledgeKey);
                    this.chain.add(new Step(Kind.BREAK_STEP, sx, sy, sz, sx, sz, 0, 0, ly, s));
                    if (solve(sx, sy - s, sz, true, depth + 1)) {
                        return true;
                    }
                    this.chain.remove(this.chain.size() - 1);
                    this.ledges.remove(ledgeKey);
                }
                return false;
            } finally {
                // відкат: при невдачі виступ під ногами знову на місці (при успіху ланцюжок уже зібрано, а стан не потрібен)
                this.ledges.add(under);
            }
        }
    }
}
