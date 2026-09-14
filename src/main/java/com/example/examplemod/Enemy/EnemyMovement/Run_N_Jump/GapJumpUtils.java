package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Детекція "чи є прямо по курсу на chasePos короткий розрив, який моб може перестрибнути" +
 * оцінка дальності стрибка ПІД КОНКРЕТНОГО моба.
 * <p>
 * Різні типи мобів бігають з різною швидкістю ({@code ChangeEnemiesAttributes} /
 * {@link Run_N_JumpUtils#getRunSpeedModifier(Mob)}), а горизонтальна дальність стрибка — це
 * швидкість-в-момент-відриву помножена на час у повітрі. Тому дальність рахуємо по ЖИВІЙ
 * поточній швидкості моба ({@link Mob#getDeltaMovement()}), а не по одній константі на всіх.
 * <p>
 * Час у повітрі (JUMP_AIRTIME_TICKS), навпаки, однаковий для всіх мобів (поки жоден з них не
 * перевизначає {@code getJumpPower()}) — вертикальна фізика стрибка від горизонтальної швидкості
 * не залежить. Порахований раз тим самим рівнянням, що й ванільний
 * {@code LivingEntity#jumpFromGround()}: v0 = 0.42, гравітація -0.08/тік, опір ×0.98/тік.
 * <p>
 * ВАЖЛИВО: жодного виклику {@code EnemyBreak_N_BuildUtils.canOperate()} тут навмисно немає.
 * Стрибок нічого не будує й не ламає — на відміну від BuildPathGoal/DigThroughWallsGoal/
 * TowerClimbGoal, він має працювати і тоді, коли mobGriefing/ENABLE_MOB_TERRAFORMING вимкнено,
 * бо саме тоді мобу більше нічим перетнути розрив.
 * <p>
 * ЧЕСНО: константи стрибка (0.42 / 0.08 / 0.98) не перевірені компіляцією проти реальних
 * Minecraft-бібліотек (пісочниця без доступу до Maven/NeoForge) — це стандартні, добре
 * задокументовані ванільні значення, але перед мерджем варто звірити з реальною грою чи хоча б
 * ще раз з декомпільованими сорсами в IDE.
 */
public final class GapJumpUtils {

    private static final double JUMP_VERTICAL_VELOCITY = 0.42; // LivingEntity#jumpFromGround, без Jump Boost
    private static final double GRAVITY_PER_TICK = 0.08;
    private static final double DRAG_PER_TICK = 0.98;
    private static final int JUMP_AIRTIME_TICKS = computeAirTimeTicks();

    /**
     * Запас від теоретичного максимуму — не намагаємось стрибати рівно на межі дальності.
     */
    private static final double SAFETY_MARGIN = 0.85;

    /**
     * Мінімальна швидкість, нижче якої вважаємо, що моб ще не розігнався, і підстраховуємось атрибутом.
     */
    private static final double MIN_TRUSTED_SPEED = 0.05;

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
     * Скільки блоків по горизонталі цей КОНКРЕТНИЙ моб зараз покриє за час стрибка (із запасом).
     */
    public static int estimateJumpRangeBlocks(Mob mob) {
        double horizontalSpeed = mob.getDeltaMovement().horizontalDistance();
        if (horizontalSpeed < MIN_TRUSTED_SPEED) {
            // на старті чейсу мобу ще нема з чого братись live-швидкості - підстраховуємось
            // атрибутом, інакше estimateJumpRangeBlocks() тут завжди повертав би ~0
            horizontalSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * Run_N_JumpUtils.getRunSpeedModifier(mob);
        }
        return (int) Math.floor(horizontalSpeed * JUMP_AIRTIME_TICKS * SAFETY_MARGIN);
    }

    /**
     * Шукає точку приземлення прямо по курсу на chasePos: перший крок попереду вже без опори
     * (інакше стрибати нема сенсу — звичайний Path і так впорається), і десь у межах дальності
     * стрибка опора знову з'являється, не вище ніж на 1 блок від поточної позиції моба (вище —
     * це вже сценарій TowerClimbGoal, не наш).
     *
     * @return точка приземлення, або {@code null}, якщо стрибати нема куди/нема сенсу
     */
    public static Vec3 findLanding(Mob mob, Vec3 chasePos) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return null;
        }
        Vec3 toTarget = new Vec3(chasePos.x - mob.getX(), 0, chasePos.z - mob.getZ());
        if (toTarget.lengthSqr() < 1.0) {
            return null;
        }
        Vec3 dir = toTarget.normalize();
        BlockPos feet = mob.blockPosition();

        if (hasFloor(level, feet, dir, 1)) {
            return null; // попереду й так є опора - не наш кейс, хай веде звичайний Path
        }

        int maxGap = estimateJumpRangeBlocks(mob);
        for (int step = 2; step <= maxGap + 1; step++) {
            if (hasFloor(level, feet, dir, step)) {
                BlockPos landing = offset(feet, dir, step);
                if (landing.getY() - feet.getY() > 1) {
                    return null; // приземлення суттєво вище - TowerClimbGoal, не наш кейс
                }
                return Vec3.atBottomCenterOf(landing);
            }
        }
        return null; // розрив ширший за дальність стрибка - хай бере BuildPathGoal
    }

    private static boolean hasFloor(ServerLevel level, BlockPos origin, Vec3 dir, int step) {
        BlockPos column = offset(origin, dir, step);
        return level.getBlockState(column.below()).isSolid() && !level.getBlockState(column).isSolid();
    }

    private static BlockPos offset(BlockPos origin, Vec3 dir, int step) {
        return origin.offset((int) Math.round(dir.x * step), 0, (int) Math.round(dir.z * step));
    }
}
