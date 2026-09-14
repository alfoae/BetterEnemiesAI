package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.EnemyBreak_N_BuildUtils;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Стрибок через короткий природний розрив на шляху до chasePos.
 * <p>
 * ЧОМУ моб "губив імпульс" у першій версії: звичайний Path не вміє прокласти вузол через розрив,
 * тож PursuitEnemyMeleeBehavior вів моба до ОСТАННЬОГО досяжного вузла — а це вузол рівно на
 * краю. MoveControl бачить, що ціль (край) уже близько, і гальмує zza до нуля ще ДО того, як
 * встигав спрацювати сам стрибок — моб приходив на край з практично нульовою живою швидкістю.
 * <p>
 * Тому тут ДВІ зміни одночасно, не одна:
 * <ol>
 *   <li>{@code setWantedPosition} завжди ціляє в {@code landing} (точку ЗА розривом), а не в
 *       край. Поки моб ще підбігає, ціль лишається "далеко", MoveControl не переводить операцію
 *       в WAIT і не гальмує zza рівно на кромці — це і є основний фікс втрати імпульсу.</li>
 *   <li>Сам виклик {@code jump()} додатково гейтиться живою швидкістю
 *       ({@link GapJumpUtils#requiredTakeoffSpeed}) — якщо моб все одно підійшов до краю
 *       недостатньо розігнаним (щойно розвернувся, щойно почав чейс), він відходить назад на
 *       {@code BASE_RUNUP_BLOCKS} і заряджається ще раз, аж поки жива швидкість не наздожене
 *       потрібну (чи не вичерпає {@code MAX_RETREAT_ATTEMPTS}).</li>
 * </ol>
 * "Розгін" — це та сама сама Run_N_Jump-швидкість (sprint), жодного окремого руху не заведено:
 * і відхід назад, і заряд вперед ідуть через той самий {@code Run_N_JumpUtils.getRunSpeedModifier}.
 * <p>
 * Флаги (MOVE+LOOK+JUMP) і пріоритет — як і раніше, ті самі, що в TowerClimbGoal/BuildPathGoal;
 * не звертається до {@code EnemyBreak_N_BuildUtils.canOperate()} (нічого не будує/ламає).
 * <p>
 * НЕДОВІРЕНА ЧАСТИНА, чесно: {@code BASE_RUNUP_BLOCKS} — не розрахунок з формули прискорення
 * Minecraft (не був певний у точних константах тертя блоків, щоб на них покладатись без
 * компіляції/тестів), а проста евристика "відійти й заміряти живу швидкість ще раз", яка
 * зростає з кожною невдалою спробою. Працює незалежно від типу поверхні під ногами, але
 * оптимальне значення варто підібрати в грі.
 */
public class GapJumpAssistGoal extends Goal {

    private static final int BASE_RUNUP_BLOCKS = 5;
    private static final int MAX_RETREAT_ATTEMPTS = 3;
    private static final double EDGE_RADIUS = 0.7;

    private final Mob mob;
    private GapJumpUtils.GapJump jump;
    private int retreatAttempts;
    private boolean abandoned;

    public GapJumpAssistGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!PursuitEnemyBehavior.isMemoryChasing(this.mob) || !this.mob.onGround()) {
            return false;
        }
        Vec3 chasePos = PursuitEnemyBehavior.getChasePosition(this.mob);
        if (chasePos == null || !EnemyBreak_N_BuildUtils.isPathBlocked(this.mob, chasePos)) {
            return false; // не ганяти скан даремно, коли звичайний шлях і так вільний
        }
        this.jump = GapJumpUtils.findGapJump(this.mob, chasePos);
        return this.jump != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.abandoned) {
            return false;
        }
        // у польоті довіряємо вже взятому напрямку; на землі - переоцінюємо наново
        return !this.mob.onGround() || canUse();
    }

    @Override
    public void start() {
        this.retreatAttempts = 0;
        this.abandoned = false;
    }

    @Override
    public void tick() {
        if (!this.mob.onGround()) {
            steerTo(this.jump.landing());
            return;
        }

        Vec3 edgeCenter = Vec3.atCenterOf(this.jump.edge());
        double distToEdge = this.mob.position().distanceTo(edgeCenter);

        if (distToEdge > EDGE_RADIUS) {
            // ще підбігаємо - ціляємо на ПРИЗЕМЛЕННЯ, не на край (див. javadoc: саме це не дає
            // MoveControl загальмувати zza рівно на кромці)
            steerTo(this.jump.landing());
            return;
        }

        double currentSpeed = this.mob.getDeltaMovement().horizontalDistance();
        double requiredSpeed = GapJumpUtils.requiredTakeoffSpeed(this.jump.gapBlocks());

        if (currentSpeed >= requiredSpeed) {
            steerTo(this.jump.landing());
            this.mob.getJumpControl().jump();
            return;
        }

        // на краю, але живої швидкості не вистачає - відходимо назад для розгону замість
        // стрибка наосліп з тим імпульсом, який випадково є зараз
        if (this.retreatAttempts >= MAX_RETREAT_ATTEMPTS) {
            this.abandoned = true; // евристика вичерпана - хай цей розрив бере BuildPathGoal
            return;
        }
        this.retreatAttempts++;
        Vec3 dirToLanding = this.jump.landing().subtract(edgeCenter).normalize();
        Vec3 retreatTo = edgeCenter.subtract(dirToLanding.scale((double) BASE_RUNUP_BLOCKS * this.retreatAttempts));
        steerTo(retreatTo);
    }

    private void steerTo(Vec3 target) {
        this.mob.getLookControl().setLookAt(target.x, target.y, target.z, 30.0F, 30.0F);
        this.mob.getMoveControl().setWantedPosition(
                target.x, target.y, target.z, Run_N_JumpUtils.getRunSpeedModifier(this.mob));
    }

    @Override
    public void stop() {
        this.jump = null;
        this.mob.getNavigation().stop();
    }
}