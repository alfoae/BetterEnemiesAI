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

    private static String formatVec(Vec3 v) {
        return String.format(
                "(%.3f, %.3f, %.3f)",
                v.x,
                v.y,
                v.z
        );
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

    // === ФІКС (дебаг2 "падає на 3-4 прижок", дебаг1 "перебіг через пустоту без стрибка") ===
    // За замовчуванням Goal.requiresUpdateEveryTick()==false, і GoalSelector тоді звіряє
    // canUse() для НЕзапущених Goal-ів не щотіку, а десь раз на ~3 тіки (newGoalRate).
    // Платформи тут по 1 блоку завширшки: поки в ці "сліпі" 1-3 тіки PursuitEnemyMeleeBehavior
    // (який про розриви нічого не знає) продовжує вести моба звичайним ходом на біговій
    // швидкості (~0.15 бл/тік), цього достатньо, щоб проскочити всю вузьку платформу
    // наскрізь - і GapJumpAssistGoal просто не встигає перехопити керування на краю
    // взагалі (СТРИБОК! у логах для такого переходу відсутній). Повертаємо true, щоб
    // canUse() перевірявся щотіку - ловимо момент якнайраніше.
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {

        // =========================================================
        // AIRBORNE — моб уже летить
        // =========================================================
        if (!this.mob.onGround()) {
            this.hasBeenAirborne = true;

            // === ФІКС (переліт, дебаг 2 і 4) === requiredTakeoffSpeed/JUMP_AIRTIME_TICKS
            // рахують ЧИСТО балістичний політ (тільки гравітація + DRAG_PER_TICK, жодного
            // розгону в польоті). А steerTo() тут щотіку кликав moveControl на тій самій
            // біговій швидкості, що й на землі. Повітряне керування у ванілі слабше за
            // наземне (менший коефіцієнт прискорення в польоті), але НЕ нульове - і
            // застосоване щотіку весь ~10-тіковий політ, воно додає зайву дистанцію поверх
            // розрахунку. Звідси стабільний переліт повз ціль на 1+ блок (у дебазі 4 - аж
            // у стіну за приземленням, з різким обнуленням швидкості). Явно "гасимо"
            // moveControl (ціль = поточна позиція - а не просто перестаємо кликати
            // steerTo(), інакше moveControl і далі тягне до старої цілі, виставленої ще на
            // землі перед стрибком) і лишаємо тільки погляд на приземлення - нехай летить
            // по інерції, як і порахували.
            Vec3 herePos = this.mob.position();
            this.mob.getMoveControl().setWantedPosition(herePos.x, herePos.y, herePos.z, 0.0);
            this.mob.getLookControl().setLookAt(
                    this.jump.landing().x, this.jump.landing().y, this.jump.landing().z, 30.0F, 30.0F);

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
        // === ФІКС (дебаг 1, дебаг 2 фінальне падіння) === Vec3.atCenterOf() додає +0.5
        // по ВСІХ трьох осях, включно з Y. Але mob.position() стоїть на тому ж Y, що й
        // edge (це рівень "ніг", без +0.5 - так само, як landing/findSafeRetreatPoint
        // рахують через atBottomCenterOf, без зсуву по Y). Через це distanceTo() тут
        // завжди мала зайвих ~0.5 по Y, з'їдаючи більшість EDGE_RADIUS: реальний
        // прохідний горизонтальний радіус був sqrt(0.7²-0.5²)≈0.49 замість заявлених 0.7,
        // і то лише коли підхід рівно по центру блоку. Досить трохи офсету по X чи Z - і
        // "КРАЙ" не встигав спрацювати між двома тіками: моб проходив усю ширину вікна за
        // один крок і опинявся вже в польоті, так і не діставшись до перевірки швидкості й
        // jump() (дебаг 1 - жодного СТРИБОК! за всю сесію; дебаг 2 - те саме на 4-й
        // спробі, вже з достатньою швидкістю, яка так і лишилась невикористаною). Рахуємо
        // суто горизонтально - Y тут взагалі не мало б впливати.
        Vec3 edgeCenter = Vec3.atBottomCenterOf(this.jump.edge());

        double distToEdge = Math.sqrt(
                Math.pow(this.mob.getX() - edgeCenter.x, 2)
                        + Math.pow(this.mob.getZ() - edgeCenter.z, 2)
        );

        // === ФІКС (застрягання "ходить туди-сюди" після пробігу через розрив без
        // стрибка) === distToEdge рахується від edge і не зменшується, а РОСТЕ, щойно
        // моб минув edge і рухається далі до landing - якщо десь (везіння з хітбоксом на
        // куті платформи, чи просто ще один рідкісний випадок, який
        // requiresUpdateEveryTick не покрив) КРАЙ так і не спрацював, і моб на живих
        // ногах перейшов увесь розрив, ця відстань більше НІКОЛИ не стане <= EDGE_RADIUS.
        // Раніше Goal в цьому разі зависав у РОЗБІГ назавжди: canContinueToUse() і далі
        // true (this.jump не null, memoryChasing), а steerTo(landing) щотіку смикає то в
        // один бік від landing, то в інший - от і "ходить туди-сюди" з debug1. Додатково
        // звіряємо з landing: якщо моб уже практично там - вважаємо цей стрибок
        // фактично відбувся (ногами, а не польотом - але результат той самий) і
        // завершуємо Goal, а не жене його далі в порожнечу.
        double distToLanding = Math.sqrt(
                Math.pow(this.mob.getX() - this.jump.landing().x, 2)
                        + Math.pow(this.mob.getZ() - this.jump.landing().z, 2)
        );

        if (distToLanding <= ARRIVED_RADIUS) {
            this.done = true;

            System.out.println(
                    "[DEBUG GAP JUMP] РОЗРИВ ПРОЙДЕНО НОГАМИ (без стрибка)"
                            + " | pos=" + formatVec(this.mob.position())
                            + " | landing=" + formatVec(this.jump.landing())
            );

            return;
        }

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

            // === ФІКС (перепригує 1-блоковий розрив) === Раніше стрибали з тією живою
            // швидкістю, яка вже випадково назбиралась на землі - вона могла помітно
            // перевищувати requiredSpeed (наскільки саме - залежить від того, на якому
            // тіку прискорення перетнуло поріг, а на землі прискорення за тік доволі
            // відчутне). Що вище швидкість відриву, то далі летить моб - тому "з чим
            // встиг розігнатись" і давало нестабільний переліт понад 1-блоковий розрив.
            //
            // === ФІКС (зростаючий переліт/недоліт по кілька стрибків поспіль) ===
            // requiredSpeed рахований з дискретного gapBlocks і дає СТАЛУ дальність
            // польоту щоразу (перевірено - однакова з точністю до тисячних на 3
            // послідовних стрибках). Але платформи по 1 блоку, і точка, де EDGE_RADIUS
            // ловить моба, від стрибка до стрибка трохи гуляє (могло впіймати за 0.02
            // блока до edge, а могло вже за 0.17 блока ЗА ним) - тобто РЕАЛЬНО потрібна
            // дистанція щоразу різна, а стала швидкість/дальність - ні. Помилка від
            // цього не випадкова, а накопичувалась з кожним наступним стрибком (0.014 -
            // 0.108 - 0.230 блока повз ціль поспіль), поки не виносило повз платформу
            // зовсім. Рахуємо швидкість відриву від ЖИВОЇ горизонтальної відстані до
            // landing просто зараз, а не від округленого gapBlocks - requiredSpeed
            // лишається тільки порогом "чи взагалі варто пробувати".
            //
            // Заразом гасимо moveControl на цей тік (як і в польоті), інакше він одразу ж
            // додасть своє прискорення поверх щойно виставленої швидкості, і розрахунок
            // знову "попливе" - погляд на приземлення лишаємо через LookControl окремо.
            double dx = this.jump.landing().x - this.mob.getX();
            double dz = this.jump.landing().z - this.mob.getZ();
            double realDistance = Math.sqrt(dx * dx + dz * dz);
            double launchSpeed = GapJumpUtils.requiredTakeoffSpeedForDistance(realDistance);

            Vec3 launchDir = new Vec3(dx, 0.0, dz).normalize();

            Vec3 vel = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(
                    launchDir.x * launchSpeed,
                    vel.y,
                    launchDir.z * launchSpeed
            );

            Vec3 herePos = this.mob.position();
            this.mob.getMoveControl().setWantedPosition(herePos.x, herePos.y, herePos.z, 0.0);
            this.mob.getLookControl().setLookAt(
                    this.jump.landing().x, this.jump.landing().y, this.jump.landing().z, 30.0F, 30.0F);

            System.out.println(
                    "[DEBUG GAP JUMP] СТРИБОК!"
                            + " | pos=" + formatVec(this.mob.position())
                            + " | edge=" + this.jump.edge()
                            + " | landing=" + formatVec(this.jump.landing())
                            + " | currentSpeed(до фіксу)=" + String.format("%.3f", currentSpeed)
                            + " | realDistance=" + String.format("%.3f", realDistance)
                            + " | launchSpeed=" + String.format("%.3f", launchSpeed)
                            + " | requiredSpeed(поріг)=" + String.format("%.3f", requiredSpeed)
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

        // === ФІКС === Спроби вичерпано - раніше тут Goal здавався (done=true) БЕЗ
        // стрибка. Але здача так само НЕ рятує від падіння: PursuitEnemyMeleeBehavior
        // однаково веде моба в той самий розрив звичайним ходом без жодного стрибка (в
        // дебазі 2 4-й цикл після цього і закінчився падінням у прірву). Тому й тут -
        // стрибаємо з тим розгоном, що є, замість здаватись наосліп.
        if (this.retreatAttempts >= MAX_RETREAT_ATTEMPTS) {

            System.out.println(
                    "[DEBUG GAP JUMP] СПРОБИ ВИЧЕРПАНО - СТРИБАЄМО НАВПРОСТЕЦЬ"
                            + " | спроб=" + this.retreatAttempts
                            + " | currentSpeed=" + String.format("%.3f", currentSpeed)
                            + " | requiredSpeed=" + String.format("%.3f", requiredSpeed)
            );

            steerTo(this.jump.landing());
            this.mob.getJumpControl().jump();
            return;
        }

        Vec3 dirToLanding =
                this.jump.landing()
                        .subtract(edgeCenter)
                        .normalize();

        Vec3 safeRetreat =
                GapJumpUtils.findSafeRetreatPoint(
                        this.mob,
                        dirToLanding.scale(-1),
                        BASE_RUNUP_BLOCKS * (this.retreatAttempts + 1)
                );

        boolean noRoomAtAll =
                safeRetreat.distanceTo(this.mob.position()) < ARRIVED_RADIUS;

        // === ФІКС (дебаг 2, "ходить туди-сюди") === findSafeRetreatPoint зупиняється на
        // першій небезпечній клітині в напрямку відходу - якщо це стіна чи ще один
        // розрив ближче за BASE_RUNUP_BLOCKS, збільшення desiredBlocks на наступній
        // спробі НІЧОГО не змінює: та сама точка повернеться знову. Раніше це не
        // перевірялось, і Goal слухняно повторював ІДЕНТИЧНИЙ відхід-розгін до
        // MAX_RETREAT_ATTEMPTS разів поспіль - видимо це і є те "ходить туди-сюди" з
        // дебага 2. Порівнюємо з точкою МИНУЛОЇ спроби і, якщо вона та сама, одразу
        // стрибаємо, не витрачаючи решту спроб на завідомо той самий результат.
        boolean stuckAtSameSpot =
                this.retreatTarget != null
                        && safeRetreat.distanceTo(this.retreatTarget) < ARRIVED_RADIUS;

        if (noRoomAtAll || stuckAtSameSpot) {

            System.out.println(
                    "[DEBUG GAP JUMP] "
                            + (noRoomAtAll ? "НЕМАЄ МІСЦЯ ДЛЯ РОЗБІГУ" : "УПИРАЄМОСЬ У ТЕ САМЕ МІСЦЕ")
                            + " - СТРИБАЄМО НАВПРОСТЕЦЬ"
                            + " | pos=" + formatVec(this.mob.position())
                            + " | currentSpeed=" + String.format("%.3f", currentSpeed)
                            + " | requiredSpeed=" + String.format("%.3f", requiredSpeed)
            );

            steerTo(this.jump.landing());
            this.mob.getJumpControl().jump();
            return;
        }

        this.retreatAttempts++;
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

    private enum Phase {CHARGING, RETREATING}
}