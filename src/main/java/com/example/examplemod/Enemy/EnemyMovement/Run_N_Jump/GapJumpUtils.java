package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Спільна фізика стрибка + читання "цей сегмент шляху вимагає стрибка" з реального Path.
 * <p>
 * v4 — виявлення розриву більше НЕ робить свій скан наперед. Цим тепер займається
 * {@link GapJumpNodeEvaluator} прямо всередині pathfinding-графа (через
 * {@link GapJumpPathNavigation}) — тобто сам {@code sharedPath}, яким і так користується
 * PursuitEnemyMeleeBehavior, вже містить "далекий" вузол там, де є прохідний стрибок. Це, до
 * речі, автоматично гасить окрему проблему координації з BuildPathGoal, яку доводилось руками
 * розводити пріоритетом раніше: щойно розрив стає прохідним через стрибок, createPath() більше
 * не повертає unreachable, і {@code isPathBlocked} для НЬОГО просто перестає бути true — тож
 * BuildPathGoal сам по собі туди більше не претендує, без жодної спеціальної умови.
 * <p>
 * Лишається тут: фізика стрибка (потрібна і NodeEvaluator-у для межі сканування, і Goal-у для
 * живої перевірки швидкості) та {@link #findSafeRetreatPoint} (виконання все ще саме тут -
 * NodeEvaluator тільки каже "маршрут існує", а розгін/стрибок виконує GapJumpAssistGoal).
 */
public final class GapJumpUtils {

    static final double JUMP_VERTICAL_VELOCITY = 0.42;
    static final double GRAVITY_PER_TICK = 0.08;
    static final double DRAG_PER_TICK = 0.98;
    static final int JUMP_AIRTIME_TICKS = computeAirTimeTicks();

    /**
     * Один запас на всі похідні розрахунки - тримає межу сканування і потрібну швидкість узгодженими.
     */
    static final double SAFETY_MARGIN = 0.85;

    /**
     * Горизонтальна відстань між сусідніми вузлами шляху, з якої вважаємо сегмент "стрибком", а не звичайним кроком.
     */
    private static final double JUMP_SEGMENT_THRESHOLD = 1.5;

    private GapJumpUtils() {
    }

    static int computeAirTimeTicks() {
        double y = 0.0;
        double vy = JUMP_VERTICAL_VELOCITY;
        int ticks = 0;
        do {
            vy = (vy - GRAVITY_PER_TICK) * DRAG_PER_TICK;
            y += vy;
            ticks++;
        } while (y > 0.0 && ticks < 40); // запобіжник від нескінченного циклу
        return ticks;
    }

    /** Теоретична максимальна дальність стрибка цього моба на повній швидкості (не поточній). */
    public static int estimateMaxJumpRangeBlocks(Mob mob) {
        double topSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * Run_N_JumpUtils.getRunSpeedModifier(mob);
        return (int) Math.floor(topSpeed * JUMP_AIRTIME_TICKS * SAFETY_MARGIN);
    }

    /** Яку живу горизонтальну швидкість (mob.getDeltaMovement()) треба мати прямо перед відривом. */
    public static double requiredTakeoffSpeed(int gapBlocks) {
        return gapBlocks / (JUMP_AIRTIME_TICKS * SAFETY_MARGIN);
    }

    /**
     * Те саме, що {@link #requiredTakeoffSpeed(int)}, але від РЕАЛЬНОЇ живої горизонтальної
     * відстані до landing замість дискретизованого gapBlocks. gapBlocks - ціле число, округлене
     * від довжини сегмента шляху; фактична точка, де GapJumpAssistGoal ловить моба в межах
     * EDGE_RADIUS, від стрибка до стрибка трохи гуляє (платформи по 1 блоку - "зловити" можна і
     * за 0.05 блока від edge, і майже впритул до самого landing). requiredTakeoffSpeed(gapBlocks)
     * лишається для порогу "чи взагалі варто пробувати", а фактичну швидкість відриву варто
     * рахувати цим методом - інакше дальність польоту стала (та сама швидкість = та сама
     * дистанція), а реально потрібна щоразу різна, звідси зростаючий переліт/недоліт.
     */
    public static double requiredTakeoffSpeedForDistance(double horizontalDistance) {
        return horizontalDistance / (JUMP_AIRTIME_TICKS * SAFETY_MARGIN);
    }

    /**
     * Дивиться в РЕАЛЬНИЙ поточний Path моба (уже побудований {@link GapJumpNodeEvaluator}-ом,
     * якщо на мобі стоїть {@link GapJumpPathNavigation}) і шукає, чи серед НАЙБЛИЖЧИХ пари
     * сусідніх вузлів попереду є "стрибковий" сегмент — тобто пара вузлів, що лежать далі одне
     * від одного, ніж дає звичайний крок. Не пересканює місцевість сама — тільки читає те, що
     * вже порахував pathfinder.
     *
     * @return дані про стрибок, або {@code null}, якщо найближчий відрізок шляху - звичайний крок
     */
    public static GapJump findUpcomingJumpSegment(Mob mob) {
        Path path = mob.getNavigation().getPath();
        if (path == null) {
            return null;
        }
        int from = Math.max(0, path.getNextNodeIndex() - 1);
        int lookahead = Math.min(path.getNodeCount() - 1, from + 3); // дивимось на 2-3 вузли вперед, не на весь шлях
        for (int i = from; i < lookahead; i++) {
            Node a = path.getNode(i);
            Node b = path.getNode(i + 1);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            if (dx * dx + dz * dz > JUMP_SEGMENT_THRESHOLD * JUMP_SEGMENT_THRESHOLD) {
                BlockPos edge = new BlockPos(a.x, a.y, a.z);
                BlockPos landing = new BlockPos(b.x, b.y, b.z);
                int gapBlocks = (int) Math.round(Math.sqrt(dx * dx + dz * dz)) - 1;
                return new GapJump(edge, Vec3.atBottomCenterOf(landing), Math.max(1, gapBlocks));
            }
        }
        return null;
    }

    /**
     * Йде по прямій у напрямку {@code dir} від моба, шукаючи найдальшу БЕЗПЕЧНУ точку в межах
     * {@code desiredBlocks} — якщо позаду теж обрив (чи стіна), зупиняється на останньому
     * твердому й прохідному блоці перед ним, навіть якщо це менше за {@code desiredBlocks}.
     * Повертає поточну позицію моба, якщо небезпечно навіть на 1 блок.
     */
    public static Vec3 findSafeRetreatPoint(Mob mob, Vec3 dir, int desiredBlocks) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return mob.position();
        }
        BlockPos feet = mob.blockPosition();
        BlockPos lastSafe = feet;
        for (int step = 1; step <= desiredBlocks; step++) {
            BlockPos column = feet.offset((int) Math.round(dir.x * step), 0, (int) Math.round(dir.z * step));
            boolean solidFloor = level.getBlockState(column.below()).isSolid();
            boolean passable = !level.getBlockState(column).isSolid();
            if (!solidFloor || !passable) {
                break; // далі в цьому напрямку теж небезпечно - зупиняємось тут
            }
            lastSafe = column;
        }
        return Vec3.atBottomCenterOf(lastSafe);
    }

    /**
     * Край (останній твердий вузол перед стрибком), точка приземлення і ширина розриву в блоках.
     */
    public record GapJump(BlockPos edge, Vec3 landing, int gapBlocks) {
    }
}