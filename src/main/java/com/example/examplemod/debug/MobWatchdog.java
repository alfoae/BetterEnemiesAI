package com.example.examplemod.debug;

import com.example.examplemod.BetterEnemiesAI;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.BuildPathGoal;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpAssistGoal;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * "Сторож": щотіку пише в кільцевий буфер (чорну скриньку) позицію й стан керування кожного зомбі, що гониться
 * за ціллю, і сам помічає два симптоми:
 * <ul>
 *   <li><b>ЗАВИСАННЯ</b> - моб на землі, ціль далеко (&gt; 2.5 бл.), а за останні 40 тіків він зрушив менше ніж на
 *       0.5 бл. сумарно;</li>
 *   <li><b>СМИКАННЯ вперед-назад</b> - за 60 тіків моб пройшов ≥ 3 бл. шляху, але чистий зсув &le; 45% шляху і
 *       напрямок руху розвернувся щонайменше двічі (часто зі стрибками).</li>
 * </ul>
 * На кожен симптом друкує повний знімок ({@link MobDebug#snapshot}) разом із чорною скринькою - тобто видно не лише
 * "що зараз", а й "що було за 6 секунд до цього" (хто кому віддавав керування, коли шлях ставав порожнім тощо).
 * Знімок можна зробити й вручну: {@code /bedebug dump}.
 */
@EventBusSubscriber(modid = BetterEnemiesAI.MODID)
public final class MobWatchdog {

    private static final int RING = 120;
    private static final Map<Mob, State> STATES = new WeakHashMap<>();
    private static final int G_PURSUIT = 1;
    private static final int G_JUMP = 2;
    private static final int G_BUILD = 4;
    private static final int G_DIG = 8;
    private static final int G_TOWER = 16;
    private static final int G_OTHER = 32;
    private MobWatchdog() {
    }

    private static String goalLetters(int mask) {
        String sb = String.valueOf((mask & G_PURSUIT) != 0 ? 'P' : '.') +
                ((mask & G_JUMP) != 0 ? 'J' : '.') +
                ((mask & G_BUILD) != 0 ? 'B' : '.') +
                ((mask & G_DIG) != 0 ? 'D' : '.') +
                ((mask & G_TOWER) != 0 ? 'T' : '.') +
                ((mask & G_OTHER) != 0 ? 'o' : '.');
        return sb;
    }

    private static int runningGoalMask(Mob mob) {
        int mask = 0;
        for (WrappedGoal wg : mob.goalSelector.getAvailableGoals()) {
            if (!wg.isRunning()) {
                continue;
            }
            Goal g = wg.getGoal();
            String name = g.getClass().getSimpleName();
            if (g instanceof GapJumpAssistGoal) {
                mask |= G_JUMP;
            } else if (g instanceof BuildPathGoal) {
                mask |= G_BUILD;
            } else if (name.contains("Pursuit")) {
                mask |= G_PURSUIT;
            } else if (name.contains("Dig")) {
                mask |= G_DIG;
            } else if (name.contains("Tower")) {
                mask |= G_TOWER;
            } else {
                mask |= G_OTHER;
            }
        }
        return mask;
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Zombie mob)) {
            return;
        }
        if (mob.level().isClientSide() || !DebugLog.on(DebugLog.Cat.WATCHDOG)) {
            return;
        }
        try {
            watch(mob);
        } catch (RuntimeException e) {
            if (DebugLog.throttle("watchdog-error", 5000L)) {
                DebugLog.log(DebugLog.Cat.SYSTEM, mob, "помилка сторожа: " + e);
            }
        }
    }

    private static void watch(Zombie mob) {
        Vec3 chase = PursuitEnemyBehavior.getChasePosition(mob);
        if (chase == null) {
            STATES.remove(mob);
            return;
        }
        State st = STATES.computeIfAbsent(mob, m -> new State());

        boolean onGround = mob.onGround();
        boolean jumpedNow = st.wasOnGround && !onGround && mob.getDeltaMovement().y > 0.2;
        st.wasOnGround = onGround;
        Path path = mob.getNavigation().getPath();
        st.push(mob, jumpedNow, runningGoalMask(mob), path == null ? -1 : path.getNodeCount(),
                path == null ? 0 : path.getNextNodeIndex());

        int now = mob.tickCount;
        double dx = chase.x - mob.getX();
        double dz = chase.z - mob.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);

        // ---- ЗАВИСАННЯ ----
        boolean freeze = st.size >= 40 && st.pathLen(40) < 0.5 && hd > 2.5 && onGround;
        if (freeze) {
            if (st.freezeSinceTick < 0) {
                st.freezeSinceTick = now - 40;
            }
            if ((st.freezeDumps == 0 || now - st.lastFreezeDump >= 200) && st.freezeDumps < 4) {
                st.freezeDumps++;
                st.lastFreezeDump = now;
                dump(mob, st, "ЗАВИСАННЯ #" + st.freezeDumps + ": майже не рухається вже ~" + (now - st.freezeSinceTick)
                        + " тіків, до цілі " + String.format(Locale.ROOT, "%.1f", hd) + " бл.");
            }
        } else if (st.freezeSinceTick >= 0 && st.pathLen(20) > 1.0) {
            DebugLog.log(DebugLog.Cat.WATCHDOG, mob, "зависання ЗАКІНЧИЛОСЬ: тривало ~" + (now - st.freezeSinceTick)
                    + " тіків; моб знову рухається");
            st.freezeSinceTick = -1;
            st.freezeDumps = 0;
        }

        // ---- СМИКАННЯ вперед-назад ----
        if (st.size >= 60 && now - st.lastOscDump >= 300 && hd < 30.0) {
            double len = st.pathLen(60);
            double net = st.net(60);
            int flips = st.headingFlips(60, 5);
            if (len >= 3.0 && net <= 0.45 * len && flips >= 2) {
                st.lastOscDump = now;
                dump(mob, st, "СМИКАННЯ вперед-назад: за 60 тіків пройдено " + String.format(Locale.ROOT, "%.1f", len)
                        + " бл. шляху, чистий зсув лише " + String.format(Locale.ROOT, "%.1f", net)
                        + " бл., розворотів напрямку=" + flips + ", стрибків=" + st.jumpsIn(60)
                        + ", до цілі " + String.format(Locale.ROOT, "%.1f", hd) + " бл.");
            }
        }
    }

    private static void dump(Mob mob, State st, String reason) {
        DebugLog.forceBlock(MobDebug.snapshot(mob, reason, st == null ? "" : st.trace(mob.tickCount)));
    }

    /**
     * Ручний знімок (команда {@code /bedebug dump}).
     */
    public static void dumpNow(Mob mob, String reason) {
        State st = STATES.get(mob);
        dump(mob, st, reason);
    }

    private static final class State {
        final float[] x = new float[RING];
        final float[] y = new float[RING];
        final float[] z = new float[RING];
        final int[] tick = new int[RING];
        final boolean[] ground = new boolean[RING];
        final boolean[] jumped = new boolean[RING];
        final int[] goals = new int[RING];
        final int[] navNodes = new int[RING];
        final int[] navNext = new int[RING];
        int head = 0;
        int size = 0;
        boolean wasOnGround = true;

        int freezeSinceTick = -1;
        int freezeDumps = 0;
        int lastFreezeDump = Integer.MIN_VALUE / 2;
        int lastOscDump = Integer.MIN_VALUE / 2;

        /**
         * Індекс k-го з кінця семпла (0 = останній).
         */
        int idx(int fromEnd) {
            return ((head - 1 - fromEnd) % RING + RING) % RING;
        }

        void push(Mob mob, boolean jumpedNow, int goalMask, int nodes, int next) {
            int i = head;
            x[i] = (float) mob.getX();
            y[i] = (float) mob.getY();
            z[i] = (float) mob.getZ();
            tick[i] = mob.tickCount;
            ground[i] = mob.onGround();
            jumped[i] = jumpedNow;
            goals[i] = goalMask;
            navNodes[i] = nodes;
            navNext[i] = next;
            head = (head + 1) % RING;
            if (size < RING) {
                size++;
            }
        }

        double step(int fromEnd) {
            int a = idx(fromEnd);
            int b = idx(fromEnd + 1);
            double dx = x[a] - x[b];
            double dz = z[a] - z[b];
            return Math.sqrt(dx * dx + dz * dz);
        }

        /**
         * Сума горизонтальних зсувів за останні {@code window} семплів.
         */
        double pathLen(int window) {
            int w = Math.min(window, size) - 1;
            double sum = 0.0;
            for (int k = 0; k < w; k++) {
                sum += step(k);
            }
            return sum;
        }

        /**
         * Чистий горизонтальний зсув між останнім семплом і семплом {@code window-1} тіків тому.
         */
        double net(int window) {
            int w = Math.min(window, size) - 1;
            if (w < 1) {
                return 0.0;
            }
            int a = idx(0);
            int b = idx(w);
            double dx = x[a] - x[b];
            double dz = z[a] - z[b];
            return Math.sqrt(dx * dx + dz * dz);
        }

        /**
         * Скільки разів напрямок руху розвернувся (кут > 90°) між сусідніми відрізками по {@code chunk} тіків.
         */
        int headingFlips(int window, int chunk) {
            int w = Math.min(window, size);
            int chunks = (w - 1) / chunk;
            double prevX = 0;
            double prevZ = 0;
            boolean havePrev = false;
            int flips = 0;
            for (int c = chunks - 1; c >= 0; c--) { // від найдавнішого до найновішого
                int a = idx(c * chunk);
                int b = idx((c + 1) * chunk);
                double dx = x[a] - x[b];
                double dz = z[a] - z[b];
                if (Math.sqrt(dx * dx + dz * dz) < 0.2) {
                    continue;
                }
                if (havePrev && dx * prevX + dz * prevZ < 0.0) {
                    flips++;
                }
                prevX = dx;
                prevZ = dz;
                havePrev = true;
            }
            return flips;
        }

        int jumpsIn(int window) {
            int w = Math.min(window, size);
            int n = 0;
            for (int k = 0; k < w; k++) {
                if (jumped[idx(k)]) {
                    n++;
                }
            }
            return n;
        }

        String trace(int nowTick) {
            StringBuilder sb = new StringBuilder();
            int w = size;
            for (int k = w - 1; k >= 0; k -= 2) { // від найдавнішого, через один тік
                int i = idx(k);
                double v = k + 1 < w ? step(k) : 0.0;
                sb.append(String.format(Locale.ROOT, "      t-%-3d (%.2f, %.2f, %.2f) v=%.3f %s%s цілі=%s шлях=%s\n",
                        nowTick - tick[i], x[i], y[i], z[i], v, ground[i] ? "G" : "-", jumped[i] ? "^" : " ",
                        goalLetters(goals[i]), navNodes[i] < 0 ? "null" : navNodes[i] + "/next" + navNext[i]));
            }
            return sb.toString();
        }
    }
}
