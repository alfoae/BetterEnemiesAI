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
    // v21: окремий темп для вежі/стіни (TOWER_STRUCTURE) і для мосту (BRIDGE_*): див. TerraformRules.placeGapTicks.
    public static final ModConfigSpec.IntValue TOWER_PLACE_DELAY_TICKS;
    public static final ModConfigSpec.IntValue BRIDGE_PLACE_DELAY_MIN_TICKS;
    public static final ModConfigSpec.IntValue BRIDGE_PLACE_DELAY_MAX_TICKS;

    // Форм-орієнтована ходьба (TerrainShape): моби ходять по неповних блоках, не падають крізь відкриті люки.
    public static final ModConfigSpec.BooleanValue ENABLE_SHAPE_AWARE_WALKING;
    public static final ModConfigSpec.ConfigValue<String> SHAPE_WALK_BLACKLIST;

    public static final ModConfigSpec.IntValue UNREACHABLE_RECOMPUTE_INTERVAL_TICKS;

    // v20: стрибки з урахуванням шкоди від падіння (GapJumpNodeEvaluator / FallDamage) і режим вежі "спершу стрибок".
    public static final ModConfigSpec.BooleanValue FALL_DAMAGE_AWARE_JUMPS;
    public static final ModConfigSpec.IntValue JUMP_DOWN_MAX_BLOCKS;
    public static final ModConfigSpec.DoubleValue FALL_DAMAGE_RESERVE_HP;
    public static final ModConfigSpec.DoubleValue FALL_DAMAGE_COST_PER_HP;
    public static final ModConfigSpec.BooleanValue TOWER_JUMP_FIRST;

    // Дебаг (див. com.example.examplemod.debug.DebugLog): окремий файл logs/betterenemiesai-debug.log.
    public static final ModConfigSpec.BooleanValue DEBUG_ENABLED;
    public static final ModConfigSpec.BooleanValue DEBUG_WATCHDOG;
    public static final ModConfigSpec.BooleanValue DEBUG_FLOOR_MIXIN;
    public static final ModConfigSpec.BooleanValue DEBUG_VERBOSE_JUMP;
    public static final ModConfigSpec.IntValue DEBUG_MAX_FILE_MB;

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

        TOWER_JUMP_FIRST = builder
                .comment("Вежа (TowerClimbGoal): спершу СТРИБОК. Моб будує вежу вгору й після КОЖНОГО шару (і кожного блока мосту)",
                        "одразу перевіряє СВІЖИМ шляхом, чи вже можна допригнути до гравця через Run_N_Jump; щойно маршрут зі",
                        "стрибком є - вежа зупиняється й моб стрибає. Міст до гравця зверху й поштовх 'падіння на гравця' лишаються",
                        "ФОЛБЕКОМ - на випадок, коли стрибком дістатись не виходить. false - стара поведінка (шлях перевіряється",
                        "лише за звичайним розкладом, до 40 тіків із тротлінгом).")
                .define("towerJumpFirst", true);

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
                        "саме, що затримка гравця перед установкою блока у ванілі (~4 тіки).",
                        "Діє для решти причин (клатч, аварійна підлога, перекриття хазардів); для вежі й мосту - значення нижче.")
                .defineInRange("mobPlaceDelayTicks", 4, 0, 40);

        TOWER_PLACE_DELAY_TICKS = builder
                .comment("Вежа (піларинг): мінімум тіків між двома блоками стіни/хреста одного моба. Блоки ставляться ПО ОДНОМУ",
                        "(не кілька за тік): стіна з боку гравця -> стрибок -> у польоті добудова стіни вгору й блок під ногами.",
                        "2 тіки - швидкий гравець, що спамить ПКМ; більше значення - повільніша стіна. Блок під ногами у польоті",
                        "(PILLAR_FLOOR) цим не стримується - йому досить 1 тіку розриву, щоб моб не пропустив вікно стрибка.")
                .defineInRange("towerPlaceDelayTicks", 2, 1, 20);

        BRIDGE_PLACE_DELAY_MIN_TICKS = builder
                .comment("Міст (горизонтальне будівництво): мінімум тіків між двома блоками. Фактична затримка щоразу",
                        "випадкова в діапазоні [min..max]: 4-5 тіків - схоже на 'ніндзя-брідж', але трохи повільніше й без присідання.")
                .defineInRange("bridgePlaceDelayMinTicks", 4, 1, 40);

        BRIDGE_PLACE_DELAY_MAX_TICKS = builder
                .comment("Міст: максимум тіків між двома блоками (якщо менший за bridgePlaceDelayMinTicks - береться min).")
                .defineInRange("bridgePlaceDelayMaxTicks", 5, 1, 40);

        builder.pop();

        builder.push("Shape_Aware_Walking");

        ENABLE_SHAPE_AWARE_WALKING = builder
                .comment("Моби (УСІ наземні, включно з модовими) враховують реальну форму колізії блоків:",
                        "ходять по плитах, горщиках, килимах, краю відкритих люків (притискаючись до стіни/краю,",
                        "щоб не впасти) - замість грубого 'блок суцільний / порожній'. (true/false)")
                .define("enableShapeAwareWalking", true);

        SHAPE_WALK_BLACKLIST = builder
                .comment("ID типів мобів через кому, до яких ця система НЕ застосовується",
                        "(наприклад: minecraft:iron_golem,somemod:weird_mob). Порожньо - застосовується до всіх.")
                .define("shapeWalkBlacklist", "");

        UNREACHABLE_RECOMPUTE_INTERVAL_TICKS = builder
                .comment("Інтервал (у тіках) між повторними спробами порахувати шлях до цілі,",
                        "яку path-finder щойно визнав недосяжною.")
                .defineInRange("unreachableRecomputeIntervalTicks", 40, 1, 1200);


        builder.pop();

        builder.push("Fall_Damage_Jumps");

        FALL_DAMAGE_AWARE_JUMPS = builder
                .comment("Стрибки вниз враховують ШКОДУ ВІД ПАДІННЯ: нижній предел динамічний (замість фіксованих -3 блоків),",
                        "у вартість шляху додається штраф за шкоду (безпечне приземлення завжди вигідніше), а смертельні",
                        "приземлення відсікаються. Беруться до уваги здоров'я, золоті серця, ефекти (Опір, Повільне падіння,",
                        "Стрибучість), чари спорядження (Невагомість, Захист) і блок приземлення (вода, сіно, мед, слайм, ліжко,",
                        "павутиння). Бронею падіння НЕ зменшується - так у ваніллі. false - стара логіка (-3 без штрафів).")
                .define("fallDamageAwareJumps", true);

        JUMP_DOWN_MAX_BLOCKS = builder
                .comment("Стеля динамічного нижнього предела: на скільки блоків НИЖЧЕ відриву моб узагалі розглядає приземлення.",
                        "Справжній предел - менший із цього числа й того, що моб переживе. Рівні глибше -3 скануються лише коли",
                        "мілкі приземлення НЕ безкоштовні (є шкода) або їх нема зовсім - щоб не вантажити пошук шляху.")
                .defineInRange("jumpDownMaxBlocks", 8, 3, 24);

        FALL_DAMAGE_RESERVE_HP = builder
                .comment("Скільки HP моб мусить мати ПІСЛЯ падіння, щоб стрибок схвалили. 1.0 = 'аби вижив'.")
                .defineInRange("fallDamageReserveHp", 1.0D, 0.0D, 20.0D);

        FALL_DAMAGE_COST_PER_HP = builder
                .comment("Штраф у вартості шляху за кожен 1 HP шкоди від падіння (1 блок ходьби = 1.0). 6 - моб обійде до ~6 блоків",
                        "заради півсерця; 0 - шкода не впливає на вибір маршруту (лишається лише відсікання смертельних).")
                .defineInRange("fallDamageCostPerHp", 6.0D, 0.0D, 100.0D);

        builder.pop();

        builder.push("Debug");

        DEBUG_ENABLED = builder
                .comment("Головний вимикач дебаг-логу. Увесь вивід іде у ОКРЕМИЙ файл logs/betterenemiesai-debug.log",
                        "(а не в консоль), тому консоль/latest.log більше не переповнюються. Вимкни, коли налагодження закінчено.")
                .define("debugEnabled", true);

        DEBUG_WATCHDOG = builder
                .comment("Сторож: сам помічає, коли заагрений моб 'завис' на місці або 'смикається' вперед-назад,",
                        "і пише повний знімок стану (цілі, шлях, блоки навколо, чому не проходять переходи).")
                .define("debugWatchdog", true);

        DEBUG_FLOOR_MIXIN = builder
                .comment("Вибірка з міксина getFloorLevel/getPathTypeFromState. ДУЖЕ шумно (виклик на кожну клітинку",
                        "графа кожного моба): навіть увімкнене, друкує лише 'цікаві' клітинки й раз на ~5с на позицію.")
                .define("debugFloorMixin", false);

        DEBUG_VERBOSE_JUMP = builder
                .comment("Покадровий вивід польоту стрибка (щотіку). Для звичайних сесій краще вимкнено.")
                .define("debugVerboseJump", false);

        DEBUG_MAX_FILE_MB = builder
                .comment("Максимальний розмір файлу дебаг-логу (МБ). При перевищенні файл стає .prev.log, пишеться новий.")
                .defineInRange("debugMaxFileMb", 16, 1, 512);

        builder.pop();

        SPEC = builder.build();
    }
}