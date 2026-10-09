package com.example.examplemod.Enemy.EnemyMovement.TerrainShape;

import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry.Box;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry.Footprint;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeWalk.BodyDims;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeWalk.Spot;
import com.example.examplemod.debug.GraphProbe;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.*;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link WalkNodeEvaluator}, що розуміє РЕАЛЬНУ форму колізії (плити, люки, паркани, килими, блоки
 * з інших модів) там, де вона нерегулярна, і лишає ванільну поведінку всюди, де світ складається з
 * порожнечі та повних кубів.
 * <p>
 * <b>Як це працює.</b>
 * <ol>
 *   <li>{@link #getNeighbors}: беремо ванільних сусідів. Для кожного з 8 напрямків дивимось, чи в
 *       колонці вузла або колонці цілі є «нерегулярні» клітинки (смуга {@code y-1 .. y+висота}). Якщо
 *       НІ — ванільний сусід лишається як є. Якщо ТАК — ванільного сусіда в цьому напрямку
 *       викидаємо і шукаємо свого через {@link ShapeWalk}: чи є де стати в цільовій клітинці (центр
 *       або край/кут) і чи можна ПРОЙТИ туди з поточної точки без провалу.</li>
 *   <li>{@link #refine}: після A* з готового ланцюжка вузлів динамічно вибираються точні точки
 *       стояння ({@link WaypointPlanner}); якщо хоч одна «вузька» (край/кут) — шлях обгортається в
 *       {@code Path} із {@link ShapePathAccess}-даними, які читають міксини руху.</li>
 * </ol>
 * Широкі й високі моби підтримуються: область вузла {@code nw×nw} клітинок, висота {@code nh}.
 */
public class ShapeAwareNodeEvaluator extends WalkNodeEvaluator {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int[][] DIRS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
    private static final Spot[] NO_SPOTS = new Spot[0];
    private static boolean warned;
    private final Long2ObjectOpenHashMap<Spot[]> spotsEarly = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Spot[]> spotsAll = new Long2ObjectOpenHashMap<>();
    private final Long2ByteOpenHashMap columnIrregular = new Long2ByteOpenHashMap();
    private final Map<MovePair, Boolean> moveCache = new HashMap<>();
    protected ShapeProbe.CachedSource source;
    protected BodyDims dims;
    protected int nw = 1;
    protected int nh = 2;
    private boolean active;
    private Node startNode;
    private Spot startSpot;
    /**
     * Копія контексту пошуку: {@code done()} обнуляє {@code currentContext}, а {@link #refine} іде вже після нього.
     */
    private PathfindingContext refineContext;

    private static void warnOnce(RuntimeException e) {
        if (!warned) {
            warned = true;
            LOGGER.error("ShapeAwareNodeEvaluator failed, falling back to vanilla neighbors for this search", e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Життєвий цикл
    // ------------------------------------------------------------------------------------------

    static void warnRefine(RuntimeException e) {
        warnOnce(e);
    }

    @Override
    public void prepare(PathNavigationRegion level, Mob mob) {
        super.prepare(level, mob);
        release();
        this.refineContext = this.currentContext;
        this.active = ShapeSettings.enabledFor(mob);
        // Геометрія й кеш готуються ЗАВЖДИ (GapJump-підклас користується ними навіть при вимкненій
        // форм-орієнтованій ходьбі); active керує лише підміною сусідів і уточненням шляху.
        float width = mob.getBbWidth();
        float height = mob.getBbHeight();
        this.nw = Mth.floor(width + 1.0F);
        this.nh = Mth.floor(height + 1.0F);
        this.dims = new BodyDims(width, height, Math.max(0.5, mob.maxUpStep()), 1.0,
                Math.max(1, mob.getMaxFallDistance()));
        this.source = new ShapeProbe.CachedSource(level, this::ignorable);
    }

    /**
     * Кеші живуть до {@link #release()}: {@link ShapePathFinder} викликає {@link #refine} вже ПІСЛЯ {@code done()}.
     */
    @Override
    public void done() {
        super.done();
    }

    public void release() {
        this.source = null;
        this.startNode = null;
        this.startSpot = null;
        this.refineContext = null;
        this.spotsEarly.clear();
        this.spotsAll.clear();
        this.columnIrregular.clear();
        this.moveCache.clear();
    }

    @Override
    public Node getStart() {
        Node start = super.getStart();
        this.startNode = start;
        if (this.active && start != null && this.mob != null) {
            this.startSpot = new Spot(this.mob.getX(), this.mob.getZ(), this.mob.getY(), 1.0, true, true);
        }
        return start;
    }

    // ------------------------------------------------------------------------------------------
    // Сусіди
    // ------------------------------------------------------------------------------------------

    /**
     * Зачинені дерев'яні двері, які цей моб відчиняє, колізією не рахуємо (як і ваніль).
     */
    private boolean ignorable(BlockState state) {
        return this.canOpenDoors && this.canPassDoors
                && state.is(BlockTags.WOODEN_DOORS)
                && state.hasProperty(DoorBlock.OPEN)
                && !state.getValue(DoorBlock.OPEN);
    }

    @Override
    public int getNeighbors(Node[] nodes, Node node) {
        int count = super.getNeighbors(nodes, node);
        boolean probe = isProbeStart(node);
        if (probe) {
            GraphProbe.logNodes(this.mob, "ВАНІЛЬНІ сусіди (до форм-орієнтованої підміни)", node, nodes, count);
        }
        if (!this.active || this.source == null || this.mob == null) {
            if (probe) {
                GraphProbe.logText(this.mob, "форм-орієнтований режим НЕ активний (active=" + this.active
                        + ", source=" + (this.source != null) + ") - лишається ванільна відповідь");
            }
            return count;
        }
        try {
            int result = shapeNeighbors(nodes, node, count);
            if (probe) {
                GraphProbe.logNodes(this.mob, "ПІСЛЯ форм-орієнтованої підміни", node, nodes, result);
            }
            return result;
        } catch (RuntimeException e) {
            warnOnce(e);
            return count;
        }
    }

    /**
     * ДЕБАГ: чи це стартовий вузол пошуку, на який озброєно {@link GraphProbe}.
     */
    protected final boolean isProbeStart(Node node) {
        return node == this.startNode && GraphProbe.armedFor(this.mob);
    }

    private int shapeNeighbors(Node[] nodes, Node node, int count) {
        if (isDeepFluid(node.x, node.y, node.z)) {
            return count; // глибока вода: нехай веде ваніль (плавання)
        }
        Spot[] from = null;
        for (int[] d : DIRS) {
            int dx = d[0];
            int dz = d[1];
            if (!zoneIrregular(node, dx, dz) || hasFluid(node.x + dx, node.y, node.z + dz)) {
                continue; // регулярна зона: відповідь ванілі лишається
            }
            count = removeDirection(nodes, count, node, dx, dz);
            if (from == null) {
                from = fromSpots(node);
            }
            Node found = findShapeNode(node, from, dx, dz);
            if (node == this.startNode && GraphProbe.armedFor(this.mob)) {
                GraphProbe.logText(this.mob, "напрямок (" + dx + "," + dz + ") - нерегулярна зона, ванільного сусіда видалено; "
                        + (found == null ? "форм-орієнтований пошук НЕ знайшов куди ступити"
                        : "знайдено (" + found.x + "," + found.y + "," + found.z + ")"));
            }
            if (found != null && count < nodes.length) {
                nodes[count++] = found;
            }
        }
        return count;
    }

    private int removeDirection(Node[] nodes, int count, Node node, int dx, int dz) {
        int w = 0;
        for (int i = 0; i < count; i++) {
            Node n = nodes[i];
            if (n.x - node.x == dx && n.z - node.z == dz) {
                continue;
            }
            nodes[w++] = n;
        }
        for (int i = w; i < count; i++) {
            nodes[i] = null;
        }
        return w;
    }

    /**
     * Звідки моб виходить: для старту — де він реально стоїть + все, куди з цієї клітинки можна пересунутись.
     */
    protected final Spot[] fromSpots(Node node) {
        Spot[] all = geoSpotsAll(node.x, node.y, node.z);
        if (node == this.startNode && this.startSpot != null) {
            Spot[] r = new Spot[all.length + 1];
            r[0] = this.startSpot;
            System.arraycopy(all, 0, r, 1, all.length);
            return r;
        }
        if (all.length == 0) {
            return new Spot[]{fallbackCenter(node.x, node.y, node.z)};
        }
        return all;
    }

    private Node findShapeNode(Node node, Spot[] from, int dx, int dz) {
        int tx = node.x + dx;
        int tz = node.z + dz;
        int maxFall = (int) Math.max(1, Math.floor(this.dims.maxFall()));
        boolean sawPlatform = false;
        for (int step = 0; step <= maxFall + 1; step++) {
            // порядок: той самий рівень, крок вгору, далі вниз
            int yy = step == 0 ? node.y : step == 1 ? node.y + 1 : node.y - (step - 1);
            if (yy < node.y && sawPlatform) {
                break; // над провалом є площадка, на яку не потрапити, — не «пірнаємо» під неї
            }
            Spot[] to = geoSpotsEarly(tx, yy, tz);
            if (to.length == 0) {
                continue;
            }
            if (!anyMove(from, to)) {
                if (yy >= node.y) {
                    sawPlatform = true;
                }
                continue;
            }
            Node n = this.getNode(tx, yy, tz);
            if (n.closed) {
                // Як ванільний isNeighborValid: уже оброблений вузол в A* повертати не можна — інакше його
                // cameFrom перезапишеться нащадком, утвориться цикл і reconstructPath зациклиться.
                return null;
            }
            if (!applyType(n, tx, yy, tz)) {
                return null;
            }
            if (yy < node.y) {
                n.costMalus = Math.max(n.costMalus, 0.5F * (node.y - yy));
            }
            return n;
        }
        return null;
    }

    protected final boolean anyMove(Spot[] from, Spot[] to) {
        for (Spot a : from) {
            for (Spot b : to) {
                if (movable(a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Тип вузла (небезпеки) — ваніль
    // ------------------------------------------------------------------------------------------

    private boolean movable(Spot a, Spot b) {
        if (!a.check() && !b.check()) {
            return true;
        }
        MovePair key = new MovePair(a, b);
        Boolean cached = moveCache.get(key);
        if (cached == null) {
            cached = ShapeWalk.traverse(this.source, a, b, this.dims);
            moveCache.put(key, cached);
        }
        return cached;
    }

    // ------------------------------------------------------------------------------------------
    // Кандидати стояння й «нерегулярність»
    // ------------------------------------------------------------------------------------------

    /**
     * Виставляє тип і штраф вузла за ВАНІЛЬНИМИ правилами небезпек (лава, вогонь, кактус, вода…) по всій
     * області тіла. Типи, що кажуть лише «тут твердо» ({@code BLOCKED}, {@code FENCE}, двері) пропускаємо:
     * про форму вже відповіла геометрія. {@code false} — тут стояти не можна (від'ємний штраф).
     */
    private boolean applyType(Node n, int x, int y, int z) {
        PathType worst = PathType.WALKABLE;
        float worstMalus = 0.0F;
        for (int i = 0; i < nw; i++) {
            for (int j = 0; j < nh; j++) {
                for (int k = 0; k < nw; k++) {
                    PathType t = this.getPathType(this.currentContext, x + i, y + j, z + k);
                    if (t == PathType.BLOCKED || t == PathType.FENCE || t == PathType.DOOR_WOOD_CLOSED
                            || t == PathType.DOOR_IRON_CLOSED || t == PathType.DOOR_OPEN) {
                        continue;
                    }
                    float m = this.mob.getPathfindingMalus(t);
                    if (m < 0.0F) {
                        return false;
                    }
                    if (m > worstMalus) {
                        worstMalus = m;
                        worst = t;
                    }
                }
            }
        }
        n.type = worst;
        n.costMalus = Math.max(n.costMalus, worstMalus);
        return true;
    }

    /**
     * Кандидати (центр, якщо він повністю на опорі, інакше всі придатні). Порожньо — стояти не можна.
     */
    protected final Spot[] geoSpotsEarly(int x, int y, int z) {
        return geoSpots(x, y, z, spotsEarly, true);
    }

    /**
     * Усі придатні позиції в клітинці — для вирівнювання.
     */
    protected final Spot[] geoSpotsAll(int x, int y, int z) {
        return geoSpots(x, y, z, spotsAll, false);
    }

    private Spot[] geoSpots(int x, int y, int z, Long2ObjectOpenHashMap<Spot[]> cache, boolean early) {
        long key = BlockPos.asLong(x, y, z);
        Spot[] cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<Spot> list = early
                ? ShapeWalk.candidates(this.source, x, y, z, nw, dims)
                : ShapeWalk.candidatesAll(this.source, x, y, z, nw, dims);
        boolean check = columnIsIrregular(x, y, z);
        List<Spot> filtered = new ArrayList<>(list.size());
        for (Spot s : list) {
            if (!floorForbidden(s)) {
                filtered.add(s.withCheck(check));
            }
        }
        Spot[] result = filtered.isEmpty() ? NO_SPOTS : filtered.toArray(new Spot[0]);
        cache.put(key, result);
        return result;
    }

    /**
     * Мобам без «ходьби по парканах» не можна ставати на паркан/стіну (ванільний FENCE).
     */
    private boolean floorForbidden(Spot s) {
        if (this.canWalkOverFences) {
            return false;
        }
        int cx = Mth.floor(s.x());
        int cz = Mth.floor(s.z());
        int cy = Mth.floor(s.surfaceY() - 0.01);
        PathfindingContext ctx = this.currentContext != null ? this.currentContext : this.refineContext;
        if (ctx == null) {
            return false;
        }
        return this.getPathType(ctx, cx, cy, cz) == PathType.FENCE;
    }

    private Spot fallbackCenter(int x, int y, int z) {
        return new Spot(x + nw / 2.0, z + nw / 2.0, y, 0.5, true, columnIsIrregular(x, y, z));
    }

    /**
     * Нерегулярна форма в смузі {@code y-1 .. y+nh} у колонці області вузла.
     */
    protected final boolean columnIsIrregular(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte known = columnIrregular.get(key);
        if (known != 0) {
            return known == 2;
        }
        boolean irr = false;
        outer:
        for (int i = 0; i < nw; i++) {
            for (int k = 0; k < nw; k++) {
                for (int yy = y - 1; yy <= y + nh; yy++) {
                    if (source.isIrregular(x + i, yy, z + k)) {
                        irr = true;
                        break outer;
                    }
                }
            }
        }
        columnIrregular.put(key, (byte) (irr ? 2 : 1));
        return irr;
    }

    private boolean zoneIrregular(Node node, int dx, int dz) {
        return columnIsIrregular(node.x, node.y, node.z) || columnIsIrregular(node.x + dx, node.y, node.z + dz);
    }

    private boolean hasFluid(int x, int y, int z) {
        BlockState s = stateAt(x, y, z);
        return s != null && !s.getFluidState().isEmpty();
    }

    private boolean isDeepFluid(int x, int y, int z) {
        return hasFluid(x, y, z) && hasFluid(x, y - 1, z);
    }

    // ------------------------------------------------------------------------------------------
    // Допоміжні запити для підкласів (GapJumpNodeEvaluator)
    // ------------------------------------------------------------------------------------------

    private BlockState stateAt(int x, int y, int z) {
        return this.currentContext != null ? this.currentContext.getBlockState(new BlockPos(x, y, z)) : null;
    }

    /**
     * Чи може моб стояти у вузлі {@code (x,y,z)} (за реальною формою).
     */
    protected final boolean isStandableCell(int x, int y, int z) {
        return this.source != null && geoSpotsEarly(x, y, z).length > 0;
    }

    /**
     * Тіло вільне від перешкод на рівні {@code y} (нічого вищого за крок підйому в смузі висоти моба над
     * центром клітинки) — відрізняє «провалля» від «стіни».
     */
    protected final boolean isBodyZoneClear(int x, int y, int z) {
        if (this.source == null) {
            return false;
        }
        Footprint fp = Footprint.centered(x + nw / 2.0, z + nw / 2.0, Math.max(0.01, dims.width() - 2 * ShapeWalk.SKIN));
        List<Box> boxes = new ArrayList<>(4);
        this.source.collect(fp.minX(), y, fp.minZ(), fp.maxX(), y + dims.height(), fp.maxZ(), boxes);
        for (Box b : boxes) {
            if (b.maxY() > y + dims.stepUp() + ShapeGeometry.EPS && b.minY() < y + dims.height() - ShapeGeometry.EPS
                    && b.overlapsXZ(fp)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // Уточнення готового шляху
    // ------------------------------------------------------------------------------------------

    /**
     * Клітинка {@code y} на траєкторії польоту/стрибка заблокована (виступ вищий за крок підйому).
     */
    protected final boolean blocksFlightAt(int x, int y, int z) {
        if (this.source == null) {
            return true; // світ недоступний — обережно вважаємо заблокованим
        }
        Footprint fp = Footprint.centered(x + nw / 2.0, z + nw / 2.0, Math.max(0.01, dims.width() - 2 * ShapeWalk.SKIN));
        List<Box> boxes = new ArrayList<>(4);
        this.source.collect(fp.minX(), y, fp.minZ(), fp.maxX(), y + 1.0, fp.maxZ(), boxes);
        for (Box b : boxes) {
            if (b.maxY() - y > dims.stepUp() + ShapeGeometry.EPS && b.overlapsXZ(fp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * З готового A*-шляху будує шлях із точними точками стояння. Повертає ТОЙ САМИЙ {@code path}, якщо
     * уточнювати нічого (немає «вузьких» точок) або щось пішло не так.
     */
    public Path refine(Path path) {
        if (!this.active || this.source == null || path == null) {
            return path;
        }
        int n = path.getNodeCount();
        if (n < 2) {
            return path;
        }
        boolean[] irr = new boolean[n];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            Node nd = path.getNode(i);
            irr[i] = columnIsIrregular(nd.x, nd.y, nd.z);
            any |= irr[i];
        }
        if (!any) {
            return path;
        }
        Spot[][] cands = new Spot[n][];
        boolean[] walk = new boolean[n - 1];
        for (int i = 0; i < n; i++) {
            Node nd = path.getNode(i);
            boolean near = irr[i] || (i > 0 && irr[i - 1]) || (i + 1 < n && irr[i + 1]);
            Spot[] base = near ? geoSpotsAll(nd.x, nd.y, nd.z) : geoSpotsEarly(nd.x, nd.y, nd.z);
            if (base.length == 0) {
                base = new Spot[]{fallbackCenter(nd.x, nd.y, nd.z)};
            }
            if (i == 0 && this.startSpot != null) {
                Spot[] withStart = new Spot[base.length + 1];
                withStart[0] = this.startSpot;
                System.arraycopy(base, 0, withStart, 1, base.length);
                base = withStart;
            }
            cands[i] = base;
            if (i + 1 < n) {
                Node next = path.getNode(i + 1);
                // ГОП (HopNode) - сусідня клітинка, але ребро НЕ ходьба: це стрибок через ділянку без опори.
                walk[i] = Math.abs(next.x - nd.x) <= 1 && Math.abs(next.z - nd.z) <= 1 && !(next instanceof HopNode);
            }
        }
        List<WaypointPlanner.Waypoint> plan = WaypointPlanner.plan(cands, walk, this::movable);
        if (plan == null) {
            return path;
        }
        boolean anyTight = plan.size() != n;
        for (WaypointPlanner.Waypoint w : plan) {
            anyTight |= !w.spot().center();
        }
        if (!anyTight) {
            return path;
        }
        List<Node> nodes = new ArrayList<>(plan.size());
        Vec3[] spots = new Vec3[plan.size()];
        boolean[] tight = new boolean[plan.size()];
        int lastIndex = -1;
        for (int i = 0; i < plan.size(); i++) {
            WaypointPlanner.Waypoint w = plan.get(i);
            Node orig = path.getNode(w.nodeIndex());
            Node use = orig;
            if (w.nodeIndex() == lastIndex) {
                use = new Node(orig.x, orig.y, orig.z);
                use.type = orig.type;
                use.costMalus = orig.costMalus;
            }
            lastIndex = w.nodeIndex();
            nodes.add(use);
            spots[i] = new Vec3(w.spot().x(), w.spot().surfaceY(), w.spot().z());
            tight[i] = !w.spot().center();
        }
        Path refined = new Path(nodes, path.getTarget(), path.canReach());
        Object holder = refined;
        if (holder instanceof ShapePathAccess access) {
            access.betterEnemies$setWaypoints(spots, tight);
            return refined;
        }
        return path; // міксин Path не застосований — лишаємо ванільний шлях
    }

    private record MovePair(Spot a, Spot b) {
    }
}