package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.BoxSource;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry.Box;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeWalk.BodyDims;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeWalk.Spot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Стрибок між НЕСТАНДАРТНИМИ опорами (плита, край відкритого люка, горщик, свічка, блоки з модів): куди
 * саме відриватись, куди саме приземлятись і чи долетить моб, якщо навколо нерегулярна форма колізії.
 * Без Minecraft-типів (як і {@code ShapeWalk}) - тільки {@link BoxSource}, тож усе перевіряється окремо
 * від гри.
 * <p>
 * <b>Що було не так раніше.</b> Весь стрибок цілився в ЦЕНТР клітинки на ЦІЛІЙ висоті вузла, а край
 * опори вважався рівно за 0.5 від центру. Для повного блока це правда. Для всього іншого - ні:
 * <ul>
 *   <li>плита / килим / пісок душ: поверхня НЕ на цілій висоті, тож {@code heightAbove} і кількість
 *       тіків польоту хибили до 0.5 блока;</li>
 *   <li>край відкритого люка, тонкий стовпчик: опора вузька й може бути збоку від центру клітинки.
 *       Стрибок у центр приземляв моба у порожнечу, а правило відриву (центр на 0.5 від центру клітинки)
 *       спрацьовувало вже ПІСЛЯ того, як хітбокс зійшов з опори;</li>
 *   <li>крок за тік обмежувався радіусом ПОВНОГО блока ({@code 0.5 + halfWidth}), а на вузьку опору
 *       хітбокс на тіку торкання ще не встигає потрапити.</li>
 * </ul>
 * <b>Як тепер.</b> {@link #choose} із придатних точок стояння ({@code ShapeWalk.candidates}) відриву й
 * приземлення вибирає пару, для якої {@link #simulate} (ванільна фізика польоту + КОЛІЗІЯ хітбокса з
 * реальними коробками) доводить: моб долітає, нічого не зачіпає по дорозі й приземляється саме на ЦЮ
 * поверхню. Із пари виходить {@link Plan}: точка відриву, точка приземлення з РЕАЛЬНОЮ висотою, межі
 * опори вздовж стрибка (де хітбокс ще стоїть, а де вже ні) і радіус опори приземлення.
 * <p>
 * Для повного блока всі числа плану збігаються зі старими ({@code frontBorder}, {@code 0.5 + halfWidth},
 * {@code (0.5 + halfWidth) * (|dirX| + |dirZ|)}) - див. тести.
 */
public final class ShapeJump {

    /**
     * Ванільний onGround лишається, якщо земля не глибше за цю відстань нижче ніг (1 тік падіння ~ 0.078).
     */
    public static final double GROUND_TOLERANCE = 0.08;

    /**
     * Висота, на якій ще вважається «та сама поверхня» (допуск порівняння коробок, а не фізики).
     */
    static final double SAME_SURFACE_TOL = 1.0E-3;
    /**
     * Додатковий запас довжини плану польоту понад відстань сегмента (як {@code MAX_LAUNCH_EXTRA_BLOCKS} у Goal).
     */
    public static final double PLAN_EXTRA_BLOCKS = 2.0;
    static final double TOP_EPS = 1.0E-5;
    /** Допуск ванільної колізії по перпендикулярних осях ({@code Shapes.collide}). */
    static final double COLLISION_EPS = 1.0E-7;
    /** Нижня межа кроку за тік, щоб вузька опора не дала нульового/від'ємного ліміту. */
    static final double MIN_LANDING_STEP = 0.06;
    /**
     * Опора приземлення вужча за це (хітбокс на тіку торкання перекриває її менш ніж на ~0.1 блока) - на неї
     * не приземлюємось: точність контуру (1-2 соті блока) тут уже порівнянна з самою опорою.
     */
    static final double MIN_LANDING_REACH = 0.10;
    /**
     * Менший за це політ - не стрибок: центр моба на всьому шляху до точки приземлення лишається над опорою тієї
     * самої висоти (опори з'єднані), тобто туди просто йдуть ногами. Такий "стрибок" відхиляється.
     */
    static final double MIN_FLIGHT = 0.30;
    /**
     * Якщо опора ВПЕРЕД від точки приземлення (хітбокс ще лежить на ній) коротша за це - приземлення «вузьке»:
     * після нього мобові горизонтальну швидкість гасимо, інакше інерція зсуне його з опори.
     * Для повного блока вона 0.5 + halfWidth (0.8 для зомбі), тож там гальмо не вмикається.
     */
    static final double TIGHT_FORWARD_REACH = 0.70;
    /**
     * Приземлення зараховується, лише якщо моб опинився так близько до цілі (інакше сів деінде - напр. на ту ж опору відриву).
     */
    static final double LANDING_TARGET_TOLERANCE = 0.35;
    private static final int MAX_SIM_TICKS = 80;
    /** Не менше стількох блоків між «центр над краєм» і «хітбокс зійшов», щоб бути певними у відриві. */
    private static final double MIN_LATERAL_LIMIT = 0.15;
    /**
     * Скільки найякісніших точок брати на етапі 1.
     */
    private static final int FIRST_STAGE_FROM = 2;

    // =====================================================================================
    // ДАНІ
    // =====================================================================================
    private static final int FIRST_STAGE_TO = 3;

    private ShapeJump() {
    }

    /** До {@code n} найякісніших точок: центр клітинки, потім більше покриття. Порядок стабільний. */
    private static List<Spot> best(List<Spot> spots, int n) {
        if (spots.size() <= n) {
            return spots;
        }
        List<Spot> sorted = new ArrayList<>(spots);
        sorted.sort(Comparator.comparingDouble(s -> (s.center() ? 0.0 : 0.3) + (1.0 - Math.min(1.0, s.coverage())) * 0.1));
        return new ArrayList<>(sorted.subList(0, n));
    }

    /** Коробки навколо ВСІХ точок відриву й приземлення (з запасом на ширину моба й бічні межі опор). */
    private static List<Box> collectRegion(BoxSource src, BodyDims d, List<Spot> from, List<Spot> to) {
        double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (List<Spot> list : List.of(from, to)) {
            for (Spot s : list) {
                minX = Math.min(minX, s.x());
                maxX = Math.max(maxX, s.x());
                minZ = Math.min(minZ, s.z());
                maxZ = Math.max(maxZ, s.z());
                minY = Math.min(minY, s.surfaceY());
                maxY = Math.max(maxY, s.surfaceY());
            }
        }
        double m = d.width() * 0.5 + 1.0;
        List<Box> boxes = new ArrayList<>();
        src.collect(minX - m, minY - 1.5, minZ - m, maxX + m, maxY + d.height() + 2.0, maxZ + m, boxes);
        return boxes;
    }

    /** Центр клітинки, повне покриття, цілий рівень - тобто звичайний блок. */
    static boolean isPlain(Spot s) {
        return s.center() && s.coverage() >= 0.999 && Math.abs(s.surfaceY() - Math.rint(s.surfaceY())) < 1.0E-6;
    }

    // =====================================================================================
    // ВИБІР ПАРИ ТОЧОК
    // =====================================================================================

    /**
     * Найкращий план стрибка з однієї з точок {@code from} в одну з {@code to}, або {@code null}, якщо
     * жодна пара не проходить. Порядок і критерії детерміновані: граф і виконавець, викликаючи це з
     * однаковими даними, отримують ОДНУ Й ТУ САМУ пару.
     */
    public static Plan choose(BoxSource src, BodyDims dims, List<Spot> from, List<Spot> to,
                              Physics ph, RangeModel range) {
        return choose(src, dims, from, to, Model.of(ph, range));
    }

    public static Plan choose(BoxSource src, BodyDims dims, List<Spot> from, List<Spot> to, Model model) {
        if (from.isEmpty() || to.isEmpty()) {
            return null;
        }
        // Коробки світу збираємо ОДИН раз на весь виклик (а не на кожну пару точок): на тонкій опорі в клітинці до
        // 9 точок стояння, тобто до 81 пар, і саме збір коробок з CachedSource був найдорожчим у парі.
        List<Box> boxes = collectRegion(src, dims, from, to);
        RectCache rects = new RectCache(boxes);

        // Етап 1: лише найякісніші точки (центр клітинки, ширше покриття). Майже завжди придатна одна з них.
        // Етап 2 (лише якщо етап 1 нічого не дав): усі решта пар. Множина придатних стрибків та сама, що й при
        // повному переборі; різниться лише вибір ПОМІЖ придатними - перевага якісним точкам.
        List<Spot> fromTop = best(from, FIRST_STAGE_FROM);
        List<Spot> toTop = best(to, FIRST_STAGE_TO);
        Plan plan = evaluate(boxes, rects, dims, fromTop, toTop, model, null);
        if (plan != null || (fromTop.size() == from.size() && toTop.size() == to.size())) {
            return plan;
        }
        return evaluate(boxes, rects, dims, from, to, model, new FirstStage(fromTop, toTop));
    }

    /** Прямокутники {@code {minX, minZ, maxX, maxZ}} верхніх граней коробок із {@code maxY} у {@code [lo, hi]}. */
    static List<double[]> rects(List<Box> boxes, double lo, double hi) {
        List<double[]> out = new ArrayList<>();
        for (Box b : boxes) {
            if (b.maxY() >= lo && b.maxY() <= hi) {
                out.add(new double[]{b.minX(), b.minZ(), b.maxX(), b.maxZ()});
            }
        }
        return out;
    }

    /** Те саме, з покроковим журналом у {@code trace} (може бути {@code null}). */
    public static Sim simulate(List<Box> boxes, Plan plan, BodyDims d, Physics ph, double t0, StringBuilder trace) {
        double hw = d.width() * 0.5;
        double h = d.height();
        double x = plan.takeoffX() + plan.dirX() * t0;
        double z = plan.takeoffZ() + plan.dirZ() * t0;
        double y = plan.takeoffY();
        double maxStep = plan.maxStep();
        double segLen = plan.length();
        double planCap = segLen + PLAN_EXTRA_BLOCKS;

        // Точка відриву має бути ВІЛЬНОЮ: якщо хітбокс на ній уже всередині чогось (стіна впритул за краєм
        // опори), моб до неї фізично не дійде - ванільна колізія зупинить його раніше. Ванільний рух такі
        // коробки не рахує перешкодою (він уже «всередині»), тож без цієї перевірки стіна пройшла б непоміченою.
        double[] start = {x - hw, y, z - hw, x + hw, y + h, z + hw};
        if (overlapsAny(boxes, start)) {
            return Sim.fail("точка відриву всередині блока (стіна впритул за краєм)", 0, x, y, z);
        }

        double vx = 0.0;
        double vy = 0.0;
        double vz = 0.0;
        boolean launchPending = true;   // перший тік (і всі, поки моб ще на землі) - команда відриву
        for (int tick = 1; tick <= MAX_SIM_TICKS; tick++) {
            if (launchPending) {
                double ddx = plan.landX() - x;
                double ddz = plan.landZ() - z;
                double dist = Math.sqrt(ddx * ddx + ddz * ddz);
                if (dist < 1.0E-6) {
                    return Sim.fail("вироджений політ", tick, x, y, z);
                }
                double deltaY = GapJumpPhysics.quantizeDeltaY(plan.landY() - y);
                boolean noJump = deltaY < 0.0
                        && ph.runSpeed() * GapJumpPhysics.flightSum(deltaY, ph.retention(), 0.0) >= dist;
                int airtime = GapJumpPhysics.naturalAirtime(deltaY, noJump ? 0.0 : ph.jumpFactor());
                if (airtime <= 0) {
                    return Sim.fail("висота недосяжна (Δy=" + deltaY + ")", tick, x, y, z);
                }
                double perTick = Math.min(dist, planCap) / airtime;
                vx = ddx / dist * perTick;
                vz = ddz / dist * perTick;
                vy = noJump ? (-GapJumpPhysics.GRAVITY_PER_TICK * GapJumpPhysics.VERTICAL_DRAG_PER_TICK)
                        : GapJumpPhysics.JUMP_VERTICAL_VELOCITY * ph.jumpFactor();
                launchPending = false;
            }

            double[] box = {x - hw, y, z - hw, x + hw, y + h, z + hw};
            double ry = collide(boxes, box, 1, vy);
            box[1] += ry;
            box[4] += ry;
            double rx = vx;
            double rz = vz;
            if (Math.abs(vx) < Math.abs(vz)) {
                rz = collide(boxes, box, 2, vz);
                box[2] += rz;
                box[5] += rz;
                rx = collide(boxes, box, 0, vx);
                box[0] += rx;
                box[3] += rx;
            } else {
                rx = collide(boxes, box, 0, vx);
                box[0] += rx;
                box[3] += rx;
                rz = collide(boxes, box, 2, vz);
                box[2] += rz;
                box[5] += rz;
            }
            boolean hitY = Math.abs(ry - vy) > 1.0E-9;
            boolean hitSide = Math.abs(rx - vx) > 1.0E-9 || Math.abs(rz - vz) > 1.0E-9;
            x = (box[0] + box[3]) * 0.5;
            z = (box[2] + box[5]) * 0.5;
            y = box[1];
            if (trace != null) {
                trace.append(String.format("  тік %2d: x=%.3f y=%.3f z=%.3f  v=(%.3f, %.3f, %.3f)%s%s%n",
                        tick, x, y, z, vx, vy, vz, hitY ? " [Y-колізія]" : "", hitSide ? " [бокова колізія]" : ""));
            }

            if (hitY && vy < 0.0) {
                boolean atLandLevel = Math.abs(y - plan.landY()) <= 0.02;
                boolean nearTarget = Math.hypot(x - plan.landX(), z - plan.landZ()) <= LANDING_TARGET_TOLERANCE;
                if (atLandLevel && nearTarget && tick > 1) {
                    if (supported(boxes, x, z, hw, plan.landY())) {
                        return new Sim(true, "ok", tick, x, y, z);
                    }
                    return Sim.fail("приземлився без опори", tick, x, y, z);
                }
                if (Math.abs(y - plan.takeoffY()) <= 0.02) {
                    // Ще на опорі відриву. Так буває і на першому тіку, і довше при збігу вниз без стрибка: хітбокс
                    // ще кілька тіків перекриває край опори, поки горизонталь його не знесе. Goal у такі тіки
                    // віддає команду відриву знову (від поточної позиції) - те саме й тут.
                    launchPending = true;
                    continue;
                }
                return Sim.fail("приземлився на чужу поверхню y=" + y, tick, x, y, z);
            }
            if (hitY) {
                return Sim.fail("вдарився головою", tick, x, y, z);
            }
            if (hitSide) {
                return Sim.fail("вдарився об стіну/виступ", tick, x, y, z);
            }

            double storedVy = (vy - GapJumpPhysics.GRAVITY_PER_TICK) * GapJumpPhysics.VERTICAL_DRAG_PER_TICK;
            GapJumpPhysics.FlightCommand c = GapJumpPhysics.flightCommand(
                    x, y, z, storedVy, plan.landX(), plan.landY(), plan.landZ(), planCap, maxStep, true);
            if (c == null) {
                return Sim.fail("рівня приземлення не досягти", tick, x, y, z);
            }
            vx = c.vx();
            vy = c.vy();
            vz = c.vz();
        }
        return Sim.fail("політ не завершився", MAX_SIM_TICKS, x, y, z);
    }

    /** Чи перетинає хітбокс {@code a = {minX, minY, minZ, maxX, maxY, maxZ}} бодай одну коробку (дотик не рахується). */
    static boolean overlapsAny(List<Box> boxes, double[] a) {
        for (Box b : boxes) {
            if (a[3] > b.minX() + COLLISION_EPS && a[0] < b.maxX() - COLLISION_EPS
                    && a[4] > b.minY() + COLLISION_EPS && a[1] < b.maxY() - COLLISION_EPS
                    && a[5] > b.minZ() + COLLISION_EPS && a[2] < b.maxZ() - COLLISION_EPS) {
                return true;
            }
        }
        return false;
    }

    /** Чи перекриває хітбокс (півширина {@code hw}) бодай одну коробку з верхом на висоті {@code topY}. */
    static boolean supported(List<Box> boxes, double x, double z, double hw, double topY) {
        for (Box b : boxes) {
            if (Math.abs(b.maxY() - topY) <= SAME_SURFACE_TOL
                    && b.maxX() > x - hw + COLLISION_EPS && b.minX() < x + hw - COLLISION_EPS
                    && b.maxZ() > z - hw + COLLISION_EPS && b.minZ() < z + hw - COLLISION_EPS) {
                return true;
            }
        }
        return false;
    }

    private static Plan evaluate(List<Box> boxes, RectCache rects, BodyDims dims, List<Spot> from, List<Spot> to,
                                 Model model, FirstStage skip) {
        List<Candidate> list = new ArrayList<>();
        for (Spot a : from) {
            RangeModel range = null;
            for (Spot b : to) {
                if (skip != null && skip.covers(a, b)) {
                    continue;
                }
                double dx = b.x() - a.x();
                double dz = b.z() - a.z();
                if (dx * dx + dz * dz < 1.0E-6) {
                    continue;
                }
                Plan plan = makePlan(rects, dims, a, b);
                if (plan == null || plan.landReach() < MIN_LANDING_REACH || plan.flight() < MIN_FLIGHT) {
                    continue;
                }
                if (range == null) {
                    range = model.range(a);
                }
                double limit = range.maxFlight(plan.deltaY());
                if (!(limit > 0.0) || plan.flight() > limit + 1.0E-9) {
                    continue; // недосяжно за дальністю/висотою
                }
                list.add(new Candidate(plan, a, score(plan, a, b)));
            }
        }
        list.sort(Comparator.comparingDouble(Candidate::score));
        for (Candidate c : list) {
            if (flightWorks(boxes, c.plan, dims, model.physics(c.takeoff))) {
                return c.plan;
            }
        }
        return null;
    }

    /** Короткий опис плану для логів. */
    public static String describe(Plan p) {
        return String.format("відрив=(%.3f, %.3f, %.3f) приземлення=(%.3f, %.3f, %.3f) довжина=%.3f край=%.3f зійде=%.3f "
                        + "вбік=%.3f радіусОпориПриземлення=%.3f%s%s",
                p.takeoffX(), p.takeoffY(), p.takeoffZ(), p.landX(), p.landY(), p.landZ(), p.length(),
                p.frontAlong(), p.loseAlong(), p.lateralLimit(), p.landReach(), p.tightLanding() ? " ВУЗЬКЕ" : "",
                p.plain() ? " (звичайні блоки)" : "");
    }

    /**
     * Менше - краще. Надійність важливіша за довжину польоту: будь-який політ у межах дальності долітає, а от на
     * кутову точку тонкої опори (хітбокс ледь її перекриває) вистачає одного промаху в соту блока. Тому: вужчий
     * радіус опори приземлення штрафується до рівня повного блока (0.5 + halfWidth = 0.8), точки не в центрі
     * клітинки - теж, і лише потім враховується довжина.
     */
    private static double score(Plan p, Spot a, Spot b) {
        double s = 0.5 * p.flight();
        s += Math.max(0.0, 0.8 - p.landReach());
        s += a.center() ? 0.0 : 0.3;
        s += b.center() ? 0.0 : 0.3;
        s += (1.0 - Math.min(1.0, a.coverage())) * 0.1 + (1.0 - Math.min(1.0, b.coverage())) * 0.1;
        return s;
    }

    /**
     * Політ придатний, якщо проходить симуляцію і при відриві рівно на краю поверхні, і трохи пізніше (моб
     * стрибає на першому ж тіку, коли центр за краєм, а це ще до одного кроку за нього).
     */
    private static boolean flightWorks(List<Box> boxes, Plan plan, BodyDims dims, Physics ph) {
        double t0 = plan.frontAlong();
        if (!simulate(boxes, plan, dims, ph, t0).ok()) {
            return false;
        }
        double late = Math.min(plan.frontAlong() + 0.3, plan.loseAlong() - 0.05);
        if (late > plan.frontAlong() + 0.05) {
            return simulate(boxes, plan, dims, ph, late).ok();
        }
        return true;
    }

    /**
     * Скільки максимум пролетить моб по горизонталі (від краю опори до центру приземлення) при заданому Δy.
     * Від'ємне значення - з цього Δy стрибок недосяжний.
     */
    public interface RangeModel {
        double maxFlight(double deltaY);
    }

    /**
     * Фізика й дальність залежать від того, З ЯКОЇ точки відриваємось (який блок під нею: лід, мед, пісок душ...).
     */
    public interface Model {
        static Model of(Physics ph, RangeModel range) {
            return new Model() {
                @Override
                public Physics physics(Spot takeoff) {
                    return ph;
                }

                @Override
                public RangeModel range(Spot takeoff) {
                    return range;
                }
            };
        }

        Physics physics(Spot takeoff);

        RangeModel range(Spot takeoff);
    }

    // =====================================================================================
    // ГЕОМЕТРІЯ ОПОР УЗДОВЖ СТРИБКА
    // =====================================================================================

    /**
     * Будує {@link Plan} за двома точками стояння й коробками навколо (геометрія, без перевірки польоту).
     */
    static Plan makePlan(List<Box> boxes, BodyDims d, Spot sT, Spot sL) {
        return makePlan(new RectCache(boxes), d, sT, sL);
    }

    private static Plan makePlan(RectCache cache, BodyDims d, Spot sT, Spot sL) {
        double dx = sL.x() - sT.x();
        double dz = sL.z() - sT.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-6) {
            return null;
        }
        double ux = dx / len;
        double uz = dz / len;
        double hw = d.width() * 0.5;
        double top = sT.surfaceY();
        double landTop = sL.surfaceY();

        List<double[]> sameSurface = cache.same(top);
        List<double[]> ground = cache.ground(top);

        double lose = rayExit(ground, sT.x(), sT.z(), ux, uz, hw);
        double front;
        if (insideUnion(sameSurface, sT.x(), sT.z())) {
            front = rayExit(sameSurface, sT.x(), sT.z(), ux, uz, 0.0);
        } else {
            front = Math.max(0.0, lose - hw); // центр ніколи не над поверхнею (притиснутий до стінки) - стрибаємо, коли хітбокс от-от зійде
        }
        front = Math.min(front, lose);

        double latPos = rayExit(ground, sT.x(), sT.z(), -uz, ux, hw);
        double latNeg = rayExit(ground, sT.x(), sT.z(), uz, -ux, hw);
        double lateral = Math.max(MIN_LATERAL_LIMIT, Math.min(latPos, latNeg));

        List<double[]> landSurface = cache.same(landTop);
        double reach = rayExit(landSurface, sL.x(), sL.z(), -ux, -uz, hw);
        double forward = rayExit(landSurface, sL.x(), sL.z(), ux, uz, hw);
        boolean tight = forward < TIGHT_FORWARD_REACH;

        boolean plain = isPlain(sT) && isPlain(sL);
        return new Plan(sT.x(), sT.z(), top, sL.x(), sL.z(), landTop, ux, uz, len, front, lose, lateral, reach, tight, plain);
    }

    /**
     * Готовий план стрибка. Координати світові; {@code dir} - одиничний напрямок від точки відриву до точки
     * приземлення; усі «вздовж» - уздовж {@code dir}, відлік від точки відриву.
     *
     * @param frontAlong   «центр моба над краєм поверхні»: тут стрибають (для повного блока 0.5 / max(|dirX|,|dirZ|))
     * @param loseAlong    хітбокс повністю зійшов з опори: далі onGround=false (для повного блока frontAlong + halfWidth)
     * @param lateralLimit наскільки вбік від осі моб ще стоїть на опорі відриву
     * @param landReach    наскільки ПЕРЕД точкою приземлення (проти {@code dir}) хітбокс ще перекриває опору
     * @param tightLanding опора приземлення вузька: після нього треба гасити інерцію
     * @param plain        обидві точки - центр ПОВНОГО покриття на ЦІЛІЙ висоті (звичайний блок): тут усі числа
     *                     плану збігаються зі старими формулами, тож виконавець може лишатись на старому коді
     */
    public record Plan(double takeoffX, double takeoffZ, double takeoffY,
                       double landX, double landZ, double landY,
                       double dirX, double dirZ, double length,
                       double frontAlong, double loseAlong, double lateralLimit,
                       double landReach, boolean tightLanding, boolean plain) {

        /**
         * Горизонтальна відстань польоту: від точки, де центр моба сходить з поверхні, до центру приземлення.
         */
        public double flight() {
            return length - frontAlong;
        }

        /**
         * Δy стрибка: висота поверхні приземлення мінус висота поверхні відриву (кратна 1/256).
         */
        public double deltaY() {
            return GapJumpPhysics.quantizeDeltaY(landY - takeoffY);
        }

        /**
         * Найбільший горизонтальний крок за тік, при якому хітбокс на тіку торкання ще над опорою приземлення.
         */
        public double maxStep() {
            return Math.max(MIN_LANDING_STEP, landReach - GapJumpPhysics.LANDING_STEP_MARGIN);
        }
    }

    /**
     * Фізика конкретного відриву (залежить від блока під ногами й від моба).
     *
     * @param jumpFactor ванільний множник стрибка блока (мед 0.5, інакше 1.0)
     * @param retention  тертя блока відриву * 0.91 (для рішення «стрибати чи просто збігти вниз»)
     * @param runSpeed   швидкість-уставка бігу моба ({@code GapJumpUtils.runSpeedSetpoint})
     */
    public record Physics(double jumpFactor, double retention, double runSpeed) {
    }

    static boolean insideUnion(List<double[]> rects, double x, double z) {
        for (double[] r : rects) {
            if (x >= r[0] - 1.0E-9 && x <= r[2] + 1.0E-9 && z >= r[1] - 1.0E-9 && z <= r[3] + 1.0E-9) {
                return true;
            }
        }
        return false;
    }

    /**
     * Скільки можна пройти від {@code (ox, oz)} уздовж одиничного {@code (dx, dz)}, лишаючись в об'єднанні
     * прямокутників, роздутих на {@code grow} (відкрита множина: дотик краєм не рахується). Це «центр хітбокса
     * (півширина {@code grow}) іще перекриває опору». 0, якщо вже на старті опори нема.
     * Суміжні прямокутники (дотичні межами) вважаються однією безперервною опорою.
     */
    static double rayExit(List<double[]> rects, double ox, double oz, double dx, double dz, double grow) {
        int n = rects.size();
        double[] lo = new double[n];
        double[] hi = new double[n];
        int m = 0;
        for (double[] r : rects) {
            double x0 = r[0] - grow;
            double z0 = r[1] - grow;
            double x1 = r[2] + grow;
            double z1 = r[3] + grow;
            double tin = Double.NEGATIVE_INFINITY;
            double tout = Double.POSITIVE_INFINITY;
            if (Math.abs(dx) < 1.0E-12) {
                if (ox <= x0 + 1.0E-9 || ox >= x1 - 1.0E-9) {
                    continue;
                }
            } else {
                double a = (x0 - ox) / dx;
                double b = (x1 - ox) / dx;
                tin = Math.max(tin, Math.min(a, b));
                tout = Math.min(tout, Math.max(a, b));
            }
            if (Math.abs(dz) < 1.0E-12) {
                if (oz <= z0 + 1.0E-9 || oz >= z1 - 1.0E-9) {
                    continue;
                }
            } else {
                double a = (z0 - oz) / dz;
                double b = (z1 - oz) / dz;
                tin = Math.max(tin, Math.min(a, b));
                tout = Math.min(tout, Math.max(a, b));
            }
            if (tout - tin <= 1.0E-9 || tout <= 1.0E-9) {
                continue;
            }
            lo[m] = tin;
            hi[m] = tout;
            m++;
        }
        double end = 0.0;
        boolean started = false;
        for (int i = 0; i < m; i++) {
            if (lo[i] < 1.0E-9 && hi[i] > 1.0E-9) {
                end = Math.max(end, hi[i]);
                started = true;
            }
        }
        if (!started) {
            return 0.0;
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < m; i++) {
                if (lo[i] <= end + 1.0E-6 && hi[i] > end + 1.0E-9) {
                    end = hi[i];
                    changed = true;
                }
            }
        }
        return end;
    }

    // =====================================================================================
    // СИМУЛЯЦІЯ ПОЛЬОТУ
    // =====================================================================================

    /**
     * Прогін польоту так само, як його веде {@code GapJumpAssistGoal}: ванільний підйом {@code 0.42 * jumpFactor}
     * (або просто збіг вниз), рівномірний горизонтальний крок і щотіку замкнений контур
     * {@link GapJumpPhysics#flightCommand}. Хітбокс рухається за ванільним порядком осей (спершу Y, потім X/Z
     * залежно від того, яка швидкість більша) і зупиняється об КОЖНУ коробку. Успіх - тільки якщо моб
     * приземлився на поверхню висоти {@code plan.landY} і під ним є опора; будь-яке інше зіткнення (стіна,
     * стеля, чужа поверхня) - провал.
     *
     * @param t0 де вздовж стрибка (від точки відриву) моб відривається від землі
     */
    public static Sim simulate(List<Box> boxes, Plan plan, BodyDims d, Physics ph, double t0) {
        return simulate(boxes, plan, d, ph, t0, null);
    }

    /**
     * Результат симуляції одного польоту.
     */
    public record Sim(boolean ok, String reason, int ticks, double endX, double endY, double endZ) {
        static Sim fail(String why, int tick, double x, double y, double z) {
            return new Sim(false, why, tick, x, y, z);
        }
    }

    /**
     * Пари, уже перевірені на етапі 1 (етап 2 їх пропускає).
     */
    private record FirstStage(List<Spot> from, List<Spot> to) {
        boolean covers(Spot a, Spot b) {
            return this.from.contains(a) && this.to.contains(b);
        }
    }

    private record Candidate(Plan plan, Spot takeoff, double score) {
    }

    /**
     * Зсув хітбокса {@code a = {minX, minY, minZ, maxX, maxY, maxZ}} уздовж осі (0=X, 1=Y, 2=Z) на
     * {@code move}, обрізаний об коробки (ванільний {@code Shapes.collide}: дотик допустимий, перетин - ні).
     */
    static double collide(List<Box> boxes, double[] a, int axis, double move) {
        if (move == 0.0) {
            return 0.0;
        }
        double result = move;
        for (Box b : boxes) {
            if (axis != 0 && !(a[3] > b.minX() + COLLISION_EPS && a[0] < b.maxX() - COLLISION_EPS)) {
                continue;
            }
            if (axis != 1 && !(a[4] > b.minY() + COLLISION_EPS && a[1] < b.maxY() - COLLISION_EPS)) {
                continue;
            }
            if (axis != 2 && !(a[5] > b.minZ() + COLLISION_EPS && a[2] < b.maxZ() - COLLISION_EPS)) {
                continue;
            }
            double bMin = axis == 0 ? b.minX() : axis == 1 ? b.minY() : b.minZ();
            double bMax = axis == 0 ? b.maxX() : axis == 1 ? b.maxY() : b.maxZ();
            double aMin = a[axis];
            double aMax = a[axis + 3];
            if (result > 0.0) {
                if (bMin >= aMax - COLLISION_EPS) {
                    result = Math.min(result, Math.max(0.0, bMin - aMax));
                }
            } else if (bMax <= aMin + COLLISION_EPS) {
                result = Math.max(result, Math.min(0.0, bMax - aMin));
            }
        }
        return result;
    }

    /**
     * Прямокутники верхніх граней опор для заданої висоти - їх потрібно багато разів, а залежать вони лише від висоти.
     */
    private static final class RectCache {
        private final List<Box> boxes;
        private final java.util.HashMap<Long, List<double[]>> ground = new java.util.HashMap<>();
        private final java.util.HashMap<Long, List<double[]>> same = new java.util.HashMap<>();

        RectCache(List<Box> boxes) {
            this.boxes = boxes;
        }

        /**
         * Опора, на якій моб ще вважається на землі (до {@link #GROUND_TOLERANCE} нижче ніг).
         */
        List<double[]> ground(double top) {
            return this.ground.computeIfAbsent(Math.round(top * 1.0E5),
                    k -> rects(this.boxes, top - GROUND_TOLERANCE, top + TOP_EPS));
        }

        /**
         * Та сама поверхня (допуск лише на похибку порівняння).
         */
        List<double[]> same(double top) {
            return this.same.computeIfAbsent(Math.round(top * 1.0E5),
                    k -> rects(this.boxes, top - SAME_SURFACE_TOL, top + TOP_EPS));
        }
    }
}
