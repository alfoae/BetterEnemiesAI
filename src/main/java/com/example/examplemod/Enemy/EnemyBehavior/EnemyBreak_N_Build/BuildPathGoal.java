package com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.Run_N_JumpUtils;
import com.example.examplemod.debug.DebugLog;
import com.example.examplemod.debug.MobDebug;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * навігація заблокована, але причина НЕ суцільна стіна (це {@link DigThroughWallsGoal}), а
 * відсутність опори під ногами. Спільний гейт і "куди йдемо" — {@link EnemyBreak_N_BuildUtils}.
 * <p>
 * Два режими, обирається щотіку заново залежно від різниці висот з ціллю:
 * <ul>
 *   <li><b>Climb</b> (ціль значно вище — {@link EnemyBreak_N_BuildUtils#CLIMB_HEIGHT_THRESHOLD}) —
 *       ставить блок під ноги і стрибає, "піларить" вгору. Якщо щось заважає стрибку (стеля над
 *       головою) — спочатку ламає його ({@link EnemyBreak_N_BuildUtils#isBreakable}/{@link EnemyBreak_N_BuildUtils#breakBlock}),
 *       саме як просив користувач: "якщо перекриває блок — ламає". ТІЛЬКИ під час
 *       GOING_TO_LAST_SEEN/SEARCHING чи для не-гравця — живе переслідування гравця тепер
 *       веде {@link TowerClimbGoal}.</li>
 *   <li><b>Bridge</b> (ціль приблизно на тій самій висоті, але попереду яма) — кладе блок під
 *       наступний горизонтальний крок, щоб з'явилась опора, і йде далі.</li>
 * </ul>
 * Розмежування з {@link DigThroughWallsGoal} природне через {@code canUse()}: якщо наступний
 * крок — суцільний блок (стіна), тут {@code canUse()} поверне false (це не яма і не підйом),
 * і копання само візьме на себе. Явної координації між двома Goal-ами не потрібно.
 */
public class BuildPathGoal extends Goal {

    private static final int RETARGET_INTERVAL_TICKS = 40;
    private static final int STUCK_CHECK_INTERVAL_TICKS = 20;
    private static final double ARRIVED_DIST_SQ = 4.0;
    private static final int BUILD_ACTION_COOLDOWN_TICKS = 10; // не ставити/ламати щотіку - виглядає природніше

    private final Mob mob;

    private BlockPos buildTarget;
    private int retargetTimer;
    private int stuckCheckTimer;
    private int actionCooldown;

    public BuildPathGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!EnemyBreak_N_BuildUtils.canOperate(this.mob)) return false;
        if (!(this.mob.level() instanceof ServerLevel level)) return false;
        Vec3 chasePos = PursuitEnemyBehavior.getChasePosition(this.mob);
        if (chasePos == null) return false;
        if (!EnemyBreak_N_BuildUtils.isPathBlocked(this.mob, chasePos)) return false;

        BlockPos target = EnemyBreak_N_BuildUtils.findNearestOpenArea(this.mob, level, chasePos);
        boolean climb = needsClimb(target);

        // Підйом до ЖИВОЇ позиції гравця тепер веде TowerClimbGoal (проекція/зона/стіни) - тут
        // навмисно віддаємо йому чергу (чистий stop()/start(), а не бійка за прапорці по
        // пріоритету). GOING_TO_LAST_SEEN/SEARCHING лишаються тут: "як можна швидше, ігноруючи
        // всю логіку зони" - саме так, як просив користувач.
        if (climb && PursuitEnemyBehavior.isLiveChasing(this.mob)) {
            return false;
        }

        boolean bridge = !climb && needsBridge(level, target);
        // ТИМЧАСОВИЙ DEBUG: canUse() перевіряється щотіку, поки Goal НЕ активний - throttle
        // через tickCount, інакше зафлудить чат поки шлях постійно "заблокований", але Goal
        // з якоїсь причини не стартує (canUse true, але щось конфліктує по прапорцях).
        if (this.mob.tickCount % 20 == 0) {
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG BuildPathGoal] canUse(): target=" + target
                    + " needsClimb=" + climb + " needsBridge=" + bridge
                    + " -> canUse=" + (climb || bridge));
        }
        return climb || bridge;
    }

    @Override
    public boolean canContinueToUse() {
        if (!EnemyBreak_N_BuildUtils.canOperate(this.mob)) return false;
        if (this.buildTarget == null) return false;
        // Те саме правило, що й у canUse(): якщо мобу знадобився підйом і при цьому вже жива
        // (не GTLS/SEARCHING) ціль - це тепер територія TowerClimbGoal, віддаємо чергу чистим
        // stop()-ом, а не тягнемо на собі до кінця підйому.
        return !needsClimb(this.buildTarget) || !PursuitEnemyBehavior.isLiveChasing(this.mob);
    }

    @Override
    public void start() {
        this.retargetTimer = 0;
        this.stuckCheckTimer = STUCK_CHECK_INTERVAL_TICKS;
        this.actionCooldown = 0;
        // ТИМЧАСОВИЙ DEBUG
        EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG BuildPathGoal] START моб=" + this.mob.blockPosition());
    }

    @Override
    public void stop() {
        // ТИМЧАСОВИЙ DEBUG: лог ДО обнулення buildTarget, інакше в повідомленні буде null.
        EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG BuildPathGoal] STOP. Був target=" + this.buildTarget
                + " моб=" + this.mob.blockPosition());
        this.buildTarget = null;
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        // Цей Goal активний лише поки canOperate()==true (живе переслідування) — моб має бігти,
        // поки прокладає шлях/піларить до гравця.
        Run_N_JumpUtils.applyDefaultRun(this.mob);

        if (!(this.mob.level() instanceof ServerLevel level)) return;

        Vec3 chasePos = PursuitEnemyBehavior.getChasePosition(this.mob);
        if (chasePos == null) {
            this.buildTarget = null;
            return;
        }

        if (--this.retargetTimer <= 0 || this.buildTarget == null) {
            BlockPos oldTarget = this.buildTarget; // ТИМЧАСОВИЙ DEBUG
            this.buildTarget = EnemyBreak_N_BuildUtils.findNearestOpenArea(this.mob, level, chasePos);
            this.retargetTimer = RETARGET_INTERVAL_TICKS;
            // ТИМЧАСОВИЙ DEBUG: показує СТАРУ і НОВУ ціль поруч - якщо вони сильно "стрибають"
            // між ретаргетами (замість поступового наближення), це і є ефект "то туди, то назад".
            if (oldTarget != null && !oldTarget.equals(this.buildTarget)) {
                EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG BuildPathGoal] RETARGET "
                        + oldTarget + " -> " + this.buildTarget);
            }
        }

        if (--this.stuckCheckTimer <= 0) {
            this.stuckCheckTimer = STUCK_CHECK_INTERVAL_TICKS;
            if (!EnemyBreak_N_BuildUtils.isPathBlocked(this.mob, chasePos)) {
                // ТИМЧАСОВИЙ DEBUG
                EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG BuildPathGoal] СКИДАННЯ (stuckCheck): "
                        + "шлях більше не заблокований. target=" + this.buildTarget);
                this.buildTarget = null;
                return;
            }
        }

        if (this.mob.blockPosition().distSqr(this.buildTarget) <= ARRIVED_DIST_SQ
                && !EnemyBreak_N_BuildUtils.isPathBlocked(this.mob, chasePos)) {
            // ТИМЧАСОВИЙ DEBUG: якщо ЦЕ спрацьовує майже одразу після старту - схоже на кейс
            // "добіг до найближчої точки, миттєво вирішив що прийшов, і все скинулось".
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG BuildPathGoal] СКИДАННЯ (arrived): "
                    + "моб=" + this.mob.blockPosition() + " target=" + this.buildTarget);
            this.buildTarget = null;
            return;
        }

        // ТИМЧАСОВИЙ DEBUG: heartbeat раз/сек поки Goal активний.
        if (this.mob.tickCount % 20 == 0) {
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, String.format(
                    "[DEBUG BuildPathGoal] активний. моб=%s target=%s chasePos=(%.1f,%.1f,%.1f) climb=%s onGround=%s",
                    this.mob.blockPosition(), this.buildTarget, chasePos.x, chasePos.y, chasePos.z,
                    needsClimb(this.buildTarget), this.mob.onGround()));
        }

        this.mob.getLookControl().setLookAt(
                this.buildTarget.getX() + 0.5, this.buildTarget.getY() + 0.5, this.buildTarget.getZ() + 0.5, 30.0F, 30.0F);

        // ФІКС (не ставив блок при стрибку): підкладаємо підлогу під ноги КОЖЕН тік, поки моб у
        // повітрі (onGround() == false) - а не лише разом з рештою дій раз на
        // BUILD_ACTION_COOLDOWN_TICKS (10 тіків) в блоці нижче. Стара логіка перевіряла
        // feet.below() ДО стрибка - тобто саму землю, на якій моб і так стояв (вона за
        // визначенням завжди суцільна), тож умова "якщо не суцільний" ніколи не спрацьовувала.
        // Правильне місце для нового блоку - це стара позиція під ногами, яка звільняється
        // ПІСЛЯ стрибка; перевіряти це потрібно щотіку, бо вікно "у повітрі" коротке і по
        // 10-тіковому кулдауну його легко проґавити повністю (моб просто впаде назад).
        if (!this.mob.onGround()) {
            BlockPos below = this.mob.blockPosition().below();
            if (!level.getBlockState(below).isSolid()) {
                EnemyBreak_N_BuildUtils.placeBlock(level, below, this.mob);
            }
        }

        if (--this.actionCooldown <= 0) {
            this.actionCooldown = BUILD_ACTION_COOLDOWN_TICKS;
            if (needsClimb(this.buildTarget)) {
                handleClimb(level);
            } else {
                handleBridge(level);
            }
        }

        boolean moveOk = this.mob.getNavigation().moveTo(
                this.buildTarget.getX() + 0.5, this.buildTarget.getY(), this.buildTarget.getZ() + 0.5,
                Run_N_JumpUtils.getRunSpeedModifier(this.mob));
        // ДЕБАГ: чи дав moveTo(buildTarget) реальний шлях. Саме тут видно, що моб "стоїть", бо шлях порожній/1 вузол.
        if (DebugLog.on(DebugLog.Cat.BUILD) && DebugLog.throttle("build-moveto-" + this.mob.getId(), 500L)) {
            DebugLog.log(DebugLog.Cat.BUILD, this.mob, "moveTo(buildTarget=" + this.buildTarget.toShortString() + ")="
                    + moveOk + " | шлях=" + MobDebug.pathBrief(this.mob.getNavigation().getPath())
                    + " | моб=" + this.mob.position() + " onGround=" + this.mob.onGround()
                    + " vel=" + String.format("%.3f", this.mob.getDeltaMovement().horizontalDistance()));
        }
    }

    private boolean needsClimb(BlockPos target) {
        return EnemyBreak_N_BuildUtils.needsClimb(this.mob, target);
    }

    /**
     * Яма: наступний крок сам НЕ суцільний (інакше це DigThroughWallsGoal), і під ним теж немає опори.
     */
    private boolean needsBridge(Level level, BlockPos target) {
        BlockPos step = EnemyBreak_N_BuildUtils.nextHorizontalStep(this.mob, target);
        if (EnemyBreak_N_BuildUtils.isBreakable(level, step)) return false; // стіна - не наша справа
        boolean result = !level.getBlockState(step.below()).isSolid();
        // ДЕБАГ: чому саме "міст потрібен / не потрібен". Рішення тут ґрунтується на legacy isSolid() блока під
        // кроком, а не на реальній геометрії опори - у логу видно обидва, щоб зловити розбіжність (люки!).
        if (DebugLog.on(DebugLog.Cat.BUILD)) {
            String sig = step.toShortString() + "|" + result;
            if (DebugLog.throttle("build-nb|" + this.mob.getId() + "|" + sig, 3000L)) {
                DebugLog.log(DebugLog.Cat.BUILD, this.mob, "needsBridge(target=" + target.toShortString() + "): step="
                        + step.toShortString() + " -> " + (result ? "ТАК (під кроком isSolid()=false)"
                        : "НІ (під кроком isSolid()=true)") + " | " + MobDebug.supportReport(level, step, this.mob.getBbWidth()));
            }
        }
        return result;
    }

    private void handleClimb(ServerLevel level) {
        BlockPos feet = this.mob.blockPosition();
        BlockPos overhead = feet.above(2);

        // Спочатку прибираємо перепону над головою, якщо є ("якщо перекриває блок - ламає") -
        // інакше стрибок все одно вдариться об стелю і нічого не дасть.
        if (EnemyBreak_N_BuildUtils.isBreakable(level, overhead)) {
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG BuildPathGoal] climb: ламаю стелю " + overhead); // DEBUG
            EnemyBreak_N_BuildUtils.breakBlock(level, overhead, this.mob);
            return;
        }

        // Підкладання блоку під ноги ПІД ЧАС стрибка тепер робить tick() щотіку (див. вище) -
        // тут лишається тільки сам стрибок, і тільки коли моб дійсно стоїть на землі (інакше
        // getJumpControl().jump() посеред польоту нічого не змінює).
        if (this.mob.onGround()) {
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG BuildPathGoal] climb: стрибок з " + feet); // DEBUG
            this.mob.getJumpControl().jump();
        }
    }

    private void handleBridge(ServerLevel level) {
        BlockPos step = EnemyBreak_N_BuildUtils.nextHorizontalStep(this.mob, this.buildTarget);
        BlockPos stepBelow = step.below();

        boolean place = !level.getBlockState(stepBelow).isSolid();
        if (DebugLog.on(DebugLog.Cat.BUILD)) {
            DebugLog.log(DebugLog.Cat.BUILD, this.mob, "handleBridge: step=" + step.toShortString() + " stepBelow="
                    + stepBelow.toShortString() + " -> " + (place ? "СТАВЛЮ БЛОК" : "НЕ ставлю (isSolid()=true під кроком)")
                    + " | " + MobDebug.supportReport(level, step, this.mob.getBbWidth())
                    + " | моб=" + this.mob.position() + " onGround=" + this.mob.onGround());
        }
        if (place) {
            EnemyBreak_N_BuildUtils.debugMsg(this.mob, "[DEBUG BuildPathGoal] bridge: ставлю блок " + stepBelow
                    + " (крок " + step + ")"); // DEBUG
            EnemyBreak_N_BuildUtils.placeBlock(level, stepBelow, this.mob);
        }
    }

    /**
     * ДЕБАГ: стан цілі для знімка (MobDebug).
     */
    public String debugState() {
        return "buildTarget=" + this.buildTarget + " retargetTimer=" + this.retargetTimer
                + " stuckCheckTimer=" + this.stuckCheckTimer + " actionCooldown=" + this.actionCooldown;
    }
}