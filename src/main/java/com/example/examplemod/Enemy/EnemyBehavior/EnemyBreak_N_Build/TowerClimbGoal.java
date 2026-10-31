package com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build;

import com.example.examplemod.Config;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.Run_N_JumpUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * ФАЗА 2+3. Замінює {@link BuildPathGoal}'s наївний "піларь-впритул" саме для підйому до ЖИВОЇ
 * позиції гравця (GOING_TO_LAST_SEEN/SEARCHING і далі лишаються за {@link BuildPathGoal} —
 * навмисно, "як можна швидше, ігноруючи логіку зони").
 * <p>
 * Покроково (узгоджено з користувачем):
 * <ol>
 *   <li>{@link Stage#DECIDE} (і, опортуністично, кожен тік {@link Stage#CLIMBING} теж) — є
 *       суцільний 7×7 І моб уже НЕ нижче за його поверхню (інакше чиста навігація не дійде туди
 *       без готових сходинок — саме тут був баг "моб просто стоїть")? → {@link Stage#TO_7X7}.
 *       Інакше — шукає найближчий вихід за межу проекції ВІДНОСНО ГРАВЦЯ (не моба - інший
 *       знайдений живим тестом баг: інакше стовп міг початись за 12+ блоків, там де моб просто
 *       випадково зупинився), комітиться туди.</li>
 *   <li>{@link Stage#TO_7X7} → {@link Stage#CROSS_AND_DIG} — доходить до центру 7×7 (може
 *       перетнути зону — короткий захід, стіна тут НЕ будується), ставить хрест-підпору й
 *       ламає стелю (з тими самими лава-перевірками, що й нижче). Захищено таймаутом
 *       ({@link #TO_7X7_STUCK_TIMEOUT_TICKS}) — якщо навігація все ж не може дійти (не лише
 *       через висоту), не стоїмо вічно, повертаємось до звичайного підйому.</li>
 *   <li>{@link Stage#CLIMBING} — комітнута (x,z)-колонка: піларить вгору, на кожному НОВОМУ
 *       шарі заморожує напрямок до гравця й будує стіну (1 блок кардинально / 3 блоки в куток
 *       по діагоналі — картинка користувача про щілину хітбокса). Перед КОЖНИМ проломом стелі:
 *       лава над головою? → {@link Stage#SIDESTEP} в обхід (сусідня колонка без лави); стеля
 *       рівно 1 блок і далі повітря? → хрест-підпора (як в CROSS_AND_DIG) ПЕРЕД ломом. Якщо
 *       зона за час підйому виросла настільки, що поглинула колонку — теж {@link Stage#SIDESTEP}
 *       (інша причина, той самий механізм). Якщо піднялись вище даху зони —
 *       {@link Stage#BRIDGE_TO_PLAYER}.</li>
 *   <li>{@link Stage#BRIDGE_TO_PLAYER} — <b>СПРОЩЕННЯ:</b> звичайний міст до гравця (як
 *       {@code BuildPathGoal.handleBridge}). Повноцінний "обліт даху зони й посадка з
 *       пріоритетами" (12-блоків-правило, "не над прірвою" тощо) — ще НЕ реалізовано, це
 *       наступний прохід (Фаза 4).</li>
 * </ol>
 * <p>
 * v21, ОДИН ШАР піларингу (стадія CLIMBING): стоїть → ставить стіну з боку гравця ПО ОДНОМУ блоку (темп — {@code towerPlaceDelayTicks},
 * а не кілька за тік) → стрибає → у польоті добудовує стіну на блок угору й ставить блок під ногами (див.
 * {@link #tickPillarGround}, {@link #tickPillarAir}). Якщо до гравця вже можна допригнути ({@link Config#TOWER_JUMP_FIRST}),
 * верхні блоки стіни НЕ ставляться наперед: спершу свіжа проба шляху ({@link #tickJumpCheck}), бо власна стіна по голову
 * закрила б стрибок. Горизонтальний міст ставить блоки із затримкою {@code bridgePlaceDelayMin/MaxTicks} (4-5 тіків).
 * <p>
 * Коли шлях перестає бути заблокованим (мобу вже недалеко) — {@link #canContinueToUse} сам
 * поверне false, і керування чисто (stop/start, не через прапорці) переходить до
 * {@code PursuitEnemyMeleeBehavior}, як і в {@link BuildPathGoal}.
 * <p>
 * НЕ реалізовано в цьому проході (свідомо відкладено, Фаза 4): "обліт+посадка з пріоритетами"
 * вище даху зони, нейтралізація гравецьких лави/вогню/води НА ШЛЯХУ до гравця (тут — лише лава,
 * яка сама заважає ЗВЕРХУ під час підйому), клатч-механіка при падінні.
 */
public class TowerClimbGoal extends Goal {

    // v21: ця ціль тепер тікає КОЖЕН ігровий тік (requiresUpdateEveryTick). Раніше ваніль викликала tick() раз на 2 тіки (див.
    // Mob#serverAiStep), тож колишні "10" насправді означали 20 тіків (1 с) між діями - звідси була дуже повільна стіна.
    // Ламання стелі лишаю у ТОМУ Ж реальному темпі (20 ігрових тіків); темп ПОСТАНОВКИ блоків задає placeBlock
    // (towerPlaceDelayTicks для вежі/стіни, bridgePlaceDelayMin/MaxTicks для мосту).
    private static final int BREAK_ACTION_COOLDOWN_TICKS = 20;
    private static final double NAV_ARRIVE_DIST_SQ = 4.0;
    private static final int WALL_HEIGHT = 2; // "стінку, яка буде мобу по голову"
    // Наскільки менша складова (dx чи dz) має бути відносно більшої, щоб вважати напрямок
    // ДІАГОНАЛЬНИМ (і будувати кутовий блок), а не чисто кардинальним (1 блок). 0.5 = менша
    // складова хоча б половина більшої.
    private static final double DIAGONAL_RATIO_THRESHOLD = 0.5;
    private static final Direction[] CARDINALS =
            {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static final int TO_7X7_STUCK_TIMEOUT_TICKS = 100; // 5с без прогресу - здаємось, лізем звичайним шляхом
    private static final double FALL_ZONE_DIST_SQ = 1.0; // ~2 блоки - тут вже просто падає, не будує
    // v21: не частіше за ванільний noJumpDelay (10 тіків) між командами стрибка піларингу - якщо стрибок не вдався
    // (стеля тощо), моб не "качається" щотіку.
    private static final int JUMP_REISSUE_TICKS = 10;
    // v21: скільки тіків чекаємо вердикт проби стрибка (tickJumpCheck), перш ніж будувати далі (бюджет проб на сервері міг вичерпатись).
    private static final int JUMP_CHECK_MAX_WAIT_TICKS = 8;
    // v21: скільки тіків без жодного поставленого блока стіни (на землі) терпимо, перш ніж стрибнути без неї (заважає хітбокс/видимість).
    private static final int WALL_GIVE_UP_BASE_TICKS = 30;
    // v21: повітряна добудова стіни має сенс лише поки триває СВІЙ стрибок (повітряний час ~10 тіків; запас на нокбек).
    private static final int AIR_EXTEND_WINDOW_TICKS = 30;
    private final Mob mob;
    private Stage stage = Stage.DECIDE;
    private BlockPos pillarColumn;   // закомітчена (x,z) колонка підйому (Y не використовується)
    private BlockPos sidestepTarget;
    private BlockPos target7x7;
    private int to7x7StuckTicks;
    private int layerY = Integer.MIN_VALUE; // Y, для якого вже заморожений напрямок стіни нижче
    private Direction wallDirA;
    private Direction wallDirB;      // != null лише для діагонального (кутового) випадку
    private long nextBreakTick;          // v21: game time, раніше якого не ламаємо наступний блок стелі (замість лічильника)
    // v21: стан ОДНОГО шару піларингу (див. tickPillarGround / tickPillarAir).
    private BlockPos takeoffFeet;        // клітинка ніг у момент останньої команди стрибка (від неї росте стіна у польоті)
    private boolean preExtendBlocked;    // true - стіну в польоті НЕ добудовуємо (на наступному шарі можна допригнути до гравця)
    private long jumpIssuedTick = Long.MIN_VALUE / 2; // game time останньої команди стрибка
    private long lastWallProgressTick;   // game time останнього поставленого блока стіни (для "здаюсь і стрибаю без неї")
    private JumpCheck jumpCheck = JumpCheck.DONE;
    private int jumpCheckSeq;            // computeSeq на момент запиту проби стрибка
    private int jumpCheckWaitTicks;

    // v20 (Config.TOWER_JUMP_FIRST): проба "чи вже можна допригнути до гравця" - не частіше за цей інтервал (тіків).
    private static final int JUMP_PROBE_MIN_INTERVAL_TICKS = 8;
    private int lastJumpProbeTick = -1_000_000;

    public TowerClimbGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    // v21: щотіку. Без цього ваніль викликає tick() цілі лише раз на 2 тіки (Mob#serverAiStep -> GoalSelector#tickRunningGoals) -
    // і затримка між блоками 4-5 тіків була б неможлива (виходять лише парні), а блок під ногами у польоті міг пропустити
    // вікно. (canContinueToUse/canUse і далі рахуються лише на "повних" тіках - це нормально.)
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        if (!EnemyBreak_N_BuildUtils.canOperate(this.mob)) return false;
        if (!PursuitEnemyBehavior.isLiveChasing(this.mob)) return false; // GTLS/SEARCHING - не сюди
        if (!(this.mob.level() instanceof ServerLevel level)) return false;
        if (PursuitEnemyBehavior.getTrackedPlayer(this.mob) == null) return false;

        Vec3 chasePos = PursuitEnemyBehavior.getChasePosition(this.mob);
        if (chasePos == null) return false;
        if (!EnemyBreak_N_BuildUtils.isPathBlocked(this.mob, chasePos)) return false;

        BlockPos target = EnemyBreak_N_BuildUtils.findNearestOpenArea(this.mob, level, chasePos);
        boolean climb = EnemyBreak_N_BuildUtils.needsClimb(this.mob, target);
        if (this.mob.tickCount % 20 == 0) {
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] canUse(): needsClimb=" + climb);
        }
        return climb;
    }

    @Override
    public boolean canContinueToUse() {
        if (!EnemyBreak_N_BuildUtils.canOperate(this.mob)) return false;
        if (!PursuitEnemyBehavior.isLiveChasing(this.mob)) return false;
        if (PursuitEnemyBehavior.getTrackedPlayer(this.mob) == null) return false;
        Vec3 chasePos = PursuitEnemyBehavior.getChasePosition(this.mob);
        if (chasePos == null) return false;
        return EnemyBreak_N_BuildUtils.isPathBlocked(this.mob, chasePos);
    }

    @Override
    public void start() {
        this.stage = Stage.DECIDE;
        this.pillarColumn = null;
        this.sidestepTarget = null;
        this.target7x7 = null;
        this.to7x7StuckTicks = 0;
        this.layerY = Integer.MIN_VALUE;
        this.wallDirA = null;
        this.wallDirB = null;
        this.nextBreakTick = 0;
        this.takeoffFeet = null;
        this.preExtendBlocked = false;
        this.jumpIssuedTick = Long.MIN_VALUE / 2;
        this.lastWallProgressTick = 0;
        this.jumpCheck = JumpCheck.DONE;
        this.jumpCheckSeq = 0;
        this.jumpCheckWaitTicks = 0;
        EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] START моб=" + EnemyBreak_N_BuildUtils.feetCell(this.mob));
    }

    // ================= DECIDE =================

    @Override
    public void stop() {
        EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] STOP stage=" + this.stage
                + " моб=" + EnemyBreak_N_BuildUtils.feetCell(this.mob));
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        // Дефолт бігу — АЛЕ НЕ в CLIMBING/CROSS_AND_DIG: там mob.getJumpControl().jump()
        // використовується ЛИШЕ для вертикального підйому по одній колонці (пілар/вихід крізь
        // пробиту стелю), а ванільний LivingEntity#jumpFromGround() додає горизонтальний поштовх
        // УПЕРЕД (за поточним yRot) щоразу, коли isSprinting()==true в момент стрибка — саме
        // звідси видимий "стрибає трохи вперед", хоча по X/Z моб мав лишитись на місці. У решті
        // стадій (TO_7X7, SIDESTEP, BRIDGE_TO_PLAYER) рух дійсно горизонтальний, тож дефолт біжить.
        if (this.stage == Stage.CLIMBING || this.stage == Stage.CROSS_AND_DIG) {
            this.mob.setSprinting(false);
        } else {
            Run_N_JumpUtils.applyDefaultRun(this.mob);
        }

        if (!(this.mob.level() instanceof ServerLevel level)) return;
        // v19: блок, відкладений через власний хітбокс моба, повторюємо щотіку, поки моб підстрибнув (див. placeBlock).
        EnemyBreak_N_BuildUtils.retryPendingPlacement(level, this.mob);
        Player player = PursuitEnemyBehavior.getTrackedPlayer(this.mob);
        if (player == null) return;

        TowerZoneData zone = TowerZoneData.updateForClimbingMob(this.mob, level);
        if (zone == null) return; // теоретично неможливо тут (player != null вище), про всяк випадок

        if (this.mob.tickCount % 20 == 0) {
            BlockPos herePos = EnemyBreak_N_BuildUtils.feetCell(this.mob);
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] tick stage=" + this.stage
                    + " моб=" + herePos + " roofY(тут)=" + zone.getLocalRoofY(herePos.getX(), herePos.getZ()));
        }

        if (this.stage == Stage.DECIDE) {
            decide(zone, player);
        }
        if (this.stage == Stage.TO_7X7) {
            tickTo7x7();
        }
        if (this.stage == Stage.CROSS_AND_DIG) {
            tickCrossAndDig(level);
        }
        if (this.stage == Stage.CLIMBING) {
            tickClimbing(level, player, zone);
        }
        if (this.stage == Stage.SIDESTEP) {
            tickSidestep(level, zone);
        }
        if (this.stage == Stage.BRIDGE_TO_PLAYER) {
            tickBridgeToPlayer(level, player, zone);
        }
    }

    private void decide(TowerZoneData zone, Player player) {
        if (tryEnter7x7IfReachable(zone)) return;

        // ВИПРАВЛЕНО (живий тест): раніше шукали вихід відносно ПОТОЧНОЇ позиції моба - якщо той
        // зупинився далеко (наприклад melee просто йшла, поки могла, і стала за 12+ блоків),
        // стовп починався саме там. "Проекція" з опису - це позиція БІЛЯ ГРАВЦЯ, тому шукаємо
        // відносно гравця; моб сам дійде туди через stepToward() у CLIMBING.
        BlockPos playerPos = player.getOnPos().above();
        BlockPos exit = findNearestPointOutsideFootprint(zone, playerPos);
        // Узгоджений фолбек: валідної точки старту нема (заблоковано з усіх боків) - будуємось
        // де стоїмо, а не софтлочимось без дій.
        commitPillar(exit != null ? exit : EnemyBreak_N_BuildUtils.feetCell(this.mob));
        EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] DECIDE -> CLIMBING, комітнуто "
                + this.pillarColumn);
        this.stage = Stage.CLIMBING;
    }

    /**
     * ВИПРАВЛЕНО (живий тест, "моб просто стоїть"): раніше перемикались на TO_7X7 БЕЗУМОВНО,
     * щойно 7x7 взагалі існує — навіть якщо моб ще внизу, далеко під площею. tickTo7x7() це
     * ЧИСТА навігація (nav.moveTo), без стрибків і без блоків — а звичайна навігація не вміє
     * прокласти шлях вгору без готових сходинок, тож моб просто стояв, намагаючись дійти туди,
     * куди фізично не може піднятись. Тепер: (а) перевіряємо реальну досяжність (моб уже НЕ
     * нижче за поверхню площі) перед перемиканням; (б) цей метод викликається не тільки з
     * DECIDE, а й з КОЖНОГО тіку {@link #tickClimbing} — тож щойно моб підніметься досить
     * високо посеред звичайного підйому, він одразу скористається шорткатом, а не долізе аж
     * до даху зони.
     */
    private boolean tryEnter7x7IfReachable(TowerZoneData zone) {
        BlockPos found7x7 = zone.getFull7x7Center();
        if (found7x7 == null) return false;
        if (EnemyBreak_N_BuildUtils.feetCell(this.mob).getY() < found7x7.getY()) return false; // ще занадто низько

        this.target7x7 = found7x7;
        this.stage = Stage.TO_7X7;
        this.to7x7StuckTicks = 0;
        EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] -> TO_7X7 " + found7x7);
        return true;
    }

    // ================= TO_7X7 / CROSS_AND_DIG =================

    private void commitPillar(BlockPos column) {
        this.pillarColumn = new BlockPos(column.getX(), EnemyBreak_N_BuildUtils.feetCell(this.mob).getY(), column.getZ());
        this.layerY = Integer.MIN_VALUE; // форсує заморозку напрямку стіни на найпершому шарі
    }

    /**
     * Ring-пошук (по Чебишеву) найближчої (x,z)-колонки, якої НЕМАЄ в буферній зоні —
     * "проекція" з опису: моб має стати ЗА її межами, перш ніж почати ставити перший блок.
     */
    private BlockPos findNearestPointOutsideFootprint(TowerZoneData zone, BlockPos from) {
        int maxRadius = Config.TOWER_ZONE_SCAN_RADIUS.get() + Config.TOWER_ZONE_REACH_CAP.get() + 4;
        for (int r = 1; r <= maxRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // лише периметр кільця r
                    int x = from.getX() + dx;
                    int z = from.getZ() + dz;
                    if (!zone.isColumnInsideFootprint(x, z)) {
                        return new BlockPos(x, from.getY(), z);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Захист від того самого класу багів, що й вище: якщо ЧОМУСЬ (не лише через висоту -
     * наприклад перепону, яку не вміє обійти vanilla-навігація) tickTo7x7 не може дійти,
     * не стоїмо тут вічно - за {@link #TO_7X7_STUCK_TIMEOUT_TICKS} відмовляємось від 7x7 для
     * цього заходу й повертаємось до звичайного підйому.
     */
    private void tickTo7x7() {
        BlockPos mobPos = EnemyBreak_N_BuildUtils.feetCell(this.mob);
        if (mobPos.distSqr(this.target7x7) <= NAV_ARRIVE_DIST_SQ) {
            this.stage = Stage.CROSS_AND_DIG;
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] TO_7X7 -> CROSS_AND_DIG "
                    + this.target7x7);
            return;
        }

        if (++this.to7x7StuckTicks > TO_7X7_STUCK_TIMEOUT_TICKS) {
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] TO_7X7 застряг - відмовляюсь, "
                    + "комітюсь на місці й лізу звичайним шляхом");
            commitPillar(EnemyBreak_N_BuildUtils.feetCell(this.mob));
            this.stage = Stage.CLIMBING;
            return;
        }

        this.mob.getNavigation().moveTo(
                this.target7x7.getX() + 0.5, this.target7x7.getY(), this.target7x7.getZ() + 0.5,
                Run_N_JumpUtils.getRunSpeedModifier(this.mob));
    }

    /**
     * Хрест + "паркан" по краях (по 1 блоку в кожен бік на рівні ніг, і ще по 1 зверху кожного з
     * них) — щоб коли моб проб'є стелю над центром, нокбек не міг зіштовхнути його вбік у
     * повітря: з усіх 4 боків на висоті голови вже стоїть блок.
     */
    private void tickCrossAndDig(ServerLevel level) {
        BlockPos center = EnemyBreak_N_BuildUtils.feetCell(this.mob);
        // v21: хрест добудовується по одному блоку, а темп задає placeBlock (towerPlaceDelayTicks) - окремий кулдаун не потрібен.
        if (buildCrossSupportStep(level, center)) return; // ще не все готово

        long now = level.getGameTime();
        if (now < this.nextBreakTick) return; // ламання/стрибок - у тому ж реальному темпі, що й було
        this.nextBreakTick = now + BREAK_ACTION_COOLDOWN_TICKS;

        BlockPos overhead = center.above(2);
        if (EnemyBreak_N_BuildUtils.isBreakable(level, overhead)) {
            breakCeilingWithLavaCover(level, overhead);
            return;
        }

        if (this.mob.onGround()) {
            this.mob.getJumpControl().jump();
        }
        // Досягли гравця - canContinueToUse() сам поверне false (isPathBlocked стане false).
    }

    /**
     * Хрест + "паркан" по краях (по 1 блоку в кожен бік НА РІВНІ ПІДЛОГИ під мобом, і ще по 1
     * зверху кожного з них, уже на рівні ніг моба) — щоб коли моб проб'є стелю над центром,
     * нокбек не міг зіштовхнути його вбік у повітря: з усіх 4 боків на висоті тіла вже стоїть
     * блок. Ставить РІВНО ОДИН відсутній блок за виклик і повертає true (ще не готово) — false,
     * коли хрест уже повністю зібраний.
     * <p>
     * ВИПРАВЛЕНО (живий тест, "блоки в повітрі"): руки раніше ставились на висоті НІГ моба, не
     * підлоги — тобто рівно поряд із власним "повітряним" простором моба, без жодної реальної
     * грані-сусіда, до якої можна приліпитись (мобове тіло не solid). "блок на якому стоїть" з
     * опису буквально означає підлогу ({@code center.below()}), не самого моба.
     */
    private boolean buildCrossSupportStep(ServerLevel level, BlockPos center) {
        BlockPos floor = center.below(); // блок, на якому моб СТОЇТЬ
        for (Direction dir : CARDINALS) {
            BlockPos armFloor = floor.relative(dir);
            if (!level.getBlockState(armFloor).isSolid()) {
                EnemyBreak_N_BuildUtils.placeBlock(level, armFloor, this.mob, PlaceReason.TOWER_STRUCTURE);
                return true;
            }
            BlockPos armParapet = armFloor.above(); // тепер на висоті ніг моба - правильна висота "паркану"
            if (!level.getBlockState(armParapet).isSolid()) {
                EnemyBreak_N_BuildUtils.placeBlock(level, armParapet, this.mob, PlaceReason.TOWER_STRUCTURE);
                return true;
            }
        }
        return false;
    }

    /**
     * Ламає стелю, а якщо над нею сиділа лава — одразу перекриває (перевірено ДО лому, поки
     * ще відомо, що там було). Той самий фолбек з опису: "не зважаючи на те що має обходити,
     * все ж натикається" — коли обійти вже нема з чого (використовується і для 7×7, і як
     * останній fallback у звичайному підйомі, коли обхід не знайшов вільної сусідньої колонки).
     */
    private void breakCeilingWithLavaCover(ServerLevel level, BlockPos overhead) {
        boolean lavaAbove = isLavaAbove(level, overhead);
        EnemyBreak_N_BuildUtils.breakBlock(level, overhead, this.mob);
        if (lavaAbove) {
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] лава над проломом - перекриваю");
            EnemyBreak_N_BuildUtils.placeBlock(level, overhead.above(), this.mob, PlaceReason.HAZARD_COVER);
        }
    }

    /**
     * "якщо товщина потолка 1 блок і далі повітря" — саме цей випадок (не товща стеля) означає,
     * що пролом одразу виставить моба у відкрите повітря, де нокбек може скинути його вбік.
     */
    private boolean ceilingIsThinWithAirBeyond(ServerLevel level, BlockPos overhead) {
        return !level.getBlockState(overhead.above()).isSolid();
    }

    private boolean isLavaAbove(ServerLevel level, BlockPos overhead) {
        return level.getFluidState(overhead.above()).is(FluidTags.LAVA);
    }

    /**
     * "нехай він перевіряє чи є над ним лава якщо так то нехай обходить її" — для звичайного
     * підйому (на відміну від CROSS_AND_DIG, де обходити нема з чого — це вже єдиний знайдений
     * 7x7) обхід МОЖЛИВИЙ: колонка не закомітчена намертво до конкретної точки простору, тож
     * шукаємо серед 4 сусідніх (та сама висота), у якої над відповідною стелею лави немає.
     */
    private BlockPos findLavaFreeAdjacentColumn(ServerLevel level, BlockPos center, int overheadY) {
        for (Direction dir : CARDINALS) {
            BlockPos candidate = center.relative(dir);
            BlockPos candidateOverhead = new BlockPos(candidate.getX(), overheadY, candidate.getZ());
            if (!level.getFluidState(candidateOverhead.above()).is(FluidTags.LAVA)) {
                return candidate;
            }
        }
        return null; // з усіх боків лава - нема куди обійти, викликач сам впаде на фолбек-перекриття
    }

    // ================= CLIMBING / SIDESTEP =================

    /**
     * true — вдалось знайти й почати обхід (стан уже CLIMBING -> SIDESTEP), викликач просто
     * виходить із тіку. false — обходити нема куди, викликач сам ламає й перекриває (фолбек).
     */
    private boolean tryAvoidLavaBySidestep(ServerLevel level, BlockPos overhead) {
        BlockPos altColumn = findLavaFreeAdjacentColumn(level, EnemyBreak_N_BuildUtils.feetCell(this.mob), overhead.getY());
        if (altColumn == null) return false;
        this.sidestepTarget = altColumn;
        this.stage = Stage.SIDESTEP;
        EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] лава над стелею - обхожу через " + altColumn);
        return true;
    }

    private void tickClimbing(ServerLevel level, Player player, TowerZoneData zone) {
        ensureFloorUnderneathWhileAirborne(level);

        if (tryEnter7x7IfReachable(zone)) return; // піднялись досить високо - скористаємось шорткатом

        if (zone.isColumnInsideFootprint(this.pillarColumn.getX(), this.pillarColumn.getZ())) {
            beginSidestep(zone);
            if (this.stage != Stage.CLIMBING) return; // дійсно перейшли в SIDESTEP
            // інакше (фолбек - нема куди виходити) - просто ігноруємо цей ріст і йдемо далі нижче
        }

        // ФІКС ("дах до неба по сходах"): дах тепер локальний для КОЛОНКИ, з якої моб реально
        // піляриться (pillarColumn), а не одне глобальне число на всю накопичену зону - див.
        // TowerZoneData#getLocalRoofY.
        int localRoof = zone.getLocalRoofY(this.pillarColumn.getX(), this.pillarColumn.getZ());
        boolean roofKnown = localRoof != Integer.MIN_VALUE; // захист від того самого сентинел-багу
        boolean aboveRoof = roofKnown && EnemyBreak_N_BuildUtils.feetCell(this.mob).getY() >= localRoof;
        // "якщо моб на границі висоти то він просто йде прямо до ближайшої площі без того щоб
        // простроїтися више" - лишаємо запас в WALL_HEIGHT+1, бо на цьому ж кроці ще треба буде
        // місце під стіну поверх підлоги.
        boolean atWorldHeightLimit = EnemyBreak_N_BuildUtils.feetCell(this.mob).getY() >= level.getMaxBuildHeight() - (WALL_HEIGHT + 1);
        if (aboveRoof || atWorldHeightLimit) {
            this.stage = Stage.BRIDGE_TO_PLAYER;
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] CLIMBING -> BRIDGE_TO_PLAYER "
                    + (atWorldHeightLimit ? "(межа висоти світу) " : "(вище даху зони) ")
                    + "Y=" + EnemyBreak_N_BuildUtils.feetCell(this.mob).getY() + " roofY(тут)=" + localRoof);
            return;
        }

        if (!stepToward(level, this.pillarColumn)) return;

        // v21: ОДИН ШАР піларингу = НАЗЕМНА фаза (стеля -> проба стрибка -> стіна по одному блоку) -> стрибок -> ПОВІТРЯНА фаза
        // (добудова стіни вгору + блок під ногами). Раніше все це було одним "пакетом дій" раз на 10 викликів tick() (= 20
        // ігрових тіків, бо ваніль тікає ціль раз на 2 тіки), а кілька блоків стіни в одному тіку розбивались об затримку між
        // блоками - тож за шар добудовувався рівно один блок стіни.
        if (!this.mob.onGround()) {
            tickPillarAir(level);
            return;
        }
        tickPillarGround(level, player);
    }

    // ================= CLIMBING: ОДИН ШАР піларингу =================

    /**
     * v21: ПОВІТРЯ під час стрибка піларингу. Блок ПІД ногами (PILLAR_FLOOR) ставить
     * {@link #ensureFloorUnderneathWhileAirborne} - вона йде першою в {@link #tickClimbing} і має мінімальний розрив у 1 тік, тож
     * стіна її не стримує. Тут моб, поки летить, добудовує стіну ШАРУ, на який приземлиться, - на один блок угору, по одному
     * блоку з розривом {@code towerPlaceDelayTicks}. Що не встигло - добудується на землі наступного шару.
     * <p>
     * Не робиться, якщо вже на наступному шарі можна допригнути до гравця ({@link #preExtendBlocked}): верхній блок стіни
     * закрив би цей стрибок, тож його ставимо лише ПІСЛЯ проби ({@link #tickJumpCheck}).
     */
    private void tickPillarAir(ServerLevel level) {
        if (this.takeoffFeet == null || this.preExtendBlocked) return;
        long now = level.getGameTime();
        if (now - this.jumpIssuedTick > AIR_EXTEND_WINDOW_TICKS) return; // не наш стрибок (нокбек тощо)
        if (!EnemyBreak_N_BuildUtils.canPlaceNow(this.mob, PlaceReason.TOWER_STRUCTURE, now)) return;
        for (BlockPos cell : missingWallCells(level, this.takeoffFeet.above(), true)) {
            if (EnemyBreak_N_BuildUtils.placeBlock(level, cell, this.mob, PlaceReason.TOWER_STRUCTURE)) {
                this.lastWallProgressTick = now;
                return;
            }
        }
    }

    /**
     * v21: НАЗЕМНА фаза шару піларингу. Порядок (узгоджено з користувачем): стоїть -> стеля -> [проба "чи вже можна допригнути"]
     * -> стіна з боку гравця ПО ОДНОМУ блоку (темп задає placeBlock, {@code towerPlaceDelayTicks}) -> стрибок. Блок під ногами
     * й добудову стіни вгору робить {@link #tickPillarAir} уже у польоті.
     */
    private void tickPillarGround(ServerLevel level, Player player) {
        BlockPos feet = EnemyBreak_N_BuildUtils.feetCell(this.mob);
        long now = level.getGameTime();

        if (feet.getY() != this.layerY) {
            lockWallDirectionForNewLayer(player);
            beginLayer(feet, now);
        }

        // 1) стеля над головою (як і раніше): лава -> обхід; тонка стеля -> хрест-підпора; інакше ламаємо.
        BlockPos overhead = feet.above(2);
        if (EnemyBreak_N_BuildUtils.isBreakable(level, overhead)) {
            if (isLavaAbove(level, overhead) && tryAvoidLavaBySidestep(level, overhead)) {
                return; // пішли в обхід - до лому цього конкретного блоку тут не дійшло
            }
            if (ceilingIsThinWithAirBeyond(level, overhead) && buildCrossSupportStep(level, feet)) {
                return; // хрест ще не добудований - добудовуємо по одному блоку (темп - placeBlock)
            }
            if (now < this.nextBreakTick) return;
            this.nextBreakTick = now + BREAK_ACTION_COOLDOWN_TICKS;
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] climb: ламаю стелю " + overhead);
            breakCeilingWithLavaCover(level, overhead);
            return;
        }

        // 2) чи вже можна допригнути до гравця - ДО верхніх блоків стіни (власна стіна по голову закрила б стрибок)
        if (!tickJumpCheck(level, feet)) {
            return;
        }

        // 3) стіна: ПО ОДНОМУ блоку (спершу підлога-розширення, потім ноги, потім голова - для кожного стовпчика).
        List<BlockPos> missing = missingWallCells(level, feet, true);
        if (!missing.isEmpty()) {
            if (EnemyBreak_N_BuildUtils.canPlaceNow(this.mob, PlaceReason.TOWER_STRUCTURE, now)) {
                for (BlockPos cell : missing) {
                    if (EnemyBreak_N_BuildUtils.placeBlock(level, cell, this.mob, PlaceReason.TOWER_STRUCTURE)) {
                        this.lastWallProgressTick = now;
                        return;
                    }
                }
            }
            if (now - this.lastWallProgressTick <= wallGiveUpTicks()) {
                return; // чекаємо темпу / умов постановки (хітбокс, видимість) - не стрибаємо з недобудованою стіною
            }
            EnemyBreak_N_BuildUtils.buildLog(this.mob, "TowerClimbGoal: стіну шару Y=" + feet.getY() + " не вдається добудувати "
                    + wallGiveUpTicks() + " тіків (лишилось " + missing.size() + ") - стрибаю без неї");
        }

        // 4) стіна готова (або добудувати нема як) - стрибок піларингу
        if (now - this.jumpIssuedTick < JUMP_REISSUE_TICKS) {
            return;
        }
        this.takeoffFeet = feet;
        // Стіну наступного шару наперед (у польоті) НЕ добудовуємо, якщо вже з нього можна допригнути до гравця - див. tickJumpCheck.
        this.preExtendBlocked = Config.TOWER_JUMP_FIRST.get() && jumpPlausibleFrom(feet.getY() + 1);
        this.jumpIssuedTick = now;
        this.mob.getJumpControl().jump();
    }

    /**
     * v21: початок нового шару піларингу (моб на землі на новій висоті).
     */
    private void beginLayer(BlockPos feet, long now) {
        this.lastWallProgressTick = now;
        this.jumpCheckWaitTicks = 0;
        this.takeoffFeet = null;
        boolean check = Config.TOWER_JUMP_FIRST.get() && jumpPlausibleFrom(feet.getY());
        this.jumpCheck = check ? JumpCheck.PENDING : JumpCheck.DONE;
        EnemyBreak_N_BuildUtils.buildLog(this.mob, "TowerClimbGoal: новий шар Y=" + feet.getY() + " | проба стрибка: " + this.jumpCheck);
    }

    private int wallGiveUpTicks() {
        return WALL_GIVE_UP_BASE_TICKS + Config.TOWER_PLACE_DELAY_TICKS.get();
    }

    /**
     * v21: чи може стрибок до гравця бути можливим із висоти ніг {@code feetY} (лише ПОПЕРЕДНЯ перевірка, чи варто пробувати;
     * вердикт дає свіжий A*). Ціль - та сама клітинка, до якої рахується шлях ({@link EnemyBreak_N_BuildUtils#pathTargetCell}).
     */
    private boolean jumpPlausibleFrom(int feetY) {
        Vec3 chasePos = PursuitEnemyBehavior.getChasePosition(this.mob);
        if (chasePos == null) return false;
        BlockPos target = EnemyBreak_N_BuildUtils.pathTargetCell(this.mob, chasePos);
        return TerraformRules.jumpMayBePossible(target.getY(), feetY);
    }

    /**
     * v21: ПЕРЕВІРКА "чи вже можна допригнути до гравця" на поточному шарі ({@link Config#TOWER_JUMP_FIRST}) - ДО того, як стіна
     * дійде до висоти голови. Власна стіна по голову закриває стрибок (A* бачить її як звичайну перешкоду, а вище за 1 блок
     * через неї не перестрибнути), тому раніше моб добудовував вежу далі, хоча без стіни допригнути вже було можна. Тепер:
     * <ol>
     *   <li>власні ВЕРХНІ блоки стіни, які моб міг поставити наперед (гравець опинився нижче/ближче, поки моб летів),
     *       прибираються ({@link #openOwnHeadWall});</li>
     *   <li>просимо свіжий шлях ({@link EnemyBreak_N_BuildUtils#requestFreshPath}) і чекаємо вердикт, не довше за
     *       {@link #JUMP_CHECK_MAX_WAIT_TICKS}. Нижня частина стіни (на рівні ніг) лишається - для A* це звичайна сходинка
     *       заввишки 1 блок, на яку можна зійти;</li>
     *   <li>маршрут є ({@code isPathBlocked == false}) - нічого більше не ставимо: {@link #canContinueToUse} зупинить вежу, а
     *       Pursuit + GapJumpAssistGoal стрибнуть. Маршруту нема - верхні блоки стіни ставляться як звичайно.</li>
     * </ol>
     *
     * @return true - можна ставити верхні блоки стіни й стрибати далі; false - чекаємо вердикт (або маршрут уже знайдено)
     */
    private boolean tickJumpCheck(ServerLevel level, BlockPos feet) {
        if (this.jumpCheck == JumpCheck.DONE) return true;

        Vec3 chasePos = PursuitEnemyBehavior.getChasePosition(this.mob);
        if (chasePos == null) {
            this.jumpCheck = JumpCheck.DONE;
            return true;
        }

        if (this.jumpCheck == JumpCheck.PENDING) {
            openOwnHeadWall(level, feet);
            int seqBefore = EnemyBreak_N_BuildUtils.getComputeSeq(this.mob);
            if (EnemyBreak_N_BuildUtils.requestFreshPath(this.mob)) {
                this.jumpCheckSeq = seqBefore;
                this.jumpCheckWaitTicks = 0;
                this.jumpCheck = JumpCheck.WAITING;
                this.lastJumpProbeTick = this.mob.tickCount;
                EnemyBreak_N_BuildUtils.logJumpProbe(this.mob, "TowerClimbGoal[climb]: проба стрибка ПЕРЕД верхніми блоками стіни (Y="
                        + feet.getY() + ")");
            } else if (++this.jumpCheckWaitTicks > JUMP_CHECK_MAX_WAIT_TICKS) {
                this.jumpCheck = JumpCheck.DONE; // бюджет проб вичерпано надовго - не стоїмо, будуємо як звичайно
                return true;
            }
            return false;
        }

        // WAITING: чекаємо СПРАВЖНЄ обчислення шляху (computeSeq росте лише на новому createPath)
        if (EnemyBreak_N_BuildUtils.getComputeSeq(this.mob) == this.jumpCheckSeq) {
            if (++this.jumpCheckWaitTicks > JUMP_CHECK_MAX_WAIT_TICKS) {
                this.jumpCheck = JumpCheck.DONE;
                return true;
            }
            return false;
        }
        if (!EnemyBreak_N_BuildUtils.isPathBlocked(this.mob, chasePos)) {
            return false; // є маршрут (стрибком) - вежа зупиниться в canContinueToUse(); верхніх блоків не ставимо
        }
        this.jumpCheck = JumpCheck.DONE;
        EnemyBreak_N_BuildUtils.logJumpProbe(this.mob, "TowerClimbGoal[climb]: з цієї висоти допригнути ще не можна - будую стіну далі (Y="
                + feet.getY() + ")");
        return true;
    }

    /**
     * v21: прибирає власні ВЕРХНІ блоки стіни (на висоті голови) - див. {@link #tickJumpCheck}. Чіпає ЛИШЕ блоки, які поставив
     * сам моб (TemporaryBlockData): ні природний рельєф, ні блоки гравця не ламаються.
     */
    private void openOwnHeadWall(ServerLevel level, BlockPos feet) {
        TemporaryBlockData temporary = TemporaryBlockData.get(level);
        for (BlockPos base : wallBases(feet)) {
            BlockPos head = base.above(WALL_HEIGHT - 1);
            if (level.getBlockState(head).isSolid() && temporary.isTracked(head)) {
                EnemyBreak_N_BuildUtils.buildLog(this.mob, "TowerClimbGoal: прибираю власний верхній блок стіни " + head.toShortString()
                        + " - він закрив би стрибок до гравця");
                EnemyBreak_N_BuildUtils.breakBlock(level, head, this.mob);
            }
        }
    }

    /**
     * v20: просить свіжий шлях до гравця (див. {@link EnemyBreak_N_BuildUtils#requestFreshPath}). Якщо з поточної висоти вже
     * є маршрут зі стрибком, {@link EnemyBreak_N_BuildUtils#isPathBlocked} стане false, {@link #canContinueToUse} зупинить
     * вежу, а Pursuit + GapJumpAssistGoal стрибнуть. Без цього тротлінг недосяжної цілі (до 40 тіків) змушував моба
     * добудовувати ще кілька зайвих шарів, хоча допригнути вже було можна.
     */
    private void probeJumpRoute(String where) {
        if (!Config.TOWER_JUMP_FIRST.get()) {
            return;
        }
        if (this.mob.tickCount - this.lastJumpProbeTick < JUMP_PROBE_MIN_INTERVAL_TICKS) {
            return;
        }
        if (!EnemyBreak_N_BuildUtils.requestFreshPath(this.mob)) {
            return; // загальний бюджет запитів цього тіку вичерпано (багато башт одразу) - спробуємо наступного разу
        }
        this.lastJumpProbeTick = this.mob.tickCount;
        EnemyBreak_N_BuildUtils.logJumpProbe(this.mob, "TowerClimbGoal[" + where + "]: свіжий шлях - чи вже можна допригнути до гравця (Y="
                + EnemyBreak_N_BuildUtils.feetCell(this.mob).getY() + ")");
    }

    private void beginSidestep(TowerZoneData zone) {
        BlockPos exit = findNearestPointOutsideFootprint(zone, this.pillarColumn);
        if (exit == null) {
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] зона поглинула колонку, "
                    + "вийти нема куди - ігнорую й продовжую підйом як є (фолбек)");
            return; // лишаємось у CLIMBING без реакції на цей конкретний ріст
        }
        this.sidestepTarget = exit;
        this.stage = Stage.SIDESTEP;
        EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] CLIMBING -> SIDESTEP до " + exit);
    }

    private void tickSidestep(ServerLevel level, TowerZoneData zone) {
        ensureFloorUnderneathWhileAirborne(level);

        BlockPos mobPos = EnemyBreak_N_BuildUtils.feetCell(this.mob);
        // ВИПРАВЛЕНО (живий тест): раніше завершували сайдстеп щойно мобу опинявся в межах
        // generic-порогу відстані від sidestepTarget - а сусідня клітинка ЗАВЖДИ в цих межах від
        // старту, тож перевірка проходила МИТТЄВО, без жодного реального кроку. Моб комітився
        // туди ж, звідки щойно "вийшов", наступний тік знову бачив себе в зоні - нескінченний
        // цикл (звідси й "ставить блок собі і стрибає на наступний": насправді ніколи не рухався
        // вгору, тільки туди-сюди по одній і тій самій клітинці). Тепер перевіряємо РЕАЛЬНИЙ
        // геометричний факт - чи колонка, де моб ЗАРАЗ стоїть, дійсно поза footprint-ом.
        if (!zone.isColumnInsideFootprint(mobPos.getX(), mobPos.getZ())) {
            commitPillar(mobPos);
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] SIDESTEP -> CLIMBING, вийшли з "
                    + "проекції, нова колонка " + this.pillarColumn);
            this.stage = Stage.CLIMBING;
            return;
        }
        stepToward(level, this.sidestepTarget); // рух далі - результат тут не вирішальний, див. вище
    }

    /**
     * Фіксує напрямок(и) стіни РІВНО РАЗ на новий шар (Y змінився відносно {@link #layerY}) —
     * навмисно НЕ перечитує позицію гравця щотіку всередині того самого шару, щоб зигзаг-стовп
     * гравця не смикав моба між кутовим і кардинальним варіантом посеред одного й того ж рівня.
     */
    private void lockWallDirectionForNewLayer(Player player) {
        this.layerY = EnemyBreak_N_BuildUtils.feetCell(this.mob).getY();

        double dx = player.getX() - this.mob.getX();
        double dz = player.getZ() - this.mob.getZ();
        double absDx = Math.abs(dx);
        double absDz = Math.abs(dz);
        Direction ew = dx >= 0 ? Direction.EAST : Direction.WEST;
        Direction ns = dz >= 0 ? Direction.SOUTH : Direction.NORTH;

        if (absDx < 1.0e-6 && absDz < 1.0e-6) {
            this.wallDirA = ew; // гравець майже точно над мобом - довільний вибір, аби не ділити на 0
            this.wallDirB = null;
            return;
        }

        double bigger = Math.max(absDx, absDz);
        double smaller = Math.min(absDx, absDz);
        if (smaller >= bigger * DIAGONAL_RATIO_THRESHOLD) {
            this.wallDirA = ns;
            this.wallDirB = ew; // діагональ - додається ще й кутовий блок нижче
        } else if (absDx >= absDz) {
            this.wallDirA = ew;
            this.wallDirB = null;
        } else {
            this.wallDirA = ns;
            this.wallDirB = null;
        }
    }

    /**
     * Опорні клітинки стовпчиків стіни для шару з ногами в {@code feet}: 1 клітинка з боку гравця (кардинальний випадок) або 3
     * (обидва кардинальні + сам кутовий) для діагоналі - без кутового блока два кардинальні торкаються лише по діагоналі й
     * лишають щілину, крізь яку може "виглядати" ребро хітбокса моба.
     */
    private List<BlockPos> wallBases(BlockPos feet) {
        List<BlockPos> bases = new ArrayList<>(3);
        if (this.wallDirA == null) return bases;
        bases.add(feet.relative(this.wallDirA));
        if (this.wallDirB != null) {
            bases.add(feet.relative(this.wallDirB));
            bases.add(feet.relative(this.wallDirA).relative(this.wallDirB));
        }
        return bases;
    }

    /**
     * v21: клітинки стіни шару з ногами в {@code feet}, яких ще БРАКУЄ й які можна поставити вже зараз, у порядку будівництва:
     * по кожному стовпчику знизу вгору - підлога-розширення, ноги, голова. Викликач ставить з них по ОДНОМУ (темп - placeBlock).
     * <p>
     * ВИПРАВЛЕНО (живий тест, "блоки в повітрі"; перенесено зі старого placeWallColumn): стіна стирчить убік від вузького
     * 1-блочного стовпа - сама по собі, без розширення підлоги під собою, вона НЕ дотикається гранню жодного вже існуючого
     * блока (сусід "до моба" - власний повітряний простір моба, не solid). Так само, як довелось би гравцю: спершу
     * продовжуємо підлогу вбік ({@code base.below()}), і лише ПОТІМ будуємо стіну на ній.
     * <p>
     * Пропускаються клітинки: де вже є solid-блок; зайняті чимось не-замінюваним (факел тощо); без суміжної опори (вони
     * з'являться в наступних викликах, щойно нижня клітинка стовпчика буде поставлена). {@code allowHead == false} ховає верхні
     * клітинки (на висоті голови) - їх не можна ставити, поки не відома відповідь проби стрибка ({@link #tickJumpCheck}).
     */
    private List<BlockPos> missingWallCells(ServerLevel level, BlockPos feet, boolean allowHead) {
        List<BlockPos> out = new ArrayList<>(4);
        for (BlockPos base : wallBases(feet)) {
            for (int k = 0; k <= WALL_HEIGHT; k++) { // k=0 - підлога-розширення, 1..WALL_HEIGHT - ноги ... голова
                if (k == WALL_HEIGHT && !allowHead) continue;
                BlockPos cell = k == 0 ? base.below() : base.above(k - 1);
                BlockState state = level.getBlockState(cell);
                if (state.isSolid()) continue;
                if (!state.canBeReplaced()) continue;
                if (!EnemyBreak_N_BuildUtils.hasAdjacentSolid(level, cell)) continue;
                out.add(cell);
            }
        }
        return out;
    }

    // ================= BRIDGE_TO_PLAYER =================

    /**
     * "0 - до гравця, 1 - до безопасної площі, 2 - просто до площі" — обирає куди летіти/падати.
     * Пріоритет 0 (сам гравець) теж перевіряється на "не скраю", бо мета цих пріоритетів саме
     * ЦЕ: не націлюватись точно на клітинку над відкритим падінням.
     */
    private BlockPos chooseLandingTarget(Player player, TowerZoneData zone) {
        BlockPos playerPos = player.getOnPos().above();
        if (zone.isSafeLandingTile(playerPos.getX(), playerPos.getZ())) {
            return playerPos;
        }
        BlockPos safe = zone.findSafeLandingTile(EnemyBreak_N_BuildUtils.feetCell(this.mob));
        if (safe != null) return safe;

        BlockPos any = zone.findAnyLandingTile(EnemyBreak_N_BuildUtils.feetCell(this.mob));
        if (any != null) return any;

        return playerPos; // площа порожня (не мало б траплятись) - фолбек на самого гравця
    }

    private void tickBridgeToPlayer(ServerLevel level, Player player, TowerZoneData zone) {
        BlockPos landingTarget = chooseLandingTarget(player, zone);
        BlockPos mobPos = EnemyBreak_N_BuildUtils.feetCell(this.mob);
        double dx = landingTarget.getX() - mobPos.getX();
        double dz = landingTarget.getZ() - mobPos.getZ();
        double horizontalDistSq = dx * dx + dz * dz;

        // v20: міст і поштовх-падіння нижче - ФОЛБЕК. Спершу з кожним кроком перевіряємо СВІЖИМ шляхом, чи вже можна
        // допригнути до гравця через Run_N_Jump (стрибок вниз на платформу в межах того, що моб переживе, теж рахується).
        probeJumpRoute("bridge");

        if (horizontalDistSq <= FALL_ZONE_DIST_SQ) {
            EnemyBreak_N_BuildUtils.logJumpProbe(this.mob, "ФОЛБЕК: стрибком до гравця не вийшло - поштовх-падіння на ціль");
            // ВИПРАВЛЕНО (живий тест: моб "ступорився" рівно тут замість стрибка): це був той
            // самий "vanilla-навігація мовчки відмовляється будувати шлях" баг, що описаний нижче
            // для travelY - тільки пропущений саме в тому місці, де моб і мав нарешті стрибнути.
            // moveTo() до РЕАЛЬНОЇ висоти гравця (може лежати на 5-10+ непідпертих блоків нижче
            // моба, що стоїть на даху зони) не будує шлях узагалі: ціль технічно поставлена, але
            // навігатор просто нічого не робить, і моб намертво зависає на краю вже готового
            // мосту - у логах видно як координати моба, що не змінюються сотні тіків підряд.
            // Замість навігації - прямий горизонтальний поштовх у бік цілі (як стрибок/пoунс):
            // гравітація сама довершить падіння, щойно моб зійде з опори (яку свідомо більше не
            // підкладаємо - див. відсутність ensureFloorUnderneathWhileAirborne нижче по цій гілці).
            this.mob.getNavigation().stop();
            double pushX = (landingTarget.getX() + 0.5) - this.mob.getX();
            double pushZ = (landingTarget.getZ() + 0.5) - this.mob.getZ();
            double horizLen = Math.sqrt(pushX * pushX + pushZ * pushZ);
            if (horizLen > 1.0E-4) {
                double speed = Math.max(this.mob.getSpeed(), 0.2F) * 1.8D;
                Vec3 currentMotion = this.mob.getDeltaMovement();
                this.mob.setDeltaMovement(
                        (pushX / horizLen) * speed, currentMotion.y, (pushZ / horizLen) * speed);
                this.mob.hasImpulse = true;
            }
            return;
        }

        ensureFloorUnderneathWhileAirborne(level);

        // ВИПРАВЛЕНО (живий тест, стабільно відтворюється): поки МИ САМІ ще не в "зоні падіння"
        // (перевірка вище) - завжди цілимось на БЕЗПЕЧНУ (свою поточну / дах зони) висоту, а НЕ
        // на реальну висоту гравця, скільки б по горизонталі не лишалось. Раніше тут була межа
        // "≤12 блоків - одразу цілимось на реальну висоту" - і саме вона ламала все: до реальної
        // висоти гравця (може бути на 5-10+ блоків нижче, якщо гравець на стовпі) просто НЕМАЄ
        // прохідного шляху - там ще порожньо, під це не підкладено жодного блока, і vanilla-
        // навігація мовчки відмовляється туди йти. Крок мосту (nextHorizontalStep, вже
        // виправлений) міг рахувати правильний напрямок - але якщо navigation.moveTo() взагалі не
        // веде моба нікуди (бо шляху до цілі не існує), напрямок кроку не має значення: моб просто
        // стоїть на місці. Тепер і навігація, і крок мосту весь час цілять в ОДНУ безпечну точку,
        // яка завжди досяжна звичайною ходьбою (бо міст під неї щойно добудований).
        // ФІКС ("дах до неба по сходах"): локальний дах, а не одне глобальне число - беремо
        // максимум із "де моб зараз" і "де ціль посадки", щоб траєкторія мосту гарантовано
        // очищала дах з ОБОХ кінців (вони зазвичай близькі, але не завжди рівні).
        int travelY = Math.max(mobPos.getY(), Math.max(
                zone.getLocalRoofY(mobPos.getX(), mobPos.getZ()),
                zone.getLocalRoofY(landingTarget.getX(), landingTarget.getZ())));
        BlockPos travelTarget = new BlockPos(landingTarget.getX(), travelY, landingTarget.getZ());

        this.mob.getNavigation().moveTo(travelTarget.getX() + 0.5, travelTarget.getY(), travelTarget.getZ() + 0.5,
                Run_N_JumpUtils.getRunSpeedModifier(this.mob));

        // v21: темп мосту задає placeBlock (випадкові 4-5 тіків між блоками), а не 10-тіковий кулдаун цілі: між блоками моб
        // просто йде далі навігацією й не робить зайвої роботи.
        if (!EnemyBreak_N_BuildUtils.canPlaceNow(this.mob, PlaceReason.BRIDGE_STEP, level.getGameTime())) return;

        BlockPos step = EnemyBreak_N_BuildUtils.nextHorizontalStep(this.mob, travelTarget);
        neutralizeHazardIfPlayerPlaced(level, step);
        neutralizeHazardIfPlayerPlaced(level, step.above());
        BlockPos stepBelow = step.below();
        if (!level.getBlockState(stepBelow).isSolid()) {
            EnemyBreak_N_BuildUtils.placeBlock(level, stepBelow, this.mob, PlaceReason.BRIDGE_STEP);
        }
    }

    /**
     * ФІКС, той самий що й у BuildPathGoal: підкладає підлогу під ноги КОЖЕН тік, поки моб у
     * повітрі - коротке вікно, 10-тіковий кулдаун легко його пропускає повністю.
     * <p>
     * v18: лише коли це справді потрібно - піларинг (стадії CROSS_AND_DIG/CLIMBING: моб сам підстрибує на місці) або моб
     * щойно зійшов з краю й падає; під час паркур-стрибка - ніколи ({@link EnemyBreak_N_BuildUtils#allowAirborneFloor}).
     */
    private void ensureFloorUnderneathWhileAirborne(ServerLevel level) {
        boolean pillaring = this.stage == Stage.CLIMBING || this.stage == Stage.CROSS_AND_DIG;
        if (EnemyBreak_N_BuildUtils.allowAirborneFloor(this.mob, pillaring)) {
            BlockPos below = EnemyBreak_N_BuildUtils.floorCellToFill(level, this.mob); // v19: null - опора вже є
            if (below != null) {
                EnemyBreak_N_BuildUtils.placeBlock(level, below, this.mob,
                        pillaring ? PlaceReason.PILLAR_FLOOR : PlaceReason.EDGE_CATCH);
            }
        }
    }

    // ================= спільні дрібні хелпери =================

    /**
     * Один крок вбік до цільової (x,z)-колонки (Y цілі ігнорується — йдемо на поточній висоті
     * моба): якщо під наступним кроком порожньо, підкладає опору (як
     * {@code BuildPathGoal.handleBridge}), інакше просто веде туди навігацією. true, коли вже
     * практично на місці по X/Z.
     */
    private boolean stepToward(ServerLevel level, BlockPos targetColumn) {
        BlockPos mobPos = EnemyBreak_N_BuildUtils.feetCell(this.mob);
        double dx = targetColumn.getX() - mobPos.getX();
        double dz = targetColumn.getZ() - mobPos.getZ();
        if (dx * dx + dz * dz <= NAV_ARRIVE_DIST_SQ) return true;

        this.mob.getNavigation().moveTo(targetColumn.getX() + 0.5, mobPos.getY(), targetColumn.getZ() + 0.5,
                Run_N_JumpUtils.getRunSpeedModifier(this.mob));

        BlockPos step = EnemyBreak_N_BuildUtils.nextHorizontalStep(this.mob, targetColumn);
        neutralizeHazardIfPlayerPlaced(level, step);
        neutralizeHazardIfPlayerPlaced(level, step.above());
        BlockPos stepBelow = step.below();
        if (!level.getBlockState(stepBelow).isSolid()) {
            EnemyBreak_N_BuildUtils.placeBlock(level, stepBelow, this.mob, PlaceReason.BRIDGE_STEP);
        }
        return false;
    }

    /**
     * "!!ТІЛЬКИ КОЛИ ВОНА НА ПУТИ ЗОМБИ!!" — перевіряє й за потреби нейтралізує (перекриває
     * блоком) гравецький хазард САМЕ на позиції наступного кроку, не будь-де поруч.
     * <p>
     * СПРОЩЕНО відносно опису: правило "3+ джерел підряд — обійти замість перекривати" в цьому
     * проході не реалізовано (порахувати "скільки джерел підряд на шляху" і "чи є обхід" —
     * окрема, доволі велика підзадача) — зараз завжди перекриває, незалежно від кількості.
     */
    private void neutralizeHazardIfPlayerPlaced(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        if (!PlacedHazardRegistry.isPlayerPlaced(level, pos, now)) return;

        boolean isFire = level.getBlockState(pos).is(Blocks.FIRE);
        boolean isHazardFluidSource = level.getFluidState(pos).isSource()
                && (level.getFluidState(pos).is(FluidTags.LAVA) || level.getFluidState(pos).is(FluidTags.WATER));

        if (isFire || isHazardFluidSource) {
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG TowerClimbGoal] гравецький хазард на шляху - "
                    + "перекриваю " + pos);
            EnemyBreak_N_BuildUtils.placeBlock(level, pos, this.mob, PlaceReason.HAZARD_COVER);
        }
    }

    private enum Stage {DECIDE, TO_7X7, CROSS_AND_DIG, CLIMBING, SIDESTEP, BRIDGE_TO_PLAYER}

    /**
     * v21: перевірка "чи вже можна допригнути до гравця" на поточному шарі піларингу, див. {@link #tickJumpCheck}.
     */
    private enum JumpCheck {PENDING, WAITING, DONE}
}