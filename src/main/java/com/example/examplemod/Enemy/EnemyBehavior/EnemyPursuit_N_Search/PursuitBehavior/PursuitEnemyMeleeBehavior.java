package com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.EnemyBreak_N_BuildUtils;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpAssistGoal;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpUtils;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.Run_N_JumpUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.function.Predicate;

/**
 * Базовий Goal для мелі-мобів без стрільби (зомбі, wither skeleton, iron golem,
 * крипер тощо), а також для дуал-мод мобів (Vindicator/Pillager в axe-режимі —
 * через predicate-конструктор). Розширюється окремим класом для кожного моба
 * (BetterZombieGoalAi тощо) або використовується напряму з предикатом. Інтегрує
 * PursuitEnemyBehavior: коли гравець вийшов за FOLLOW_RANGE або втратив пряму
 * видимість — моб біжить до застиглої останньої відомої точки або шукає гравця
 * навколо неї.
 * <p>
 * Це ПОВНА заміна ванільного MeleeAttackGoal/ZombieAttackGoal (їх видалено в
 * мixin-ах кожного моба) — відповідає і за рух (в тому числі крізь стіни), і за
 * саму атаку через {@link Mob#doHurtTarget}. Виклик саме doHurtTarget (а не щось
 * нижчого рівня) навмисний: усі мобо-специфічні ефекти при ударі (Wither-ефект у
 * WitherSkeleton, нокбек/підкидання у IronGolem тощо) реалізовані як override
 * doHurtTarget на самому класі моба — завдяки поліморфізму вони спрацюють
 * автоматично, без жодного мобо-специфічного коду тут.
 */
public class PursuitEnemyMeleeBehavior extends Goal {

    protected final Mob mob;
    protected final double speedModifier;
    private final Predicate<Mob> extraCanUseCondition;
    private final boolean canYieldToTerraforming;
    private int ticksUntilNextAttack;

    public PursuitEnemyMeleeBehavior(Mob mob, double speedModifier) {
        this(mob, speedModifier, m -> true);
    }

    /**
     * @param extraCanUseCondition додаткова умова понад наявність chasePos — наприклад
     *                             "тримає сокиру в руці" для дуал-мод мобів, які інакше
     *                             б'ються дистанційно. Для звичайних мелі-мобів передавай
     *                             {@code m -> true} (або юзай 2-арг конструктор).
     */
    public PursuitEnemyMeleeBehavior(Mob mob, double speedModifier, Predicate<Mob> extraCanUseCondition) {
        this(mob, speedModifier, extraCanUseCondition, false);
    }

    /**
     * @param canYieldToTerraforming true — лише для мобів, яким РЕАЛЬНО додано
     *                               {@code BuildPathGoal}/{@code DigThroughWallsGoal} (зараз
     *                               тільки Zombie через {@code BetterZombieGoalAi}). Якщо true,
     *                               цей Goal сам добровільно зупиняється
     *                               ({@code canContinueToUse()=false}), коли шлях реально
     *                               заблокований довше за grace-період — щоб build/dig
     *                               отримали чергу через ЧИСТИЙ stop()/start(), а не через
     *                               примусове перехоплення прапорців посеред такту (саме це
     *                               ламало трекінг гравця — підтверджено A/B тестом). Для
     *                               решти мобів лишай false (або юзай 2/3-арг конструктор) —
     *                               інакше цей Goal зупинявся б, коли isPathBlocked()=true, а
     *                               зупинити НІКОМУ (для них build/dig не зареєстровані), і
     *                               моб просто застигав би замість спроби йти звичайним шляхом.
     */
    public PursuitEnemyMeleeBehavior(Mob mob, double speedModifier, Predicate<Mob> extraCanUseCondition,
                                     boolean canYieldToTerraforming) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.extraCanUseCondition = extraCanUseCondition;
        this.canYieldToTerraforming = canYieldToTerraforming;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        boolean yieldToTerraforming = this.shouldYieldToTerraforming();
        boolean yieldToGapJump = this.shouldYieldToGapJump();
        boolean result = this.extraCanUseCondition.test(this.mob)
                && !this.shouldYield()
                && !yieldToTerraforming
                && !yieldToGapJump
                && PursuitEnemyBehavior.getChasePosition(this.mob) != null;
        // ТИМЧАСОВИЙ DEBUG: без throttle - рідкісна подія (раз на весь епізод переслідування),
        // варто бачити кожного разу. Якщо ЦЕ раптом = false довго поки моб на вигляд атакує -
        // цей Goal взагалі не стартує, і вся справа деінде.
        if (!result) {
            Player p = PursuitEnemyBehavior.getTrackedPlayer(this.mob);
            if (p != null) {
                PursuitEnemyBehavior.debugMsg(p, "[DEBUG PursuitEnemyMeleeBehavior] canUse()=false. "
                        + "chasePos=" + PursuitEnemyBehavior.getChasePosition(this.mob)
                        + " shouldYield=" + this.shouldYield()
                        + " yieldToTerraforming=" + yieldToTerraforming
                        + " yieldToGapJump=" + yieldToGapJump + " моб=" + this.mob.blockPosition());
            }
        }
        return result;
    }

    @Override
    public boolean canContinueToUse() {
        boolean yieldToTerraforming = this.shouldYieldToTerraforming();
        boolean yieldToGapJump = this.shouldYieldToGapJump();
        boolean result = this.extraCanUseCondition.test(this.mob)
                && !this.shouldYield()
                && !yieldToTerraforming
                && !yieldToGapJump
                && (PursuitEnemyBehavior.getChasePosition(this.mob) != null
                || !this.mob.getNavigation().isDone());
        // ТИМЧАСОВИЙ DEBUG: без throttle - показує ТОЧНИЙ момент STOP і чому саме.
        if (!result) {
            Player p = PursuitEnemyBehavior.getTrackedPlayer(this.mob);
            if (p != null) {
                PursuitEnemyBehavior.debugMsg(p, "[DEBUG PursuitEnemyMeleeBehavior] canContinueToUse()=false -> STOP. "
                        + "chasePos=" + PursuitEnemyBehavior.getChasePosition(this.mob)
                        + " navigation.isDone()=" + this.mob.getNavigation().isDone()
                        + " shouldYield=" + this.shouldYield()
                        + " yieldToTerraforming=" + yieldToTerraforming
                        + " yieldToGapJump=" + yieldToGapJump + " моб=" + this.mob.blockPosition());
            }
        }
        return result;
    }

    /**
     * true, якщо моб уже досить близько до цілі, щоб самому звільнити Flag.MOVE/LOOK —
     * потрібно для мобів типу Creeper, де на цій дистанції має перехопити керування ІНШИЙ
     * ванільний Goal з тим самим флагом (SwellGoal). Без цього наш Goal, раз стартувавши
     * здалеку, ніколи сам не зупиняється (canContinueToUse лишається true весь бій) і
     * назавжди блокує той інший Goal від старту, навіть коли моб уже впритул.
     */
    private boolean shouldYield() {
        double yieldDistSq = this.getYieldDistanceSqr();
        if (yieldDistSq < 0) {
            return false;
        }
        LivingEntity target = this.mob.getTarget();
        if (target == null) {
            return false;
        }
        // Йти в yield має сенс ТІЛЬКИ якщо реально бачимо ціль — інакше під час SEARCHING
        // (коли жива позиція гравця може випадково опинитись поруч, хоч і схована) ми
        // віддамо Flag.MOVE іншому Goal-у (напр. SwellGoal), який теж нічого корисного без
        // LOS не зробить — і обидва просто зависнуть на місці, нікого не рухаючи.
        if (!this.mob.getSensing().hasLineOfSight(target)) {
            return false;
        }
        return this.mob.distanceToSqr(target) <= yieldDistSq;
    }

    /**
     * Чи слід ЦЬОМУ Goal-у самому зупинитись, щоб дати чергу build/dig
     * ({@code BuildPathGoal}/{@code DigThroughWallsGoal}) — лише коли
     * {@link #canYieldToTerraforming} увімкнено (тобто ці Goal-и РЕАЛЬНО зареєстровані на
     * цьому мобі) і шлях дійсно заблокований довше за grace-період
     * ({@link EnemyBreak_N_BuildUtils#isPathBlocked} вже враховує grace-період сам). Навмисно
     * НЕ форсимо це через пріоритет Goal-ів (build/dig раніше примусово перебивали цей Goal по
     * прапорцях — і саме це ламало трекінг гравця, підтверджено A/B тестом; тепер pursuit сам
     * чемно звільняє прапорці, а не його виривають посеред такту).
     */
    private boolean shouldYieldToTerraforming() {
        if (!this.canYieldToTerraforming) {
            return false;
        }
        Vec3 chasePos = PursuitEnemyBehavior.getChasePosition(this.mob);
        if (chasePos == null) {
            return false;
        }
        return EnemyBreak_N_BuildUtils.isPathBlocked(this.mob, chasePos);
    }

    /**
     * Той самий принцип, що й {@link #shouldYieldToTerraforming()} (чемний voluntary yield, не
     * примусове перехоплення прапорців), але для {@code GapJumpAssistGoal}. НАВМИСНО не через
     * {@code isPathBlocked}: щойно розрив стає прохідним через GapJumpNodeEvaluator, createPath()
     * для нього більше НЕ unreachable — isPathBlocked сам собою перестає бачити тут проблему.
     * Тому тригер тут окремий: чи в РЕАЛЬНОМУ поточному Path попереду є стрибковий сегмент.
     * <p>
     * Той самий {@link #canYieldToTerraforming} прапорець використано як гейт (а не окремий
     * конструкторний параметр) — на практиці GapJumpAssistGoal зареєстрований на тих самих
     * мобах, що й build/dig, тож розділяти прапорці поки нема сенсу.
     */
    private boolean shouldYieldToGapJump() {
        if (!this.canYieldToTerraforming) {
            return false;
        }

        // Якщо GapJumpAssistGoal уже забрав керування,
        // Pursuit не має права запускатися назад навіть після
        // navigation.stop(), коли Path вже null.
        if (GapJumpAssistGoal.isActive(this.mob)) {
            return true;
        }

        // Поки GapJumpAssist ще не стартував — шукаємо GapJump у Path.
        return GapJumpUtils.findUpcomingJumpSegment(this.mob) != null;
    }

    @Override
    public void start() {
        super.start();
        this.mob.setAggressive(true);
        this.ticksUntilNextAttack = 0;
        // ТИМЧАСОВИЙ DEBUG
        Player p = PursuitEnemyBehavior.getTrackedPlayer(this.mob);
        if (p != null) {
            PursuitEnemyBehavior.debugMsg(p, "[DEBUG PursuitEnemyMeleeBehavior] START моб=" + this.mob.blockPosition());
        }
    }

    @Override
    public void stop() {
        boolean yieldToGapJump = this.shouldYieldToGapJump();

        super.stop();
        this.mob.setAggressive(false);
        this.mob.setSprinting(false);

        Player p = PursuitEnemyBehavior.getTrackedPlayer(this.mob);
        if (p != null) {
            PursuitEnemyBehavior.debugMsg(
                    p,
                    "[DEBUG PursuitEnemyMeleeBehavior] STOP моб="
                            + this.mob.blockPosition()
                            + " yieldToGapJump=" + yieldToGapJump
            );
        }

        if (!yieldToGapJump) {
            this.mob.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        Vec3 chasePos = PursuitEnemyBehavior.getChasePosition(this.mob);

        if (this.ticksUntilNextAttack > 0) {
            this.ticksUntilNextAttack--;
        }

        if (chasePos == null) {
            return;
        }

        double sprintSpeed = Run_N_JumpUtils.getRunSpeedModifier(this.mob);

        // chasePos — ЗАВЖДИ правильна точка руху для всіх трьох активних станів (жива позиція
        // в CHASING, застигла точка в GOING_TO_LAST_SEEN, точка пошуку в SEARCHING). НЕ звіряємось
        // з canSee для вибору точки: hasLineOfSight — це чистий рейкаст, він не враховує8
        // дистанцію, тож у відкритому полі гравець "видимий" навіть далеко за FOLLOW_RANGE — і
        // код лазив у гілку "жива позиція", намагаючись прокласти шлях за межу дальності
        // навігатора, а vanilla pathfinding такий довгий шлях просто не будує — моб стояв на
        // місці. chasePos сам по собі завжди в розумній (досяжній) відстані для поточного стану.
        //
        // ВАЖЛИВО: раніше тут був прямий moveTo(chasePos.x, chasePos.y, chasePos.z, sprintSpeed) -
        // а BuildPathGoal/DigThroughWallsGoal.canUse() (опитуються GoalSelector-ом ЩОТІКУ,
        // навіть коли не виграють пріоритет) окремо кличуть createPath() на ТОМУ Ж навігаторі
        // через EnemyBreak_N_BuildUtils. Три незалежні виклики createPath() на спільному
        // об'єкті в одному тіку - ванільний NodeEvaluator не розрахований на це (підтверджено
        // "[PATH DEBUG]" логом: різні результати для тієї самої цілі того самого тіку). Тепер
        // беремо ГОТОВИЙ Path із того самого спільного кешу, яким користуються і Build/Dig -
        // реальний createPath() рахується не більше разу на тік на моба, хто б не питав першим.
        Path sharedPath = EnemyBreak_N_BuildUtils.getOrComputePath(this.mob, chasePos);
        this.mob.getNavigation().moveTo(sharedPath, sprintSpeed);
        this.mob.getLookControl().setLookAt(
                chasePos.x, chasePos.y + this.mob.getBbHeight() * 0.5, chasePos.z, 30.0F, 30.0F);
        Run_N_JumpUtils.applyDefaultRun(this.mob);

        // А атака — дійсно тільки коли реально бачимо ціль, незалежно від того, з якого
        // стану взявся chasePos.
        boolean canSee = target != null && this.mob.getSensing().hasLineOfSight(target);

        // ТИМЧАСОВИЙ DEBUG: heartbeat раз/сек - живий chasePos (яким щотіку годуємо moveTo) і
        // canSee (від нього залежить, чи взагалі викликається tryAttack нижче). Якщо в чаті
        // видно ЦЕ повідомлення з canSee=true, але немає повідомлень з tryAttack нижче - справа
        // в дистанції/кулдауні, а не у видимості.
        if (this.mob.tickCount % 5 == 0) {
            Player debugPlayer = PursuitEnemyBehavior.getTrackedPlayer(this.mob);
            if (debugPlayer != null) {
                PursuitEnemyBehavior.debugMsg(debugPlayer, String.format(
                        "[DEBUG PursuitEnemyMeleeBehavior] моб=%.1f %.1f %.1f chasePos=%.1f %.1f %.1f canSee=%s ціль=%s",
                        this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                        chasePos.x, chasePos.y, chasePos.z, canSee,
                        target != null ? target.getName().getString() : "null"));
            }
        }

        if (canSee) {
            this.tryAttack(target);
        }
    }

    private void tryAttack(LivingEntity target) {
        if (!this.canAttack()) {
            return;
        }
        double distSq = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        double reachSq = this.getAttackReachSqr(target);
        boolean inRange = distSq <= reachSq;
        boolean offCooldown = this.ticksUntilNextAttack <= 0;
        if (inRange && offCooldown) {
            this.ticksUntilNextAttack = this.getAttackIntervalTicks();
            this.mob.swing(InteractionHand.MAIN_HAND);
            this.mob.doHurtTarget(target);
            // ТИМЧАСОВИЙ DEBUG
            if (target instanceof Player p) {
                PursuitEnemyBehavior.debugMsg(p, "[DEBUG PursuitEnemyMeleeBehavior] УДАР! dist="
                        + String.format("%.2f", Math.sqrt(distSq)));
            }
        } else if (target instanceof Player p && this.mob.tickCount % 20 == 0) {
            // ТИМЧАСОВИЙ DEBUG: canSee=true, але удару немає - ось точна причина (дистанція чи
            // кулдаун). Саме цей лог має пояснити кейс "агриться, але не бʼє" (якщо
            // PursuitEnemyMeleeBehavior взагалі встигає тікати - див. лог активності
            // BuildPathGoal/DigThroughWallsGoal вище: вони мають вищий пріоритет і за спільними
            // прапорцями MOVE/LOOK можуть не пускати цей Goal тікати ВЗАГАЛІ, тоді цього
            // повідомлення не буде в чаті, і причина не тут, а в тому, що цей tick() не викликався).
            PursuitEnemyBehavior.debugMsg(p, String.format(
                    "[DEBUG PursuitEnemyMeleeBehavior] canSee=true, АЛЕ не бʼю: dist=%.2f reach=%.2f inRange=%s cooldown=%d",
                    Math.sqrt(distSq), Math.sqrt(reachSq), inRange, this.ticksUntilNextAttack));
        }
    }

    /**
     * Чи бʼє цей моб взагалі (doHurtTarget) при контакті з ціллю. За замовчуванням —
     * так. Перевизначи на false для мобів, які не мають ванільної мелі-атаки і
     * підходять близько з іншою метою (наприклад Creeper — підривається, а не бʼє).
     */
    protected boolean canAttack() {
        return true;
    }

    /**
     * Дистанція в квадраті, ближче якої цей Goal сам себе зупиняє (canUse/canContinueToUse
     * повертають false), звільняючи Flag.MOVE/LOOK іншому Goal-у. За замовчуванням -1
     * (вимкнено — ніколи не звільняємось самі, бо звичайним мелі-мобам більше нема кому
     * передавати керування, атаку ми й самі робимо). Перевизначай, якщо на близькій
     * дистанції має перехопити інший ванільний Goal з тим самим флагом.
     */
    protected double getYieldDistanceSqr() {
        return -1;
    }

    /**
     * Дистанція атаки в квадраті — впритул з невеликим запасом (як у ванільного
     * MeleeAttackGoal), автоматично враховує розмір моба й цілі. Перевизначай
     * у нащадку, якщо конкретному мобу треба інша дистанція.
     */
    protected double getAttackReachSqr(LivingEntity target) {
        double reach = this.mob.getBbWidth() * 2.0D + target.getBbWidth();
        return reach * reach;
    }

    /**
     * Кулдаун між ударами в тіках (20 = 1 секунда, як ванільний дефолт).
     * Перевизначай у нащадку за потреби.
     */
    protected int getAttackIntervalTicks() {
        return 20;
    }
}