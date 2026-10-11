package com.example.examplemod.debug;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.BuildPathGoal;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.EnemyBreak_N_BuildUtils;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpAssistGoal;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpUtils;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.ShapeJump;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

/**
 * Збирає ПОВНИЙ знімок стану моба одним текстовим блоком: хто зараз керує (які цілі працюють), що в навігаторі,
 * який шлях і де в ньому стрибкові ділянки, які блоки навколо (з формою колізії), і - головне - ЧОМУ кожен
 * перехід у сусідню клітинку проходить або не проходить (причина відмови від {@link ShapeWalk#explainTraverse},
 * того самого коду, що рахує path-finder).
 * <p>
 * Усі секції незалежні: помилка в одній не ламає решту.
 */
public final class MobDebug {

    private static final Map<Mob, String> LAST_PATH_SIG = new WeakHashMap<>();

    // ------------------------------------------------------------------------------------------
    // Форматування
    // ------------------------------------------------------------------------------------------
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private MobDebug() {
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String v(Vec3 p) {
        return p == null ? "null" : "(" + f(p.x) + ", " + f(p.y) + ", " + f(p.z) + ")";
    }

    /**
     * {@code oak_trapdoor[facing=north,half=bottom,open=true,...]} - коротше за BlockState.toString().
     */
    public static String stateName(BlockState s) {
        String name = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        String full = s.toString();
        int i = full.indexOf('[');
        return i >= 0 ? name + full.substring(i) : name;
    }

    /**
     * Коробки колізії клітинки у координатах ВІДНОСНО клітинки: {@code [x0,y0,z0->x1,y1,z1]}.
     */
    public static String boxesRel(BlockGetter level, BlockPos pos) {
        List<ShapeGeometry.Box> boxes = ShapeProbe.boxesOf(level, pos);
        if (boxes.isEmpty()) {
            return "порожньо";
        }
        StringBuilder sb = new StringBuilder();
        for (ShapeGeometry.Box b : boxes) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('[').append(f2(b.minX() - pos.getX())).append(',').append(f2(b.minY() - pos.getY())).append(',')
                    .append(f2(b.minZ() - pos.getZ())).append("->").append(f2(b.maxX() - pos.getX())).append(',')
                    .append(f2(b.maxY() - pos.getY())).append(',').append(f2(b.maxZ() - pos.getZ())).append(']');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Опис шляху
    // ------------------------------------------------------------------------------------------

    /**
     * Один рядок про клітинку: стан, legacy isSolid(), blocksMotion(), форма.
     */
    public static String cellBrief(BlockGetter level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return pos.toShortString() + " " + stateName(s) + " isSolid=" + s.isSolid() + " blocksMotion=" + s.blocksMotion()
                + " форма=" + boxesRel(level, pos);
    }

    /**
     * Розбіжність "що думає будівельник" (legacy isSolid() блока під кроком) і "що є насправді" (покриття опори
     * по реальній формі). Для відкритих/закритих люків ці два можуть розходитись.
     */
    public static String supportReport(BlockGetter level, BlockPos step, double width) {
        BlockPos below = step.below();
        BlockState bs = level.getBlockState(below);
        ShapeGeometry.Support sup = ShapeProbe.floorSupport(level, step, ShapeProbe.centeredFootprint(step, width));
        ShapeProbe.StandSpot spot = ShapeProbe.resolveStandSpot(level, step, width);
        String spotText = spot == null ? "НЕМАЄ (ні центр, ні краї)"
                : "є, опора Y=" + f(spot.support().surfaceY()) + " покриття=" + f2(spot.support().coverage());
        return "під кроком " + below.toShortString() + " " + stateName(bs) + " isSolid()=" + bs.isSolid()
                + " blocksMotion=" + bs.blocksMotion() + " | РЕАЛЬНА опора в центрі кроку: покриття=" + f2(sup.coverage())
                + (sup.isPresent() ? " Y=" + f(sup.surfaceY()) : " (нема)") + " | місце стояння у кроці: " + spotText
                + " | у самому кроці " + stateName(level.getBlockState(step)) + " форма=" + boxesRel(level, step);
    }

    private static double hdist(Node a, Node b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Коротко: кількість вузлів, canReach, кінцевий вузол.
     */
    public static String pathBrief(Path path) {
        if (path == null) {
            return "null";
        }
        int n = path.getNodeCount();
        if (n == 0) {
            return "порожній(0 вузлів) canReach=" + path.canReach() + " next=" + path.getNextNodeIndex();
        }
        Node end = path.getNode(n - 1);
        return "вузлів=" + n + " next=" + path.getNextNodeIndex() + " canReach=" + path.canReach()
                + " done=" + path.isDone() + " кінець=(" + end.x + "," + end.y + "," + end.z + ")";
    }

    /**
     * Повний опис вузлів: {@code [i](x,y,z) ... =J=> ...}; {@code *} - "вузька" точка, {@code ->} - ребро-стрибок.
     */
    public static String pathFull(Path path, int maxNodes) {
        if (path == null) {
            return "null";
        }
        int n = path.getNodeCount();
        Object holder = path;
        boolean[] tight = holder instanceof ShapePathAccess acc ? acc.betterEnemies$tight() : null;
        Vec3[] spots = holder instanceof ShapePathAccess acc2 ? acc2.betterEnemies$spots() : null;
        StringBuilder sb = new StringBuilder(pathBrief(path)).append(" | refined=").append(spots != null).append('\n');
        int shown = 0;
        for (int i = 0; i < n && shown < maxNodes; i++, shown++) {
            Node nd = path.getNode(i);
            sb.append("      [").append(i).append("] (").append(nd.x).append(',').append(nd.y).append(',').append(nd.z).append(')');
            if (i == path.getNextNodeIndex()) {
                sb.append(" <-- next");
            }
            if (tight != null && i < tight.length && tight[i]) {
                sb.append(" *вузька*");
            }
            if (spots != null && i < spots.length && spots[i] != null) {
                sb.append(" точка=").append(v(spots[i]));
            }
            sb.append(" тип=").append(nd.type);
            if (i > 0) {
                Node prev = path.getNode(i - 1);
                double d = hdist(prev, nd);
                if (nd instanceof HopNode) {
                    sb.append("  <== ГОП з [").append(i - 1).append("] (стрибок на сусідню клітинку)");
                } else if (d > 1.5) {
                    sb.append("  <== СТРИБОК з [").append(i - 1).append("] відстань=").append(f2(d))
                            .append(" dy=").append(nd.y - prev.y);
                }
            }
            sb.append('\n');
        }
        if (n > shown) {
            sb.append("      ... ще ").append(n - shown).append(" вузлів\n");
        }
        return sb.toString().stripTrailing();
    }

    // ------------------------------------------------------------------------------------------
    // Знімок стану
    // ------------------------------------------------------------------------------------------

    /**
     * Друкує шлях, лише якщо він ЗМІНИВСЯ відносно останнього надрукованого для цього моба.
     */
    public static void logPathIfChanged(Mob mob, BlockPos target, Path path, int unreachableStreak) {
        int h = 17;
        int n = path == null ? -1 : path.getNodeCount();
        if (path != null) {
            for (int i = 0; i < n; i++) {
                Node nd = path.getNode(i);
                h = h * 31 + nd.x;
                h = h * 31 + nd.y;
                h = h * 31 + nd.z;
            }
        }
        String sig = target.asLong() + "|" + n + "|" + (path != null && path.canReach()) + "|" + h;
        String last = LAST_PATH_SIG.get(mob);
        if (sig.equals(last)) {
            return;
        }
        LAST_PATH_SIG.put(mob, sig);
        if (!DebugLog.throttle("path-new-" + mob.getId(), 200L)) {
            return;
        }
        DebugLog.log(DebugLog.Cat.PATH, mob, "НОВИЙ ШЛЯХ до " + target.toShortString() + " недосяжна_підряд=" + unreachableStreak
                + " | моб=" + v(mob.position()) + " onGround=" + mob.onGround() + "\n    " + pathFull(path, 40));
    }

    private static void section(StringBuilder sb, String title, Section s) {
        sb.append("  --- ").append(title).append('\n');
        try {
            s.run(sb);
        } catch (Throwable t) {
            sb.append("    !! помилка секції: ").append(t).append('\n');
        }
    }

    /**
     * Повний знімок. {@code trace} - "чорна скринька" останніх тіків від сторожа (може бути порожнім).
     */
    public static String snapshot(Mob mob, String reason, String trace) {
        StringBuilder sb = new StringBuilder(8192);
        Level level = mob.level();
        sb.append("[WATCHDOG] ┌──────── ЗНІМОК СТАНУ: ").append(reason).append(" ── ").append(DebugLog.tag(mob))
                .append(" tickCount=").append(mob.tickCount).append(" gameTime=").append(level.getGameTime()).append('\n');

        section(sb, "ТІЛО", s -> {
            Vec3 vel = mob.getDeltaMovement();
            s.append("    pos=").append(v(mob.position())).append(" блок=").append(mob.blockPosition().toShortString())
                    .append(" onGround=").append(mob.onGround()).append(" vel=").append(v(vel))
                    .append(" горизонт.швидкість=").append(f(vel.horizontalDistance()))
                    .append(" sprint=").append(mob.isSprinting()).append(" hp=").append(f2(mob.getHealth())).append('\n');
            s.append("    хітбокс=").append(f2(mob.getBbWidth())).append('x').append(f2(mob.getBbHeight()))
                    .append(" stepUp=").append(f2(mob.maxUpStep())).append(" maxFall=").append(mob.getMaxFallDistance())
                    .append(" yRot=").append(f2(mob.getYRot())).append('\n');
        });

        section(sb, "ПЕРЕСЛІДУВАННЯ", s -> {
            Vec3 chase = PursuitEnemyBehavior.getChasePosition(mob);
            Player tracked = PursuitEnemyBehavior.getTrackedPlayer(mob);
            s.append("    memoryChasing=").append(PursuitEnemyBehavior.isMemoryChasing(mob))
                    .append(" liveChasing=").append(PursuitEnemyBehavior.isLiveChasing(mob))
                    .append(" vanillaTarget=").append(mob.getTarget() == null ? "null" : mob.getTarget().getName().getString())
                    .append('\n');
            s.append("    chasePos=").append(v(chase));
            if (chase != null) {
                double dx = chase.x - mob.getX();
                double dz = chase.z - mob.getZ();
                s.append(" горизонт.відстань=").append(f(Math.sqrt(dx * dx + dz * dz))).append(" dy=").append(f(chase.y - mob.getY()));
            }
            s.append('\n');
            if (tracked != null) {
                s.append("    гравець ").append(tracked.getName().getString()).append(" pos=").append(v(tracked.position()))
                        .append(" onGround=").append(tracked.onGround()).append(" блок=")
                        .append(tracked.blockPosition().toShortString()).append('\n');
            }
        });

        section(sb, "ЦІЛІ (працюють позначені *; показано наші + всі, що працюють)", s -> {
            List<WrappedGoal> list = new ArrayList<>(mob.goalSelector.getAvailableGoals());
            list.sort(Comparator.comparingInt(WrappedGoal::getPriority));
            for (WrappedGoal wg : list) {
                Goal g = wg.getGoal();
                boolean running = wg.isRunning();
                boolean ours = g instanceof GapJumpAssistGoal || g instanceof BuildPathGoal
                        || g.getClass().getSimpleName().contains("Pursuit")
                        || g.getClass().getSimpleName().contains("Dig")
                        || g.getClass().getSimpleName().contains("Tower")
                        || g.getClass().getSimpleName().contains("ZombieGoal");
                if (!running && !ours) {
                    continue;
                }
                s.append(running ? "    * " : "      ").append('[').append(wg.getPriority()).append("] ")
                        .append(g.getClass().getSimpleName()).append(" flags=").append(wg.getFlags());
                if (g instanceof GapJumpAssistGoal gj) {
                    s.append("\n          ").append(gj.debugState());
                }
                if (g instanceof BuildPathGoal bg) {
                    s.append("\n          ").append(bg.debugState());
                }
                s.append('\n');
            }
            s.append("    GapJumpAssistGoal.isActive=").append(GapJumpAssistGoal.isActive(mob)).append('\n');
        });

        section(sb, "НАВІГАТОР І ШЛЯХ", s -> {
            PathNavigation nav = mob.getNavigation();
            Path path = nav.getPath();
            s.append("    клас=").append(nav.getClass().getSimpleName()).append(" isDone=").append(nav.isDone())
                    .append(" inProgress=").append(nav.isInProgress()).append(" isStuck=").append(nav.isStuck())
                    .append(" moveControl.wanted=(").append(f(mob.getMoveControl().getWantedX())).append(", ")
                    .append(f(mob.getMoveControl().getWantedY())).append(", ").append(f(mob.getMoveControl().getWantedZ()))
                    .append(") speedMod=").append(f(mob.getMoveControl().getSpeedModifier())).append('\n');
            s.append("    шлях: ").append(pathFull(path, 40)).append('\n');
        });

        section(sb, "СТРИБКОВИЙ СЕГМЕНТ У ШЛЯХУ (те, що бачить Pursuit/GapJumpAssist)", s -> {
            GapJumpUtils.GapJump seg = GapJumpUtils.findUpcomingJumpSegment(mob);
            if (seg == null) {
                s.append("    нема (findUpcomingJumpSegment=null)\n");
                return;
            }
            s.append("    edge=").append(seg.edge().toShortString()).append(" landing=").append(v(seg.landing()))
                    .append(" розрив=").append(seg.gapBlocks()).append(" exact=").append(seg.exact())
                    .append(" approachВузлів=").append(seg.approach() == null ? 0 : seg.approach().getNodeCount()).append('\n');
            if (seg.plan() != null) {
                s.append("    план: ").append(ShapeJump.describe(seg.plan())).append('\n');
            }
        });

        section(sb, "КЕШ ШЛЯХУ (EnemyBreak_N_BuildUtils)", s ->
                s.append("    ").append(EnemyBreak_N_BuildUtils.debugCacheInfo(mob)).append('\n'));

        section(sb, "КАРТА БЛОКІВ НАВКОЛО (x→схід, z↓південь; @ = моб; # повний куб, i нерегулярна форма, ~ рідина, . порожньо)", s ->
                appendBlockMap(s, mob));

        section(sb, "ПЕРЕХОДИ З ПОТОЧНОЇ ТОЧКИ В СУСІДНІ КЛІТИНКИ (точна причина від ShapeWalk.traverse)", s ->
                appendNeighborMoves(s, mob));

        section(sb, "ВИД БУДІВЕЛЬНИКА: що думає isSolid() і що є насправді", s -> {
            Vec3 chase = PursuitEnemyBehavior.getChasePosition(mob);
            BlockPos feet = mob.blockPosition();
            s.append("    під ногами: ").append(supportReport(level, feet, mob.getBbWidth())).append('\n');
            if (chase != null) {
                BlockPos step = EnemyBreak_N_BuildUtils.nextHorizontalStep(mob, BlockPos.containing(chase));
                s.append("    наступний крок до гравця ").append(step.toShortString()).append(": ")
                        .append(supportReport(level, step, mob.getBbWidth())).append('\n');
            }
        });

        if (trace != null && !trace.isEmpty()) {
            sb.append("  --- ЧОРНА СКРИНЬКА (останні тіки перед знімком; v=зсув за тік, G=земля, цілі: P=Pursuit J=GapJump B=Build D=Dig T=Tower o=інша)\n")
                    .append(trace);
        }
        GraphProbe.arm(mob);
        sb.append("  (GraphProbe озброєно: найближчий пошук шляху цього моба надрукує у [GRAPH] справжніх сусідів стартового вузла)\n");
        sb.append("[WATCHDOG] └──────── кінець знімка ").append(DebugLog.tag(mob));
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Карта блоків
    // ------------------------------------------------------------------------------------------

    private static char symbol(Level level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        VoxelShape shape = st.getCollisionShape(level, p);
        if (shape.isEmpty()) {
            return st.getFluidState().isEmpty() ? '.' : '~';
        }
        return shape == Shapes.block() ? '#' : 'i';
    }

    private static void appendBlockMap(StringBuilder s, Mob mob) {
        Level level = mob.level();
        BlockPos feet = mob.blockPosition();
        int r = 3;
        for (int dy = -2; dy <= 1; dy++) {
            s.append("    шар y").append(dy >= 0 ? "+" : "").append(dy).append(dy == -1 ? " (підлога)" : dy == 0 ? " (ноги)" : dy == 1 ? " (голова)" : "")
                    .append(":\n");
            for (int dz = -r; dz <= r; dz++) {
                s.append("      ");
                for (int dx = -r; dx <= r; dx++) {
                    BlockPos p = feet.offset(dx, dy, dz);
                    char c = (dx == 0 && dz == 0 && dy == 0) ? '@' : symbol(level, p);
                    s.append(c).append(' ');
                }
                s.append('\n');
            }
        }
        s.append("    нерегулярні клітинки (форма відносно клітинки):\n");
        int listed = 0;
        for (int dy = -2; dy <= 1 && listed < 40; dy++) {
            for (int dz = -r; dz <= r && listed < 40; dz++) {
                for (int dx = -r; dx <= r && listed < 40; dx++) {
                    BlockPos p = feet.offset(dx, dy, dz);
                    if (symbol(level, p) == 'i') {
                        s.append("      (").append(dx).append(',').append(dy).append(',').append(dz).append(") ")
                                .append(cellBrief(level, p)).append('\n');
                        listed++;
                    }
                }
            }
        }
        if (listed == 0) {
            s.append("      (нема)\n");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Переходи у сусідні клітинки з поясненням
    // ------------------------------------------------------------------------------------------

    private static String spotText(ShapeWalk.Spot sp) {
        return "(" + f2(sp.x()) + "," + f2(sp.z()) + ") Y=" + f(sp.surfaceY()) + " покр=" + f2(sp.coverage())
                + (sp.center() ? " центр" : " край");
    }

    private static void appendNeighborMoves(StringBuilder s, Mob mob) {
        Level level = mob.level();
        double width = mob.getBbWidth();
        ShapeWalk.BodyDims dims = new ShapeWalk.BodyDims(width, mob.getBbHeight(), Math.max(0.5, mob.maxUpStep()), 1.0,
                Math.max(1, mob.getMaxFallDistance()));
        int nw = Mth.floor(width + 1.0F);
        // УВАГА: evaluator будує джерело з предикатом ignorable (напр. ігнорує деякі стани); тут беремо "нічого не ігнорувати".
        ShapeProbe.CachedSource src = new ShapeProbe.CachedSource(level, st -> false);
        BlockPos feet = mob.blockPosition();
        ShapeWalk.Spot real = new ShapeWalk.Spot(mob.getX(), mob.getZ(), mob.getY(), 1.0, true, true);
        s.append("    старт: реальна точка моба ").append(spotText(real)).append('\n');
        List<ShapeWalk.Spot> here = ShapeWalk.candidatesAll(src, feet.getX(), feet.getY(), feet.getZ(), nw, dims);
        s.append("    місця стояння у поточній клітинці (candidatesAll): ");
        if (here.isEmpty()) {
            s.append("НЕМАЄ");
        }
        for (ShapeWalk.Spot sp : here) {
            s.append(spotText(sp)).append("; ");
        }
        s.append('\n');
        int[] levels = {0, 1, -1, -2};
        for (int[] d : DIRS) {
            s.append("    напрямок (").append(d[0]).append(',').append(d[1]).append("):");
            boolean any = false;
            for (int dy : levels) {
                List<ShapeWalk.Spot> to = ShapeWalk.candidates(src, feet.getX() + d[0], feet.getY() + dy, feet.getZ() + d[1], nw, dims);
                if (to.isEmpty()) {
                    continue;
                }
                any = true;
                for (ShapeWalk.Spot sp : to) {
                    s.append("\n        рівень ").append(dy >= 0 ? "+" : "").append(dy).append(" ").append(spotText(sp))
                            .append(" => ").append(ShapeWalk.explainTraverse(src, real, sp, dims));
                }
                break; // як і в evaluator-і: перший рівень, де є куди ставати
            }
            if (!any) {
                s.append(" немає місця стояння ні на рівнях +0/+1/-1/-2 (повітря/провалля/перешкода)");
            }
            s.append('\n');
        }
    }

    private interface Section {
        void run(StringBuilder sb) throws Exception;
    }
}
