package com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build;

import com.example.examplemod.Config;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpAssistGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * ФАЗА 4, клатч: "коли моб падає з стовба... шанс того що він поставить перший блок 70%... 2й
 * блок 20%... після чого рандомиться число блоків на скільки він упаде... поставить блок з 90%
 * шансом як тільки зможе туда дотянутись". Уточнення користувача: відсотки — це шанс УСПІШНО
 * ВИКОНАТИ конкретну спробу розміщення (не шанс "врятуватись" як такий); чи це реально зупинить
 * падіння — вирішує сама геометрія (блок можна ставити лише впритул до вже існуючого — той
 * самий принцип, що й скрізь у цьому моді).
 * <p>
 * Два независимі шматки: {@link #onKnockback} лише РЕЄСТРУЄ, що моба щойно штовхнуло (жодних дій
 * тут — сам нокбек ще навіть не застосований до швидкості на момент події); {@link #onLevelTick}
 * щотіку веде вже зареєстрованих мобів крізь три стадії, поки не приземляться або не вичерпають
 * усі спроби.
 */
@EventBusSubscriber(modid = "betterenemysai")
public final class MobClutchRecovery {

    private static final Map<Mob, ClutchState> PENDING = new WeakHashMap<>();

    private MobClutchRecovery() {
    }

    @SubscribeEvent
    public static void onKnockback(LivingKnockBackEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!(mob.level() instanceof ServerLevel serverLevel)) return;
        if (!Config.ENABLE_MOB_TERRAFORMING.get()) return; // той самий вимикач, що й уся система
        // v18: і той самий mobGriefing, що й решта системи (див. Config: "якщо він вимкнений на сервері, ця система теж не діятиме") -
        // клатч раніше його ігнорував і ставив блоки навіть при mobGriefing=false.
        if (!serverLevel.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return;

        // Новий нокбек ПОВЕРХ уже активного клатчу (наприклад друга стріла) - починаємо рахунок
        // спроб заново: логічно, це вже "новий" зліт, а не продовження попереднього падіння.
        PENDING.put(mob, new ClutchState());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (PENDING.isEmpty()) return;

        Iterator<Map.Entry<Mob, ClutchState>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Mob, ClutchState> entry = it.next();
            Mob mob = entry.getKey();

            if (!mob.isAlive() || mob.onGround()) {
                it.remove(); // приземлився (сам, чи вдалим клатчем) або зник - більше не актуально
                continue;
            }
            if (mob.level() != level) continue; // тікаємо по одному виміру за раз, як і решта подій

            if (tickOne(mob, level, entry.getValue())) {
                it.remove(); // ця спроба була ОСТАННЬОЮ (успішною чи ні) - клатч завершено
            }
        }
    }

    /**
     * true, якщо це була фінальна дія для цього моба (більше приходити не треба).
     */
    private static boolean tickOne(Mob mob, ServerLevel level, ClutchState state) {
        // v18: поки паркур-політ іде за планом (моб вище нижньої межі польоту), це звичайний стрибок, а не падіння: клатч на
        // паузі (спроби не витрачаються). Якщо політ провалився - моб упав нижче межі, і клатч працює як і раніше.
        if (GapJumpAssistGoal.isFlightOnTrack(mob)) {
            return false;
        }
        if (state.attemptsUsed == 0) {
            attemptQuickSave(mob, level, state, Config.CLUTCH_FIRST_ATTEMPT_CHANCE.get());
            return false;
        }
        if (state.attemptsUsed == 1) {
            attemptQuickSave(mob, level, state, Config.CLUTCH_SECOND_ATTEMPT_CHANCE.get());
            return false;
        }
        if (!state.fallTargetChosen) {
            chooseFallTarget(mob, level, state);
            return false;
        }
        if (mob.blockPosition().getY() <= state.fallTargetY) {
            attemptFinalSave(mob, level, Config.CLUTCH_FINAL_ATTEMPT_CHANCE.get());
            return true; // остання спроба зроблена (успішно чи ні) - далі не втручаємось
        }
        return false; // ще не долетів до обраної точки - чекаємо наступного тіку
    }

    /**
     * "миттєва рефлекторна" спроба (перша/друга) - завжди зафіксовує спробу як використану,
     * незалежно від результату, щоб наступного тіку перейти до наступної стадії.
     */
    private static void attemptQuickSave(Mob mob, ServerLevel level, ClutchState state, double chance) {
        state.attemptsUsed++;
        BlockPos spot = mob.blockPosition().below();
        // Геометрично нема до чого приліпити - не рахуємо навіть шанс (placeBlock і сам це
        // перевірить, але тут важливо саме НЕ витратити шанс на завідомо неможливу спробу).
        if (!EnemyBreak_N_BuildUtils.hasAdjacentSolid(level, spot)) return;
        if (mob.getRandom().nextDouble() < chance) {
            EnemyBreak_N_BuildUtils.placeBlock(level, spot, mob, PlaceReason.CLUTCH);
        }
    }

    /**
     * Обидві "миттєві" спроби провалились - рандомимо, на скільки блоків моб ще пролетить,
     * перш ніж спробує фінальний, надійніший клатч.
     */
    private static void chooseFallTarget(Mob mob, ServerLevel level, ClutchState state) {
        int currentY = mob.blockPosition().getY();
        int groundStandY = findStandYBelow(level, mob.blockPosition());
        int availableDrop = currentY - groundStandY;

        state.fallTargetChosen = true;
        if (availableDrop <= 0) {
            state.fallTargetY = currentY; // вже практично на землі - фінальна спроба наступного тіку
            return;
        }
        int randomDrop = 1 + mob.getRandom().nextInt(availableDrop); // від 1 до всього запасу висоти
        state.fallTargetY = currentY - randomDrop;
    }

    private static void attemptFinalSave(Mob mob, ServerLevel level, double chance) {
        BlockPos spot = mob.blockPosition().below();
        if (!EnemyBreak_N_BuildUtils.hasAdjacentSolid(level, spot)) return; // нема до чого приліпити - падає до кінця
        if (mob.getRandom().nextDouble() < chance) {
            EnemyBreak_N_BuildUtils.placeBlock(level, spot, mob, PlaceReason.CLUTCH);
        }
    }

    /**
     * Шукає вниз від `from` перший суцільний блок і повертає висоту СТОЯННЯ на ньому (тобто
     * +1). Обмежено знизу мінімальною висотою світу - без цього був би необмежений цикл на
     * позиції без жодної підлоги під собою аж до бедрока (та й нижче, теоретично).
     */
    private static int findStandYBelow(ServerLevel level, BlockPos from) {
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos cursor = from.mutable();
        for (int y = from.getY() - 1; y >= minY; y--) {
            cursor.setY(y);
            if (level.getBlockState(cursor).isSolid()) {
                return y + 1;
            }
        }
        return minY;
    }

    private static final class ClutchState {
        int attemptsUsed;          // 0 - ще нічого не пробували; 1 - перша спроба вже була; 2 - і друга теж
        boolean fallTargetChosen;  // чи вже розрандомили, на якій висоті буде фінальна спроба
        int fallTargetY;           // валідне лише коли fallTargetChosen == true
    }
}
