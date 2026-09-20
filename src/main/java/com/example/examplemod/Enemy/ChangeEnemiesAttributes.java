package com.example.examplemod.Enemy;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;

import java.util.HashMap;
import java.util.Map;


@Mod(ChangeEnemiesAttributes.MODID)

public class ChangeEnemiesAttributes {

    public static final String MODID = "betterenemiesai";

    public ChangeEnemiesAttributes(IEventBus modEventBus) {
        modEventBus.addListener(this::modifyAttributes);
    }

    private static final double DEFAULT_RUN_SPEED_MULTIPLIER = 1.0;

    // ==========================================
    // 3. МНОЖНИК ШВИДКОСТІ БІГУ (Run_N_Jump)
    // ==========================================
    // На скільки швидше за звичайну ходьбу (MOVEMENT_SPEED вище) рухається моб, поки заагрений.
    // Це НЕ vanilla-атрибут (такого Attribute не існує), тому не через EntityAttributeModificationEvent,
    // а звичайна таблиця по типу моба. Єдина точка читання — Run_N_JumpUtils.getRunSpeedModifier(mob),
    // який викликає getRunSpeedMultiplier(mob.getType()) нижче.
    //
    // Список типів тут — рівно ті моби, які взагалі проходять через PursuitEnemyBehavior (див. Mixin-и
    // в mobAi/Mixin). Для будь-якого іншого типу повертається DEFAULT_RUN_SPEED_MULTIPLIER.
    private static final Map<EntityType<?>, Double> RUN_SPEED_MULTIPLIERS = new HashMap<>();

    static {
        // ==========================================
        // МОНСТРИ
        // ==========================================
        RUN_SPEED_MULTIPLIERS.put(EntityType.ZOMBIE, 1.4);
        RUN_SPEED_MULTIPLIERS.put(EntityType.HUSK, 1.4);
        RUN_SPEED_MULTIPLIERS.put(EntityType.ZOMBIE_VILLAGER, 1.4);
        RUN_SPEED_MULTIPLIERS.put(EntityType.DROWNED, 1.0);

        RUN_SPEED_MULTIPLIERS.put(EntityType.SKELETON, 1.0);
        RUN_SPEED_MULTIPLIERS.put(EntityType.STRAY, 1.0);
        RUN_SPEED_MULTIPLIERS.put(EntityType.WITHER_SKELETON, 1.4);

        RUN_SPEED_MULTIPLIERS.put(EntityType.CREEPER, 1.4);

        // ==========================================
        // РОЗБІЙНИКИ
        // ==========================================
        RUN_SPEED_MULTIPLIERS.put(EntityType.PILLAGER, 1.0);
        RUN_SPEED_MULTIPLIERS.put(EntityType.VINDICATOR, 1.0);

        // ==========================================
        // ПІГЛІНИ
        // ==========================================
        RUN_SPEED_MULTIPLIERS.put(EntityType.PIGLIN, 1.4);

        // ==========================================
        // ІНШІ
        // ==========================================
        RUN_SPEED_MULTIPLIERS.put(EntityType.IRON_GOLEM, 1.4);
    }

    /**
     * Множник швидкості бігу для типу моба. Якщо типу нема в таблиці вище — повертає
     * {@value #DEFAULT_RUN_SPEED_MULTIPLIER} (=звичайна ходьба), щоб новий/незареєстрований тип
     * ніколи не отримав прискорення випадково.
     */
    public static double getRunSpeedMultiplier(EntityType<?> type) {
        return RUN_SPEED_MULTIPLIERS.getOrDefault(type, DEFAULT_RUN_SPEED_MULTIPLIER);
    }

    public void modifyAttributes(EntityAttributeModificationEvent event) {

        // ==========================================
        // 1. РАДІУС ПОШУКУ
        // ==========================================

        // ==========================================
        // МОНСТРИ
        // ==========================================
        event.add(EntityType.ZOMBIE, Attributes.FOLLOW_RANGE, 30.0);
        event.add(EntityType.HUSK, Attributes.FOLLOW_RANGE, 30.0);
        event.add(EntityType.DROWNED, Attributes.FOLLOW_RANGE, 30.0);
        event.add(EntityType.ZOMBIE_VILLAGER, Attributes.FOLLOW_RANGE, 30.0);

        event.add(EntityType.SKELETON, Attributes.FOLLOW_RANGE, 40.0);
        event.add(EntityType.STRAY, Attributes.FOLLOW_RANGE, 35.0);
        event.add(EntityType.WITHER_SKELETON, Attributes.FOLLOW_RANGE, 30.0);

        event.add(EntityType.SPIDER, Attributes.FOLLOW_RANGE, 24.0);
        event.add(EntityType.CAVE_SPIDER, Attributes.FOLLOW_RANGE, 16.0);

        event.add(EntityType.CREEPER, Attributes.FOLLOW_RANGE, 20.0);
        event.add(EntityType.ZOGLIN, Attributes.FOLLOW_RANGE, 20.0);
        event.add(EntityType.ZOMBIFIED_PIGLIN, Attributes.FOLLOW_RANGE, 20.0);

        // ==========================================
        // РОЗБІЙНИКИ
        // ==========================================
        event.add(EntityType.PILLAGER, Attributes.FOLLOW_RANGE, 45.0);
        event.add(EntityType.VINDICATOR, Attributes.FOLLOW_RANGE, 45.0);
        event.add(EntityType.EVOKER, Attributes.FOLLOW_RANGE, 25.0);
        event.add(EntityType.VEX, Attributes.FOLLOW_RANGE, 20.0);
        event.add(EntityType.RAVAGER, Attributes.FOLLOW_RANGE, 30.0);

        // ==========================================
        // ПІГЛІНИ
        // ==========================================
        event.add(EntityType.PIGLIN, Attributes.FOLLOW_RANGE, 50.0);
        event.add(EntityType.PIGLIN_BRUTE, Attributes.FOLLOW_RANGE, 20.0);

        // ==========================================
        // ІНШІ
        // ==========================================
        event.add(EntityType.IRON_GOLEM, Attributes.FOLLOW_RANGE, 30.0);
        event.add(EntityType.WITCH, Attributes.FOLLOW_RANGE, 25.0);

        // ==========================================
        // НЕЗЕР
        // ==========================================
        event.add(EntityType.GHAST, Attributes.FOLLOW_RANGE, 70.0);
        event.add(EntityType.BLAZE, Attributes.FOLLOW_RANGE, 70.0);


        // ==========================================
        // 2. ШВИДКІСТЬ ХОДЬБИ
        // ==========================================

        // ==========================================
        // МОНСТРИ
        // ==========================================
        event.add(EntityType.ZOMBIE, Attributes.MOVEMENT_SPEED, 0.23);
        event.add(EntityType.HUSK, Attributes.MOVEMENT_SPEED, 0.23);
        event.add(EntityType.DROWNED, Attributes.MOVEMENT_SPEED, 0.23);
        event.add(EntityType.ZOMBIE_VILLAGER, Attributes.MOVEMENT_SPEED, 0.23);

        event.add(EntityType.SKELETON, Attributes.MOVEMENT_SPEED, 0.25);
        event.add(EntityType.STRAY, Attributes.MOVEMENT_SPEED, 0.25);
        event.add(EntityType.WITHER_SKELETON, Attributes.MOVEMENT_SPEED, 0.25);

        event.add(EntityType.SPIDER, Attributes.MOVEMENT_SPEED, 0.30);
        event.add(EntityType.CAVE_SPIDER, Attributes.MOVEMENT_SPEED, 0.30);

        event.add(EntityType.CREEPER, Attributes.MOVEMENT_SPEED, 0.25);
        event.add(EntityType.ZOGLIN, Attributes.MOVEMENT_SPEED, 0.40);
        event.add(EntityType.ZOMBIFIED_PIGLIN, Attributes.MOVEMENT_SPEED, 0.23);

        // ==========================================
        // РОЗБІЙНИКИ
        // ==========================================
        event.add(EntityType.PILLAGER, Attributes.MOVEMENT_SPEED, 0.35);
        event.add(EntityType.VINDICATOR, Attributes.MOVEMENT_SPEED, 0.35);
        event.add(EntityType.EVOKER, Attributes.MOVEMENT_SPEED, 0.50);
        event.add(EntityType.VEX, Attributes.MOVEMENT_SPEED, 0.60);
        event.add(EntityType.RAVAGER, Attributes.MOVEMENT_SPEED, 0.30);

        // ==========================================
        // ПІГЛІНИ
        // ==========================================
        event.add(EntityType.PIGLIN, Attributes.MOVEMENT_SPEED, 0.35);
        event.add(EntityType.PIGLIN_BRUTE, Attributes.MOVEMENT_SPEED, 0.35);

        // ==========================================
        // ІНШІ
        // ==========================================
        event.add(EntityType.IRON_GOLEM, Attributes.MOVEMENT_SPEED, 0.25);
        event.add(EntityType.WITCH, Attributes.MOVEMENT_SPEED, 0.25);

        // ==========================================
        // НЕЗЕР
        // ==========================================
        event.add(EntityType.GHAST, Attributes.MOVEMENT_SPEED, 0.40);
        event.add(EntityType.BLAZE, Attributes.MOVEMENT_SPEED, 0.23);

    }
}