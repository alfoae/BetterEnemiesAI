package com.example.examplemod.mobAi.Mixin;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.BuildPathGoal;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.DigThroughWallsGoal;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.TowerClimbGoal;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpAssistGoal;
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

        // Ванільний ZombieAttackGoal (public, extends MeleeAttackGoal) — прибираємо,
        // щоб не конфліктував по флагах MOVE/LOOK з PursuitEnemyMeleeBehavior.
        mob.goalSelector.getAvailableGoals().removeIf(goal ->
                goal.getGoal() instanceof MeleeAttackGoal
        );

        mob.goalSelector.addGoal(0, new PursuitEnemyBehavior(mob, true));
        // pursuit=1 (найвищий) - build/dig/jump НІКОЛИ не перебивають його силою. Підтверджено
        // експериментально: саме примусове переривання прапорців MOVE/LOOK посеред виконання
        // PursuitEnemyMeleeBehavior ламало трекінг гравця (не кількість викликів createPath() -
        // кеш сам собою це не виправив). PursuitEnemyMeleeBehavior сам добровільно віддає чергу
        // (canYieldToTerraforming=true) через чистий stop()/start(), коли шлях дійсно
        // заблокований довше за grace-період — і той самий сигнал (isPathBlocked) покриває і
        // розрив-без-Path для GapJumpAssistGoal нижче, без жодних додаткових змін тут.
        mob.goalSelector.addGoal(1, new BetterZombieGoalAi(mob, 1.0D));
        // TowerClimbGoal веде підйом до ЖИВОЇ позиції гравця (проекція/зона/стіни); BuildPathGoal
        // сам віддає йому чергу в цьому випадку (canUse()/canContinueToUse() повертають false) -
        // конкретна цифра пріоритету тут другорядна порівняно з тим явним yield-ом, але нижче за
        // pursuit і вище за "наївний" BuildPathGoal для наочності.
        mob.goalSelector.addGoal(2, new TowerClimbGoal(mob));
        // GapJumpAssistGoal — ВИЩЕ за BuildPathGoal/DigThroughWallsGoal: якщо розрив у межах
        // дальності стрибка моба, стрибок має перехопити MOVE/LOOK/JUMP раніше, ніж BuildPathGoal
        // встигне вирішити бриджити його блоками (стрибок дешевший і швидший). Той самий набір
        // флагів, що й у Tower/Build/Dig, тому звичайне блокування прапорців за пріоритетом у
        // GoalSelector-і саме й розводить їх — жодного ручного yield() між GapJumpAssistGoal і
        // BuildPathGoal писати не треба.
        mob.goalSelector.addGoal(3, new GapJumpAssistGoal(mob));
        mob.goalSelector.addGoal(4, new BuildPathGoal(mob));
        mob.goalSelector.addGoal(5, new DigThroughWallsGoal(mob));
    }
}