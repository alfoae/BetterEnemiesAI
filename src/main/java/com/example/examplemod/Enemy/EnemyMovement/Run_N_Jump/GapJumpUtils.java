package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Детекція розриву на курсі до chasePos + розрахунок дальності стрибка.
 * <p>
 * Дальність стрибка рахується у ДВА окремі числа, і це навмисно, не зайва складність:
 * <ul>
 *   <li>{@link #estimateMaxJumpRangeBlocks} — ТЕОРЕТИЧНА межа на повній швидкості моба
 *       (з атрибута, не з поточного руху). Використовується тільки щоб зрозуміти, чи розрив
 *       В ПРИНЦИПІ коли-небудь проходимий для цього моба — це межа сканування.</li>
 *   <li>{@link #requiredTakeoffSpeed} — яку ЖИВУ швидкість треба мати прямо перед відривом
 *       для КОНКРЕТНОГО знайденого розриву. GapJumpAssistGoal порівнює це з
 *       {@code mob.getDeltaMovement()} і не стрибає, поки моб реально не розігнався —
 *       якщо просто довіритись атрибуту "мав би вміти", а не живій швидкості, моб стрибає
 *       з тим імпульсом, який реально має в моменту, а не з тим, що "мав би" на бумазі.
 * </ul>
 * Обидві формули використовують ОДИН і той самий SAFETY_MARGIN, тому вони узгоджені: для
 * найширшого розриву, який взагалі пройде через скан, необхідна жива швидкість ≈ повна швидкість
 * моба — рівно те, чого й слід очікувати.
 * <p>
 * Час у повітрі (JUMP_AIRTIME_TICKS) однаковий для всіх мобів (поки жоден не перевизначає
 * getJumpPower()) — порахований раз тим самим рівнянням, що й ванільний
 * LivingEntity#jumpFromGround(): v0=0.42, гравітація -0.08/тік, опір ×0.98/тік.
 * <p>
 * ЧЕСНО: не компілилось проти реальних Minecraft-бібліотек (пісочниця без Maven/NeoForge) —
 * константи стрибка стандартні й добре задокументовані, звір перед мерджем.
 */
public final class GapJumpUtils {

    private static final double JUMP_VERTICAL_VELOCITY = 0.42;
    private static final double GRAVITY_PER_TICK = 0.08;
    private static final double DRAG_PER_TICK = 0.98;
    private static final int JUMP_AIRTIME_TICKS = computeAirTimeTicks();

    /**
     * Один запас на обидва розрахунки (і межу сканування, і потрібну швидкість) - тримає їх узгодженими.
     */
    private static final double SAFETY_MARGIN = 0.85;

    /**
     * Скільки блоків вперед взагалі готові шукати край розриву.
     */
    private static final int MAX_SCAN_BLOCKS = 16;

    private GapJumpUtils() {
    }

    private static int computeAirTimeTicks() {
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

    /**
     * Теоретична максимальна дальність стрибка цього моба на повній швидкості (не поточній).
     */
    public static int estimateMaxJumpRangeBlocks(Mob mob) {
        double topSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * Run_N_JumpUtils.getRunSpeedModifier(mob);
        return (int) Math.floor(topSpeed * JUMP_AIRTIME_TICKS * SAFETY_MARGIN);
    }

    /**
     * Яку живу горизонтальну швидкість (mob.getDeltaMovement()) треба мати прямо перед відривом.
     */
    public static double requiredTakeoffSpeed(int gapBlocks) {
        return gapBlocks / (JUMP_AIRTIME_TICKS * SAFETY_MARGIN);
    }

    /**
     * Шукає розрив по курсу на chasePos незалежно від того, чи моб уже стоїть на самому краю,
     * чи ще підбігає — межу сканування бере ТЕОРЕТИЧНОЮ (estimateMaxJumpRangeBlocks), бо живої
     * швидкості на момент виявлення в моба ще могло й не бути (саме на це і чекає
     * GapJumpAssistGoal, перш ніж дозволити сам стрибок).
     *
     * @return дані про розрив, або {@code null}, якщо стрибати нема куди/нема сенсу
     */
    public static GapJump findGapJump(Mob mob, Vec3 chasePos) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return null;
        }
        Vec3 toTarget = new Vec3(chasePos.x - mob.getX(), 0, chasePos.z - mob.getZ());
        if (toTarget.lengthSqr() < 1.0) {
            return null;
        }
        Vec3 dir = toTarget.normalize();
        BlockPos feet = mob.blockPosition();

        int edgeStep = -1;
        for (int step = 0; step <= MAX_SCAN_BLOCKS; step++) {
            if (!hasFloor(level, feet, dir, step)) {
                edgeStep = step - 1; // останній твердий блок перед розривом
                break;
            }
        }
        if (edgeStep < 0) {
            return null; // або весь скан твердий, або моб уже якимось чином у повітрі
        }

        int maxGap = estimateMaxJumpRangeBlocks(mob);
        for (int step = edgeStep + 2; step <= edgeStep + 1 + maxGap; step++) {
            if (hasFloor(level, feet, dir, step)) {
                BlockPos edge = offset(feet, dir, edgeStep);
                BlockPos landing = offset(feet, dir, step);
                if (landing.getY() - edge.getY() > 1) {
                    return null; // приземлення суттєво вище - TowerClimbGoal, не наш кейс
                }
                return new GapJump(edge, Vec3.atBottomCenterOf(landing), step - edgeStep - 1);
            }
        }
        return null; // розрив ширший за теоретичну дальність стрибка - хай бере BuildPathGoal
    }

    /**
     * Край (останній твердий блок перед розривом), точка приземлення і ширина розриву в блоках.
     */
    public record GapJump(BlockPos edge, Vec3 landing, int gapBlocks) {
    }

    private static boolean hasFloor(ServerLevel level, BlockPos origin, Vec3 dir, int step) {
        BlockPos column = offset(origin, dir, step);
        return level.getBlockState(column.below()).isSolid() && !level.getBlockState(column).isSolid();
    }

    private static BlockPos offset(BlockPos origin, Vec3 dir, int step) {
        return origin.offset((int) Math.round(dir.x * step), 0, (int) Math.round(dir.z * step));
    }
}