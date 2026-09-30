package com.example.examplemod;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLE_CUSTOM_AI;

    public static final ModConfigSpec.BooleanValue ENABLE_CHANGE_MOBS_RANGE;

    public static final ModConfigSpec.BooleanValue ENABLE_MOB_TERRAFORMING;
    public static final ModConfigSpec.ConfigValue<String> OVERWORLD_DIG_BLOCK;
    public static final ModConfigSpec.ConfigValue<String> NETHER_DIG_BLOCK;
    public static final ModConfigSpec.ConfigValue<String> END_DIG_BLOCK;
    public static final ModConfigSpec.IntValue PLACED_BLOCK_LIFETIME_SECONDS;
    public static final ModConfigSpec.BooleanValue PLACED_BLOCK_DROPS_WHEN_BROKEN;

    // Фаза 1 "зони переслідування вгору" (площа гравця + радіус атаки) — див. TowerZoneData.
    public static final ModConfigSpec.IntValue TOWER_ZONE_SCAN_RADIUS;
    public static final ModConfigSpec.IntValue TOWER_ZONE_GAP_MERGE_BLOCKS;
    public static final ModConfigSpec.IntValue TOWER_ZONE_REACH_CAP;
    public static final ModConfigSpec.IntValue TOWER_ZONE_FLAT_PATCH_RADIUS;

    // Фаза 4, клатч (див. MobClutchRecovery) - шанс УСПІШНО виконати кожну конкретну спробу.
    public static final ModConfigSpec.DoubleValue CLUTCH_FIRST_ATTEMPT_CHANCE;
    public static final ModConfigSpec.DoubleValue CLUTCH_SECOND_ATTEMPT_CHANCE;
    public static final ModConfigSpec.DoubleValue CLUTCH_FINAL_ATTEMPT_CHANCE;

    // Фаза 4, нейтралізація гравецьких хазардів (див. PlacedHazardRegistry).
    public static final ModConfigSpec.IntValue PLAYER_HAZARD_MEMORY_SECONDS;

    // "у ванілі у гравця є задержка перед установкою блока" - той самий принцип для мобів,
    // рахується на рівні самого API постановки блоку (EnemyBreak_N_BuildUtils.placeBlock).
    public static final ModConfigSpec.IntValue MOB_PLACE_DELAY_TICKS;

    // TerrainShape (неповні блоки: горщики/плити/килими/люки/паркани) - див.
    // Enemy.EnemyMovement.TerrainShape.ShapeProbe та GapJumpNodeEvaluator.*ShapeAware/*Legacy.
    public static final ModConfigSpec.BooleanValue ENABLE_SHAPE_AWARE_PATHING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("Global_AI_Settings");

        ENABLE_CUSTOM_AI = builder
                .comment("Увімкнути/Вимкнути кастомний штучний інтелект (війну фракцій) (true/false)")
                .define("enableCustomAI", true);
        builder.pop();

        builder.push("Change_Mobs_Vision_Range");

        ENABLE_CHANGE_MOBS_RANGE = builder
                .comment("Увімкнути/Вимкнути кастомний радіус бачення мобів (true/false)")
                .define("enableSpecialFeature", true);
        builder.pop();

        builder.push("Mob_Terraforming");

        ENABLE_MOB_TERRAFORMING = builder
                .comment("Увімкнути/Вимкнути копання та будівництво мобами (true/false). ",
                        "Незалежно від цього значення, поважається ванільний геймрул mobGriefing — ",
                        "якщо він вимкнений на сервері, ця система теж не діятиме.")
                .define("enableMobTerraforming", true);

        OVERWORLD_DIG_BLOCK = builder
                .comment("ID блоку, який моби ставлять/копають у Звичайному світі.")
                .define("overworldDigBlock", "minecraft:cobblestone");
        NETHER_DIG_BLOCK = builder
                .comment("ID блоку, який моби ставлять/копають у Незері.")
                .define("netherDigBlock", "minecraft:nether_bricks");
        END_DIG_BLOCK = builder
                .comment("ID блоку, який моби ставлять/копають в Енді.")
                .define("endDigBlock", "minecraft:end_stone");

        PLACED_BLOCK_LIFETIME_SECONDS = builder
                .comment("Через скільки секунд блок, поставлений мобом (міст/вежа), зникає сам.")
                .defineInRange("placedBlockLifetimeSeconds", 30, 1, 24000);

        PLACED_BLOCK_DROPS_WHEN_BROKEN = builder
                .comment("Чи випадає предмет, якщо гравець зламає блок, поставлений мобом ",
                        "(поки той ще не зник сам) (true/false).")
                .define("placedBlockDropsWhenBroken", false);

        builder.pop();

        builder.push("Tower_Assault_Zone");

        TOWER_ZONE_SCAN_RADIUS = builder
                .comment("Радіус (у блоках) від гравця, в межах якого моб сканує його площу ",
                        "(платформу), щоб порахувати зону, яку треба обходити під час підйому.")
                .defineInRange("towerZoneScanRadius", 12, 4, 24);

        TOWER_ZONE_GAP_MERGE_BLOCKS = builder
                .comment("Максимальний розрив (у блоках) між сусідніми ділянками площі, який ",
                        "сканування ще вважає з'єднаним (гравець міг перестрибнути).")
                .defineInRange("towerZoneGapMergeBlocks", 4, 0, 8);

        TOWER_ZONE_REACH_CAP = builder
                .comment("Стеля (у блоках) на РОЗМІР буферної зони навколо площі гравця. НЕ ",
                        "обмежує саму перевірку 'чи гравець зараз дістане мене' (та завжди ",
                        "рахується з реального, некапнутого значення атрибута reach) — лише те, ",
                        "наскільки великий периметр моб ЗАЗДАЛЕГІДЬ планує обходити. Захист від ",
                        "того, що модовий предмет з reach=64 змусить моба будувати структуру на ",
                        "десятки блоків в обхід.")
                .defineInRange("towerZoneReachCap", 8, 3, 20);

        TOWER_ZONE_FLAT_PATCH_RADIUS = builder
                .comment("Радіус (у блоках) суцільної рівної площадки, яку моб шукає для короткого ",
                        "'пробити стелю просто під гравцем і застрибнути' заходу (TowerClimbGoal, ",
                        "стадії TO_7X7/CROSS_AND_DIG) — сам механізм і код і далі звуться '7x7' ",
                        "(це радіус 3, оригінальне значення), але ЖИВИЙ ТЕСТ показав: вимога РІВНО ",
                        "7x7 без жодного розриву на реальних площадках гравця майже ніколи не ",
                        "виконується (нерівність, огорожа, площадка менша за 7x7) — тому цей ",
                        "короткий шлях лишався готовим, але без жодного шансу увімкнутись, і моб ",
                        "завжди йшов довшим шляхом (підйом аж до даху зони, тоді міст зверху). ",
                        "Радіус 1 = 3x3 — той самий мінімум, що й так реально потрібен самому ",
                        "CROSS_AND_DIG (хрест-підпору він добудовує сам; менший радіус лише про ",
                        "впевненість, що звичайна навігація туди дійде).")
                .defineInRange("towerZoneFlatPatchRadius", 1, 1, 3);

        builder.pop();

        builder.push("Clutch");

        CLUTCH_FIRST_ATTEMPT_CHANCE = builder
                .comment("Шанс, що моб, щойно збитий з опори (наприклад стрілою), одразу ",
                        "успішно підставить блок під себе (миттєва 'рефлекторна' спроба).")
                .defineInRange("clutchFirstAttemptChance", 0.70, 0.0, 1.0);

        CLUTCH_SECOND_ATTEMPT_CHANCE = builder
                .comment("Те саме, друга спроба (якщо перша не спрацювала/не мала до чого ",
                        "приліпитись) - моб уже летить далі, тому надійність нижча.")
                .defineInRange("clutchSecondAttemptChance", 0.20, 0.0, 1.0);

        CLUTCH_FINAL_ATTEMPT_CHANCE = builder
                .comment("Обидві миттєві спроби провалились - моб націлюється на розраховану ",
                        "точку падіння заздалегідь (більше часу на підготовку - вищий шанс).")
                .defineInRange("clutchFinalAttemptChance", 0.90, 0.0, 1.0);

        builder.pop();

        builder.push("Placed_Hazards");

        PLAYER_HAZARD_MEMORY_SECONDS = builder
                .comment("Скільки секунд моб 'пам'ятає', що конкретний блок лави/води/вогню ",
                        "поставив саме гравець (а не природний) - і тому може його нейтралізувати.")
                .defineInRange("playerHazardMemorySeconds", 300, 10, 3600);

        builder.pop();

        builder.push("Mob_Place_Delay");

        MOB_PLACE_DELAY_TICKS = builder
                .comment("Мінімальна кількість тіків між постановками блоків ОДНИМ мобом - те ",
                        "саме, що затримка гравця перед установкою блока у ванілі (~4 тіки).")
                .defineInRange("mobPlaceDelayTicks", 4, 0, 40);

        builder.pop();

        builder.push("Terrain_Shape_Awareness");

        ENABLE_SHAPE_AWARE_PATHING = builder
                .comment("Увімкнути/Вимкнути врахування РЕАЛЬНОЇ форми неповних блоків (горщики, ",
                        "плити, килими, люки, паркани - ванільні й з інших модів) у побудові шляху ",
                        "замість грубого blocksMotion() (true/false). Вимкнення повертає стару ",
                        "поведінку (GapJumpNodeEvaluator.*Legacy) - для відкату чи порівняння.")
                .define("enableShapeAwarePathing", true);

        builder.pop();

        SPEC = builder.build();
    }
}