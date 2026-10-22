package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.debug.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Виконання стрибка через розрив, який {@link GapJumpNodeEvaluator} уже заклав у sharedPath як
 * "далекий" вузол — сама детекція розриву живе в pathfinding-графі, цей Goal лише читає
 * {@link GapJumpUtils#findUpcomingJumpSegment} і виконує розбіг/стрибок.
 * <p>
 * <b>v15 — ПРИРОДНИЙ СТРИБОК «як у гравця» (варіант B).</b> Ціль більше НЕ веде моба по дузі. Усе, що нижче описано для
 * v5-v13 про рівномірний політ, швидкість на землі між стрибками, виявлення удару й щотіку перезаписану швидкість, -
 * історія. Тепер:
 * <ul>
 *   <li><b>розбіг</b> - звичайний MoveControl; ціль лише обирає УСТАВКУ швидкості і чи тримати спринт
 *       ({@link GapJumpPhysics#chooseRunPlan}): повну швидкість і спринт для довгого стрибка, повільніше й без спринту
 *       (отже без поштовху +0.2) для короткого - як гравець, що відпускає спринт. Вибір - з прогнозу всього стрибка
 *       (розбіг, відрив, політ) тією самою ванільною фізикою;</li>
 *   <li><b>відрив</b> - звичайне {@code jumpControl.jump()} зі ввімкненим спринтом: ванільний {@code jumpFromGround()}
 *       сам дає підйом 0.42 і поштовх +0.2 уздовж погляду; горизонтальну швидкість ніхто не виставляє;</li>
 *   <li><b>політ</b> - ванільний {@code travel()} (гравітація, опір 0.91). Єдине, що ціль додає, - повітряне прискорення,
 *       яке має гравець, що тримає W/A/S/D при спринті (не більше 0.0255 бл/тік², {@link GapJumpPhysics#airAccel}):
 *       ванільний моб у повітрі має втричі менше, тому без цього він літав би значно коротше за гравця. Вимикається
 *       константою {@code GapJumpPhysics.AIR_CONTROL_SCALE = 0.0} (суто балістичний політ);</li>
 *   <li>відкидання, лід, слайм і мед працюють самі, бо швидкість мобу не перезаписується (виявлення імпульсу,
 *       збереження швидкості на землі й жорстке ведення в польоті прибрано);</li>
 *   <li>єдиний «одноразовий» елемент - невеликий додатковий підштовх на відриві
 *       ({@code GapJumpPhysics.TAKEOFF_EXTRA_BOOST}, лише зі спринтом): зомбі стрибає трохи сильніше за гравця, щоб граф не
 *       втрачав довгі стрибки. Політ після нього ванільний. {@code 0.0} - рівно як гравець.</li>
 * </ul>
 * Рятувальний стрибок ({@link #tryRescueJump}) лишено: він лікує інше - запізніле передавання керування, а не фізику.
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
 * стрибками моб зберігає швидкість польоту ({@code carryTo}, прибрано у v15), а не розганяється й не гальмує.
 * <p>
 * <b>Відкидання працює.</b> Ціль щотіку сама виставляє горизонтальну швидкість (політ, збереження швидкості на
 * землі), а ванільне відкидання - це разова зміна deltaMovement, яку наступний тік цілі затер би (так було у
 * v9/v10: у паркурі мобів не відкидало). Тому ціль перевіряє, чи швидкість після нашого виставлення змінилась
 * ЛИШЕ тертям ({@code checkExternalImpulse}, прибрано у v15); якщо ні - її вдарили/штовхнули: керування швидкістю знімається до
 * кінця сегмента, ланцюжок не продовжується.
 * <p>
 * Той самий набір флагів (MOVE+LOOK+JUMP), що й TowerClimbGoal/BuildPathGoal — АЛЕ тригер для
 * {@code PursuitEnemyMeleeBehavior.shouldYieldToTerraforming()} (isPathBlocked) більше НЕ спрацьовує
 * для прохідних-через-стрибок розривів, бо createPath() тепер для них реально знаходить шлях. Тому в
 * PursuitEnemyMeleeBehavior є окрема умова {@code shouldYieldToGapJump()} — без неї GoalSelector
 * ніколи не віддасть нам MOVE/LOOK.
 * <p>
 * <b>«Криві» блоки (плити, краї відкритих люків, стовпчики, килими, блоки з модів).</b> Якщо опора відриву чи
 * приземлення нестандартна, сегмент приходить із ТОЧНИМ планом ({@link GapJumpUtils.GapJump#plan()},
 * {@link ShapeJump}), побудованим із тієї самої геометрії, що й шлях ({@code TerrainShape}):
 * <ul>
 *   <li>ціль {@code landing} - реальна точка стояння (x, z і висота поверхні), а не центр клітинки на цілій
 *       висоті: тому {@code heightAbove}, Δy і кількість тіків польоту рахуються від справжньої поверхні;</li>
 *   <li>відрив - за реальними межами опори вздовж стрибка ({@code frontAlong}, {@code loseAlong}), а не за
 *       межами повного блока: на стовпчику чи краї люка хітбокс сходить з опори ДО того, як центр дійшов до
 *       0.5 від центру клітинки;</li>
 *   <li>крок за тік обмежений реальним радіусом опори приземлення; для вузької опори політ лишається
 *       ванільним і лише останній крок короткий ({@link GapJumpPhysics#flightCommand}), а після приземлення
 *       горизонтальна інерція гаситься (інакше зсуне моба з опори);</li>
 *   <li>підхід до краю веде навігатор по вузлах шляху ({@link GapJumpUtils.GapJump#approach()}) - тим самим
 *       рухом, що й Pursuit (точні точки, гальмо на краях), а не прямою до центру краю.</li>
 * </ul>
 * Для звичайних блоків (центр, повне покриття, цілий рівень) план не створюється ({@code plan()==null}) і все
 * працює старим кодом без змін.
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

    /** Скільки стрибків підряд ціль виконує, не віддаючи керування (запобіжник від нескінченного циклу). */
    private static final int MAX_CHAIN_SEGMENTS = 32;

    /** Наступний стрибок ланцюжка має починатись не далі ніж за стільки блоків від моба. */
    private static final double CHAIN_MAX_EDGE_DISTANCE = 4.0;

    /**
     * Наскільки (у блоках) моб міг просісти нижче рівня краю, щоб його ще можна було врятувати
     * рятувальним стрибком ({@link #tryRescueJump}). 1 тік падіння = 0.078, 2 тіки = 0.23.
     */
    static final double RESCUE_MAX_DROP = 0.3;

    /** Рятувальний стрибок не летить далі ніж на стільки блоків за довжину сегмента (запобіжник від зламаної геометрії). */
    private static final double RESCUE_MAX_EXTRA_BLOCKS = 2.0;

    /**
     * Чи вмикати рятувальний стрибок ({@link #tryRescueJump}) - єдине місце, де ціль у v15 разово виставляє швидкість.
     * {@code false} - суворо природно: моб, що зійшов з краю без стрибка (запізніле керування), просто падає.
     */
    private static final boolean RESCUE_ENABLED = true;

    private final Mob mob;
    private GapJumpUtils.GapJump jump;
    private boolean hasBeenAirborne;
    private boolean jumpFired;
    private boolean done;
    private int chargeTicks;
    /**
     * Фаза підходу: навігатор сам веде моба вузлами шляху до вузла відриву ({@link #start}, {@link #stillApproaching}),
     * і лише потім керування бере ця ціль. Вмикається, коли в сегменті є шлях підходу (точний стрибок чи уточнений
     * шлях); для звичайних блоків - прямий розбіг, як і було.
     */
    private boolean approachActive;
    private int approachTicks;
    /**
     * Запобіжник: підхід навігатором не довше стількох тіків.
     */
    private static final int MAX_APPROACH_TICKS = 160;
    private static final java.util.Set<Mob> ACTIVE_MOBS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private int chainCount;
    /**
     * Властивості опори відриву поточного сегмента (тертя, гальмо ходьби, jumpFactor): з них рахується і наземне
     * прискорення розбігу, і політ ({@link #computeSegmentPhysics}).
     */
    private GapJumpPhysics.Takeoff takeoff = GapJumpPhysics.ORDINARY_TAKEOFF;
    /** Δy поточного сегмента (приземлення мінус відрив), округлений до 1/256, як і в графі. */
    private double segDeltaY;
    /** Чи вистачає моби просто збігти з краю (без стрибка): тоді {@code jumpControl.jump()} не викликається. */
    private boolean segNoJump;
    /** Остання уставка швидкості розбігу (для діагностики). */
    private double lastSetpoint;
    /** Чи тримаємо спринт на розбігу (поштовх +0.2 при відриві); для коротких стрибків відпускаємо - див. chooseRunPlan. */
    private boolean runBoost = true;
    /** Прогноз помилки приземлення в момент відриву (для порівняння з фактичною в лозі); NaN - прогнозу нема. */
    private double launchPredictedError = Double.NaN;

    // =========================================
    private boolean debugRunupPrinted = false;
    // =========================================

    // ---- ДЕБАГ (див. com.example.examplemod.debug.DebugLog) -----------------------------------------
    /**
     * Підпис останнього залогованого результату canUse(): друкуємо лише ЗМІНУ (раніше друкувалось щотіку).
     */
    private String lastCanUseSig = "";

    private void jumpLog(String msg) {
        DebugLog.log(DebugLog.Cat.JUMP, this.mob, msg);
    }

    /**
     * Короткий опис внутрішнього стану цілі - для знімка стану (MobDebug) і діагностики зависань.
     */
    public String debugState() {
        return "jump=" + (this.jump == null ? "null"
                : "edge=" + this.jump.edge() + " landing=" + formatVec(this.jump.landing())
                + " gap=" + this.jump.gapBlocks() + " exact=" + this.jump.exact()
                + " approach=" + (this.jump.approach() != null))
                + " done=" + this.done + " approachActive=" + this.approachActive
                + " approachTicks=" + this.approachTicks + " chargeTicks=" + this.chargeTicks
                + " airborne=" + this.hasBeenAirborne + " jumpFired=" + this.jumpFired
                + " chain=" + this.chainCount + " setpoint=" + String.format("%.3f", this.lastSetpoint)
                + " sprint=" + this.runBoost + " noJump=" + this.segNoJump;
    }

    @Override
    public boolean canUse() {
        // v13 - ТУТ РАНІШЕ був "if (!PursuitEnemyBehavior.isMemoryChasing(this.mob)) return false;" -
        // реальний знайдений баг (див. чат): через нього GapJumpAssistGoal міг стартувати ЛИШЕ коли
        // моб УЖЕ втратив ціль з очей (memory chasing), а не під час звичайного переслідування
        // видимої цілі - тобто саме тоді, коли стрибок найчастіше й потрібен. Суперечило й логіці
        // PursuitEnemyMeleeBehavior.shouldYieldToGapJump(), яка такого обмеження не має: вона
        // передає керування сюди, щойно findUpcomingJumpSegment бачить стрибок, незалежно від
        // memoryChasing - і саме ця умова (не isMemoryChasing) тепер єдина й достатня підстава.
        GapJumpUtils.GapJump segment = GapJumpUtils.findUpcomingJumpSegment(this.mob);
        // DEBUG (тимчасово - див. чат): segment=null тут означає, що готового стрибка в ПОБУДОВАНОМУ
        // шляху взагалі нема - тобто причина ще ВИЩЕ (GapJumpNodeEvaluator/мексин), не тут.
        // Раніше тут був println на КОЖЕН виклик canUse (щодва тіки на КОЖНОГО моба). Тепер - лише ЗМІНА
        // результату (і "стрибка нема" не частіше разу на 2с на моба).
        if (DebugLog.on(DebugLog.Cat.JUMP)) {
            String sig = segment == null ? "стрибка в шляху нема"
                    : "edge=" + segment.edge() + " landing=" + formatVec(segment.landing())
                    + " gap=" + segment.gapBlocks() + " exact=" + segment.exact()
                    + " approach=" + (segment.approach() != null);
            if (!sig.equals(this.lastCanUseSig)) {
                this.lastCanUseSig = sig;
                if (segment != null || DebugLog.throttle("jump-nojump-" + this.mob.getId(), 2000L)) {
                    jumpLog("canUse: " + sig + " | pos=" + formatVec(this.mob.position())
                            + " onGround=" + this.mob.onGround());
                }
            }
        }
        if (segment == null) {
            return false;
        }
        // Звичайно моб на землі. Виняток - ЗАПІЗНІЛЕ керування: між стартом Pursuit і першим тіком цієї цілі
        // проходить ~2 тіки (yield перевіряється лише на парних тіках і лише коли Pursuit сам поклав Path у
        // навігатор), а швидкий моб за цей час може вже зійти з краю. Поки він просів менше ніж на
        // RESCUE_MAX_DROP і не летить угору, його ще можна врятувати - див. tryRescueJump().
        if (!this.mob.onGround()) {
            double drop = segment.takeoffPoint().y - this.mob.getY();
            if (drop < -0.05 || drop > RESCUE_MAX_DROP || this.mob.getDeltaMovement().y > 0.1) {
                // ВАЖЛИВО для "зависань": Pursuit уступає ЦІЙ цілі (бачить стрибок у шляху), а вона відмовляє -
                // тоді моб лишається без керування. Тому фіксуємо цю ситуацію.
                if (DebugLog.on(DebugLog.Cat.JUMP) && DebugLog.throttle("jump-refuse-" + this.mob.getId(), 1000L)) {
                    jumpLog("canUse=FALSE хоча стрибок у шляху ЄСТЬ (Pursuit при цьому уступає!): моб не на землі, drop="
                            + String.format("%.3f", drop) + " vy=" + String.format("%.3f", this.mob.getDeltaMovement().y)
                            + " pos=" + formatVec(this.mob.position()) + " edge=" + segment.edge());
                }
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

    public GapJumpAssistGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    public static boolean isActive(Mob mob) {
        return ACTIVE_MOBS.contains(mob);
    }

    @Override
    public boolean canContinueToUse() {
        // v13 - те саме прибране обмеження, що й у canUse() (див. коментар там): раніше тут був
        // ще й "&& isMemoryChasing" - навіть якби canUse() стартував під час звичайного
        // переслідування, ЦЕЙ рядок зупиняв би ціль одразу на наступному тіку (isMemoryChasing тоді
        // ще false), тож стрибок усе одно б не встигав виконатись.
        boolean result = !this.done && this.jump != null;

        // Раніше друкувалось щотіку; тепер - лише момент, коли ціль збирається завершитись.
        if (!result && DebugLog.on(DebugLog.Cat.JUMP)) {
            jumpLog("canContinueToUse=FALSE done=" + this.done + " jumpNull=" + (this.jump == null)
                    + " onGround=" + this.mob.onGround() + " pos=" + formatVec(this.mob.position()));
        }

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
        // Підхід до краю: якщо є вузли шляху до вузла відриву (а опора нестандартна або шлях уточнений -
        // GapJumpUtils.buildApproach), віддаємо їх навігатору: він іде тим самим рухом, що й Pursuit (точні
        // точки біля люків/плит, гальмо на краях), а не прямою до центру краю, яка на кривих блоках веде повз
        // опору. Решту (відрив, політ) робить ця ціль. Інакше - стара навігація зупиняється, як і раніше.
        this.approachActive = false;
        this.approachTicks = 0;
        Path approach = this.jump.approach();
        if (approach != null && !approach.isDone()) {
            this.approachActive = this.mob.getNavigation().moveTo(approach, Run_N_JumpUtils.getRunSpeedModifier(this.mob));
        }
        if (!this.approachActive) {
            this.mob.getNavigation().stop();
        }
        if (DebugLog.on(DebugLog.Cat.JUMP)) {
            jumpLog("START: edge=" + this.jump.edge() + " approachПередано=" + (approach != null)
                    + " approachActive=" + this.approachActive
                    + " approachNodes=" + (approach == null ? 0 : approach.getNodeCount())
                    + " pos=" + formatVec(this.mob.position()));
        }

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
        this.launchPredictedError = Double.NaN;
        this.runBoost = true;
        if (chained) {
            this.mob.setSprinting(true);
        }

        Vec3 edgeCenter = this.jump.takeoffPoint();
        double axisLength = horizontalDistance(edgeCenter, this.jump.landing());
        computeSegmentPhysics(axisLength);

        jumpLog(
                "[DEBUG GAP JUMP] ===============================" + (chained ? " (ЛАНЦЮЖОК: без передачі керування)" : "")
                        + "\nМоб: " + this.mob.getName().getString()
                        + "\nПоточні координати: " + formatVec(this.mob.position())
                        + "\nБлок краю: " + this.jump.edge()
                        + planNote()
                        + "\nТочка приземлення: " + formatVec(this.jump.landing())
                        + "\nЗміщення край->приземлення (dx,dz): ("
                        + (int) Math.round(this.jump.landing().x - edgeCenter.x) + ", "
                        + (int) Math.round(this.jump.landing().z - edgeCenter.z) + ")"
                        + "\nРозрив: " + this.jump.gapBlocks() + " блок."
                        + "\nОцінка дальності моба (макс. розрив, звичайний блок): " + GapJumpUtils.estimateMaxJumpRangeBlocks(this.mob) + " блок."
                        + "\nБлок відриву: " + GapJumpUtils.describeTakeoffBlock(this.mob, this.jump.edge())
                        + platformsNote(edgeCenter)
                        + "\nЦентр краю -> центр приземлення: " + String.format("%.3f", axisLength)
                        + "\nПоточна швидкість: " + String.format("%.3f", this.mob.getDeltaMovement().horizontalDistance())
                        + "\nФізика відриву: тертя=" + String.format("%.3f", this.takeoff.friction())
                        + " гальмо=" + String.format("%.2f", this.takeoff.slowdown())
                        + " jumpFactor=" + String.format("%.2f", this.takeoff.jumpFactor())
                        + " | Δy=" + String.format("%+.3f", this.segDeltaY)
                        + " | макс. дальність польоту (повна швидкість, як гравець)="
                        + String.format("%.2f", GapJumpPhysics.maxReach(GapJumpUtils.runSpeedSetpoint(this.mob), this.takeoff, this.segDeltaY, true))
                        + " бл | без стрибка=" + this.segNoJump
                        + "\n================================"
        );
    }

    /**
     * Властивості опори відриву й Δy поточного сегмента, і чи достатньо мобу просто збігти з краю. Те саме, що
     * рахує граф ({@link GapJumpNodeEvaluator}), тож план і виконання сходяться на одній фізиці.
     */
    private void computeSegmentPhysics(double axisLength) {
        Vec3 landing = this.jump.landing();
        Level level = this.mob.level();
        if (this.jump.exact()) {
            ShapeJump.Plan plan = this.jump.plan();
            this.segDeltaY = GapJumpPhysics.quantizeDeltaY(landing.y - plan.takeoffY());
            GapJumpUtils.TakeoffProps props = GapJumpUtils.takeoffProps(
                    level, this.mob, plan.takeoffX(), plan.takeoffY(), plan.takeoffZ());
            this.takeoff = GapJumpPhysics.Takeoff.of(props.friction(), props.slowdown(), props.jumpFactor());
        } else {
            BlockPos edge = this.jump.edge();
            this.segDeltaY = Math.round(landing.y - edge.getY());
            BlockPos floorPos = edge.below();
            BlockState floor = level.getBlockState(floorPos);
            this.takeoff = GapJumpUtils.takeoffOf(floor, level, floorPos, this.mob);
        }
        // Політ рахується від переднього краю опори: звідти й міряємо, чи вистачить збігу без стрибка.
        double dx = landing.x - this.jump.takeoffPoint().x;
        double dz = landing.z - this.jump.takeoffPoint().z;
        double dirX = axisLength > 1.0E-6 ? dx / axisLength : 0.0;
        double dirZ = axisLength > 1.0E-6 ? dz / axisLength : 0.0;
        double flight = Math.max(0.0, axisLength - this.jump.front(dirX, dirZ));
        this.segNoJump = GapJumpPhysics.reachableWithoutJump(
                this.takeoff, GapJumpUtils.runSpeedSetpoint(this.mob), this.segDeltaY, flight);
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

        // Підхід до краю веде навігатор (вузли шляху, точні точки на нестандартних опорах): тут не втручаємось.
        if (this.approachActive && stillApproaching()) {
            return;
        }

        // =========================================================
        // AIRBORNE — моб уже летить
        // =========================================================
        if (!this.mob.onGround()) {
            if (!this.hasBeenAirborne && !this.jumpFired) {
                // Моб втратив опору, так і не отримавши команди на стрибок - майже завжди через ЗАПІЗНІЛЕ
                // керування (див. canUse). Поки він просів зовсім небагато, стрибаємо просто з повітря.
                if (!tryRescueJump()) {
                    jumpLog(
                            "[DEBUG GAP JUMP] !!! МОБ ЗІЙШОВ З КРАЮ БЕЗ СТРИБКА (врятувати вже не можна)"
                                    + " | pos=" + formatVec(this.mob.position())
                                    + " | edge=" + this.jump.edge()
                                    + " | speed=" + String.format("%.3f", this.mob.getDeltaMovement().horizontalDistance())
                    );
                }
            }
            this.hasBeenAirborne = true;

            // v15: політ ванільний (гравітація, опір 0.91); тут лише повітряне прискорення в межах сили гравця
            // (див. controlFlight). Швидкість мобу НЕ перезаписується, тож відкидання та інше діють самі.
            if (this.jumpFired) {
                controlFlight();
            }

            // Штатний рух моба в повітрі гасимо: ціль moveControl = поточна позиція (інакше він і далі тягне до цілі
            // розбігу, виставленої ще на землі, і додає свій вхід zza); лишаємо тільки погляд на приземлення.
            Vec3 herePos = this.mob.position();
            this.mob.getMoveControl().setWantedPosition(herePos.x, herePos.y, herePos.z, 0.0);
            this.mob.getLookControl().setLookAt(
                    this.jump.landing().x, this.jump.landing().y, this.jump.landing().z, 30.0F, 30.0F);

            if (DebugLog.on(DebugLog.Cat.JUMP_TICK)) {
                DebugLog.log(DebugLog.Cat.JUMP_TICK, this.mob, "МОБ У ПОВІТРІ"
                        + " | pos=" + formatVec(this.mob.position())
                        + " | landing=" + formatVec(this.jump.landing())
                        + " | speed=" + String.format("%.3f",
                        this.mob.getDeltaMovement().horizontalDistance()));
            }

            return;
        }

        // =========================================================
        // ПОСАДКА
        // =========================================================
        if (this.hasBeenAirborne) {
            // Похибка приземлення відносно ЦЕНТРУ блока: вздовж осі стрибка (+ переліт, - недоліт) і
            // вбік. v15: політ природний, тож це ПРЯМА перевірка моделі (GapJumpPhysics.predict): повітряне
            // керування підтягує до центру, і при звичайних блоках очікуємо |вздовж| ~ 0.0-0.2. Стабільний зсув
            // в один бік - ознака, що модель розбігу/польоту розходиться з грою (напр. інше прискорення мобів).
            Vec3 edgeCenter = this.jump.takeoffPoint();
            double axisX = this.jump.landing().x - edgeCenter.x;
            double axisZ = this.jump.landing().z - edgeCenter.z;
            double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);
            double dirX = axisLength > 1.0E-6 ? axisX / axisLength : 0.0;
            double dirZ = axisLength > 1.0E-6 ? axisZ / axisLength : 0.0;
            double errX = this.mob.getX() - this.jump.landing().x;
            double errZ = this.mob.getZ() - this.jump.landing().z;
            double errAlong = errX * dirX + errZ * dirZ;
            double errSide = errX * dirZ - errZ * dirX;

            jumpLog(
                    "[DEBUG GAP JUMP] ПРИЗЕМЛЕННЯ"
                            + " | pos=" + formatVec(this.mob.position())
                            + " | landing=" + formatVec(this.jump.landing())
                            + " | помилка вздовж осі=" + String.format("%+.3f", errAlong)
                            + " (+переліт / -недоліт)"
                            + " | вбік=" + String.format("%+.3f", errSide)
                            + (Double.isNaN(this.launchPredictedError) ? ""
                            : " | прогноз моделі був " + String.format("%+.3f", this.launchPredictedError))
                            + " | speed=" + String.format("%.3f",
                            this.mob.getDeltaMovement().horizontalDistance())
            );

            // ЛАНЦЮЖОК: якщо попереду знову стрибок - стартуємо його ЩЕ В ЦЬОМУ ТІКУ, не віддаючи керування
            // Pursuit. Інакше після кожного приземлення моб ~3 тіки лишався без керування (done -> ще
            // один порожній тік -> stop -> старт Pursuit -> ~2 тіки до нової цілі) і стояв на місці.
            boolean chained = chainNextSegment();
            if (!chained) {
                // Вузька опора (стовпчик, ребро люка): залишок швидкості польоту (~0.3 бл/тік, ще пів блока ковзання)
                // зсунув би моба з неї. Гасимо горизонталь у перший же тік на землі, ДО того як travel() його зсуне.
                if (this.jump.plan() != null && this.jump.plan().tightLanding()) {
                    Vec3 landedVel = this.mob.getDeltaMovement();
                    this.mob.setDeltaMovement(0.0, landedVel.y, 0.0);
                    jumpLog("[DEBUG GAP JUMP] ВУЗЬКА ОПОРА: інерцію погашено, моб лишається на місці");
                }
                this.done = true;
                return;
            }
            // Стан скинуто під наступний сегмент - далі цей самий тік іде наземною гілкою (розбіг до краю).
        }

        // =========================================================
        // НА ЗЕМЛІ, ДО ВІДРИВУ
        // =========================================================
        Vec3 landing = this.jump.landing();
        Vec3 edgeCenter = this.jump.takeoffPoint();

        // === Моб уже практично на landing без стрибка (перейшов ногами) ===
        // Без цього перевірки Goal у такому разі зависав би в розбігу назавжди (distToEdge більше
        // ніколи не спрацює, а steerTo(landing) щотіку смикає моба туди-сюди).
        double distToLanding = horizontalDistance(this.mob.position(), landing);
        if (distToLanding <= ARRIVED_RADIUS) {
            this.done = true;

            jumpLog(
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

        // Передня межа блока-краю ВЗДОВЖ напрямку стрибка: 0.5 по осі, 0.707 по діагоналі (кут блока) - і межа, де
        // хітбокс уже зійшов з опори (для повного блока (0.5 + півширина) / |вісь|).
        double front = this.jump.front(dirX, dirZ);
        double lose = this.jump.lose(dirX, dirZ, halfWidth);

        // v15: РЕГУЛЮВАННЯ РОЗБІГУ. Не "виставляємо швидкість", а обираємо уставку для звичайного MoveControl: така, щоб
        // природний політ (ванільний відрив зі спринтом + повітряне керування) приземлив моба в центр. Далеко від краю -
        // повна швидкість; поблизу довгий стрибок бігом, короткий - повільніше (як гравець, що відпускає спринт).
        double setpointMax = GapJumpUtils.runSpeedSetpoint(this.mob);
        GapJumpPhysics.Geometry geometry = new GapJumpPhysics.Geometry(
                front, lose, axisLength, this.segDeltaY, true, this.segNoJump);
        GapJumpPhysics.RunPlan runPlan = GapJumpPhysics.chooseRunPlan(along, speedAlong, this.takeoff, geometry,
                setpointMax, GapJumpPhysics.MAX_AIR_ACCEL * GapJumpPhysics.NOMINAL_AIR_FRACTION, this.runBoost);
        double setpoint = runPlan.setpoint();
        this.lastSetpoint = setpoint;
        this.runBoost = runPlan.boost();
        // Спринт ставимо ДО того, як рахуватимемо множник для MoveControl: атрибут швидкості залежить від нього (+30%).
        if (this.mob.isSprinting() != this.runBoost) {
            this.mob.setSprinting(this.runBoost);
        }
        double groundAccel = GapJumpPhysics.groundAccel(setpoint, this.takeoff.friction());

        // Моб має бути поблизу ОСІ стрибка (на смузі блока-краю), а не збоку на широкій платформі: звідти
        // відрив приземлив би його на цій же платформі, а не за розривом. ВЗДОВЖ осі обмеження навмисно
        // НЕМАЄ: якщо керування прийшло запізно й моб уже пробіг за передню межу (навіть повис над
        // проваллям), прапор onGround ще тік лишається true - стрибок ще виконається, і прогноз
        // рахується від ПОТОЧНОЇ позиції. Забороняти відрив тут = зіштовхнути моба.
        double lateralLimit = this.jump.lateralLimit(halfWidth, dirX, dirZ);
        boolean nearAxis = across <= lateralLimit;
        boolean atEdge = nearAxis
                && this.jump.takeoffNow(relX, relZ, dirX, dirZ, speedAlong, halfWidth, groundAccel);

        // ---------------------------------------------------------
        // Ще не час: біжимо далі до краю
        // ---------------------------------------------------------
        if (!atEdge) {
            this.chargeTicks++;
            if (this.chargeTicks > MAX_CHARGE_TICKS) {
                this.done = true;
                jumpLog(
                        "[DEBUG GAP JUMP] РОЗБІГ ЗАТЯГНУВСЯ - відпускаємо керування"
                                + " | pos=" + formatVec(this.mob.position())
                                + " | along=" + String.format("%.3f", along)
                                + " | across=" + String.format("%.3f", across)
                );
                return;
            }

            Vec3 takeoffTarget = this.jump.exact()
                    ? takeoffTargetExact(edgeCenter, dirX, dirZ, relX, relZ)
                    : takeoffTarget(edgeCenter, dirX, dirZ, relX, relZ);
            steerTo(takeoffTarget, setpoint);

            if ((!this.debugRunupPrinted || this.chargeTicks % 10 == 0) && DebugLog.on(DebugLog.Cat.JUMP)) {
                // Додано нарівні з "along/across" ПРИЧИНУ, чому ще не відрив: nearAxis/lateralLimit/atEdge, куди
                // саме цілимось (takeoffTarget) і чи йде підхід навігатором. Саме це видно при "зависанні на краю".
                jumpLog("РОЗБІГ"
                        + " | pos=" + formatVec(this.mob.position())
                        + " | along=" + String.format("%+.3f", along)
                        + " (край блока = +" + String.format("%.3f", front) + ")"
                        + " | across=" + String.format("%.3f", across)
                        + " (допуск=" + String.format("%.3f", lateralLimit) + ", nearAxis=" + nearAxis + ")"
                        + " | speed=" + String.format("%.3f", speedAlong)
                        + " | atEdge=" + atEdge
                        + " | уставка=" + String.format("%.3f", setpoint)
                        + " (" + String.format("%.0f", setpointMax > 1.0E-6 ? 100.0 * setpoint / setpointMax : 0.0) + "% від повної)"
                        + " спринт=" + this.runBoost
                        + " | уздовж_до_краю=" + String.format("%+.3f", front - along)
                        + " | ціль_розбігу=" + formatVec(takeoffTarget)
                        + " (відстань " + String.format("%.3f", horizontalDistance(this.mob.position(), takeoffTarget)) + ")"
                        + " | chargeTicks=" + this.chargeTicks + " onGround=" + this.mob.onGround());
            }
            this.debugRunupPrinted = true;
            return;
        }

        // ---------------------------------------------------------
        // Моб на краю — СТРИБАЄМО
        // ---------------------------------------------------------
        launch(landing, along, across, speedAlong, front, setpoint, geometry);
    }

    /**
     * Рятувальний стрибок: моб уже зійшов з краю (прапор onGround став false), а стрибок ми так і не
     * скомандували. Це буває, коли керування прийшло запізно (див. {@link #canUse}), а моб швидкий - малий
     * зомбі з прискоренням ~0.44 за тік проходить за 2 тіки понад блок, тобто всю платформу 1x1.
     * <p>
     * Поки моб просів зовсім небагато ({@link #RESCUE_MAX_DROP}) і не летить угору, даємо йому той самий
     * імпульс стрибка, що й на землі (вертикальна швидкість 0.42 * jumpFactor), а горизонтальну швидкість - ОДИН РАЗ,
     * таку, щоб політ, що лишився, долетів до landing; далі летить природно, з тим самим легким повітряним
     * керуванням ({@link #controlFlight}). Це єдине місце, де ціль у v15 сама виставляє швидкість: у повітрі
     * ванільний моб підстрибнути не може, тож без цього запізніле керування означало б падіння. Для мобів, що
     * зійшли з краю без цілі (нокбек, падіння), не спрацьовує: ціль запускається лише коли попереду в Path є
     * стрибковий сегмент і моб на рівні його краю.
     *
     * @return true, якщо стрибок було дано
     */
    private boolean tryRescueJump() {
        if (!RESCUE_ENABLED) {
            return false;
        }
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

        double jumpVy = GapJumpPhysics.JUMP_VERTICAL_VELOCITY * this.takeoff.jumpFactor();
        int ticks = GapJumpPhysics.remainingAirTicks(heightAbove, jumpVy);
        if (ticks <= 0) {
            return false; // у межах RESCUE_MAX_DROP такого практично не буває, але про всяк випадок
        }
        double reach = Math.min(dist, segmentLength() + RESCUE_MAX_EXTRA_BLOCKS);
        double speed = GapJumpPhysics.airSpeedForReach(reach, ticks, 0.0);

        this.mob.setDeltaMovement(dx / dist * speed, jumpVy, dz / dist * speed);
        this.jumpFired = true;

        jumpLog(
                "[DEBUG GAP JUMP] РЯТІВНИЙ СТРИБОК (керування прийшло запізно)"
                        + " | pos=" + formatVec(this.mob.position())
                        + " | просів на=" + String.format("%.3f", -heightAbove)
                        + " | до landing=" + String.format("%.3f", dist)
                        + " | " + ticks + " тіків, разова швидкість " + String.format("%.3f", speed) + " бл/тік"
        );
        return true;
    }

    /**
     * Щотіку в польоті: повітряне прискорення на цей тік - те, що може гравець (W/A/S/D + поворот), і не більше.
     * <p>
     * Швидкість мобу НЕ перезаписується: ціль лише ДОДАЄ до deltaMovement прискорення, а далі ванільний
     * {@code travel()} зсуває моба й множить швидкість на 0.91 - рівно те саме робить {@code moveRelative} гравця, що
     * тримає клавішу. Прискорення вибране так, щоб політ, що лишився, закінчився в центрі landing
     * ({@link GapJumpPhysics#airAccel}), але його довжина обмежена силою гравця ({@link GapJumpPhysics#MAX_AIR_ACCEL}),
     * тож велику помилку воно не виправить - лише підправить малу. Вертикаль не чіпаємо взагалі.
     * <p>
     * Висота приземлення ({@code landing.y}) - РЕАЛЬНА висота поверхні для нестандартної опори (плита 0.5, килим...) і
     * ЦІЛА координата вузла для звичайних блоків. Для Δy вгору {@code heightAbove} СТАРТУЄ від'ємним (ціль ще вища за
     * моба) - це нормальний стан у середині польоту; про промах каже {@link GapJumpPhysics#remainingAirTicks}
     * (повертає -1, коли рівня landing уже не досягти) - тоді керувати нічим, хай моб падає.
     */
    private void controlFlight() {
        if (GapJumpPhysics.MAX_AIR_ACCEL <= 0.0) {
            return;
        }
        Vec3 landing = this.jump.landing();
        Vec3 vel = this.mob.getDeltaMovement();
        int ticks = GapJumpPhysics.remainingAirTicks(this.mob.getY() - landing.y, vel.y);
        if (ticks <= 0) {
            return;
        }
        GapJumpPhysics.AirAccel accel = GapJumpPhysics.airAccel(
                this.mob.getX(), this.mob.getZ(), vel.x, vel.z, ticks,
                landing.x, landing.z, GapJumpPhysics.MAX_AIR_ACCEL);
        if (accel.ax() == 0.0 && accel.az() == 0.0) {
            return;
        }
        this.mob.setDeltaMovement(vel.x + accel.ax(), vel.y, vel.z + accel.az());
    }

    /**
     * Ланцюжок стрибків. У тіку приземлення бере СВІЖИЙ шлях від поточного положення
     * ({@link GapJumpUtils#findFreshJumpSegment}) і, якщо попереду знову стрибок, перемикає ціль на нього
     * без {@code stop()/start()} і без передачі керування Pursuit.
     *
     * @return true, якщо наступний сегмент прийнято (стан уже скинуто {@link #beginSegment})
     */
    private boolean chainNextSegment() {
        // v13 - те саме прибране обмеження (див. canUse()): інакше ланцюжок із кількох стрибків
        // поспіль (наприклад, кілька відкритих люків підряд) обривався б одразу після першого
        // стрибка, поки моб ще бачить ціль - саме це й пояснювало "пригнув лише з одного люка,
        // далі йде пішки" у довших паркурах.
        if (this.chainCount >= MAX_CHAIN_SEGMENTS) {
            jumpLog("[DEBUG GAP JUMP] ЛАНЦЮЖОК ЗАВЕРШЕНО: ліміт " + MAX_CHAIN_SEGMENTS + " стрибків підряд | pos=" + formatVec(this.mob.position()));
            return false;
        }
        GapJumpUtils.GapJump next = GapJumpUtils.findFreshJumpSegment(this.mob);
        if (next == null) {
            // Найчастіша причина, чому моб "вертається й обходить": граф від місця приземлення не дає наступного стрибка
            // (розрив/висота поза дальністю - див. макс. розрив у заголовку сегмента) і A* веде пішки чи в обхід.
            jumpLog("[DEBUG GAP JUMP] ЛАНЦЮЖОК ЗАВЕРШЕНО: свіжий шлях від місця приземлення не містить стрибка"
                    + " (або шляху нема) | pos=" + formatVec(this.mob.position())
                    + " | макс. розрив рівно=" + GapJumpUtils.estimateMaxJumpRangeBlocks(this.mob));
            return false;
        }
        // Не той самий сегмент, що щойно пройшли (шлях міг бути порахований до приземлення).
        if (next.edge().equals(this.jump.edge()) && next.landing().distanceTo(this.jump.landing()) < 1.0E-6) {
            jumpLog("[DEBUG GAP JUMP] ЛАНЦЮЖОК ЗАВЕРШЕНО: свіжий шлях ще веде в той самий сегмент (порахований до приземлення)");
            return false;
        }
        // Початок сегмента має бути поруч з мобом (той самий рівень і в межах видимості Path).
        Vec3 nextEdgeCenter = next.takeoffPoint();
        if (horizontalDistance(this.mob.position(), nextEdgeCenter) > CHAIN_MAX_EDGE_DISTANCE
                || Math.abs(nextEdgeCenter.y - this.mob.getY()) > 1.0) {
            jumpLog("[DEBUG GAP JUMP] ЛАНЦЮЖОК ЗАВЕРШЕНО: наступний край задалеко/на іншій висоті | pos=" + formatVec(this.mob.position())
                    + " | наступний край=" + next.edge()
                    + " | відстань=" + String.format("%.3f", horizontalDistance(this.mob.position(), nextEdgeCenter))
                    + " (поріг " + CHAIN_MAX_EDGE_DISTANCE + ") | Δy=" + String.format("%.3f", nextEdgeCenter.y - this.mob.getY()));
            return false;
        }
        this.chainCount++;
        this.jump = next;
        beginSegment(true);
        return true;
    }

    /**
     * Відрив - ПРИРОДНИЙ, як у гравця: «натискаємо пробіл» ({@code jumpControl.jump()}) зі ввімкненим спринтом. Далі все
     * робить ваніль: {@code jumpFromGround()} дає підйом {@code 0.42 * jumpFactor} і поштовх спринту +0.2 уздовж
     * погляду, а горизонтальна швидкість - та, з якою моб добіг (її задала уставка розбігу, див. tick()). Ця функція
     * швидкість НЕ виставляє. Вона лише повертає моба обличчям до landing (бо поштовх спринту йде за getYRot()) і тримає
     * MoveControl на тій самій уставці: на тіку відриву ванільний travel() ще вважає, що моб на землі, і додає наземне
     * прискорення - саме так це й прогнозує {@link GapJumpPhysics#forecastFlight}.
     * <p>
     * Якщо мобу вистачає просто збігти з краю ({@link #segNoJump}: спуск на близьку відстань), стрибок не викликається -
     * моб збігає з уступу, як гравець, і далі той самий політ із повітряним керуванням.
     */
    private void launch(Vec3 landing, double along, double across, double speedAlong, double front,
                        double setpoint, GapJumpPhysics.Geometry geometry) {
        double dx = landing.x - this.mob.getX();
        double dz = landing.z - this.mob.getZ();
        double realDistance = Math.sqrt(dx * dx + dz * dz);

        // Спринт - за рішенням розбігу (chooseRunPlan): якщо він увімкнений, ванільний jumpFromGround() сам додасть
        // поштовх +0.2 уздовж getYRot(); для короткого стрибка спринт відпущено (як у гравця) - поштовху нема.
        this.mob.setSprinting(this.runBoost);

        if (realDistance > 1.0E-6) {
            // Обличчям на landing. Ціль MoveControl - на 2 блоки вперед по лінії на landing, на ВИСОТІ МОБА: напрямок
            // збігається з поглядом (MoveControl не довертає), а рівна висота не дає йому вирішити підстрибнути самому.
            this.mob.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            this.mob.setYHeadRot(this.mob.getYRot());
            this.mob.getMoveControl().setWantedPosition(
                    this.mob.getX() + dx / realDistance * 2.0,
                    this.mob.getY(),
                    this.mob.getZ() + dz / realDistance * 2.0,
                    GapJumpUtils.speedModifierFor(this.mob, setpoint));
        }
        this.mob.getLookControl().setLookAt(landing.x, landing.y, landing.z, 30.0F, 30.0F);

        if (!this.jumpFired) {
            // Прогноз моделі на момент відриву - щоб у лозі приземлення порівняти його з фактом.
            double nominal = GapJumpPhysics.MAX_AIR_ACCEL * GapJumpPhysics.NOMINAL_AIR_FRACTION;
            GapJumpPhysics.Geometry forecast = new GapJumpPhysics.Geometry(
                    geometry.front(), geometry.lose(), geometry.length(), geometry.deltaY(), this.runBoost, geometry.noJump());
            GapJumpPhysics.Prediction prediction = GapJumpPhysics.predict(
                    along, speedAlong, setpoint, this.takeoff, forecast, nominal);
            this.launchPredictedError = prediction == null ? Double.NaN : prediction.touchdown() - geometry.length();

            // ОДНОРАЗОВИЙ додатковий підштовх на відриві (GapJumpPhysics.TAKEOFF_EXTRA_BOOST): зомбі «стрибає трохи сильніше
            // за гравця», бо інакше граф втрачає довгі стрибки й моб віддає перевагу обходу. Це не ведення: політ далі
            // ванільний. Додаємо ДО ванільного jumpFromGround() (воно виконається в цьому ж тіку після цілі), уздовж погляду;
            // лише зі спринтом (для коротких стрибків його відпущено) і лише раз - на першому виклику.
            if (!this.segNoJump && this.runBoost && GapJumpPhysics.TAKEOFF_EXTRA_BOOST > 0.0 && realDistance > 1.0E-6) {
                Vec3 v = this.mob.getDeltaMovement();
                double k = GapJumpPhysics.TAKEOFF_EXTRA_BOOST / realDistance;
                this.mob.setDeltaMovement(v.x + dx * k, v.y, v.z + dz * k);
            }

            jumpLog(
                    "[DEBUG GAP JUMP] " + (this.segNoJump ? "ЗБІГ З КРАЮ (без стрибка)" : "СТРИБОК! (природний: розбіг -> ванільний відрив -> політ)")
                            + " | pos=" + formatVec(this.mob.position())
                            + " | edge=" + this.jump.edge()
                            + " | landing=" + formatVec(landing)
                            + " | Δy=" + (this.segDeltaY > 0 ? "+" + String.format("%.3f", this.segDeltaY) + " (вгору)"
                            : this.segDeltaY < 0 ? String.format("%.3f", this.segDeltaY) + " (вниз)" : "0 (рівно)")
                            + " | along=" + String.format("%+.3f", along)
                            + " (край блока = +" + String.format("%.3f", front) + ")"
                            + " | across=" + String.format("%.3f", across)
                            + " | швидкість до відриву=" + String.format("%.3f", speedAlong)
                            + " (" + String.format("%.1f", speedAlong * 20.0) + " бл/с)"
                            + " | уставка розбігу=" + String.format("%.3f", setpoint)
                            + " | спринт-поштовх=" + (this.runBoost && !this.segNoJump)
                            + " | realDistance=" + String.format("%.3f", realDistance)
                            + " | прогноз помилки приземлення="
                            + (Double.isNaN(this.launchPredictedError) ? "н/д" : String.format("%+.3f", this.launchPredictedError))
                            + (this.chargeTicks == 0 && along > front + 0.1
                            ? " | ЗАПІЗНІЛЕ КЕРУВАННЯ: моб уже за краєм у перший тік цілі" : "")
            );
        }

        // jumpFired тут означає "почали політ цього сегмента", а не буквально "стрибнули": для збігу без стрибка це
        // так само вмикає повітряне керування на наступних тіках (див. tick()).
        this.jumpFired = true;
        if (!this.segNoJump) {
            this.mob.getJumpControl().jump();
        }
    }

    @Override
    public void stop() {
        if (DebugLog.on(DebugLog.Cat.JUMP)) {
            jumpLog("STOP: " + debugState() + " pos=" + formatVec(this.mob.position())
                    + " onGround=" + this.mob.onGround());
        }
        this.lastCanUseSig = "";
        ACTIVE_MOBS.remove(this.mob);

        this.jump = null;
        this.approachActive = false;

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
        Vec3 edgeCenter = this.jump.takeoffPoint();
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
     * Те саме для нестандартної опори відриву (див. {@link ShapeJump}): точка {@code o} - реальна точка стояння на
     * опорі (а не центр клітинки), а межі опори - реальні. Поки моб далеко від опори чи збоку від осі - на цю
     * точку (щоб вирівнятись); щойно він над опорою біля осі - уздовж стрибка, трохи за межу, де хітбокс
     * сходить з опори (щоб не гальмував біля краю; сам відрив вирішує {@link GapJumpUtils.GapJump#takeoffNow}).
     */
    private Vec3 takeoffTargetExact(Vec3 o, double dirX, double dirZ, double relX, double relZ) {
        ShapeJump.Plan plan = this.jump.plan();
        double along = relX * dirX + relZ * dirZ;
        double across = Math.abs(relX * dirZ - relZ * dirX);
        if (Math.sqrt(relX * relX + relZ * relZ) <= 0.5 || (along >= 0.0 && across <= 0.3)) {
            double reach = plan.loseAlong() + 0.5;
            return new Vec3(o.x + dirX * reach, o.y, o.z + dirZ * reach);
        }
        return o;
    }

    /**
     * Фаза підходу ({@link #approachActive}): навігатор веде моба вузлами до вузла відриву. Закінчується, коли
     * шлях пройдено, навігатор його скинув (застрягання, нова ціль) або минув запобіжник часу - тоді
     * навігацію зупиняємо, й далі керує сама ціль (розбіг до точки відриву, відрив).
     *
     * @return true, поки підхід триває (ціль в цей тік нічого не робить)
     */
    private boolean stillApproaching() {
        this.approachTicks++;
        Path approach = this.jump.approach();
        var navigation = this.mob.getNavigation();
        boolean finished = approach == null
                || navigation.getPath() != approach
                || approach.isDone()
                || this.approachTicks > MAX_APPROACH_TICKS;
        if (finished) {
            if (DebugLog.on(DebugLog.Cat.JUMP)) {
                jumpLog("ПІДХІД завершено навігатором: approachNull=" + (approach == null)
                        + " шляхЗамінено=" + (approach != null && navigation.getPath() != approach)
                        + " approachDone=" + (approach != null && approach.isDone())
                        + " таймаут=" + (this.approachTicks > MAX_APPROACH_TICKS)
                        + " тіків_підходу=" + this.approachTicks + " pos=" + formatVec(this.mob.position()));
            }
            this.approachActive = false;
            navigation.stop();
            return false;
        }
        return true;
    }

    /** Рядок для логу: точний план (якщо є) і шлях підходу. */
    private String planNote() {
        StringBuilder sb = new StringBuilder();
        if (this.jump.plan() != null) {
            sb.append("\nТОЧНИЙ ПЛАН (нестандартна опора): ").append(ShapeJump.describe(this.jump.plan()));
            Vec3 t = this.jump.takeoffPoint();
            GapJumpUtils.TakeoffProps props = GapJumpUtils.takeoffProps(
                    this.mob.level(), this.mob, t.x, t.y, t.z);
            sb.append("\nБлок для тертя: ").append(props.frictionPos())
                    .append(" | тертя=").append(String.format("%.3f", props.friction()))
                    .append(" гальмо=").append(String.format("%.2f", props.slowdown()))
                    .append(" jumpFactor=").append(String.format("%.2f", props.jumpFactor()));
        }
        if (this.jump.approach() != null) {
            sb.append("\nПідхід навігатором: ").append(this.jump.approach().getNodeCount()).append(" вуз.");
        }
        return sb.toString();
    }

    /**
     * Біжимо до {@code target} з уставкою швидкості {@code setpoint} (її обирає {@link GapJumpPhysics#chooseRunPlan}).
     * Це звичайний MoveControl: ефективна швидкість = множник * атрибут, тож множник виводимо з уставки.
     */
    private void steerTo(Vec3 target, double setpoint) {
        this.mob.getLookControl().setLookAt(target.x, target.y, target.z, 30.0F, 30.0F);
        this.mob.getMoveControl().setWantedPosition(
                target.x, target.y, target.z, GapJumpUtils.speedModifierFor(this.mob, setpoint));
    }
}