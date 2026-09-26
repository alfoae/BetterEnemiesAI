package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.EnemyBreak_N_BuildUtils;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Спільні утиліти стрибка + читання "цей сегмент шляху вимагає стрибка" з реального Path.
 * <p>
 * v4 — виявлення розриву більше НЕ робить свій скан наперед. Цим тепер займається
 * {@link GapJumpNodeEvaluator} прямо всередині pathfinding-графа (через
 * {@link GapJumpPathNavigation}) — тобто сам {@code sharedPath}, яким і так користується
 * PursuitEnemyMeleeBehavior, вже містить "далекий" вузол там, де є прохідний стрибок. Це, до
 * речі, автоматично гасить окрему проблему координації з BuildPathGoal, яку доводилось руками
 * розводити пріоритетом раніше: щойно розрив стає прохідним через стрибок, createPath() більше
 * не повертає unreachable, і {@code isPathBlocked} для НЬОГО просто перестає бути true — тож
 * BuildPathGoal сам по собі туди більше не претендує, без жодної спеціальної умови.
 * <p>
 * v5 — ФІЗИКА ПОЛЬОТУ винесена в {@link GapJumpPhysics} (точна ванільна модель, звірена з
 * незалежною реалізацією). Тут лишилась лише ОЦІНКА дальності для NodeEvaluator-а
 * ({@link #estimateMaxJumpRangeBlocks}) та читання Path.
 */
public final class GapJumpUtils {

    /**
     * Калібрувальний коефіцієнт ОЦІНКИ "на скільки блоків взагалі варто вважати розрив прохідним":
     * {@code maxGap = floor(topSpeed * RANGE_ESTIMATE_FACTOR)}. Це ДОРІВНЮЄ старому
     * {@code 10 тіків * 0.85} — навмисно лишив ті самі числа, щоб не змінювати, які розриви
     * NodeEvaluator вважає прохідними (зомбі в спринті → 3 блоки, як і було).
     * <p>
     * УВАГА: це НЕ модель польоту. Раніше цей самий коефіцієнт використовувався ще й для
     * обчислення швидкості відриву ({@code відстань / 8.5}) — і саме це ламало стрибок на 3 блоки:
     * реальна дальність польоту = швидкість × {@link GapJumpPhysics#FLIGHT_FACTOR_TO_LANDING} ≈ ×4.92,
     * а не ×8.5. Швидкість відриву тепер рахує лише {@link GapJumpPhysics#launchSpeedForDistance}.
     */
    static final double RANGE_ESTIMATE_FACTOR = 8.5;

    /**
     * Горизонтальна відстань між сусідніми вузлами шляху, з якої вважаємо сегмент "стрибком", а не звичайним кроком.
     */
    private static final double JUMP_SEGMENT_THRESHOLD = GapJumpRays.MIN_JUMP_DISTANCE;

    private GapJumpUtils() {
    }

    /**
     * Ванільний множник спринту: {@code LivingEntity.setSprinting(true)} додає до атрибута швидкості
     * тимчасовий модифікатор +0.3 (MULTIPLY_TOTAL), тобто ×1.3. Тільки поки прапор спринту увімкнений.
     */
    static final double SPRINT_SPEED_FACTOR = 1.3;

    /**
     * Швидкість-уставка бігу моба (те, що MoveControl множить на прискорення): атрибут швидкості ×
     * множник бігу. Спільна для оцінки дальності та для передбачення кроку в GapJumpAssistGoal.
     * <p>
     * <b>Спринт рахується "як увімкнений" завжди, коли моб у погоні.</b> Раніше атрибут читався як є, тож
     * оцінка залежала від того, чи прапор спринту ввімкнений САМЕ ЗАРАЗ — а він вимикається на кожному
     * {@code stop()} ({@code PursuitEnemyMeleeBehavior}, ця ціль) і вмикається лише в
     * {@code applyDefaultRun}, який у {@code PursuitEnemyMeleeBehavior.tick()} стоїть ПІСЛЯ
     * {@code getOrComputePath}. У результаті шлях, з яким моб їде в кожен стрибок, завжди рахувався при
     * sprint=false: для зомбі {@code 0.23 * 1.5 * 8.5 = 2.9 -> 2}, і перестрибування (відстань 4)
     * у графі просто не з'являлось, хоча пізніші перерахунки (вже в спринті: {@code 3.8 -> 3}) його мали —
     * моб їхав "блок за блоком" по шляху першого, короткозорого розрахунку.
     * Правило спринту одне на всіх ({@code Run_N_JumpUtils.applyDefaultRun}: sprint = isMemoryChasing),
     * тож у погоні множник додаємо самі; поза погонею (блукання) нічого не змінюється.
     */
    public static double runSpeedSetpoint(Mob mob) {
        double speed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (!mob.isSprinting() && PursuitEnemyBehavior.isMemoryChasing(mob)) {
            speed *= SPRINT_SPEED_FACTOR;
        }
        return speed * Run_N_JumpUtils.getRunSpeedModifier(mob);
    }

    /**
     * Теоретична максимальна дальність стрибка цього моба на повній швидкості (не поточній) для ЗВИЧАЙНОГО
     * блока під ногами. Дальність із урахуванням блока відриву - {@link #estimateMaxJumpRangeBlocks(Mob, double)}.
     */
    public static int estimateMaxJumpRangeBlocks(Mob mob) {
        return (int) Math.floor(runSpeedSetpoint(mob) * RANGE_ESTIMATE_FACTOR);
    }

    /**
     * Те саме, але для моба, що відривається з блока з множником дальності {@code blockRangeFactor}
     * ({@link #blockRangeFactor}). Для звичайного блока множник рівно 1.0 - тоді результат збігається зі
     * старим {@link #estimateMaxJumpRangeBlocks(Mob)}.
     */
    public static int estimateMaxJumpRangeBlocks(Mob mob, double blockRangeFactor) {
        return (int) Math.floor(runSpeedSetpoint(mob) * RANGE_ESTIMATE_FACTOR * blockRangeFactor);
    }

    /**
     * Ванільний {@code SlimeBlock.stepOn}: щотіку, поки моб іде по слайму, горизонтальна швидкість множиться на
     * {@code 0.4 + 0.2 * |vy|} (при ходьбі |vy| майже 0). Це НЕ властивість блока (як speedFactor), тож із
     * самого блока його не прочитати - тому слайм (і нащадки {@code SlimeBlock} з модів) розпізнається окремо.
     */
    static final double SLIME_STEP_SLOWDOWN = 0.4;

    /**
     * Множник дальності стрибка для блока, З ЯКОГО моб відривається, ДЛЯ ТАКОГО Δy (вгору/вниз/рівно). Береться
     * з САМОГО блока, тож працює й для блоків з інших модів:
     * <ul>
     *   <li><b>тертя</b> - через {@code BlockState.getFriction(level, pos, entity)} (хук NeoForge, той самий
     *       виклик, що й у ванільному {@code LivingEntity.travel}); блоки з модів перевизначають саме його;</li>
     *   <li><b>speedFactor</b> - гальмо ходьби (пісок душ, мед = 0.4): у ванілі вони мають ЗВИЧАЙНЕ тертя 0.6,
     *       а липкими їх робить саме це;</li>
     *   <li><b>jumpFactor</b> - мед = 0.5 (стрибок нижчий і коротший); ванільну висоту стрибка ми не чіпаємо,
     *       лише знаємо, що з цього блока далеко не долетіти (а вгору - може й ЗОВСІМ не піднятись).</li>
     * </ul>
     * Приблизні значення для ванільних блоків, Δy=0 (детальніше - {@link GapJumpPhysics#blockRangeFactor}):
     * звичайний 1.00, лід 1.45, блакитний лід 1.54, пісок душ 0.58, мед 0.43, слайм 0.34. Для Δy=+1 (вгору)
     * множник ЗАВЖДИ менший, навіть на звичайному блоці (~0.33 - вузьке вікно на підйом), а з меду вгору
     * взагалі недосяжно (0.0 - апекс стрибка нижчий за повний блок). Для Δy=-1 (вниз) - трохи більший за
     * рівний (більше часу на політ).
     *
     * @param floor    стан блока під ногами (блок відриву)
     * @param level    світ (для хука тертя; у навігації - {@code mob.level()})
     * @param floorPos позиція цього блока
     * @param entity   моб, для якого рахуємо (передається в хук тертя; може бути {@code null})
     * @param deltaY   landing.y - floorPos.getY(): {@code 0} рівно, {@code >0} вгору, {@code <0} вниз
     */
    public static double blockRangeFactor(BlockState floor, LevelReader level, BlockPos floorPos, Entity entity, double deltaY) {
        Block block = floor.getBlock();
        double friction = floor.getFriction(level, floorPos, entity);
        return GapJumpPhysics.blockRangeFactor(friction, runSlowdown(block), block.getJumpFactor(), deltaY);
    }

    /**
     * Те саме на рівному (Δy=0) - як до появи сходинок.
     */
    public static double blockRangeFactor(BlockState floor, LevelReader level, BlockPos floorPos, Entity entity) {
        return blockRangeFactor(floor, level, floorPos, entity, 0.0);
    }

    /**
     * Гальмо ходьби по блоку за тік: {@code speedFactor} (пісок душ, мед = 0.4; блоки з модів - будь-яке) і для
     * слайма ще {@link #SLIME_STEP_SLOWDOWN}. 1.0 - без гальма.
     */
    private static double runSlowdown(Block block) {
        double slowdown = block.getSpeedFactor();
        return block instanceof SlimeBlock ? slowdown * SLIME_STEP_SLOWDOWN : slowdown;
    }

    /**
     * Яку частку горизонтальної швидкості лишає ОДИН наземний тік на цьому блоці (ванільна фізика):
     * {@code тертя * 0.91 * гальмо ходьби}. Звичайний блок - 0.546, лід - 0.89, слайм - 0.29, пісок душ і
     * мед - 0.22. Береться з САМОГО блока (тертя через хук NeoForge), тож працює й для блоків з інших модів.
     * Потрібно, щоб відрізняти гальмо блока від удару (див. {@code GapJumpAssistGoal.checkExternalImpulse}).
     */
    public static double groundRetention(BlockState floor, LevelReader level, BlockPos floorPos, Entity entity) {
        return floor.getFriction(level, floorPos, entity) * GapJumpPhysics.AIR_FRICTION * runSlowdown(floor.getBlock());
    }

    /** Рядок для логу: блок під краєм, його коефіцієнти, множник і дальність звідти. Лише діагностика. */
    public static String describeTakeoffBlock(Mob mob, BlockPos feet) {
        BlockPos floorPos = feet.below();
        BlockState floor = mob.level().getBlockState(floorPos);
        Block block = floor.getBlock();
        double factor = blockRangeFactor(floor, mob.level(), floorPos, mob);
        return BuiltInRegistries.BLOCK.getKey(block)
                + " | тертя=" + String.format("%.3f", floor.getFriction(mob.level(), floorPos, mob))
                + " speedFactor=" + String.format("%.2f", block.getSpeedFactor())
                + " jumpFactor=" + String.format("%.2f", block.getJumpFactor())
                + (block instanceof SlimeBlock ? " (слайм: гальмо ходьби x" + SLIME_STEP_SLOWDOWN + ")" : "")
                + " | множник дальності=" + String.format("%.3f", factor)
                + " | макс. розрив звідси=" + estimateMaxJumpRangeBlocks(mob, factor);
    }

    /**
     * Дивиться в РЕАЛЬНИЙ поточний Path моба (уже побудований {@link GapJumpNodeEvaluator}-ом,
     * якщо на мобі стоїть {@link GapJumpPathNavigation}) і шукає, чи серед НАЙБЛИЖЧИХ пари
     * сусідніх вузлів попереду є "стрибковий" сегмент — тобто пара вузлів, що лежать далі одне
     * від одного, ніж дає звичайний крок. Не пересканює місцевість сама — тільки читає те, що
     * вже порахував pathfinder.
     *
     * @return дані про стрибок, або {@code null}, якщо найближчий відрізок шляху - звичайний крок
     */
    public static GapJump findUpcomingJumpSegment(Mob mob) {
        Path path = mob.getNavigation().getPath();
        if (path == null) {
            return null;
        }
        return findJumpSegmentInPath(path, Math.max(0, path.getNextNodeIndex() - 1));
    }

    /**
     * Те саме, що {@link #findUpcomingJumpSegment}, але від ПОТОЧНОГО положення моба й по СВІЖОМУ шляху:
     * бере спільний кеш {@code EnemyBreak_N_BuildUtils.getOrComputePath} (той самий, з якого їде
     * Pursuit; рахується не більше разу на тік на моба, тож додаткового createPath() це не дає).
     * Потрібне для ланцюжка стрибків: у тіку приземлення навігатор моба порожній (ціль його зупиняє на
     * старті), тож {@link #findUpcomingJumpSegment} нічого не бачить, і наступний стрибок мусив би чекати,
     * поки Pursuit заново покладе шлях у навігатор (~2 тіки, поки моб "повзе" без керування).
     *
     * @return наступний стрибок від поточного положення, або {@code null}, якщо шляху нема чи поруч лише кроки
     */
    public static GapJump findFreshJumpSegment(Mob mob) {
        Vec3 chasePos = PursuitEnemyBehavior.getChasePosition(mob);
        if (chasePos == null) {
            return null;
        }
        Path path = EnemyBreak_N_BuildUtils.getOrComputePath(mob, chasePos);
        if (path == null || path.getNodeCount() < 2) {
            return null;
        }
        return findJumpSegmentInPath(path, 0);
    }

    /**
     * Шукає стрибковий сегмент серед 2-3 пар вузлів Path, починаючи з індексу {@code from}.
     */
    static GapJump findJumpSegmentInPath(Path path, int from) {
        int lookahead = Math.min(path.getNodeCount() - 1, from + 3); // дивимось на 2-3 вузли вперед, не на весь шлях
        for (int i = from; i < lookahead; i++) {
            Node a = path.getNode(i);
            Node b = path.getNode(i + 1);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            if (dx * dx + dz * dz > JUMP_SEGMENT_THRESHOLD * JUMP_SEGMENT_THRESHOLD) {
                BlockPos edge = new BlockPos(a.x, a.y, a.z);
                BlockPos landing = new BlockPos(b.x, b.y, b.z);
                int gapBlocks = (int) Math.round(Math.sqrt(dx * dx + dz * dz)) - 1;
                return new GapJump(edge, Vec3.atBottomCenterOf(landing), Math.max(1, gapBlocks));
            }
        }
        return null;
    }

    /**
     * (v5: GapJumpAssistGoal більше не відходить для розгону — швидкість відриву задається явно, тож
     * розбіг на дальність не впливає. Метод лишений на випадок, якщо розгін знадобиться знову.)
     * <p>
     * Йде по прямій у напрямку {@code dir} від моба, шукаючи найдальшу БЕЗПЕЧНУ точку в межах
     * {@code desiredBlocks} — якщо позаду теж обрив (чи стіна), зупиняється на останньому
     * твердому й прохідному блоці перед ним, навіть якщо це менше за {@code desiredBlocks}.
     * Повертає поточну позицію моба, якщо небезпечно навіть на 1 блок.
     */
    public static Vec3 findSafeRetreatPoint(Mob mob, Vec3 dir, int desiredBlocks) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return mob.position();
        }
        BlockPos feet = mob.blockPosition();
        BlockPos lastSafe = feet;
        for (int step = 1; step <= desiredBlocks; step++) {
            BlockPos column = feet.offset((int) Math.round(dir.x * step), 0, (int) Math.round(dir.z * step));
            boolean solidFloor = level.getBlockState(column.below()).isSolid();
            boolean passable = !level.getBlockState(column).isSolid();
            if (!solidFloor || !passable) {
                break; // далі в цьому напрямку теж небезпечно - зупиняємось тут
            }
            lastSafe = column;
        }
        return Vec3.atBottomCenterOf(lastSafe);
    }

    /**
     * Край (останній твердий вузол перед стрибком), точка приземлення і ширина розриву в блоках.
     * Для косого/діагонального стрибка {@code gapBlocks} лише наближене (округлена відстань між
     * центрами мінус 1) і потрібне тільки для логів; уся геометрія береться з {@code edge}/{@code landing}.
     */
    public record GapJump(BlockPos edge, Vec3 landing, int gapBlocks) {
    }
}