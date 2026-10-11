package com.example.examplemod.debug;

import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.HopNode;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;

/**
 * "Зонд графа". Знімок стану моба показує геометрію навколо нього, але НЕ те, що насправді повернув
 * {@code getNeighbors} під час пошуку шляху. Зонд це закриває: його "озброюють" на конкретного моба (сторож або
 * {@code /bedebug dump}), і найближчий пошук шляху цього моба один раз друкує сусідів стартового вузла на кожному
 * етапі: ванільні -> після форм-орієнтованої підміни -> після стрибкових ребер.
 * Це справжня відповідь evaluator-а, а не наша реконструкція.
 */
public final class GraphProbe {

    private static volatile int armedMobId = -1;
    private static volatile long expireMs = 0L;

    private GraphProbe() {
    }

    /**
     * Озброїти зонд на цього моба на 10 секунд (до першого пошуку шляху).
     */
    public static void arm(Mob mob) {
        armedMobId = mob.getId();
        expireMs = System.currentTimeMillis() + 10_000L;
    }

    public static boolean armedFor(Mob mob) {
        return mob != null && mob.getId() == armedMobId && System.currentTimeMillis() < expireMs;
    }

    public static void disarm() {
        armedMobId = -1;
    }

    /**
     * Один рядок-список вузлів: {@code (x,y,z) тип штраф [J=стрибок]}, відносно стартового вузла.
     */
    public static void logNodes(Mob mob, String stage, Node start, Node[] nodes, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("старт=(").append(start.x).append(',').append(start.y).append(',').append(start.z).append(") ")
                .append(stage).append(" [").append(count).append("]:");
        for (int i = 0; i < count; i++) {
            Node n = nodes[i];
            if (n == null) {
                sb.append("\n    #").append(i).append(" null");
                continue;
            }
            int dx = n.x - start.x;
            int dz = n.z - start.z;
            int dy = n.y - start.y;
            double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
            sb.append("\n    (").append(n.x).append(',').append(n.y).append(',').append(n.z).append(") d=(")
                    .append(dx).append(',').append(dy).append(',').append(dz).append(") type=").append(n.type)
                    .append(" malus=").append(String.format(java.util.Locale.ROOT, "%.2f", n.costMalus))
                    .append(n instanceof HopNode ? "  <-- ГОП (стрибок на сусідню клітинку)" : dist > 1.5 ? "  <-- СТРИБОК" : "");
        }
        DebugLog.log(DebugLog.Cat.GRAPH, mob, sb.toString());
    }

    public static void logText(Mob mob, String text) {
        DebugLog.log(DebugLog.Cat.GRAPH, mob, text);
    }
}
