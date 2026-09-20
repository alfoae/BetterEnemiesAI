package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Спільні утиліти стрибка + читання "цей сегмент шляху вимагає стрибка" з реального Path.
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
 * v5 — ФІЗИКА ПОЛЬОТУ винесена в {@link GapJumpPhysics} (точна ванільна модель, звірена з
 * незалежною реалізацією). Тут лишилась лише ОЦІНКА дальності для NodeEvaluator-а
 * ({@link #estimateMaxJumpRangeBlocks}) та читання Path.
 */
public final class GapJumpUtils {

    /**
     * Калібрувальний коефіцієнт ОЦІНКИ "на скільки блоків взагалі варто вважати розрив прохідним":
     * {@code maxGap = floor(topSpeed * RANGE_ESTIMATE_FACTOR)}. Це ДОРІВНЮЄ старому
     * {@code 10 тіків * 0.85} — навмисно лишив ті самі числа, щоб не змінювати, які розриви
     * NodeEvaluator вважає прохідними (зомбі в спринті → 3 блоки, як і було).
     * <p>
     * УВАГА: це НЕ модель польоту. Раніше цей самий коефіцієнт використовувався ще й для
     * обчислення швидкості відриву ({@code відстань / 8.5}) — і саме це ламало стрибок на 3 блоки:
     * реальна дальність польоту = швидкість × {@link GapJumpPhysics#FLIGHT_FACTOR_TO_LANDING} ≈ ×4.92,
     * а не ×8.5. Швидкість відриву тепер рахує лише {@link GapJumpPhysics#launchSpeedForDistance}.
     */
    static final double RANGE_ESTIMATE_FACTOR = 8.5;

    /**
     * Горизонтальна відстань між сусідніми вузлами шляху, з якої вважаємо сегмент "стрибком", а не звичайним кроком.
     */
    private static final double JUMP_SEGMENT_THRESHOLD = GapJumpRays.MIN_JUMP_DISTANCE;

    private GapJumpUtils() {
    }

    /**
     * Швидкість-уставка бігу моба (те, що MoveControl множить на прискорення): атрибут швидкості
     * (зі спринтом, якщо він увімкнений) × множник бігу. Спільна для оцінки дальності та для
     * передбачення кроку в GapJumpAssistGoal.
     */
    public static double runSpeedSetpoint(Mob mob) {
        return mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * Run_N_JumpUtils.getRunSpeedModifier(mob);
    }

    /** Теоретична максимальна дальність стрибка цього моба на повній швидкості (не поточній). */
    public static int estimateMaxJumpRangeBlocks(Mob mob) {
        return (int) Math.floor(runSpeedSetpoint(mob) * RANGE_ESTIMATE_FACTOR);
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
     * (v5: GapJumpAssistGoal більше не відходить для розгону — швидкість відриву задається явно, тож
     * розбіг на дальність не впливає. Метод лишений на випадок, якщо розгін знадобиться знову.)
     * <p>
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
     * Для косого/діагонального стрибка {@code gapBlocks} лише наближене (округлена відстань між
     * центрами мінус 1) і потрібне тільки для логів; уся геометрія береться з {@code edge}/{@code landing}.
     */
    public record GapJump(BlockPos edge, Vec3 landing, int gapBlocks) {
    }
}