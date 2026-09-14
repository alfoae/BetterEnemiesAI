package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Стрибок через короткий природний розрив (провалля/паркур) на шляху до chasePos.
 * <p>
 * Той самий набір флагів (MOVE+LOOK+JUMP), що й TowerClimbGoal/BuildPathGoal — НАВМИСНО, щоб
 * стояти з ними в ОДНІЙ черзі пріоритетів GoalSelector-а, а не заводити окрему паралельну
 * систему, яка може одночасно смикати той самий розрив. PursuitEnemyMeleeBehavior/
 * BetterZombieGoalAi вже вміє чемно звільняти ці ж флаги
 * (shouldYieldToTerraforming() -> isPathBlocked()), коли шлях заблокований — розрив без
 * прохідного Path це той самий сигнал, тож жодної ДОДАТКОВОЇ логіки yield там не треба.
 * <p>
 * Реєструй ВИЩЕ (менше число пріоритету), ніж BuildPathGoal/DigThroughWallsGoal: якщо розрив у
 * межах дальності стрибка моба — стрибок мусить перехопити чергу раніше, ніж BuildPathGoal
 * встигне вирішити бриджити його блоками (навіщо витрачати блоки, якщо можна перестрибнути).
 * Сам GoalSelector це й забезпечує через звичайне блокування прапорців за пріоритетом — жодного
 * ручного yield() між GapJumpAssistGoal і BuildPathGoal писати не треба.
 * <p>
 * НА ВІДМІНУ від BuildPathGoal/DigThroughWallsGoal/TowerClimbGoal — свідомо НЕ звертається до
 * {@code EnemyBreak_N_BuildUtils.canOperate()}: стрибок нічого не будує й не ламає, тому працює
 * і тоді, коли mobGriefing/ENABLE_MOB_TERRAFORMING вимкнено (а це якраз єдиний випадок, коли
 * мобу більше нічим перетнути розрив).
 * <p>
 * ТИМЧАСОВО: не компілилось проти реальних Minecraft-бібліотек (нема доступу з пісочниці) -
 * перш ніж вживляти, звір {@code MoveControl#setWantedPosition} і решту сигнатур з автокомплітом.
 */
public class GapJumpAssistGoal extends Goal {

    private final Mob mob;
    private Vec3 landing;

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
        this.landing = chasePos == null ? null : GapJumpUtils.findLanding(this.mob, chasePos);
        return this.landing != null;
    }

    @Override
    public boolean canContinueToUse() {
        // у польоті довіряємо вже взятому напрямку і не смикаємось; на землі - переоцінюємо
        // наново (як PursuitBrainBridgeGoal.canContinueToUse() -> canUse())
        return !this.mob.onGround() || canUse();
    }

    @Override
    public void tick() {
        this.mob.getLookControl().setLookAt(this.landing.x, this.landing.y, this.landing.z, 30.0F, 30.0F);
        this.mob.getMoveControl().setWantedPosition(
                this.landing.x, this.landing.y, this.landing.z,
                Run_N_JumpUtils.getRunSpeedModifier(this.mob));
        if (this.mob.onGround()) {
            this.mob.getJumpControl().jump();
        }
    }

    @Override
    public void stop() {
        this.landing = null;
        this.mob.getNavigation().stop();
    }
}
