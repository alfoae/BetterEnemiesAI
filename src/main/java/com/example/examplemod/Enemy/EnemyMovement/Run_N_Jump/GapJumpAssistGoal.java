package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Виконання стрибка через розрив, який {@link GapJumpNodeEvaluator} уже заклав у sharedPath як
 * "далекий" вузол — сама детекція розриву тепер живе в pathfinding-графі, цей Goal лише читає
 * {@link GapJumpUtils#findUpcomingJumpSegment} і виконує розгін/стрибок.
 * <p>
 * Виконавча частина (фази CHARGING/RETREATING, безпечний відступ, гейт живою швидкістю,
 * самозавершення після приземлення) не змінилась проти v3 — та логіка вже перевірена в грі,
 * змінилось тільки ЗВІДКИ береться сам факт "тут є стрибок".
 * <p>
 * Той самий набір флагів (MOVE+LOOK+JUMP), що й TowerClimbGoal/BuildPathGoal, і той самий
 * пріоритет — АЛЕ тригер для {@code PursuitEnemyMeleeBehavior.shouldYieldToTerraforming()}
 * (isPathBlocked) більше НЕ спрацьовує для прохідних-через-стрибок розривів, бо createPath()
 * тепер для них реально знаходить шлях. Тому в PursuitEnemyMeleeBehavior додано окрему умову
 * {@code shouldYieldToGapJump()} — без неї GoalSelector ніколи не віддасть нам MOVE/LOOK
 * (пріоритет 1 форсовано не перебивається вищими номерами, це підтверджена ванільна поведінка,
 * не якась забаганка).
 */
public class GapJumpAssistGoal extends Goal {

    private static final double ARRIVED_RADIUS = 0.5;

    private static final int BASE_RUNUP_BLOCKS = 5;
    private static final int MAX_RETREAT_ATTEMPTS = 3;
    private static final double EDGE_RADIUS = 0.7;
    private Phase phase;

    private final Mob mob;
    private GapJumpUtils.GapJump jump;
    private Vec3 retreatTarget;
    private boolean hasBeenAirborne;
    private int retreatAttempts;
    private boolean done;

    @Override
    public boolean canUse() {
        if (!PursuitEnemyBehavior.isMemoryChasing(this.mob) || !this.mob.onGround()) {
            return false;
        }
        this.jump = GapJumpUtils.findUpcomingJumpSegment(this.mob);
        return this.jump != null;
    }

    public GapJumpAssistGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canContinueToUse() {
        // не пересканюємо і не чіпаємо this.jump посеред маневру - інакше кожен тік знаходить
        // "трохи інший" сегмент і весь стан (фаза, retreatTarget, лічильник спроб) втрачає сенс
        return !this.done && this.jump != null && PursuitEnemyBehavior.isMemoryChasing(this.mob);
    }

    @Override
    public void start() {
        this.phase = Phase.CHARGING;
        this.retreatAttempts = 0;
        this.hasBeenAirborne = false;
        this.done = false;
    }

    @Override
    public void tick() {
        if (!this.mob.onGround()) {
            this.hasBeenAirborne = true;
            steerTo(this.jump.landing());
            return;
        }

        if (this.hasBeenAirborne) {
            this.done = true; // долетіли й сіли - завдання виконане, далі звичайний чейс по sharedPath
            return;
        }

        double requiredSpeed = GapJumpUtils.requiredTakeoffSpeed(this.jump.gapBlocks());

        if (this.phase == Phase.RETREATING) {
            double liveSpeed = this.mob.getDeltaMovement().horizontalDistance();
            boolean arrived = this.mob.position().distanceTo(this.retreatTarget) < ARRIVED_RADIUS;
            if (arrived || liveSpeed >= requiredSpeed) {
                this.phase = Phase.CHARGING; // доїхали до точки розгону (чи вже й так розігнались) - заряджаємось вперед
            } else {
                steerTo(this.retreatTarget);
                return;
            }
        }

        // phase == CHARGING
        Vec3 edgeCenter = Vec3.atCenterOf(this.jump.edge());
        double distToEdge = this.mob.position().distanceTo(edgeCenter);

        if (distToEdge > EDGE_RADIUS) {
            steerTo(this.jump.landing()); // ціляти на приземлення, не на край - не гальмує zza на кромці
            return;
        }

        double currentSpeed = this.mob.getDeltaMovement().horizontalDistance();
        if (currentSpeed >= requiredSpeed) {
            steerTo(this.jump.landing());
            this.mob.getJumpControl().jump(); // Сам стрибок

            // ================= [DEBUG] =================
            // 1. Повідомлення в консоль з деталями стрибка
            System.out.printf("[DEBUG JUMP] Моб %s стрибнув! Швидкість: %.2f (потрібно: %.2f) | Розрив: %d блоків%n",
                    this.mob.getName().getString(),
                    currentSpeed,
                    requiredSpeed,
                    this.jump.gapBlocks());

            // 2. Візуальний ефект у грі (спавнить зелені партикли над головою моба)
            if (this.mob.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                serverLevel.sendParticles(
                        net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                        this.mob.getX(),
                        this.mob.getY() + this.mob.getBbHeight() + 0.5,
                        this.mob.getZ(),
                        20,   // кількість партиклів
                        0.2, 0.2, 0.2, // розкид по X, Y, Z
                        0.05  // швидкість рух партиклів
                );
            }
            // ===========================================

            return;
        }

        // на краю, але живої швидкості не вистачає - готуємо відступ для розгону
        if (this.retreatAttempts >= MAX_RETREAT_ATTEMPTS) {
            this.done = true; // евристика вичерпана - хай цей розрив бере BuildPathGoal (якщо він ширший за стрибок - там і так тільки він)
            return;
        }
        this.retreatAttempts++;

        Vec3 dirToLanding = this.jump.landing().subtract(edgeCenter).normalize();
        Vec3 safeRetreat = GapJumpUtils.findSafeRetreatPoint(
                this.mob, dirToLanding.scale(-1), BASE_RUNUP_BLOCKS * this.retreatAttempts);

        if (safeRetreat.distanceTo(this.mob.position()) < ARRIVED_RADIUS) {
            this.done = true; // позаду теж обрив/стіна - нема куди відступати, не штовхаємо в діру
            return;
        }

        this.retreatTarget = safeRetreat;
        this.phase = Phase.RETREATING;
        steerTo(this.retreatTarget);
    }

    @Override
    public void stop() {
        this.jump = null;
        this.retreatTarget = null;
        this.mob.getNavigation().stop();
    }

    private void steerTo(Vec3 target) {
        this.mob.getLookControl().setLookAt(target.x, target.y, target.z, 30.0F, 30.0F);
        this.mob.getMoveControl().setWantedPosition(
                target.x, target.y, target.z, Run_N_JumpUtils.getRunSpeedModifier(this.mob));
    }

    private enum Phase {CHARGING, RETREATING}
}