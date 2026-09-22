package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import net.minecraft.core.BlockPos;
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
 *       швидкість рахує {@link GapJumpPhysics#launchSpeedForDistance} — точно під ванільну фізику
 *       (у v9 політ ще й рівномірний, див. {@link #controlFlight}).</li>
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
 * <b>v8 — перестрибування проміжних платформ.</b> {@code ▣▢▣▢▣}: сегмент може вести з 1-го блока одразу на 3-й,
 * над 2-м (це вирішує {@link GapJumpNodeEvaluator}). Цій цілі нічого спеціального для цього не треба: швидкість
 * відриву рахується від відстані до центру приземлення, а політ над проміжним блоком безпечний (ноги вже
 * вище 0.42 над його верхом). У лог на старті лише додається рядок "ПЕРЕСТРИБУВАННЯ", якщо під
 * маршрутом є проміжна платформа.
 * <p>
 * <b>v10 — стрибки підряд без гальмування.</b> Раніше між двома стрибками моб "просідав": політ затухав x0.91 за
 * тік (на приземленні лишалось ~0.06 бл/тік), а потім ~3 тіки ніхто не керував мобом (done -> зайвий тік -> stop ->
 * старт Pursuit -> ~2 тіки до нової цілі): стрибок -> гальмо -> стрибок -> гальмо. Тепер (1) стрибок завжди
 * ВАНІЛЬНИЙ (12 тіків, ~1.25 блока), а горизонталь РІВНОМІРНА ({@link #controlFlight}: відстань / тіків до
 * приземлення) - довжина стрибка задає швидкість, для короткого вона менша ("ходьба"); (2) наступний стрибок
 * стартує в тіку приземлення ({@link #chainNextSegment}) без передачі керування Pursuit; (3) на землі між
 * стрибками моб зберігає швидкість польоту ({@link #carryTo}), а не розганяється й не гальмує.
 * <p>
 * <b>Відкидання працює.</b> Ціль щотіку сама виставляє горизонтальну швидкість (політ, збереження швидкості на
 * землі), а ванільне відкидання - це разова зміна deltaMovement, яку наступний тік цілі затер би (так було у
 * v9/v10: у паркурі мобів не відкидало). Тому ціль перевіряє, чи швидкість після нашого виставлення змінилась
 * ЛИШЕ тертям ({@link #checkExternalImpulse}); якщо ні - її вдарили/штовхнули: керування швидкістю знімається до
 * кінця сегмента, ланцюжок не продовжується.
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

    /**
     * Ланцюжок: між приземленням і наступним відривом моб іде по землі зі ШВИДКІСТЮ ПОЛЬОТУ (не розганяється
     * до максимуму й не гальмує). Це діє лише поки він біля точки відриву й не довше стількох тіків -
     * далі (довгий підхід по широкій платформі) моб біжить своєю звичайною швидкістю.
     */
    private static final int MAX_CARRY_TICKS = 6;
    private static final double CARRY_MAX_TAKEOFF_DISTANCE = 1.6;

    /** Скільки стрибків підряд ціль виконує, не віддаючи керування (запобіжник від нескінченного циклу). */
    private static final int MAX_CHAIN_SEGMENTS = 32;

    /** Наступний стрибок ланцюжка має починатись не далі ніж за стільки блоків від моба. */
    private static final double CHAIN_MAX_EDGE_DISTANCE = 4.0;

    /**
     * Розпізнавання ЗОВНІШНЬОГО імпульсу (удар/відкидання, поштовх, вибух). Ми щотіку самі виставляємо
     * горизонтальну швидкість (політ, збереження швидкості на землі); до наступного тіку її змінює лише тертя:
     * швидкість лишається на тій самій прямій і множиться на 0.3-1.0. Ванільне відкидання ж дає
     * {@code old/2 + поштовх*сила} - величина й напрямок інші. Такий тік ми не перезаписуємо.
     * Допуск: проекція на виставлений напрямок у частках {@code [MIN, MAX]}, бічна складова не більше
     * {@code FRACTION * швидкість + MIN_ABS} (дрібні штовхання сусідніх мобів імпульсом не вважаємо).
     */
    private static final double IMPULSE_MIN_RATIO = 0.30;
    private static final double IMPULSE_MAX_RATIO = 1.05;
    private static final double IMPULSE_LATERAL_FRACTION = 0.25;
    private static final double IMPULSE_LATERAL_MIN_ABS = 0.02;

    /**
     * Наскільки (у блоках) моб міг просісти нижче рівня краю, щоб його ще можна було врятувати
     * рятувальним стрибком ({@link #tryRescueJump}). 1 тік падіння = 0.078, 2 тіки = 0.23.
     */
    private static final double RESCUE_MAX_DROP = 0.3;

    private final Mob mob;
    private GapJumpUtils.GapJump jump;
    private boolean hasBeenAirborne;
    private boolean jumpFired;
    private boolean done;
    private int chargeTicks;
    private static final java.util.Set<Mob> ACTIVE_MOBS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /**
     * Горизонтальний крок за тік попереднього польоту (бл/тік) - його ланцюжок "несе" через землю.
     */
    private double lastFlightStep;
    /**
     * Швидкість на землі до наступного відриву (0 = не керуємо, іде звичайний розбіг).
     */
    private double carryStep;
    private int chainCount;
    /**
     * Горизонтальна швидкість, яку ми виставили на попередньому тіку (щоб відрізнити зовнішній імпульс).
     */
    private double lastSetX;
    private double lastSetZ;
    private boolean hasLastSet;

    // =========================================
    private boolean debugRunupPrinted = false;
    // =========================================

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
    /**
     * У цьому сегменті моба вдарили/штовхнули: його швидкість більше НЕ перезаписуємо.
     */
    private boolean impulseDetected;

    // Тікаємо щотіку: відрив, політ і приземлення мають ловитись покроково (платформи по 1 блоку,
    // моб за тік проходить 0.3-0.9 блока - пропущений тік = пропущений момент відриву).
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    public GapJumpAssistGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

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
        this.done = false;
        this.chainCount = 0;

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

        beginSegment(false);
    }

    /**
     * Суто горизонтальна відстань (Y тут взагалі не має впливати - mob.position() на рівні "ніг").
     */
    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String formatVec(Vec3 v) {
        return String.format(
                "(%.3f, %.3f, %.3f)",
                v.x,
                v.y,
                v.z
        );
    }

    /**
     * Скидає стан під поточний {@code this.jump} і друкує заголовок. Викликається зі {@link #start()} і
     * при переході на НАСТУПНИЙ стрибок ланцюжка ({@link #chainNextSegment}).
     */
    private void beginSegment(boolean chained) {
        this.hasBeenAirborne = false;
        this.jumpFired = false;
        this.chargeTicks = 0;
        this.debugRunupPrinted = false;
        this.carryStep = chained ? this.lastFlightStep : 0.0;
        this.impulseDetected = false;
        this.hasLastSet = false;
        if (chained) {
            this.mob.setSprinting(true);
        }

        Vec3 edgeCenter = Vec3.atBottomCenterOf(this.jump.edge());
        double axisLength = horizontalDistance(edgeCenter, this.jump.landing());

        System.out.println(
                "[DEBUG GAP JUMP] ===============================" + (chained ? " (ЛАНЦЮЖОК: без передачі керування, швидкість збережена: "
                        + String.format("%.3f", this.carryStep) + " бл/тік)" : "")
                        + "\nМоб: " + this.mob.getName().getString()
                        + "\nПоточні координати: " + formatVec(this.mob.position())
                        + "\nБлок краю: " + this.jump.edge()
                        + "\nТочка приземлення: " + formatVec(this.jump.landing())
                        + "\nЗміщення край->приземлення (dx,dz): ("
                        + (int) Math.round(this.jump.landing().x - edgeCenter.x) + ", "
                        + (int) Math.round(this.jump.landing().z - edgeCenter.z) + ")"
                        + "\nРозрив: " + this.jump.gapBlocks() + " блок."
                        + "\nОцінка дальності моба (макс. розрив): " + GapJumpUtils.estimateMaxJumpRangeBlocks(this.mob) + " блок."
                        + platformsNote(edgeCenter)
                        + "\nЦентр краю -> центр приземлення: " + String.format("%.3f", axisLength)
                        + "\nПоточна швидкість: " + String.format("%.3f", this.mob.getDeltaMovement().horizontalDistance())
                        + "\n================================"
        );
    }

    @Override
    public void tick() {

        // Ціль уже завершена (приземлився / пройшов ногами / розбіг затягнувся). GoalSelector перевіряє
        // canContinueToUse() лише на ПАРНИХ тіках, а на непарних тікає запущені цілі з
        // requiresUpdateEveryTick() без цієї перевірки - тож після done ми можемо отримати ще один tick().
        // Нічого не робимо (раніше в цьому тіку приземлення друкувалось вдруге).
        if (this.done) {
            return;
        }

        // Чи не вдарили/штовхнули моба після того, як ми востаннє виставляли йому швидкість? Якщо так - більше
        // не перезаписуємо її (інакше відкидання зникає: раніше цього не було, бо швидкість задавалась раз на відриві).
        checkExternalImpulse();

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

            // РІВНОМІРНИЙ політ: щотіку виставляємо горизонтальну швидкість = відстань_до_центру /
            // тіків_до_приземлення (див. controlFlight). Раніше швидкість задавалась раз на відриві й далі
            // затухала x0.91 за тік - моб приземлявся на 0.06 бл/тік і майже зупинявся.
            if (this.jumpFired && !this.impulseDetected) {
                controlFlight();
            }

            // Горизонталь у повітрі ведемо самі (controlFlight), тож штатне керування моба гасимо: ціль
            // moveControl = поточна позиція (а не просто перестаємо кликати steerTo(): інакше moveControl
            // і далі тягне до старої цілі, виставленої ще на землі); лишаємо тільки погляд на приземлення.
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

            // ЛАНЦЮЖОК: якщо попереду знову стрибок - стартуємо його ЩЕ В ЦЬОМУ ТІКУ, не віддаючи керування
            // Pursuit. Інакше після кожного приземлення моб ~3 тіки лишався без керування (done -> ще
            // один порожній тік -> stop -> старт Pursuit -> ~2 тіки до нової цілі) і стояв на місці.
            if (this.impulseDetected || !chainNextSegment()) {
                this.done = true;
                return;
            }
            // Стан скинуто під наступний сегмент - далі цей самий тік іде наземною гілкою (розбіг до краю).
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
        // Поки моб іде до відриву зі збереженою швидкістю польоту (ланцюжок, див. carryTo), швидкість на землі
        // виставляємо САМІ й MoveControl вимкнено, тобто прискорення нема - інакше правило відриву
        // "передбачало" б розгін (для малого зомбі 0.44 за тік) і відривало б моба ще від центру платформи.
        boolean carryMode = this.carryStep > 0.0 && this.chargeTicks <= MAX_CARRY_TICKS && !this.impulseDetected;
        double groundAccel = carryMode
                ? 0.0
                : GapJumpPhysics.estimateGroundAccel(GapJumpUtils.runSpeedSetpoint(this.mob));

        // Передня межа блока-краю ВЗДОВЖ напрямку стрибка: 0.5 по осі, 0.707 по діагоналі (кут блока).
        double front = GapJumpPhysics.frontBorder(dirX, dirZ);

        // Моб має бути поблизу ОСІ стрибка (на смузі блока-краю), а не збоку на широкій платформі: звідти
        // відрив приземлив би його на цій же платформі, а не за розривом. ВЗДОВЖ осі обмеження навмисно
        // НЕМАЄ: якщо керування прийшло запізно й моб уже пробіг за передню межу (навіть повис над
        // проваллям), прапор onGround ще тік лишається true - стрибок ще виконається, і швидкість
        // відриву все одно рахується від ПОТОЧНОЇ позиції. Забороняти відрив тут = зіштовхнути моба.
        double lateralLimit = (0.5 + halfWidth) * (Math.abs(dirX) + Math.abs(dirZ));
        boolean nearAxis = across <= lateralLimit;
        // Швидкість для правила відриву. У режимі збереження швидкості наступний крок відомий точно (це carryStep);
        // брати його з deltaMovement не можна: у перший тік після приземлення там залишок ПОВІТРЯНОГО польоту
        // (тертя 0.91), а правило ділить на НАЗЕМНЕ (0.546) і завищило б крок майже вдвічі.
        double speedForRule = carryMode ? this.carryStep * GapJumpPhysics.GROUND_FRICTION : speedAlong;
        boolean atEdge = nearAxis
                && GapJumpPhysics.shouldTakeOff(relX, relZ, dirX, dirZ, speedForRule, halfWidth, groundAccel);

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

            Vec3 takeoffTarget = takeoffTarget(edgeCenter, dirX, dirZ, relX, relZ);
            if (carryMode
                    && horizontalDistance(this.mob.position(), takeoffTarget) <= CARRY_MAX_TAKEOFF_DISTANCE) {
                carryTo(takeoffTarget);
            } else {
                steerTo(takeoffTarget);
            }

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
     * Відрив. Стрибок завжди ВАНІЛЬНИЙ, як натискання пробілу: підйом 0.42, 12 тіків, висота ~1.25 блока -
     * незалежно від відстані. Горизонталь рівномірна: {@code відстань до центру landing / 12} за тік (далі щотіку
     * {@link #controlFlight} це підтримує й підганяє). Довгий стрибок - ~0.29 бл/тік (~5.8 бл/с, як спринт
     * гравця), короткий - повільніший ("швидкість ходьби"): так само, як гравець відпускає спринт чи тисне S,
     * щоб не перелетіти. Швидкість задає ДОВЖИНА стрибка, а не максимальна швидкість моба.
     * Відстань беремо від ЖИВОЇ позиції до центру landing (а не від округленого gapBlocks), тож моб приземляється
     * рівно на центр незалежно від того, де саме на краю його "піймали".
     */
    private void launch(Vec3 landing, double along, double across, double speedAlong, double front) {
        double dx = landing.x - this.mob.getX();
        double dz = landing.z - this.mob.getZ();
        double realDistance = Math.sqrt(dx * dx + dz * dz);
        double planDistance = Math.min(realDistance, segmentLength() + MAX_LAUNCH_EXTRA_BLOCKS);

        int airtime = GapJumpPhysics.AIRTIME_TICKS;
        double perTick = planDistance / airtime;
        this.lastFlightStep = perTick;

        Vec3 launchDir = new Vec3(dx, 0.0, dz).normalize();

        // Sprint-поштовок ванільного jumpFromGround() (+0.2 у бік getYRot()) вимикаємо: горизонталь
        // задаємо ПОВНІСТЮ самі, і вона не залежить від того, куди на цей момент довернувся моб.
        this.mob.setSprinting(false);

        Vec3 vel = this.mob.getDeltaMovement();
        this.mob.setDeltaMovement(
                launchDir.x * perTick,
                vel.y,
                launchDir.z * perTick
        );
        recordSetVelocity(launchDir.x * perTick, launchDir.z * perTick);

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
                        + " | ПЛАН ПОЛЬОТУ: ванільна дуга, " + airtime + " тіків, "
                        + String.format("%.3f", perTick) + " бл/тік"
                        + " (" + String.format("%.1f", perTick * 20.0) + " бл/с)"
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
     * імпульс стрибка, що й на землі (вертикальна швидкість 0.42), а горизонтальний крок рахуємо з ФАКТИЧНОГО
     * стану (висота, вертикальна швидкість) — так само, як {@link #controlFlight}, який далі щотіку
     * тримає рівний крок до центру landing. Візуально це виглядає як "підстрибнув на самому краю". Для мобів, що зійшли з краю без цілі (нокбек, падіння), не спрацьовує: ціль
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
        int ticks = GapJumpPhysics.remainingAirTicks(heightAbove, jumpVy);
        double perTick = Math.min(dist, segmentLength() + MAX_LAUNCH_EXTRA_BLOCKS) / ticks;

        this.mob.setSprinting(false);
        this.mob.setDeltaMovement(dx / dist * perTick, jumpVy, dz / dist * perTick);
        recordSetVelocity(dx / dist * perTick, dz / dist * perTick);
        this.jumpFired = true;
        this.lastFlightStep = perTick;

        System.out.println(
                "[DEBUG GAP JUMP] РЯТІВНИЙ СТРИБОК (керування прийшло запізно)"
                        + " | pos=" + formatVec(this.mob.position())
                        + " | просів на=" + String.format("%.3f", -heightAbove)
                        + " | до landing=" + String.format("%.3f", dist)
                        + " | " + ticks + " тіків, " + String.format("%.3f", perTick) + " бл/тік"
        );
        return true;
    }

    /**
     * Щотіку в польоті: горизонтальна швидкість = відстань до центру landing / тіків до приземлення.
     * <p>
     * Це замкнений контур: на кожному тіку ми знаємо, де моб (позиція, висота, вертикальна швидкість), тож
     * тіків до торкання рівня landing ({@link GapJumpPhysics#remainingAirTicks}) і, відповідно, який
     * крок за тік приведе його рівно в центр. Наслідки:
     * <ul>
     *   <li>політ РІВНОМІРНИЙ (замість "ривок + затухання x0.91"), моб приземляється на швидкості бігу;</li>
     *   <li>точність не залежить від тертя блока під ногами, чи виконався стрибок у той самий тік тощо -
     *       модель відриву більше нічого не гарантує, а лише задає перший крок;</li>
     *   <li>бічні збурення (штовхнули) гасяться самі.</li>
     * </ul>
     * Вертикаль НЕ чіпаємо (ванільна дуга) - крім аварійного випадку: якщо крок вийшов би більшим за радіус опори
     * блока, політ подовжується "зависанням" (див. {@link GapJumpPhysics#maxFlightStep}).
     */
    private void controlFlight() {
        Vec3 landing = this.jump.landing();
        Vec3 vel = this.mob.getDeltaMovement();

        double vy = vel.y;

        double heightAbove = this.mob.getY() - landing.y;
        double dx = landing.x - this.mob.getX();
        double dz = landing.z - this.mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        // Моб уже нижче рівня landing (промахнувся повз платформу) - керувати нічим, хай падає за фізикою.
        if (dist < 1.0E-6 || heightAbove < -0.05) {
            return;
        }

        double planDist = Math.min(dist, segmentLength() + MAX_LAUNCH_EXTRA_BLOCKS);
        int ticks = GapJumpPhysics.remainingAirTicks(heightAbove, vy);

        // Крок за тік не має перевищувати радіус опори блока приземлення (див. maxFlightStep): інакше на
        // тіку торкання хітбокс іще не над блоком, і моб пролітає повз. Зазвичай план це вже гарантує; тут
        // лише страховка (запізніле керування, збурення): якщо тіків лишилось замало - трохи зависаємо.
        double maxStep = GapJumpPhysics.maxFlightStep(this.mob.getBbWidth() * 0.5);
        int needed = (int) Math.ceil(planDist / maxStep - 1.0E-9);
        if (needed > ticks) {
            vy = GapJumpPhysics.verticalVelocityForRemainingTicks(heightAbove, vy, needed);
            ticks = GapJumpPhysics.remainingAirTicks(heightAbove, vy);
        }

        double perTick = planDist / ticks;
        this.mob.setDeltaMovement(dx / dist * perTick, vy, dz / dist * perTick);
        recordSetVelocity(dx / dist * perTick, dz / dist * perTick);
    }

    /**
     * Ланцюжок стрибків. У тіку приземлення бере СВІЖИЙ шлях від поточного положення
     * ({@link GapJumpUtils#findFreshJumpSegment}) і, якщо попереду знову стрибок, перемикає ціль на нього
     * без {@code stop()/start()} і без передачі керування Pursuit.
     *
     * @return true, якщо наступний сегмент прийнято (стан уже скинуто {@link #beginSegment})
     */
    private boolean chainNextSegment() {
        if (this.chainCount >= MAX_CHAIN_SEGMENTS
                || !PursuitEnemyBehavior.isMemoryChasing(this.mob)) {
            return false;
        }
        GapJumpUtils.GapJump next = GapJumpUtils.findFreshJumpSegment(this.mob);
        if (next == null) {
            return false;
        }
        // Не той самий сегмент, що щойно пройшли (шлях міг бути порахований до приземлення).
        if (next.edge().equals(this.jump.edge()) && next.landing().distanceTo(this.jump.landing()) < 1.0E-6) {
            return false;
        }
        // Початок сегмента має бути поруч з мобом (той самий рівень і в межах видимості Path).
        Vec3 nextEdgeCenter = Vec3.atBottomCenterOf(next.edge());
        if (horizontalDistance(this.mob.position(), nextEdgeCenter) > CHAIN_MAX_EDGE_DISTANCE
                || Math.abs(nextEdgeCenter.y - this.mob.getY()) > 1.0) {
            return false;
        }
        this.chainCount++;
        this.jump = next;
        beginSegment(true);
        return true;
    }

    private void recordSetVelocity(double vx, double vz) {
        this.lastSetX = vx;
        this.lastSetZ = vz;
        this.hasLastSet = true;
    }

    /**
     * Порівнює поточну швидкість моба з тією, що ми виставили на попередньому тіку. Тертя (0.546 на землі, 0.91 в
     * повітрі, на льоду/слаймі інакше) лишає швидкість на тій самій прямій, змінюючи лише величину в межах
     * {@code [IMPULSE_MIN_RATIO, IMPULSE_MAX_RATIO]}. Ванільне відкидання ({@code old/2 + поштовх*сила}), вибух чи
     * сильний поштовх це порушують - тоді {@link #impulseDetected}: швидкість більше не перезаписуємо (політ далі
     * балістичний, збереження швидкості на землі вимкнено), а після приземлення ланцюжок не продовжується,
     * керування повертається Pursuit.
     */
    private void checkExternalImpulse() {
        if (!this.hasLastSet) {
            return;
        }
        this.hasLastSet = false;
        if (this.impulseDetected) {
            return;
        }
        double setLen = Math.sqrt(this.lastSetX * this.lastSetX + this.lastSetZ * this.lastSetZ);
        if (setLen < 1.0E-6) {
            return;
        }
        Vec3 v = this.mob.getDeltaMovement();
        double along = (v.x * this.lastSetX + v.z * this.lastSetZ) / setLen;
        double lateral = (v.x * this.lastSetZ - v.z * this.lastSetX) / setLen;
        double ratio = along / setLen;
        boolean friction = ratio >= IMPULSE_MIN_RATIO && ratio <= IMPULSE_MAX_RATIO
                && Math.abs(lateral) <= IMPULSE_LATERAL_FRACTION * setLen + IMPULSE_LATERAL_MIN_ABS;
        if (!friction) {
            this.impulseDetected = true;
            this.carryStep = 0.0;
            System.out.println(
                    "[DEBUG GAP JUMP] ЗОВНІШНІЙ ІМПУЛЬС (відкидання/поштовх): керування швидкістю знято"
                            + " | виставляли=(" + String.format("%.3f, %.3f", this.lastSetX, this.lastSetZ) + ")"
                            + " | стало=(" + String.format("%.3f, %.3f", v.x, v.z) + ")"
                            + " | pos=" + formatVec(this.mob.position())
            );
        }
    }

    @Override
    public void stop() {
        ACTIVE_MOBS.remove(this.mob);

        this.jump = null;

        this.mob.setSprinting(false);
        this.mob.getNavigation().stop();
    }

    /**
     * Рядок для логу: чи лежить під маршрутом польоту проміжна платформа (клітинка з підлогою між краєм і
     * приземленням). Якщо так - це ПЕРЕСТРИБУВАННЯ: моб летить НАД нею, а не приземляється (див.
     * {@link GapJumpNodeEvaluator}). Лише діагностика, на поведінку не впливає.
     */
    private String platformsNote(Vec3 edgeCenter) {
        int over = countPlatformsUnderFlight(edgeCenter, this.jump.landing());
        return over > 0
                ? "\nПЕРЕСТРИБУВАННЯ: під маршрутом " + over + " клітин. з підлогою (проміжна платформа) - летимо НАД нею"
                : "";
    }

    private int countPlatformsUnderFlight(Vec3 edgeCenter, Vec3 landing) {
        double dx = landing.x - edgeCenter.x;
        double dz = landing.z - edgeCenter.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-6) {
            return 0;
        }
        BlockPos edge = this.jump.edge();
        int landingX = (int) Math.floor(landing.x);
        int landingZ = (int) Math.floor(landing.z);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        int count = 0;
        for (double t = 0.5; t < length - 0.25; t += 0.25) {
            int cx = (int) Math.floor(edgeCenter.x + dx / length * t);
            int cz = (int) Math.floor(edgeCenter.z + dz / length * t);
            if ((cx == edge.getX() && cz == edge.getZ()) || (cx == landingX && cz == landingZ)) {
                continue;
            }
            long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
            if (!seen.add(key)) {
                continue;
            }
            BlockPos floor = new BlockPos(cx, edge.getY() - 1, cz);
            if (this.mob.level().getBlockState(floor).blocksMotion()) {
                count++;
            }
        }
        return count;
    }

    /** Відстань між центром блока-краю і центром приземлення (для осі це gap + 1). */
    private double segmentLength() {
        Vec3 edgeCenter = Vec3.atBottomCenterOf(this.jump.edge());
        return horizontalDistance(edgeCenter, this.jump.landing());
    }

    /**
     * Куди бігти до відриву. Не одразу на точку приземлення: для стрибка вздовж осі це та сама пряма, а от для
     * діагоналі/косого напрямку пряма "моб -> landing" зрізає повз блок-край (з вузького містка моб просто
     * зісковзнув би вбік ще до краю). Тому: поки центр моба не над блоком-краєм - на ЦЕНТР цього блока, а
     * вже над ним - уздовж напрямку стрибка, трохи за передню межу (щоб не гальмував біля краю; сам відрив
     * вирішує {@link GapJumpPhysics#shouldTakeOff}).
     */
    private Vec3 takeoffTarget(Vec3 edgeCenter, double dirX, double dirZ, double relX, double relZ) {
        if (Math.abs(relX) <= 0.5 && Math.abs(relZ) <= 0.5) {
            double reach = GapJumpPhysics.frontBorder(dirX, dirZ) + 0.5;
            return new Vec3(edgeCenter.x + dirX * reach, edgeCenter.y, edgeCenter.z + dirZ * reach);
        }
        return edgeCenter;
    }

    /**
     * Ланцюжок: після приземлення моб іде до точки відриву зі ШВИДКІСТЮ ПОЛЬОТУ. Без цього MoveControl розганяв
     * би його на землі до повної швидкості бігу (а політ повільніший) - виходило "політ -> ривок -> політ", тобто
     * знову гальмо на відриві. Швидкість виставляємо самі й гасимо MoveControl (він додав би прискорення).
     */
    private void carryTo(Vec3 target) {
        double dx = target.x - this.mob.getX();
        double dz = target.z - this.mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-6) {
            steerTo(target);
            return;
        }
        double step = Math.min(this.carryStep, dist);
        Vec3 vel = this.mob.getDeltaMovement();
        this.mob.setDeltaMovement(dx / dist * step, vel.y, dz / dist * step);
        recordSetVelocity(dx / dist * step, dz / dist * step);

        Vec3 here = this.mob.position();
        this.mob.getMoveControl().setWantedPosition(here.x, here.y, here.z, 0.0);
        this.mob.getLookControl().setLookAt(target.x, target.y, target.z, 30.0F, 30.0F);
        this.mob.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        this.mob.setYHeadRot(this.mob.getYRot());
    }

    private void steerTo(Vec3 target) {
        this.mob.getLookControl().setLookAt(target.x, target.y, target.z, 30.0F, 30.0F);
        this.mob.getMoveControl().setWantedPosition(
                target.x, target.y, target.z, Run_N_JumpUtils.getRunSpeedModifier(this.mob));
    }
}