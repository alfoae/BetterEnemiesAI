package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Виконання стрибка через розрив, який {@link GapJumpNodeEvaluator} уже заклав у sharedPath як
 * "далекий" вузол — сама детекція розриву живе в pathfinding-графі, цей Goal лише читає
 * {@link GapJumpUtils#findUpcomingJumpSegment} і виконує розбіг/стрибок.
 * <p>
 * <b>v5 — чому моб не долітав на 3 блоки, і що змінено.</b> Було ДВІ помилки, що множились:
 * <ol>
 *   <li><b>Модель польоту.</b> Швидкість відриву рахувалась як {@code відстань / (10 * 0.85)}, тобто
 *       вважалось, що моб летить із сталою горизонтальною швидкістю. Насправді на тіку відриву тертя
 *       ще НАЗЕМНЕ (×0.546), а далі повітряне ×0.91 за тік, тож дальність = швидкість × ≈4.92, а
 *       не × 8.5. Для довгих стрибків це давало недоліт ~1 блок ("майже перепригує"). Тепер
 *       швидкість рахує {@link GapJumpPhysics#launchSpeedForDistance} — точно під ванільну фізику.</li>
 *   <li><b>Точка відриву.</b> Моб ловився в колі R=0.7 навколо ЦЕНТРУ блока-краю, тобто відривався
 *       ще ДО блока (більш ніж за 1.2 блока до реального краю), і кожен зайвий блок відстані
 *       посилював похибку п.1. Тепер відрив — коли центр моба на передній межі блока-краю
 *       ({@link GapJumpPhysics#shouldTakeOff}), як стрибає гравець.</li>
 * </ol>
 * Швидкість відриву задається ЯВНО і не залежить від розбігу, тому гейт "жива швидкість ≥ потрібної"
 * і фаза RETREATING (відхід для розгону) більше не потрібні: гейт порівнював deltaMovement (це лише
 * 0.546 від реального кроку за тік) із порогом в іншій шкалі і міг не проходити взагалі, після чого
 * моб стрибав "з тим, що є" — і падав. Sprint-поштовх ванільного jumpFromGround() теж вимкнений на
 * момент відриву: швидкість задаємо повністю самі, без залежності від напрямку погляду.
 * <p>
 * <b>v6 — напрямок стрибка будь-який.</b> Раніше моб стрибав лише вздовж 4 осей (це було зашито в
 * {@link GapJumpNodeEvaluator}); тепер там будь-які зміщення (діагоналі, "коні" тощо), а тут уся
 * геометрія відриву рахується вздовж ВЛАСНОЇ осі стрибка (від центру блока-краю до центру приземлення):
 * передня межа блока = {@link GapJumpPhysics#frontBorder} (0.5 по осі, 0.707 по діагоналі), моб "на краї",
 * коли його хітбокс перекриває блок-край по обох осях, а розбіг іде спершу на центр краю, потім уздовж
 * стрибка (пряма "моб -> landing" по діагоналі зрізала б повз вузький місток). Сама фізика польоту від
 * напрямку не залежить: тертя ізотропне, тож швидкість відриву = відстань / {@link GapJumpPhysics#FLIGHT_FACTOR_TO_LANDING}.
 * <p>
 * <b>v7 — стійкість до запізнілого керування.</b> Ціль отримує керування не одразу: Pursuit спершу сам
 * рухає моба ~2 тіки. Швидкий малий зомбі (прискорення ~0.44 за тік) за цей час проходить понад блок і
 * на платформі 1x1 сходить з краю ще до першого тіку цієї цілі. Тепер (1) відрив дозволений і тоді,
 * коли моб уже за передньою межею, доки onGround ще true, а (2) якщо він уже сповз на 1-2 тіки — рятувальний
 * стрибок із повітря ({@link #tryRescueJump}); {@link #canUse} для цього пропускає й моба, що щойно зійшов.
 * <p>
 * Той самий набір флагів (MOVE+LOOK+JUMP), що й TowerClimbGoal/BuildPathGoal — АЛЕ тригер для
 * {@code PursuitEnemyMeleeBehavior.shouldYieldToTerraforming()} (isPathBlocked) більше НЕ спрацьовує
 * для прохідних-через-стрибок розривів, бо createPath() тепер для них реально знаходить шлях. Тому в
 * PursuitEnemyMeleeBehavior є окрема умова {@code shouldYieldToGapJump()} — без неї GoalSelector
 * ніколи не віддасть нам MOVE/LOOK.
 */
public class GapJumpAssistGoal extends Goal {

    /**
     * Якщо моб уже практично на landing - вважаємо розрив пройденим (ногами, без стрибка).
     */
    private static final double ARRIVED_RADIUS = 0.5;

    /**
     * Запобіжник: скільки тіків моб може добігати до краю. Якщо не дійшов (упирається, зіштовхнули) -
     * відпускаємо керування назад PursuitEnemyMeleeBehavior, а не висимо в розбігу вічно.
     */
    private static final int MAX_CHARGE_TICKS = 100;

    /**
     * Стеля швидкості відриву — у "блоках дальності" понад ширину розриву. Не впливає на звичайні
     * стрибки (точна швидкість завжди нижча), лише не дає вистрілити абсурдною швидкістю, якщо
     * геометрія раптом поламана (моб далеко від краю, застарілий segment тощо).
     */
    private static final double MAX_LAUNCH_EXTRA_BLOCKS = 2.0;

    /** Допуск (частка від потрібної швидкості), в межах якого корекцію польоту вважаємо непотрібною. */
    private static final double CORRECTION_TOLERANCE = 0.01;

    /**
     * Наскільки (у блоках) моб міг просісти нижче рівня краю, щоб його ще можна було врятувати
     * рятувальним стрибком ({@link #tryRescueJump}). 1 тік падіння = 0.078, 2 тіки = 0.23.
     */
    private static final double RESCUE_MAX_DROP = 0.3;

    private final Mob mob;
    private GapJumpUtils.GapJump jump;
    private boolean hasBeenAirborne;
    private boolean jumpFired;
    private boolean airCorrected;
    private boolean done;
    private int chargeTicks;

    // =========================================
    private boolean debugRunupPrinted = false;
    // =========================================

    /**
     * Суто горизонтальна відстань (Y тут взагалі не має впливати - mob.position() на рівні "ніг").
     */
    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
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
    public boolean canUse() {
        if (!PursuitEnemyBehavior.isMemoryChasing(this.mob)) {
            return false;
        }
        GapJumpUtils.GapJump segment = GapJumpUtils.findUpcomingJumpSegment(this.mob);
        if (segment == null) {
            return false;
        }
        // Звичайно моб на землі. Виняток - ЗАПІЗНІЛЕ керування: між стартом Pursuit і першим тіком цієї цілі
        // проходить ~2 тіки (yield перевіряється лише на парних тіках і лише коли Pursuit сам поклав Path у
        // навігатор), а швидкий моб за цей час може вже зійти з краю. Поки він просів менше ніж на
        // RESCUE_MAX_DROP і не летить угору, його ще можна врятувати - див. tryRescueJump().
        if (!this.mob.onGround()) {
            double drop = segment.edge().getY() - this.mob.getY();
            if (drop < -0.05 || drop > RESCUE_MAX_DROP || this.mob.getDeltaMovement().y > 0.1) {
                return false;
            }
        }
        this.jump = segment;
        return true;
    }

    // Тікаємо щотіку: відрив, політ і приземлення мають ловитись покроково (платформи по 1 блоку,
    // моб за тік проходить 0.3-0.9 блока - пропущений тік = пропущений момент відриву).
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.hasBeenAirborne = false;
        this.jumpFired = false;
        this.airCorrected = false;
        this.done = false;
        this.chargeTicks = 0;
        this.debugRunupPrinted = false;

        // 1. ACTIVE_MOBS: PursuitEnemyMeleeBehavior.shouldYieldToGapJump() читає isActive().
        // 2. mob.getNavigation() досі тримає СТАРИЙ шлях від PursuitEnemyMeleeBehavior (той свідомо не
        //    викликав navigation.stop(), щоб findUpcomingJumpSegment міг читати Path). Але
        //    PathNavigation.tick() виконується КОЖЕН тік ПІСЛЯ goalSelector.tick() і сам додає
        //    moveControl.setWantedPosition() у бік старого шляху (до гравця, ЧЕРЕЗ розрив), затираючи
        //    наш steerTo(). Зупиняємо стару навігацію тут.
        ACTIVE_MOBS.add(this.mob);
        this.mob.getNavigation().stop();

        // Спринт лишаємо ТІЛЬКИ для розбігу до краю (атрибут швидкості +30%). У момент самого
        // відриву він вимикається - див. launch().
        this.mob.setSprinting(true);

        Vec3 edgeCenter = Vec3.atBottomCenterOf(this.jump.edge());
        double axisLength = horizontalDistance(edgeCenter, this.jump.landing());
        double startDirX = (this.jump.landing().x - edgeCenter.x) / Math.max(axisLength, 1.0E-6);
        double startDirZ = (this.jump.landing().z - edgeCenter.z) / Math.max(axisLength, 1.0E-6);
        double fromEdgeFront = axisLength - GapJumpPhysics.frontBorder(startDirX, startDirZ);

        System.out.println(
                "[DEBUG GAP JUMP] ==============================="
                        + "\nМоб: " + this.mob.getName().getString()
                        + "\nПоточні координати: " + formatVec(this.mob.position())
                        + "\nБлок краю: " + this.jump.edge()
                        + "\nТочка приземлення: " + formatVec(this.jump.landing())
                        + "\nЗміщення край->приземлення (dx,dz): ("
                        + (int) Math.round(this.jump.landing().x - edgeCenter.x) + ", "
                        + (int) Math.round(this.jump.landing().z - edgeCenter.z) + ")"
                        + "\nРозрив: " + this.jump.gapBlocks() + " блок."
                        + "\nЦентр краю -> центр приземлення: " + String.format("%.3f", axisLength)
                        + "\nШвидкість відриву з самого краю (розрахунок): "
                        + String.format("%.3f", GapJumpPhysics.launchSpeedForDistance(fromEdgeFront))
                        + " (політ " + GapJumpPhysics.AIRTIME_TICKS + " тіків, дальність = швидкість x "
                        + String.format("%.3f", GapJumpPhysics.FLIGHT_FACTOR_TO_LANDING) + ")"
                        + "\nПоточна швидкість: " + String.format("%.3f", this.mob.getDeltaMovement().horizontalDistance())
                        + "\n================================"
        );
    }

    @Override
    public void tick() {

        // =========================================================
        // AIRBORNE — моб уже летить
        // =========================================================
        if (!this.mob.onGround()) {
            if (!this.hasBeenAirborne && !this.jumpFired) {
                // Моб втратив опору, так і не отримавши команди на стрибок - майже завжди через ЗАПІЗНІЛЕ
                // керування (див. canUse). Поки він просів зовсім небагато, стрибаємо просто з повітря.
                if (!tryRescueJump()) {
                    System.out.println(
                            "[DEBUG GAP JUMP] !!! МОБ ЗІЙШОВ З КРАЮ БЕЗ СТРИБКА (врятувати вже не можна)"
                                    + " | pos=" + formatVec(this.mob.position())
                                    + " | edge=" + this.jump.edge()
                                    + " | speed=" + String.format("%.3f", this.mob.getDeltaMovement().horizontalDistance())
                    );
                }
            }
            this.hasBeenAirborne = true;

            // Самокорекція на ПЕРШОМУ тіку польоту (див. correctFlight): підстраховка від того, що на
            // тіку відриву щось пішло не за моделлю (інше тертя блока, стрибок не в той самий тік...).
            if (this.jumpFired && !this.airCorrected) {
                this.airCorrected = true;
                correctFlight();
            }

            // Політ у нас ЧИСТО балістичний (так його і рахує GapJumpPhysics): жодного керування в
            // повітрі. Явно "гасимо" moveControl (ціль = поточна позиція, а не просто перестаємо
            // кликати steerTo(): інакше moveControl і далі тягне до старої цілі, виставленої ще на
            // землі) і лишаємо тільки погляд на приземлення.
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

            // Похибка приземлення відносно ЦЕНТРУ блока: вздовж осі стрибка (+ переліт, - недоліт) і
            // вбік. Модель польоту точна, тож при звичайних блоках очікуємо |вздовж| ~ 0.0-0.1.
            // Якщо стабільно щось інше - значить, тертя блока не 0.6 (лід/слайм/...) або змінена
            // сила стрибка мобом.
            Vec3 edgeCenter = Vec3.atBottomCenterOf(this.jump.edge());
            double axisX = this.jump.landing().x - edgeCenter.x;
            double axisZ = this.jump.landing().z - edgeCenter.z;
            double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);
            double dirX = axisLength > 1.0E-6 ? axisX / axisLength : 0.0;
            double dirZ = axisLength > 1.0E-6 ? axisZ / axisLength : 0.0;
            double errX = this.mob.getX() - this.jump.landing().x;
            double errZ = this.mob.getZ() - this.jump.landing().z;
            double errAlong = errX * dirX + errZ * dirZ;
            double errSide = errX * dirZ - errZ * dirX;

            System.out.println(
                    "[DEBUG GAP JUMP] ПРИЗЕМЛЕННЯ"
                            + " | pos=" + formatVec(this.mob.position())
                            + " | landing=" + formatVec(this.jump.landing())
                            + " | помилка вздовж осі=" + String.format("%+.3f", errAlong)
                            + " (+переліт / -недоліт)"
                            + " | вбік=" + String.format("%+.3f", errSide)
                            + " | speed=" + String.format("%.3f",
                            this.mob.getDeltaMovement().horizontalDistance())
            );

            return;
        }

        // =========================================================
        // НА ЗЕМЛІ, ДО ВІДРИВУ
        // =========================================================
        Vec3 landing = this.jump.landing();
        Vec3 edgeCenter = Vec3.atBottomCenterOf(this.jump.edge());

        // === Моб уже практично на landing без стрибка (перейшов ногами) ===
        // Без цього перевірки Goal у такому разі зависав би в розбігу назавжди (distToEdge більше
        // ніколи не спрацює, а steerTo(landing) щотіку смикає моба туди-сюди).
        double distToLanding = horizontalDistance(this.mob.position(), landing);
        if (distToLanding <= ARRIVED_RADIUS) {
            this.done = true;

            System.out.println(
                    "[DEBUG GAP JUMP] РОЗРИВ ПРОЙДЕНО НОГАМИ (без стрибка)"
                            + " | pos=" + formatVec(this.mob.position())
                            + " | landing=" + formatVec(landing)
            );

            return;
        }

        // === Геометрія вздовж осі стрибка (від ЦЕНТРУ блока-краю у бік landing) ===
        // РАНІШЕ тут міряли евклідову відстань до ЦЕНТРУ блока-краю і ловили моба в колі R>=0.7 -
        // тобто відрив ставався ще за ~0.7 блока ДО центру (більш ніж за 1.2 до реального краю).
        double axisX = landing.x - edgeCenter.x;
        double axisZ = landing.z - edgeCenter.z;
        double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);
        if (axisLength < 1.0E-6) {
            this.done = true; // вироджений сегмент: край і landing збігаються - нічого стрибати
            return;
        }
        double dirX = axisX / axisLength;
        double dirZ = axisZ / axisLength;

        double relX = this.mob.getX() - edgeCenter.x;
        double relZ = this.mob.getZ() - edgeCenter.z;
        double along = relX * dirX + relZ * dirZ;          // скільки пройшов уздовж стрибка від центру краю
        double across = Math.abs(relX * dirZ - relZ * dirX); // на скільки збоку від осі

        Vec3 vel = this.mob.getDeltaMovement();
        double speedAlong = vel.x * dirX + vel.z * dirZ;
        double halfWidth = this.mob.getBbWidth() * 0.5;
        double groundAccel = GapJumpPhysics.estimateGroundAccel(GapJumpUtils.runSpeedSetpoint(this.mob));

        // Передня межа блока-краю ВЗДОВЖ напрямку стрибка: 0.5 по осі, 0.707 по діагоналі (кут блока).
        double front = GapJumpPhysics.frontBorder(dirX, dirZ);

        // Моб має бути поблизу ОСІ стрибка (на смузі блока-краю), а не збоку на широкій платформі: звідти
        // відрив приземлив би його на цій же платформі, а не за розривом. ВЗДОВЖ осі обмеження навмисно
        // НЕМАЄ: якщо керування прийшло запізно й моб уже пробіг за передню межу (навіть повис над
        // проваллям), прапор onGround ще тік лишається true - стрибок ще виконається, і швидкість
        // відриву все одно рахується від ПОТОЧНОЇ позиції. Забороняти відрив тут = зіштовхнути моба.
        double lateralLimit = (0.5 + halfWidth) * (Math.abs(dirX) + Math.abs(dirZ));
        boolean nearAxis = across <= lateralLimit;
        boolean atEdge = nearAxis
                && GapJumpPhysics.shouldTakeOff(relX, relZ, dirX, dirZ, speedAlong, halfWidth, groundAccel);

        // ---------------------------------------------------------
        // Ще не час: біжимо далі до краю
        // ---------------------------------------------------------
        if (!atEdge) {
            this.chargeTicks++;
            if (this.chargeTicks > MAX_CHARGE_TICKS) {
                this.done = true;
                System.out.println(
                        "[DEBUG GAP JUMP] РОЗБІГ ЗАТЯГНУВСЯ - відпускаємо керування"
                                + " | pos=" + formatVec(this.mob.position())
                                + " | along=" + String.format("%.3f", along)
                                + " | across=" + String.format("%.3f", across)
                );
                return;
            }

            steerTowardTakeoff(edgeCenter, dirX, dirZ, relX, relZ);

            if (!this.debugRunupPrinted || this.chargeTicks % 5 == 0) {
                System.out.println(
                        "[DEBUG GAP JUMP] РОЗБІГ"
                                + " | pos=" + formatVec(this.mob.position())
                                + " | along=" + String.format("%+.3f", along)
                                + " (край блока = +" + String.format("%.3f", front) + ")"
                                + " | across=" + String.format("%.3f", across)
                                + " | speed=" + String.format("%.3f", speedAlong)
                );
                this.debugRunupPrinted = true;
            }
            return;
        }

        // ---------------------------------------------------------
        // Моб на краю — СТРИБАЄМО
        // ---------------------------------------------------------
        launch(landing, along, across, speedAlong, front);
    }

    /**
     * Відрив. Швидкість рахується від ЖИВОЇ горизонтальної відстані до центру landing (а не від
     * округленого gapBlocks) за точною ванільною моделлю польоту, тож моб приземляється рівно на
     * центр блока незалежно від того, на якому саме тіку й де саме на краю його "піймали".
     */
    private void launch(Vec3 landing, double along, double across, double speedAlong, double front) {
        double dx = landing.x - this.mob.getX();
        double dz = landing.z - this.mob.getZ();
        double realDistance = Math.sqrt(dx * dx + dz * dz);

        double exactSpeed = GapJumpPhysics.launchSpeedForDistance(realDistance);
        double speedCap = GapJumpPhysics.launchSpeedForDistance(segmentLength() + MAX_LAUNCH_EXTRA_BLOCKS);
        double launchSpeed = Math.min(exactSpeed, speedCap);

        Vec3 launchDir = new Vec3(dx, 0.0, dz).normalize();

        // Sprint-поштовх ванільного jumpFromGround() (+0.2 у бік getYRot()) вимикаємо: швидкість
        // відриву задаємо ПОВНІСТЮ самі, і вона не залежить від того, куди на цей момент довернувся моб.
        this.mob.setSprinting(false);

        Vec3 vel = this.mob.getDeltaMovement();
        this.mob.setDeltaMovement(
                launchDir.x * launchSpeed,
                vel.y,
                launchDir.z * launchSpeed
        );

        // Гасимо moveControl на цей тік (інакше він додасть своє наземне прискорення поверх щойно
        // виставленої швидкості - на тіку відриву ванільний travel() ще рахує землю під ногами).
        Vec3 herePos = this.mob.position();
        this.mob.getMoveControl().setWantedPosition(herePos.x, herePos.y, herePos.z, 0.0);
        this.mob.getLookControl().setLookAt(landing.x, landing.y, landing.z, 30.0F, 30.0F);

        // Обличчям на landing (для вигляду; на дальність більше не впливає - sprint-поштовху нема).
        this.mob.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        this.mob.setYHeadRot(this.mob.getYRot());

        System.out.println(
                "[DEBUG GAP JUMP] СТРИБОК!"
                        + " | pos=" + formatVec(this.mob.position())
                        + " | edge=" + this.jump.edge()
                        + " | landing=" + formatVec(landing)
                        + " | along=" + String.format("%+.3f", along)
                        + " (край блока = +" + String.format("%.3f", front) + ")"
                        + " | across=" + String.format("%.3f", across)
                        + " | швидкість до відриву=" + String.format("%.3f", speedAlong)
                        + " | realDistance=" + String.format("%.3f", realDistance)
                        + " | launchSpeed=" + String.format("%.3f", launchSpeed)
                        + (exactSpeed > speedCap ? " (ОБМЕЖЕНО стелею, точна=" + String.format("%.3f", exactSpeed) + ")" : "")
                        + " | очікувана дальність польоту=" + String.format("%.3f", GapJumpPhysics.flightDistance(launchSpeed))
                        + (this.chargeTicks == 0 && along > front + 0.1
                        ? " | ЗАПІЗНІЛЕ КЕРУВАННЯ: моб уже за краєм у перший тік цілі" : "")
        );

        this.jumpFired = true;
        this.mob.getJumpControl().jump();
    }

    /**
     * Рятувальний стрибок: моб уже зійшов з краю (прапор onGround став false), а стрибок ми так і не
     * скомандували. Це буває, коли керування прийшло запізно (див. {@link #canUse}), а моб швидкий - малий
     * зомбі з прискоренням ~0.44 за тік проходить за 2 тіки понад блок, тобто всю платформу 1x1.
     * <p>
     * Поки моб просів зовсім небагато ({@link #RESCUE_MAX_DROP}) і не летить угору, даємо йому той самий
     * імпульс стрибка, що й на землі (вертикальна швидкість 0.42), а горизонтальну рахуємо з ФАКТИЧНОГО
     * стану (висота, вертикальна швидкість) — так само, як {@link #correctFlight}: за решту польоту
     * повітряне тертя 0.91 донесе його рівно до центру landing. Візуально це виглядає як "підстрибнув
     * на самому краю". Для мобів, що зійшли з краю без цілі (нокбек, падіння), не спрацьовує: ціль
     * запускається лише коли попереду в Path є стрибковий сегмент і моб на рівні його краю.
     *
     * @return true, якщо стрибок було дано
     */
    private boolean tryRescueJump() {
        Vec3 landing = this.jump.landing();
        Vec3 vel = this.mob.getDeltaMovement();
        double heightAbove = this.mob.getY() - landing.y;
        double dx = landing.x - this.mob.getX();
        double dz = landing.z - this.mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        boolean falling = vel.y <= 0.1;
        boolean nearLevel = heightAbove >= -RESCUE_MAX_DROP && heightAbove <= 0.2;
        // Нижньої межі відстані навмисно нема: моб міг уже опинитись НАД блоком приземлення, просівши на
        // 0.078 нижче його верху - тоді йому якраз потрібен маленький підскок, щоб виповзти на блок.
        boolean inRange = dist <= segmentLength() + 1.0;
        if (!falling || !nearLevel || !inRange || dist < 1.0E-6) {
            return false;
        }

        double jumpVy = GapJumpPhysics.JUMP_VERTICAL_VELOCITY;
        double factor = GapJumpPhysics.remainingAirFactor(heightAbove, jumpVy);
        double cap = GapJumpPhysics.launchSpeedForDistance(segmentLength() + MAX_LAUNCH_EXTRA_BLOCKS);
        double needed = Math.min(dist / factor, cap);

        this.mob.setSprinting(false);
        this.mob.setDeltaMovement(dx / dist * needed, jumpVy, dz / dist * needed);
        this.jumpFired = true;
        this.airCorrected = true; // швидкість уже розрахована з фактичного стану - друга корекція не потрібна

        System.out.println(
                "[DEBUG GAP JUMP] РЯТІВНИЙ СТРИБОК (керування прийшло запізно)"
                        + " | pos=" + formatVec(this.mob.position())
                        + " | просів на=" + String.format("%.3f", -heightAbove)
                        + " | до landing=" + String.format("%.3f", dist)
                        + " | швидкість=" + String.format("%.3f", needed)
                        + " | повітряний множник дальності=x" + String.format("%.2f", factor)
        );
        return true;
    }

    /**
     * Самокорекція горизонтальної швидкості на першому тіку польоту.
     * <p>
     * Швидкість відриву в {@link #launch} розрахована під ванільний тік відриву (наземне тертя
     * 0.546). Якщо реальність відхилилась (лід/слайм під ногами, інша сила стрибка тощо), моб
     * після тіку відриву матиме не ту швидкість. Але ЗСУВ за сам тік відриву дорівнює швидкості
     * відриву незалежно від тертя - тож на початку 2-го тіку ми точно знаємо, де моб, і можемо
     * порахувати за станом (висота, вертикальна швидкість) швидкість, з якою ті ж повітряні тіки
     * донесуть його рівно до центру landing. Якщо модель була точна - збіг у межах 1% і нічого не
     * змінюється; якщо ні - виправляємо і пишемо в лог, наскільки саме (корисна діагностика).
     */
    private void correctFlight() {
        Vec3 landing = this.jump.landing();
        Vec3 vel = this.mob.getDeltaMovement();
        double heightAbove = this.mob.getY() - landing.y;

        // Виправляємо лише справжній початок стрибка: моб іде вгору і вище рівня landing.
        if (vel.y <= 0.0 || heightAbove < 0.2) {
            System.out.println("[DEBUG GAP JUMP] КОРЕКЦІЇ ПОЛЬОТУ НЕ БУЛО (нетиповий стан: vy="
                    + String.format("%.3f", vel.y) + ", висота=" + String.format("%.3f", heightAbove) + ")");
            return;
        }

        double dx = landing.x - this.mob.getX();
        double dz = landing.z - this.mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-6) {
            return;
        }
        double dirX = dx / dist;
        double dirZ = dz / dist;

        double factor = GapJumpPhysics.remainingAirFactor(heightAbove, vel.y);
        double needed = dist / factor;
        // Стеля від абсурду (нормальна швидкість тут = 0.546 * launchSpeed).
        double cap = GapJumpPhysics.GROUND_FRICTION
                * GapJumpPhysics.launchSpeedForDistance(segmentLength() + MAX_LAUNCH_EXTRA_BLOCKS) * 2.0;
        needed = Math.min(needed, cap);

        double along = vel.x * dirX + vel.z * dirZ;
        double side = vel.x * dirZ - vel.z * dirX;
        boolean off = Math.abs(along - needed) > CORRECTION_TOLERANCE * needed
                || Math.abs(side) > CORRECTION_TOLERANCE * needed;

        if (off) {
            this.mob.setDeltaMovement(dirX * needed, vel.y, dirZ * needed);
        }

        System.out.println(
                "[DEBUG GAP JUMP] КОРЕКЦІЯ ПОЛЬОТУ: " + (off ? "ВИПРАВЛЕНО" : "не потрібна (модель точна)")
                        + " | швидкість була=" + String.format("%.3f", along)
                        + " | потрібна=" + String.format("%.3f", needed)
                        + " | збоку було=" + String.format("%+.3f", side)
                        + " | до landing=" + String.format("%.3f", dist)
                        + " | повітряний множник дальності=x" + String.format("%.2f", factor)
        );
    }

    @Override
    public void stop() {
        ACTIVE_MOBS.remove(this.mob);

        this.jump = null;

        this.mob.setSprinting(false);
        this.mob.getNavigation().stop();
    }

    /**
     * Розбіг ДО КРАЮ, а не одразу на точку приземлення. Для стрибка вздовж осі це та сама пряма, а от
     * для діагоналі/косого напрямку пряма "моб -> landing" зрізає повз блок-край (з вузького містка
     * моб просто зісковзнув би вбік ще до краю). Тому: поки центр моба не над блоком-краєм - біжимо на
     * ЦЕНТР цього блока, а вже над ним - уздовж напрямку стрибка, трохи за передню межу (щоб не
     * гальмував біля краю; сам відрив вирішує {@link GapJumpPhysics#shouldTakeOff}).
     */
    private void steerTowardTakeoff(Vec3 edgeCenter, double dirX, double dirZ, double relX, double relZ) {
        Vec3 target;
        if (Math.abs(relX) <= 0.5 && Math.abs(relZ) <= 0.5) {
            double reach = GapJumpPhysics.frontBorder(dirX, dirZ) + 0.5;
            target = new Vec3(edgeCenter.x + dirX * reach, edgeCenter.y, edgeCenter.z + dirZ * reach);
        } else {
            target = edgeCenter;
        }
        steerTo(target);
    }

    /**
     * Відстань між центром блока-краю і центром приземлення (для осі це gap + 1).
     */
    private double segmentLength() {
        Vec3 edgeCenter = Vec3.atBottomCenterOf(this.jump.edge());
        return horizontalDistance(edgeCenter, this.jump.landing());
    }

    private void steerTo(Vec3 target) {
        this.mob.getLookControl().setLookAt(target.x, target.y, target.z, 30.0F, 30.0F);
        this.mob.getMoveControl().setWantedPosition(
                target.x, target.y, target.z, Run_N_JumpUtils.getRunSpeedModifier(this.mob));
    }
}