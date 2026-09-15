package com.example.examplemod.mobAi.Mixin;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.BuildPathGoal;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.DigThroughWallsGoal;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.TowerClimbGoal;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpAssistGoal;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpPathNavigation;
import com.example.examplemod.mobAi.Goal.BetterZombieGoalAi;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Zombie.class)
public class ZombieMixin {

    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void addBetterAI(CallbackInfo ci) {
        Zombie mob = (Zombie) (Object) this;

        // ОБОВ'ЯЗКОВО, не опціонально: Drowned extends Zombie у ванілі, тож цей інжект у
        // registerGoals() спрацьовує і для нього — а це ЗАВЖДИ ламає DrownedMixin, незалежно
        // від пріоритету BetterZombieGoalAi. Причина не в конфлікті прапорців (це окрема,
        // другорядна проблема) — а в тому, що registerGoals() виконується РАНІШЕ за
        // populateDefaultEquipmentSlots(), тож коли доходить черга до DrownedMixin, його гард
        // від подвійної реєстрації бачить PursuitEnemyBehavior, щойно доданий ЗВІДСИ, і
        // пропускає ввесь свій блок — разом із видаленням ванільного Trident-Goal і додаванням
        // BetterDrownedGoalAi. Результат: дровнед лишається на чистій ванілі (без prediction,
        // без getChasePosition) незалежно від того, яку цифру пріоритету поставити нижче.
        if (mob instanceof Drowned) {
            return;
        }

        // Підміняємо навігатор на GapJumpNodeEvaluator-ний ДО реєстрації голів нижче. Свідомо
        // тут, а не окремим @Inject у "<init>" - registerGoals() і так вже перевірений
        // інжект-пойнт (сюди ж додаються Goal-и нижче), тож не заводимо ще один непідтверджений
        // спосіб дістатись моба під час конструювання. Жоден Goal у файлі не кешує
        // mob.getNavigation() при створенні (завжди звертається живцем), тож те, що заміна
        // відбувається "пізно" відносно ванільного createNavigation(), значення не має.
        ((MobAccessor) mob).setNavigation(new GapJumpPathNavigation(mob, mob.level()));

        // Ванільний ZombieAttackGoal (public, extends MeleeAttackGoal) — прибираємо,
        // щоб не конфліктував по флагах MOVE/LOOK з PursuitEnemyMeleeBehavior.
        mob.goalSelector.getAvailableGoals().removeIf(goal ->
                goal.getGoal() instanceof MeleeAttackGoal
        );

        mob.goalSelector.addGoal(0, new PursuitEnemyBehavior(mob, true));
        // pursuit=1 (найвищий) - build/dig/jump НІКОЛИ не перебивають його силою (підтверджена
        // ванільна поведінка GoalSelector-а: молодший номер пріоритету форсить старший, ніколи
        // навпаки - тому build/dig раніше форсили саме через РІЗНИЦЮ номерів, а не через якийсь
        // баг; тепер pursuit=1 найнижчий номер із усіх MOVE-претендентів, форсити просто нема
        // кому). PursuitEnemyMeleeBehavior сам добровільно віддає чергу — окремо для
        // terraforming (build/dig, isPathBlocked) і окремо для GapJumpAssistGoal нижче
        // (shouldYieldToGapJump, читає реальний Path) — через чистий stop()/start(), а не через
        // примусове відбирання прапорців посеред такту.
        mob.goalSelector.addGoal(1, new BetterZombieGoalAi(mob, 1.0D));
        // TowerClimbGoal веде підйом до ЖИВОЇ позиції гравця (проекція/зона/стіни); BuildPathGoal
        // сам віддає йому чергу в цьому випадку (canUse()/canContinueToUse() повертають false) -
        // конкретна цифра пріоритету тут другорядна порівняно з тим явним yield-ом, але нижче за
        // pursuit і вище за "наївний" BuildPathGoal для наочності.
        mob.goalSelector.addGoal(2, new TowerClimbGoal(mob));
        // GapJumpAssistGoal — номер пріоритету тут насправді другорядний (він виконується лише
        // коли PursuitEnemyMeleeBehavior сам звільнив MOVE/LOOK/JUMP через shouldYieldToGapJump,
        // а Tower/Build/Dig у ту саму мить просто не бачать своєї умови - GapJumpNodeEvaluator
        // робить розрив прохідним, тож isPathBlocked для нього більше не true). Лишив вище за
        // Build/Dig суто для наочності порядку читання файлу.
        mob.goalSelector.addGoal(3, new GapJumpAssistGoal(mob));
        mob.goalSelector.addGoal(4, new BuildPathGoal(mob));
        mob.goalSelector.addGoal(5, new DigThroughWallsGoal(mob));
    }
}