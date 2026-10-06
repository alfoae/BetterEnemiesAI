package com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build;

import com.example.examplemod.Config;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyInventory.EnemyStorage.IMobBlockStorage;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpAssistGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

public final class EnemyBreak_N_BuildUtils {

    public static final int SEARCH_XZ_RADIUS = 8;
    public static final int SEARCH_Y_RADIUS = 3;

    /**
     * Спільний поріг "це вже підйом, а не просто крок вгору" — той самий критерій і для
     * {@link BuildPathGoal}, і для {@link TowerClimbGoal}, щоб вони ніколи не розходились у
     * тому, яку саме ситуацію кожен з них вважає "підйомом".
     */
    public static final double CLIMB_HEIGHT_THRESHOLD = 2.0;

    private static final double MIN_CANDIDATE_DIST_SQ = 4.0;

    // АРХІТЕКТУРНИЙ ФІКС (за проханням користувача): build/dig мають бути ІНСТРУМЕНТОМ, який
    // допомагає PursuitEnemyBehavior, а не замінює його. Зараз пріоритети Goal-ів (1,2 вищі за
    // 3=атака) означають, що щойно шлях "заблоковано" - build/dig миттєво перехоплюють
    // керування, не давши pursuit жодного шансу. Тому "заблоковано" тепер повертається
    // НАЗОВНІ, лише якщо це триває безперервно вже PURSUIT_GRACE_PERIOD_TICKS - весь цей час
    // pursuit (пріоритет 3) може просто дійти й вдарити сам, як і мало бути.
    // <p>
    // ФІКС (живий тест: моб зістрибував з недобудованого стовпа рівно тоді, коли Y майже
    // зрівнювався з гравцем): раніше тут було ДВА трекери за ТИПОМ причини (висота/навігація), і
    // {@link #isPathBlocked} перемикався між ними за порогом heightDifference<2.0 - у момент
    // перемикання height-трекер (уже давно пройшов grace) просто переставав опитуватись, а
    // nav-трекер стартував із НУЛЯ і сам по собі потребував нових 30 тіків, перш ніж знову
    // повернути true. Ці ~30 тіків isPathBlocked() хибно повертав false, хоча реального шляху
    // так само не було (canReach лишався false) - і саме в цьому вікні PursuitEnemyMeleeBehavior
    // встигав перехопити керування й повести моба по частковому шляху вниз/убік. Тепер ОДИН
    // трекер на СИРИЙ комбінований сигнал (висота АБО навігація) - перехід через поріг більше не
    // скидає накопичений час.
    private static final Map<Mob, Integer> PATH_BLOCKED_SINCE_TICK = new WeakHashMap<>();
    // Окремий трекер лишається лише для {@link #isNavigationBlocked} самого по собі - його
    // незалежно й напряму викликає {@link DigThroughWallsGoal} (без надбавки за висоту), тож його
    // власний grace-таймер має рахуватись сам по собі, не змішуючись із isPathBlocked.
    private static final Map<Mob, Integer> NAV_BLOCKED_SINCE_TICK = new WeakHashMap<>();
    private static final int PURSUIT_GRACE_PERIOD_TICKS = 30; // 1.5с - скільки часу дається pursuit перш ніж підключати будівництво/копання
    private static final Map<Mob, NavCache> NAV_CACHE = new WeakHashMap<>();

    /**
     * ТИМЧАСОВИЙ DEBUG-ЛОГ: тонка обгортка над {@link PursuitEnemyBehavior#debugMsg} — сама лише
     * резолвить гравця з моба (через {@link PursuitEnemyBehavior#getTrackedPlayer}), щоб виклики
     * нижче не тягали Player окремим параметром. Видалити разом з усіма викликами нижче після
     * завершення тестування копання/будівництва.
     */
    static void debugMsg(Mob mob, String msg) {
        Player player = PursuitEnemyBehavior.getTrackedPlayer(mob);
        if (player != null) {
            PursuitEnemyBehavior.debugMsg(player, msg);
        }
    }

    private EnemyBreak_N_BuildUtils() {
    }

    /**
     * Реєструє "сире" (без grace-періоду) заблоковано/ні для цього тіку в переданому трекері, і
     * повертає, чи минув {@link #PURSUIT_GRACE_PERIOD_TICKS}. Якщо в якийсь момент rawBlockedNow
     * стає false (шлях знову вільний, навіть на 1 тік) - таймер скидається повністю: pursuit
     * отримує свіжий шанс щоразу, коли перепона зникає, а не просто "накопичує" заблокованість.
     */
    private static boolean pastGracePeriod(Map<Mob, Integer> tracker, Mob mob, boolean rawBlockedNow) {
        if (!rawBlockedNow) {
            tracker.remove(mob);
            return false;
        }
        int sinceTick = tracker.computeIfAbsent(mob, m -> mob.tickCount);
        return (mob.tickCount - sinceTick) >= PURSUIT_GRACE_PERIOD_TICKS;
    }
    // Затримка між постановками блоків одним і тим самим мобом - "у ванілі у гравця є задержка
    // перед установкою блока" (короткий per-mob кулдаун, ОКРЕМИЙ від ACTION_COOLDOWN_TICKS
    // конкретних Goal-ів: цей рахується на РІВНІ самого API постановки блоку, тож діє
    // однаково для геть усіх викликів, включно з "аварійними" - ensureFloorUnderneathWhileAirborne
    // у TowerClimbGoal навмисно не чекає на власний кулдаун Goal-у, бо вікно падіння коротке).
    private static final Map<Mob, Long> LAST_PLACE_TICK = new WeakHashMap<>();

    public static boolean canOperate(Mob mob) {
        if (!Config.ENABLE_MOB_TERRAFORMING.get()) return false;
        if (!(mob.level() instanceof ServerLevel level)) return false;
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return false;
        if (!PursuitEnemyBehavior.isMemoryChasing(mob)) return false;
        return !PursuitEnemyBehavior.isSearchModeActive(mob);
    }

    /**
     * "Заблоковано" з надбавкою за різницю висот: ціль на 2+ блоки вище вважається заблокованою,
     * навіть якщо ванільний шлях технічно існує — так тригериться підйом у {@link BuildPathGoal}.
     * {@link DigThroughWallsGoal} висота сама по собі не цікавить (копання не залежить від того,
     * наскільки вище ціль) — там використовується чистіший {@link #isNavigationBlocked} без цієї
     * надбавки. Дві різні назви навмисно — щоб однакова назва не наштовхувала на думку, що це
     * один і той самий тест.
     */
    public static boolean isPathBlocked(Mob mob, Vec3 chasePos) {
        double heightDifference = chasePos.y - mob.getY();
        boolean rawHeightBlocked = heightDifference >= 2.0;

        // Той самий кеш на тік, що й isNavigationBlocked/PursuitEnemyMeleeBehavior.tick()
        // використовують через getOrComputePath - рахуємо це тут БЕЗУМОВНО (а не лише коли
        // rawHeightBlocked=false, як було раніше), щоб сирий сигнал був БЕЗПЕРЕРВНИМ по обидва
        // боки порогу heightDifference=2.0. Зайвого createPath() це не додає: поки моб ще
        // далеко/явно нижче, шлях і так майже завжди недосяжний (rawNavBlocked теж true), а сам
        // виклик кешується на тік спільно з усіма іншими викликачами.
        Path navPath = getOrComputePath(mob, chasePos);
        boolean rawNavBlocked = navPath == null || !navPath.canReach();
        boolean rawBlockedNow = rawHeightBlocked || rawNavBlocked;

        boolean pastGrace = pastGracePeriod(PATH_BLOCKED_SINCE_TICK, mob, rawBlockedNow);
        // ТИМЧАСОВИЙ DEBUG: раз/сек - показує ОБИДВІ сирі причини і спільний статус grace-періоду.
        if (mob.tickCount % 20 == 0) {
            Integer since = PATH_BLOCKED_SINCE_TICK.get(mob);
            int duration = since != null ? mob.tickCount - since : 0;
            debugMsg(mob, String.format(
                    "[DEBUG isPathBlocked] висота=%.2f (поріг 2.0, сире=%s) навігація(сире)=%s "
                            + "заблоковано_тіків=%d/%d минув_grace=%s",
                    heightDifference, rawHeightBlocked, rawNavBlocked,
                    duration, PURSUIT_GRACE_PERIOD_TICKS, pastGrace));
        }
        return pastGrace;
    }

    /**
     * СПІЛЬНИЙ кеш шляху на тік — рахує {@code createPath()} НЕ БІЛЬШЕ РАЗУ на тік на моба,
     * незалежно від того, хто питає: {@code BuildPathGoal.canUse()}, {@code DigThroughWallsGoal
     * .canUse()} (обидва опитуються GoalSelector-ом ЩОТІКУ, навіть коли не виграють пріоритет —
     * тобто "чисто перевірка" все одно чіпляє спільний навігатор) чи {@code
     * PursuitEnemyMeleeBehavior.tick()} через {@link #getOrComputePath}. Знайдено користувачем
     * через окремий debug-лог "[PATH DEBUG]": два послідовні виклики createPath() з ОДНІЄЮ й
     * тією ж ціллю в межах одного тіка давали РІЗНІ результати — ванільний NodeEvaluator не
     * розрахований на повторний виклик у тому самому тіку.
     * <p>
     * Ціль рахується через {@code getOnPos()} гравця (реальний блок опори через колізію
     * хітбокса, не голий floor координати) — АЛЕ тільки коли {@link PursuitEnemyBehavior#isLiveChasing}
     * (інакше під час GOING_TO_LAST_SEEN/SEARCHING це підмінило б НАВМИСНО застиглу точку живою
     * позицією гравця — саме така підміна ламала "переслідування по пам'яті").
     */
    private static BlockPos resolvePathTarget(Mob mob, Vec3 chasePos) {
        BlockPos naiveTarget = BlockPos.containing(chasePos);
        if (!PursuitEnemyBehavior.isLiveChasing(mob)) {
            return naiveTarget;
        }
        Player trackedPlayer = PursuitEnemyBehavior.getTrackedPlayer(mob);
        return trackedPlayer != null ? trackedPlayer.getOnPos().above() : naiveTarget;
    }

    /**
     * Прогресивний бекоф: перші {@link #UNREACHABLE_FAST_RETRY_TICKS} тіків недосяжності - кожен
     * тік (раптом перепона щойно зникла), далі - раз на {@link Config#UNREACHABLE_RECOMPUTE_INTERVAL_TICKS}.
     * Немає сенсу робити це складнішим (більше сходинок бекофу) - нам потрібно лише не бити по CPU
     * щотіку заради цілі, яка вже довго недосяжна, а не ідеальну криву витрат.
     */
    private static final int UNREACHABLE_FAST_RETRY_TICKS = 5;

    /**
     * Повертає закешований (на цей тік, для цієї цілі) {@link Path}, рахуючи {@code createPath()}
     * лише якщо ще не рахували. Викликається і з {@link #isNavigationBlocked}, і напряму з
     * {@code PursuitEnemyMeleeBehavior.tick()} — щоб реальний рух ішов по ТОМУ САМОМУ шляху, який
     * щойно перевірили на прохідність, а не рахував свій окремий виклик createPath() поверх.
     */
    public static Path getOrComputePath(Mob mob, Vec3 chasePos) {
        BlockPos pathTarget = resolvePathTarget(mob, chasePos);
        NavCache cache = NAV_CACHE.computeIfAbsent(mob, m -> new NavCache());

        // v14 - у ПОЛЬОТІ стрибка GapJumpAssistGoal шлях не рахуємо. Поки моб у повітрі, стартова точка A*
        // (getStart для !onGround) "провалюється" вниз - до ями/землі під розривом, тож шлях виходить про ІНШЕ місце
        // (часто недосяжний). BuildPath/Dig/TowerClimb/Pursuit опитують цей метод щотіку й під час польоту теж,
        // лічильник недосяжності ріс, і ПІСЛЯ приземлення тротлінг (unreachableRecomputeIntervalTicks) віддавав цей
        // застарілий шлях: ланцюжок стрибків обривався, а моб стояв до наступного справжнього перерахунку (~1 с).
        if (cache.path != null && !mob.onGround() && GapJumpAssistGoal.isActive(mob)) {
            return cache.path;
        }

        if (cache.tickComputed == mob.tickCount && pathTarget.equals(cache.pathTarget)) {
            return cache.path;
        }

        // v12 - ТРОТЛІНГ для недосяжної цілі (той самий пункт "Б", що ми давно узгодили, просто
        // руки дійшли тільки зараз - виявлено користувачем через лаг: GapJumpNodeEvaluator на
        // широкому непрохідному розриві щотіку вичерпно обшукує околицю, перш ніж здатися, а
        // createPath() тут рахувався КОЖЕН тік без винятку, поки моб переслідує - 20 повних
        // пошуків на секунду замість одного. targetChanged скидає троттлінг одразу: якщо ціль
        // (округлена до блока) зрушила - стара застигла відповідь може бути геть не про те.
        boolean targetChanged = !pathTarget.equals(cache.pathTarget);
        if (!targetChanged && cache.consecutiveUnreachableTicks > 0) {
            int interval = recomputeIntervalFor(cache.consecutiveUnreachableTicks);
            if (mob.tickCount - cache.lastRealComputeTick < interval) {
                cache.tickComputed = mob.tickCount; // той самий тік - не рахувати ще раз із getOrComputePath()
                return cache.path; // та сама (застигла) відповідь, БЕЗ нового createPath()
            }
        }

        Path path = mob.getNavigation().createPath(pathTarget, 0);
        cache.lastRealComputeTick = mob.tickCount;
        cache.consecutiveUnreachableTicks = (path != null && path.canReach()) ? 0 : cache.consecutiveUnreachableTicks + 1;

        // [PATH DEBUG] - лишаю (корисно знайшли не я): тепер спрацьовує лише на СПРАВЖНЬОМУ
        // обчисленні (не на кожному зверненні з різних місць), тож більше не дублюється з
        // різними результатами для тієї самої цілі того самого тіку.
        if (path != null && path.getNodeCount() > 0) {
            Node end = path.getEndNode();
            debugMsg(mob, String.format(
                    "[PATH DEBUG] target=%s canReach=%s nodes=%d endNode=(%d,%d,%d) недосяжна_підряд=%d",
                    pathTarget, path.canReach(), path.getNodeCount(), end.x, end.y, end.z,
                    cache.consecutiveUnreachableTicks));
        }

        cache.tickComputed = mob.tickCount;
        cache.pathTarget = pathTarget;
        cache.canReach = path != null && path.canReach();
        cache.path = path;
        return path;
    }

    /**
     * Скидає тротлінг і застиглу відповідь кешу: наступний {@link #getOrComputePath} порахує шлях ЗАНОВО.
     * Для випадків, коли моб різко змінив місце НЕ ідучи по шляху (приземлення після стрибка): тротлінг
     * недосяжної цілі вважає, що "нічого не змінилось", а після стрибка це не так. Якщо цього тіку шлях уже
     * справді рахувався - він свіжий, вдруге не чіпаємо (ванільний NodeEvaluator не любить дубль у тому самому тіку).
     */
    public static void invalidatePathCache(Mob mob) {
        NavCache cache = NAV_CACHE.get(mob);
        if (cache == null || cache.lastRealComputeTick == mob.tickCount) {
            return;
        }
        cache.consecutiveUnreachableTicks = 0;
        cache.tickComputed = -1;
    }

    private static int recomputeIntervalFor(int consecutiveUnreachableTicks) {
        if (consecutiveUnreachableTicks < UNREACHABLE_FAST_RETRY_TICKS) {
            return 1;
        }
        return Config.UNREACHABLE_RECOMPUTE_INTERVAL_TICKS.get();
    }

    /**
     * Чисто "чи існує прохідний шлях" до точки переслідування — без надбавки за висоту з
     * {@link #isPathBlocked}. Використовується {@link DigThroughWallsGoal}, якому висота не
     * заважає: копання йде в напрямку цілі незалежно від того, вище вона чи нижче.
     */
    public static boolean isNavigationBlocked(Mob mob, Vec3 chasePos) {
        Path path = getOrComputePath(mob, chasePos);
        boolean rawBlocked = path == null || !path.canReach();

        // ТИМЧАСОВИЙ DEBUG: тільки коли RAW заблоковано (щоб не спамити щотіку в межах
        // grace-періоду). Дублювання між Goal-ами того самого тіку більше нема - getOrComputePath
        // сам не рахує двічі, а лог "[PATH DEBUG]" усередині нього теж спрацьовує лише раз.
        if (rawBlocked) {
            BlockPos pathTarget = resolvePathTarget(mob, chasePos);
            BlockPos naiveTarget = BlockPos.containing(chasePos);
            Player trackedPlayer = PursuitEnemyBehavior.getTrackedPlayer(mob);
            boolean naivePassable = isPassableColumn(mob.level(), naiveTarget);
            boolean pathTargetPassable = isPassableColumn(mob.level(), pathTarget);
            Integer since = NAV_BLOCKED_SINCE_TICK.get(mob);
            int duration = since != null ? mob.tickCount - since : 0;
            debugMsg(mob, String.format(
                    "[DEBUG isNavigationBlocked] RAW=true (canReach=%s вузлів=%s) заблоковано_тіків=%d/%d "
                            + "naive-блок=%s(прохідна=%s) path-ціль=%s(прохідна=%s) liveChasing=%s "
                            + "chasePos(дроб)=%.3f,%.3f,%.3f гравець(дроб)=%.3f,%.3f,%.3f моб(дроб)=%.3f,%.3f,%.3f",
                    path != null && path.canReach(),
                    path == null ? "-" : String.valueOf(path.getNodeCount()),
                    duration, PURSUIT_GRACE_PERIOD_TICKS,
                    naiveTarget, naivePassable, pathTarget, pathTargetPassable,
                    PursuitEnemyBehavior.isLiveChasing(mob),
                    chasePos.x, chasePos.y, chasePos.z,
                    trackedPlayer != null ? trackedPlayer.getX() : -0.0,
                    trackedPlayer != null ? trackedPlayer.getY() : -0.0,
                    trackedPlayer != null ? trackedPlayer.getZ() : -0.0,
                    mob.getX(), mob.getY(), mob.getZ()));
        }

        return pastGracePeriod(NAV_BLOCKED_SINCE_TICK, mob, rawBlocked);
    }

    public static BlockPos findNearestOpenArea(Mob mob, Level level, Vec3 chasePos) {
        // Той самий getOnPos()-фікс, що й у isNavigationBlocked (через resolvePathTarget) - і та
        // сама умова isLiveChasing: не підміняти застиглу GOING_TO_LAST_SEEN/SEARCHING точку
        // живою позицією гравця.
        BlockPos center = resolvePathTarget(mob, chasePos);
        BlockPos mobPos = mob.blockPosition();

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dy = -SEARCH_Y_RADIUS; dy <= SEARCH_Y_RADIUS; dy++) {
            for (int dx = -SEARCH_XZ_RADIUS; dx <= SEARCH_XZ_RADIUS; dx++) {
                for (int dz = -SEARCH_XZ_RADIUS; dz <= SEARCH_XZ_RADIUS; dz++) {
                    BlockPos candidate = center.offset(dx, dy, dz);

                    // Виключаємо тільки впритул до МОБА (щоб не "знайти" клітинку, на якій він
                    // уже стоїть) - сам вибір керується відстанню до ЦІЛІ, не до моба (нижче).
                    if (mobPos.distSqr(candidate) <= MIN_CANDIDATE_DIST_SQ) continue;

                    // ФІКС (був баг): раніше тут стояло mobPos.distSqr(candidate) - шукали
                    // найближчу прохідну колонку ДО МОБА. На відкритій площі це тривіально
                    // знаходило точку за крок від моба (він і сам стоїть на прохідній підлозі) -
                    // моб "доходив" туди за 1 тік, спрацьовував arrived-скид, і Goal
                    // перезапускався по колу без реального прогресу (105 START/STOP-циклів у
                    // тестовому лозі біля прірви). Тепер мінімізуємо відстань до ЦІЛІ
                    // (center=chasePos) - шукаємо найближчу до ГРАВЦЯ прохідну колонку в межах
                    // вікна пошуку, а не найближчу до моба.
                    double distSqToTarget = center.distSqr(candidate);
                    if (distSqToTarget < bestDistSq && isPassableColumn(level, candidate)) {
                        bestDistSq = distSqToTarget;
                        best = candidate;
                    }
                }
            }
        }

        BlockPos result = best != null ? best : center;

        // ТИМЧАСОВИЙ DEBUG: без throttle - показує, чи клітинка САМОГО ГРАВЦЯ (center) прохідна
        // сама по собі, і чи вона була виключена через близькість до моба (MIN_CANDIDATE_DIST_SQ) -
        // це прямо перевіряє гіпотезу "чому не обирається клітинка, де стоїть гравець".
        boolean centerPassable = isPassableColumn(level, center);
        boolean centerExcluded = mobPos.distSqr(center) <= MIN_CANDIDATE_DIST_SQ;
        debugMsg(mob, String.format(
                "[DEBUG findNearestOpenArea] %s центр(=гравець)=%s прохідна=%s виключена_близькістю=%s "
                        + "зсув_від_гравця=(%d,%d,%d) зсув_від_моба=(%d,%d,%d) результат=%s",
                best != null ? "знайдено" : "FALLBACK-на-center(нічого не знайдено!)",
                center, centerPassable, centerExcluded,
                result.getX() - center.getX(), result.getY() - center.getY(), result.getZ() - center.getZ(),
                result.getX() - mobPos.getX(), result.getY() - mobPos.getY(), result.getZ() - mobPos.getZ(),
                result));

        return result;
    }

    /**
     * КЕШ createPath() НА ТІК (знайдено користувачем через окремий debug-лог "[PATH DEBUG]"):
     * {@code mob.getNavigation()} — той самий спільний об'єкт щоразу, а
     * {@code BuildPathGoal.canUse()} і {@code DigThroughWallsGoal.canUse()} ОБИДВА опитуються
     * GoalSelector-ом щотіку й ОБИДВА незалежно кличуть createPath() на ньому — навіть коли
     * жоден не "виграє" пріоритет. Лог показав: два послідовні виклики createPath() з ОДНІЄЮ
     * й тією ж ціллю в межах одного тіка давали РІЗНІ результати (canReach=true/8 вузлів, потім
     * canReach=false/22 вузли, інший endNode) — ванільний NodeEvaluator не розрахований на
     * повторний виклик у тому самому тіку. Тепер рахуємо не більше разу на тік на моба.
     */
    private static final class NavCache {
        int tickComputed = -1;
        BlockPos pathTarget;
        boolean canReach;
        Path path;
        // v12 - троттлінг недосяжної цілі, див. getOrComputePath вище.
        int consecutiveUnreachableTicks = 0;
        int lastRealComputeTick = Integer.MIN_VALUE;
    }

    private static boolean isPassableColumn(Level level, BlockPos pos) {
        return !level.getBlockState(pos).isSolid()
                && !level.getBlockState(pos.above()).isSolid()
                && level.getBlockState(pos.below()).isSolid();
    }

    /**
     * ВИПРАВЛЕНО (живий тест: моб "зависав" у BRIDGE_TO_PLAYER): рахувати напрям як ПОВНИЙ 3D
     * вектор (до цілі, включно з Y), нормалізувати його, і лише ТОДІ округлювати x/z — небезпечно,
     * коли ціль набагато нижче/вище моба. Велика різниця по Y "забирає" собі більшу частину
     * довжини одиничного вектора, тож x- і z-складові падають нижче 0.5 і округлюються до 0 —
     * тобто крок "нікуди" (mobPos сам на себе), хоча по горизонталі до цілі ще далеко. Саме це
     * траплялось у {@code TowerClimbGoal.tickBridgeToPlayer} на "близькій" дистанції, де ціллю
     * стає РЕАЛЬНА висота гравця (могла бути на 5-10+ блоків нижче/вище моба, що стоїть на
     * власному стовпі) — далі мостом просто нікуди не йшло.
     * <p>
     * Це "ГОРИЗОНТАЛЬНИЙ крок" за назвою і задумом — тому Y цілі тут взагалі не бере участі:
     * рахуємо й нормалізуємо лише (dx, dz).
     * <p>
     * ВИПРАВЛЕНО (живий тест #2: моб "застряг", коли ціль було по діагоналі): НІКОЛИ не рухаємось
     * одночасно по X і Z за один крок. {@link #hasAdjacentSolid} — умова, що дозволяє
     * {@link #placeBlock} взагалі щось ставити — перевіряє лише 4 КАРДИНАЛЬНІ сторони (північ/
     * південь/схід/захід) плюс верх/низ, БЕЗ діагоналей. Діагональний крок (dx=±1 і dz=±1
     * одночасно) торкається попереднього (вже готового) блока лише КУТОМ, а не стороною — жодна з
     * 4 кардинальних перевірок його не бачить, {@code hasAdjacentSolid} завжди false, блок ніколи
     * не ставиться, і моб намертво зависає на одній і тій самій "неможливій" діагональній клітинці.
     * <p>
     * Замість цього — рухаємось ЛИШЕ по ОДНІЙ осі за раз, тій, де залишок БІЛЬШИЙ (при рівності —
     * завжди X, це не випадково: саме таке сталий tie-break і дає чергування X,Z,X,Z,... на
     * рівно діагональній цілі). Кожен наступний блок тоді впритул СТОРОНОЮ до попереднього — точно
     * так само, як звичайні гравці мостять по діагоналі зигзагом (крок убік, крок уперед, крок
     * убік, крок уперед), і як це робиться в Bedrock. На не рівно діагональній цілі виходить
     * природний "сходовий" шлях: більшість кроків уздовж довшої осі, час від часу — крок уздовж
     * коротшої, поки різниця не вирівняється.
     */
    public static BlockPos nextHorizontalStep(Mob mob, BlockPos target) {
        BlockPos mobPos = mob.blockPosition();
        int dx = target.getX() - mobPos.getX();
        int dz = target.getZ() - mobPos.getZ();
        if (dx == 0 && dz == 0) return mobPos;

        if (Math.abs(dx) >= Math.abs(dz)) {
            return mobPos.offset(Integer.signum(dx), 0, 0);
        }
        return mobPos.offset(0, 0, Integer.signum(dz));
    }

    /**
     * Та сама умова, що раніше жила приватно всередині {@link BuildPathGoal} — винесена сюди,
     * щоб {@link TowerClimbGoal} перевіряла "це підйом?" ІДЕНТИЧНО, а не своєю копією порогу.
     */
    public static boolean needsClimb(Mob mob, BlockPos target) {
        return target.getY() - mob.blockPosition().getY() >= CLIMB_HEIGHT_THRESHOLD;
    }

    public static boolean isBreakable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.isSolid()) return false;
        if (state.getDestroySpeed(level, pos) < 0) return false;
        return !state.hasBlockEntity();
    }

    public static void breakBlock(Level level, BlockPos pos, Mob mob) {
        BlockState state = level.getBlockState(pos);

        ItemStack drop = new ItemStack(state.getBlock().asItem());
        if (!drop.isEmpty() && mob instanceof IMobBlockStorage storage) {
            storage.addDugBlock(drop);
        }

        level.playSound(null, pos, state.getSoundType().getBreakSound(), SoundSource.HOSTILE, 1.0F, 1.0F);
        level.destroyBlock(pos, false);
    }

    /**
     * "не вміти ставити блоки в повітрі... тільки якщо біля нього є інший блок" — усі 6 граней-
     * сусідів (не по діагоналі — так само суворо, як і в гравця: дотику лише кутом не досить).
     */
    public static boolean hasAdjacentSolid(Level level, BlockPos pos) {
        return level.getBlockState(pos.below()).isSolid()
                || level.getBlockState(pos.above()).isSolid()
                || level.getBlockState(pos.north()).isSolid()
                || level.getBlockState(pos.south()).isSolid()
                || level.getBlockState(pos.east()).isSolid()
                || level.getBlockState(pos.west()).isSolid();
    }

    /**
     * Ставить блок від імені {@code mob}. Три перевірки, усі "тихі" (просто нічого не робить,
     * якщо не пройшли) — знайдені живим тестуванням:
     * <ol>
     *   <li>НЕ переписує вже існуючий (не-replaceable) блок — {@code canBeReplaced()}, той самий
     *       критерій, що й у ванільного розміщення, а не вужчий {@code !isSolid()} (який
     *       пропускав, наприклад, квіти/факели/рейки — вони не "solid", але явно не порожні).</li>
     *   <li>Не частіше, ніж раз на {@link Config#MOB_PLACE_DELAY_TICKS} тіків для ЦЬОГО моба.</li>
     *   <li>{@link #hasAdjacentSolid} — інакше не ставить.</li>
     * </ol>
     */
    public static void placeBlock(ServerLevel level, BlockPos pos, Mob mob) {
        if (!level.getBlockState(pos).canBeReplaced()) return;
        if (!hasAdjacentSolid(level, pos)) return;

        long now = level.getGameTime();
        Long lastTick = LAST_PLACE_TICK.get(mob);
        if (lastTick != null && now - lastTick < Config.MOB_PLACE_DELAY_TICKS.get()) return;

        Block block = DigBlockResolver.getDigBlock(level);
        BlockState state = block.defaultBlockState();

        level.setBlock(pos, state, 3);
        level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.HOSTILE, 1.0F, 1.0F);
        LAST_PLACE_TICK.put(mob, now);

        long expireAt = now + (long) Config.PLACED_BLOCK_LIFETIME_SECONDS.get() * 20L;
        TemporaryBlockData.get(level).track(pos, expireAt);
    }
}