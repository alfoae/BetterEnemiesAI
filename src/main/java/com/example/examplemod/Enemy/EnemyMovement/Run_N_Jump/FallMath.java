package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

/**
 * Чиста арифметика шкоди від падіння БЕЗ типів Minecraft (тому її можна перевірити окремо від гри, див.
 * {@code FallMathTest}). Усе, що читає моба чи світ, - у {@link FallDamage}.
 * <p>
 * Повторює ванільний ланцюжок для падіння:
 * <ol>
 *   <li>{@code ceil((fall - safeFall) * множник_блока * FALL_DAMAGE_MULTIPLIER)} - шкода від падіння; множник блока:
 *       1.0 звичайний, 0.2 сіно/мед, 0.5 ліжко, 0 вода/павутиння/слайм;</li>
 *   <li>броня НЕ зменшує падіння (тип шкоди обходить броню), а ефект Опору й чари спорядження (Невагомість, Захист) - так;</li>
 *   <li>спершу гаситься поглинанням (золоті серця), решта віднімається від здоров'я.</li>
 * </ol>
 * <b>Скільки блоків "падає" стрибок.</b> Ванільний лічильник набирає ТІЛЬКИ рух униз, тож стрибок з приземленням на
 * {@code N} блоків нижче дає {@code N + апекс} (апекс звичайного стрибка ~1.25 блока), а не просто {@code N}: стрибок на
 * -2 уже дає 1 HP шкоди, на -3 - 2 HP. Старий код вважав -3 безкоштовним.
 */
final class FallMath {

    private FallMath() {
    }

    /**
     * Висота апекса (блоків) стрибка з початковою вертикальною швидкістю {@code vy0}: за тією ж моделлю, що й політ у GapJumpPhysics.
     */
    static double apexHeight(double vy0, double gravityPerTick, double dragPerTick) {
        double y = 0.0;
        double vy = vy0;
        for (int tick = 0; tick < 200 && vy > 0.0; tick++) { // 200 - лише захист від нескінченного циклу при сміттєвих вхідних
            y += vy;
            vy = (vy - gravityPerTick) * dragPerTick;
        }
        return y;
    }

    /**
     * Скільки блоків рахує лічильник падіння: спуск від апекса до поверхні приземлення (dy < 0 - нижче відриву).
     */
    static double fallBlocks(double apex, int dy) {
        return Math.max(0.0, apex - dy);
    }

    /**
     * Шкода моба після Опору й чарів (ще БЕЗ поглинання) від падіння на {@code fallBlocks} блоків на поверхню з множником
     * {@code blockMultiplier}. 0 - без шкоди.
     */
    static float damageAfterReductions(Profile p, double fallBlocks, double blockMultiplier) {
        if (p.immune || blockMultiplier <= 0.0) {
            return 0.0F;
        }
        float excess = (float) (fallBlocks - p.safeFall);
        int raw = (int) Math.ceil((double) (excess * (float) blockMultiplier) * p.fallMultiplier);
        if (raw <= 0) {
            return 0.0F;
        }
        float damage = raw;
        if (p.resistanceLevel > 0) {
            int reduce = p.resistanceLevel * 5;
            damage = Math.max(damage * (float) (25 - reduce) / 25.0F, 0.0F);
        }
        if (damage <= 0.0F) {
            return 0.0F;
        }
        float prot = Math.min(Math.max(p.protection, 0.0F), 20.0F);
        if (prot > 0.0F) {
            damage = damage * (1.0F - prot / 25.0F);
        }
        return damage;
    }

    /**
     * Скільки HEALTH моб втратить (поглинання вже віднято).
     */
    static float healthLoss(Profile p, float damage) {
        return Math.max(damage - p.absorption, 0.0F);
    }

    /**
     * Чи виживе моб (і збереже хоча б {@code reserve} HP).
     */
    static boolean survives(Profile p, float damage) {
        return p.health - healthLoss(p, damage) >= p.reserve - 1.0E-4F;
    }

    /**
     * Найглибше приземлення (у блоках нижче відриву; на ЗВИЧАЙНИЙ блок), яке моб переживе, але не більше {@code cap}. Це
     * динамічний "нижній предел" стрибка вниз: здоровий моб з Невагомістю - глибше, ледь живий - мілко (сканування все
     * одно не менше старого ліміту, а смертельні кандидати відсікаються окремо).
     */
    static int maxSurvivableDrop(Profile p, double apex, int cap) {
        if (p.immune) {
            return cap;
        }
        int best = 0;
        for (int drop = 0; drop <= cap; drop++) {
            if (survives(p, damageAfterReductions(p, apex + drop, 1.0))) {
                best = drop;
            } else {
                break;
            }
        }
        return best;
    }

    /**
     * Знімок моба для розрахунку шкоди (знімається один раз на пошук шляху в {@link FallDamage#profileOf}).
     *
     * @param reserve         Скільки HP моб мусить мати ПІСЛЯ падіння, щоб рішення стрибати було схвалене.
     * @param resistanceLevel 0 - нема Опору; інакше рівень (amplifier + 1).
     * @param protection      Сума захисту чарами спорядження від падіння (ванільний {@code getDamageProtection}); діє в межах 0..20.
     * @param immune          Падіння не завдає шкоди взагалі (імунний тип, Повільне падіння, невразливість).
     */
        record Profile(float health, float absorption, float reserve, double safeFall, double fallMultiplier,
                       int resistanceLevel, float protection, boolean immune) {
    }
}
