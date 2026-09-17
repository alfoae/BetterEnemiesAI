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

    // =========================================
    private boolean debugJumpInfoPrinted = false;
    private boolean debugRetreatPrinted = false;
    private boolean debugRunupPrinted = false;
    private int debugRunTicks = 0;
    // =========================================

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

    private static final java.util.Set<Mob> ACTIVE_MOBS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public static boolean isActive(Mob mob) {
        return ACTIVE_MOBS.contains(mob);
    }

    @Override
    public boolean canContinueToUse() {
        boolean result =
                !this.done
                        && this.jump != null
                        && PursuitEnemyBehavior.isMemoryChasing(this.mob);

        System.out.println(
                "[DEBUG GapJumpAssistGoal] CAN_CONTINUE=" + result
                        + " done=" + this.done
                        + " jump=" + this.jump
                        + " memoryChasing="
                        + PursuitEnemyBehavior.isMemoryChasing(this.mob)
                        + " onGround=" + this.mob.onGround()
                        + " pos=" + this.mob.position()
        );

        return result;
    }

    private static String formatVec(Vec3 v) {
        return String.format(
                "(%.3f, %.3f, %.3f)",
                v.x,
                v.y,
                v.z
        );
    }

    @Override
    public void start() {
        this.phase = Phase.CHARGING;
        this.retreatAttempts = 0;
        this.hasBeenAirborne = false;
        this.done = false;

        // === ФІКС ===
        // 1. ACTIVE_MOBS раніше ніколи не заповнювався (тільки remove() в stop() і
        //    contains() в isActive()) - isActive() завжди повертав false, тож
        //    PursuitEnemyMeleeBehavior.shouldYieldToGapJump() тримався ЛИШЕ на
        //    findUpcomingJumpSegment(Path). Реєструємо моба тут, як і задумувалось.
        // 2. mob.getNavigation() досі тримає СТАРИЙ шлях від PursuitEnemyMeleeBehavior
        //    (той свідомо не викликав navigation.stop(), щоб findUpcomingJumpSegment
        //    міг далі читати Path). Але PathNavigation.tick() виконується КОЖЕН тік
        //    БЕЗУМОВНО (customServerAiStep: sensing -> targetSelector -> goalSelector ->
        //    navigation -> controls), тобто ПІСЛЯ goalSelector.tick() - і сам додає
        //    moveControl.setWantedPosition() у напрямку старого шляху (до гравця,
        //    ЧЕРЕЗ розрив), затираючи щойно виставлений steerTo(). Це і є причина всіх
        //    5 багів: retreatTarget ігнорується (дебаг 1, 3), стрибок ніколи не
        //    викликається бо моба просто зносить уперед по старому шляху (дебаг 1, 5),
        //    зайвий імпульс від старого шляху додається до розгону і моб перелітає повз
        //    ціль (дебаг 2, 4). Зупиняємо стару навігацію тут - тепер це безпечно,
        //    бо ACTIVE_MOBS.add() вище вже гарантує shouldYieldToGapJump()=true
        //    незалежно від стану Path.
        ACTIVE_MOBS.add(this.mob);
        this.mob.getNavigation().stop();

        this.debugJumpInfoPrinted = false;
        this.debugRetreatPrinted = false;
        this.debugRunupPrinted = false;
        this.debugRunTicks = 0;

        double requiredSpeed = GapJumpUtils.requiredTakeoffSpeed(this.jump.gapBlocks());

        System.out.println(
                "[DEBUG GAP JUMP] ==============================="
                        + "\nМоб: " + this.mob.getName().getString()
                        + "\nПоточні координати: " + formatVec(this.mob.position())
                        + "\nБлок краю: " + this.jump.edge()
                        + "\nТочка приземлення: " + formatVec(this.jump.landing())
                        + "\nРозрив: " + this.jump.gapBlocks() + " блок."
                        + "\nПотрібна швидкість перед стрибком: " + String.format("%.3f", requiredSpeed)
                        + "\nПоточна швидкість: " + String.format("%.3f", this.mob.getDeltaMovement().horizontalDistance())
                        + "\nФаза: " + this.phase
                        + "\n================================"
        );
    }

    @Override
    public void stop() {
        ACTIVE_MOBS.remove(this.mob);

        this.jump = null;
        this.retreatTarget = null;

        this.mob.getNavigation().stop();
    }

    private void steerTo(Vec3 target) {
        this.mob.getLookControl().setLookAt(target.x, target.y, target.z, 30.0F, 30.0F);
        this.mob.getMoveControl().setWantedPosition(
                target.x, target.y, target.z, Run_N_JumpUtils.getRunSpeedModifier(this.mob));
    }

    @Override
    public void tick() {

        // =========================================================
        // AIRBORNE — моб уже летить
        // =========================================================
        if (!this.mob.onGround()) {
            this.hasBeenAirborne = true;

            steerTo(this.jump.landing());

            System.out.println(
                    "[DEBUG GAP JUMP] МОБ У ПОВІТРІ"
                            + " | pos=" + formatVec(this.mob.position())
                            + " | landing=" + formatVec(this.jump.landing())
                            + " | speed=" + String.format("%.3f",
                            this.mob.getDeltaMovement().horizontalDistance())
            );

            return;
        }

        // =========================================================
        // ПОСАДКА
        // =========================================================
        if (this.hasBeenAirborne) {
            this.done = true;

            System.out.println(
                    "[DEBUG GAP JUMP] ПРИЗЕМЛЕННЯ"
                            + " | pos=" + formatVec(this.mob.position())
                            + " | landing=" + formatVec(this.jump.landing())
                            + " | speed=" + String.format("%.3f",
                            this.mob.getDeltaMovement().horizontalDistance())
            );

            return;
        }

        double requiredSpeed =
                GapJumpUtils.requiredTakeoffSpeed(this.jump.gapBlocks());

        double currentSpeed =
                this.mob.getDeltaMovement().horizontalDistance();

        // =========================================================
        // RETREATING — моб відходить назад для розбігу
        // =========================================================
        if (this.phase == Phase.RETREATING) {

            double liveSpeed =
                    this.mob.getDeltaMovement().horizontalDistance();

            boolean arrived =
                    this.mob.position().distanceTo(this.retreatTarget)
                            < ARRIVED_RADIUS;

            if (arrived || liveSpeed >= requiredSpeed) {

                this.phase = Phase.CHARGING;
                this.debugRunupPrinted = false;
                this.debugRunTicks = 0;

                System.out.println(
                        "[DEBUG GAP JUMP] ПОЧАТОК РОЗБІГУ"
                                + " | pos=" + formatVec(this.mob.position())
                                + " | retreatTarget=" + formatVec(this.retreatTarget)
                                + " | currentSpeed=" + String.format("%.3f", liveSpeed)
                                + " | requiredSpeed=" + String.format("%.3f", requiredSpeed)
                                + " | причина="
                                + (arrived ? "дійшов до точки розбігу" : "швидкість уже достатня")
                );

            } else {

                steerTo(this.retreatTarget);

                // Один раз при початку відходу + потім раз на 5 тіків
                if (!this.debugRetreatPrinted || this.debugRunTicks % 5 == 0) {
                    System.out.println(
                            "[DEBUG GAP JUMP] ВІДХІД ДЛЯ РОЗБІГУ"
                                    + " | pos=" + formatVec(this.mob.position())
                                    + " | target=" + formatVec(this.retreatTarget)
                                    + " | speed=" + String.format("%.3f", liveSpeed)
                                    + " | required=" + String.format("%.3f", requiredSpeed)
                                    + " | distance="
                                    + String.format("%.3f",
                                    this.mob.position().distanceTo(this.retreatTarget))
                    );

                    this.debugRetreatPrinted = true;
                }

                this.debugRunTicks++;
                return;
            }
        }

        // =========================================================
        // CHARGING — рух вперед до краю / сам розбіг
        // =========================================================
        Vec3 edgeCenter = Vec3.atCenterOf(this.jump.edge());

        double distToEdge =
                this.mob.position().distanceTo(edgeCenter);

        // ---------------------------------------------------------
        // Моб ще далеко від краю
        // ---------------------------------------------------------
        if (distToEdge > EDGE_RADIUS) {

            steerTo(this.jump.landing());

            if (!this.debugRunupPrinted) {

                System.out.println(
                        "[DEBUG GAP JUMP] РОЗБІГ"
                                + " | pos=" + formatVec(this.mob.position())
                                + " | edge=" + this.jump.edge()
                                + " | distanceToEdge=" + String.format("%.3f", distToEdge)
                                + " | speed=" + String.format("%.3f", currentSpeed)
                                + " | required=" + String.format("%.3f", requiredSpeed)
                );

                this.debugRunupPrinted = true;
            }

            // Поточне прискорення/швидкість під час розбігу
            if (this.debugRunupPrinted && this.debugRunTicks % 5 == 0) {

                System.out.println(
                        "[DEBUG GAP JUMP] ПОТОЧНЕ ПРИСКОРЕННЯ"
                                + " | pos=" + formatVec(this.mob.position())
                                + " | speed=" + String.format("%.3f", currentSpeed)
                                + " | required=" + String.format("%.3f", requiredSpeed)
                                + " | до краю=" + String.format("%.3f", distToEdge)
                );
            }

            this.debugRunTicks++;
            return;
        }

        // =========================================================
        // Моб уже на краю
        // =========================================================

        System.out.println(
                "[DEBUG GAP JUMP] КРАЙ"
                        + " | pos=" + formatVec(this.mob.position())
                        + " | edge=" + this.jump.edge()
                        + " | speed=" + String.format("%.3f", currentSpeed)
                        + " | required=" + String.format("%.3f", requiredSpeed)
                        + " | gap=" + this.jump.gapBlocks()
        );

        // =========================================================
        // ШВИДКОСТІ ВИСТАЧАЄ — СТРИБАЄМО
        // =========================================================
        if (currentSpeed >= requiredSpeed) {

            steerTo(this.jump.landing());

            System.out.println(
                    "[DEBUG GAP JUMP] СТРИБОК!"
                            + " | pos=" + formatVec(this.mob.position())
                            + " | edge=" + this.jump.edge()
                            + " | landing=" + formatVec(this.jump.landing())
                            + " | currentSpeed=" + String.format("%.3f", currentSpeed)
                            + " | requiredSpeed=" + String.format("%.3f", requiredSpeed)
                            + " | запас="
                            + String.format("%.3f", currentSpeed - requiredSpeed)
            );

            this.mob.getJumpControl().jump();

            return;
        }

        // =========================================================
        // ШВИДКОСТІ НЕ ВИСТАЧАЄ
        // =========================================================
        System.out.println(
                "[DEBUG GAP JUMP] НЕДОСТАТНЄ ПРИСКОРЕННЯ!"
                        + " | pos=" + formatVec(this.mob.position())
                        + " | edge=" + this.jump.edge()
                        + " | currentSpeed=" + String.format("%.3f", currentSpeed)
                        + " | requiredSpeed=" + String.format("%.3f", requiredSpeed)
                        + " | не вистачає="
                        + String.format("%.3f", requiredSpeed - currentSpeed)
                        + " | retreatAttempt=" + this.retreatAttempts
        );

        // =========================================================
        // ПОТРІБЕН НОВИЙ ВІДХІД
        // =========================================================

        if (this.retreatAttempts >= MAX_RETREAT_ATTEMPTS) {

            System.out.println(
                    "[DEBUG GAP JUMP] НЕ ВДАЛОСЯ РОЗІГНАТИСЯ"
                            + " | спроб=" + this.retreatAttempts
                            + " | currentSpeed=" + String.format("%.3f", currentSpeed)
                            + " | requiredSpeed=" + String.format("%.3f", requiredSpeed)
            );

            this.done = true;
            return;
        }

        this.retreatAttempts++;

        Vec3 dirToLanding =
                this.jump.landing()
                        .subtract(edgeCenter)
                        .normalize();

        Vec3 safeRetreat =
                GapJumpUtils.findSafeRetreatPoint(
                        this.mob,
                        dirToLanding.scale(-1),
                        BASE_RUNUP_BLOCKS * this.retreatAttempts
                );

        if (safeRetreat.distanceTo(this.mob.position()) < ARRIVED_RADIUS) {

            // === ФІКС (дебаг 3) === Платформа 1х1: позаду теж обрив, відступати
            // нікуди. Раніше тут Goal просто здавався (done=true) - але це НЕ рятує
            // моба від падіння: він повертається під PursuitEnemyMeleeBehavior, яка
            // все одно веде його прямо в той самий розрив звичайним ходом (бо шлях
            // через GapJumpNodeEvaluator formally "прохідний"), і моб падає туди без
            // жодної спроби стрибка - просто пізніше й без контролю. Замість здаватись
            // наосліп - стрибаємо з тим розгоном, що вже є: requiredSpeed і так рахується
            // із запасом (SAFETY_MARGIN=0.85), тож трохи недостатня жива швидкість
            // нерідко все одно долітає, а гірший випадок нічим не гірший за те падіння,
            // яке сталось би однаково.
            System.out.println(
                    "[DEBUG GAP JUMP] НЕМАЄ МІСЦЯ ДЛЯ РОЗБІГУ - СТРИБАЄМО НАВПРОСТЕЦЬ"
                            + " | pos=" + formatVec(this.mob.position())
                            + " | currentSpeed=" + String.format("%.3f", currentSpeed)
                            + " | requiredSpeed=" + String.format("%.3f", requiredSpeed)
            );

            steerTo(this.jump.landing());
            this.mob.getJumpControl().jump();
            return;
        }

        this.retreatTarget = safeRetreat;
        this.phase = Phase.RETREATING;
        this.debugRetreatPrinted = false;

        System.out.println(
                "[DEBUG GAP JUMP] ВІДХІД"
                        + " | спроба=" + this.retreatAttempts
                        + " | pos=" + formatVec(this.mob.position())
                        + " | retreatTarget=" + formatVec(this.retreatTarget)
                        + " | distance="
                        + String.format("%.3f",
                        this.mob.position().distanceTo(this.retreatTarget))
                        + " | requiredSpeed="
                        + String.format("%.3f", requiredSpeed)
        );

        steerTo(this.retreatTarget);
    }

    private enum Phase {CHARGING, RETREATING}
}