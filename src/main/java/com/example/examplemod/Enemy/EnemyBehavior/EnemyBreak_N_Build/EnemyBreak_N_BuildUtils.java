package com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build;

import com.example.examplemod.Config;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior.PursuitEnemyBehavior;
import com.example.examplemod.Enemy.EnemyInventory.EnemyStorage.IMobBlockStorage;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpAssistGoal;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpUtils;
import com.example.examplemod.debug.DebugLog;
import com.example.examplemod.debug.MobDebug;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

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
    // v18: замість Map<Mob, Integer> (тік першого "заблоковано") - автомат TerraformRules.BlockedTracker: його годують лише
    // НОВІ справжні обчислення шляху, а не застигла відповідь тротлінгу (див. isPathBlocked).
    private static final Map<Mob, TerraformRules.BlockedTracker> PATH_BLOCKED_TRACKER = new WeakHashMap<>();
    // Окремий трекер лишається лише для {@link #isNavigationBlocked} самого по собі - його
    // незалежно й напряму викликає {@link DigThroughWallsGoal} (без надбавки за висоту), тож його
    // власний grace-таймер має рахуватись сам по собі, не змішуючись із isPathBlocked.
    private static final Map<Mob, TerraformRules.BlockedTracker> NAV_BLOCKED_TRACKER = new WeakHashMap<>();
    // v18: чи "завис" моб на маршруті паркуру (див. parkourRouteAhead).
    private static final Map<Mob, TerraformRules.StallDetector> PARKOUR_STALL = new WeakHashMap<>();
    // v19: блок, який не вдалось поставити, бо його клітинку перетинає хітбокс самого моба: моб підстрибує, і ціль повторює
    // спробу кожен свій тік, поки моб у повітрі (див. deferForOwnHitbox / retryPendingPlacement).
    private static final Map<Mob, PendingPlacement> PENDING_PLACEMENT = new WeakHashMap<>();
    // v19: до якого тіку (mob.tickCount) моб не підстрибує заради хітбокса - після PENDING_MAX_JUMPS невдалих стрибків поспіль.
    private static final Map<Mob, Integer> HITBOX_GIVE_UP_UNTIL = new WeakHashMap<>();
    private static final int PURSUIT_GRACE_PERIOD_TICKS = 30; // 1.5с - скільки часу дається pursuit перш ніж підключати будівництво/копання
    private static final Map<Mob, NavCache> NAV_CACHE = new WeakHashMap<>();

    /**
     * ТИМЧАСОВИЙ DEBUG-ЛОГ: тонка обгортка над {@link PursuitEnemyBehavior#debugMsg} — сама лише
     * резолвить гравця з моба (через {@link PursuitEnemyBehavior#getTrackedPlayer}), щоб виклики
     * нижче не тягали Player окремим параметром. Видалити разом з усіма викликами нижче після
     * завершення тестування копання/будівництва.
     */
    static void debugMsg(Mob mob, String msg) {
        // У файл - ЗАВЖДИ (навіть без відстежуваного гравця) і з тегом моба; в чат - як і раніше, якщо гравець є.
        if (DebugLog.on(DebugLog.Cat.BUILD)
                && DebugLog.throttle("bm|" + mob.getId() + "|" + DebugLog.msgKey(msg), DebugLog.msgInterval(msg))) {
            DebugLog.log(DebugLog.Cat.BUILD, mob, msg);
        }
        Player player = PursuitEnemyBehavior.getTrackedPlayer(mob);
        if (player != null) {
            PursuitEnemyBehavior.sendChatOnly(player, msg);
        }
    }

    /**
     * ДЕБАГ: рядок про рішення/результат будівництва, не частіше разу на 500мс на (моб, текст).
     */
    static void buildLog(Mob mob, String msg) {
        if (DebugLog.on(DebugLog.Cat.BUILD) && DebugLog.throttle("bl|" + mob.getId() + "|" + msg, 500L)) {
            DebugLog.log(DebugLog.Cat.BUILD, mob, msg);
        }
    }

    /**
     * ДЕБАГ: стан кешу шляху моба (для знімка стану).
     */
    public static String debugCacheInfo(Mob mob) {
        NavCache c = NAV_CACHE.get(mob);
        if (c == null) {
            return "NavCache: запису нема";
        }
        boolean computed = c.lastRealComputeTick != Integer.MIN_VALUE;
        return "NavCache: pathTarget=" + c.pathTarget + " canReach=" + c.canReach
                + " недосяжна_підряд=" + c.consecutiveUnreachableTicks
                + " останній_справжній_розрахунок=" + (computed ? (mob.tickCount - c.lastRealComputeTick) + " тіків тому" : "ніколи")
                + " поточний_інтервал_перерахунку=" + (c.consecutiveUnreachableTicks > 0
                ? recomputeIntervalFor(c.consecutiveUnreachableTicks) : 0) + " тіків"
                + " кешований_шлях=" + MobDebug.pathBrief(c.path);
    }

    private EnemyBreak_N_BuildUtils() {
    }

    // Затримка між постановками блоків одним і тим самим мобом - "у ванілі у гравця є задержка
    // перед установкою блока" (короткий per-mob кулдаун, ОКРЕМИЙ від ACTION_COOLDOWN_TICKS
    // конкретних Goal-ів: цей рахується на РІВНІ самого API постановки блоку, тож діє
    // однаково для геть усіх викликів, включно з "аварійними" - ensureFloorUnderneathWhileAirborne
    // у TowerClimbGoal навмисно не чекає на власний кулдаун Goal-у, бо вікно падіння коротке).
    private static final Map<Mob, Long> LAST_PLACE_TICK = new WeakHashMap<>();
    // v21: випадковий розрив (тіків) перед НАСТУПНИМ блоком мосту - перекидається після кожної постановки (4-5 за замовчуванням).
    private static final Map<Mob, Integer> BRIDGE_GAP_TICKS = new WeakHashMap<>();

    public static boolean canOperate(Mob mob) {
        if (!Config.ENABLE_MOB_TERRAFORMING.get()) return false;
        if (!(mob.level() instanceof ServerLevel level)) return false;
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return false;
        if (!PursuitEnemyBehavior.isMemoryChasing(mob)) return false;
        return !PursuitEnemyBehavior.isSearchModeActive(mob);
    }

    /**
     * "Заблоковано" з надбавкою за різницю висот: ціль на 2+ блоки вище вважається заблокованою, якщо до неї немає ні
     * маршруту паркуром, ні розумного звичайного проходу - так тригериться підйом у {@link BuildPathGoal}.
     * {@link DigThroughWallsGoal} висота сама по собі не цікавить (копання не залежить від того,
     * наскільки вище ціль) — там використовується чистіший {@link #isNavigationBlocked} без цієї
     * надбавки. Дві різні назви навмисно — щоб однакова назва не наштовхувала на думку, що це
     * один і той самий тест.
     * <p>
     * <b>v18: будівництво - крайній засіб, тому "заблоковано" лише коли нічого іншого не лишилось.</b>
     * <ol>
     *   <li><b>Паркур має пріоритет.</b> Поки {@link GapJumpAssistGoal} веде моба й ще
     *       {@link TerraformRules#PARKOUR_COOLDOWN_TICKS} тіків після нього - false (раніше будівельник вклинювався між
     *       двома стрибками ланцюжка й ставив блок під стрибок).</li>
     *   <li><b>Grace годують лише справжні обчислення шляху.</b> Тротлінг недосяжної цілі віддає застиглу відповідь до
     *       {@code unreachableRecomputeIntervalTicks}=40 тіків, а grace - 30: стара відповідь сама "дорощувала" grace, хоч
     *       реальність уже змінилась (збій A* на кілька тіків = вежа/міст посеред паркуру). Тепер вердикт змінюється
     *       лише на новому обчисленні, а саме обчислення планується рівно на кінець grace
     *       ({@link NavCache#wantFreshTickPath}), тож реальна перепона й далі дає будівництво через ті самі 30 тіків.</li>
     *   <li><b>Маршрут паркуром ПОПЕРЕДУ</b> (повний, а також частковий - коли A* не дотягнувся до гравця) - не
     *       "заблоковано": спершу пройти його. Виправдання діє, лише поки моб рухається ({@link #parkourRouteAhead}).</li>
     *   <li><b>Звичайний прохід</b> (сходи/схил) до цілі на 2+ вище не потребує вежі, якщо він не нерозумно довгий.</li>
     *   <li>Висота міряється до ЗЕМЛІ під гравцем (та сама ціль, що й у шляху), а не до його живої позиції: гравець, що
     *       підстрибує, додавав до різниці до 1.25 бл.</li>
     * </ol>
     */
    public static boolean isPathBlocked(Mob mob, Vec3 chasePos) {
        TerraformRules.BlockedTracker tracker =
                PATH_BLOCKED_TRACKER.computeIfAbsent(mob, m -> new TerraformRules.BlockedTracker());

        // v18 (1): паркур має пріоритет над будівництвом.
        if (isParkourBusy(mob)) {
            tracker.reset();
            markComputesSeen(mob, tracker);
            gateLog(mob, "path-parkour", () -> "isPathBlocked=false: паркур триває або щойно скінчився (GapJumpAssistGoal) - "
                    + "будівництво не втручається");
            return false;
        }

        BlockPos pathTarget = resolvePathTarget(mob, chasePos);
        // v18: різниця висот - до землі під гравцем, а не до його живої позиції (стрибок гравця не "піднімає" ціль).
        double heightDifference = pathTarget.getY() - mob.getY();

        // Той самий кеш на тік, що й isNavigationBlocked/PursuitEnemyMeleeBehavior.tick()
        // використовують через getOrComputePath - рахуємо це тут БЕЗУМОВНО (а не лише коли
        // rawHeightBlocked=false, як було раніше), щоб сирий сигнал був БЕЗПЕРЕРВНИМ по обидва
        // боки порогу heightDifference=2.0. Зайвого createPath() це не додає: поки моб ще
        // далеко/явно нижче, шлях і так майже завжди недосяжний (rawNavBlocked теж true), а сам
        // виклик кешується на тік спільно з усіма іншими викликачами.
        Path navPath = getOrComputePath(mob, chasePos);
        NavCache cache = NAV_CACHE.get(mob);
        // v18 (2): нового справжнього обчислення від минулого разу нема (застигла відповідь тротлінгу або політ) - це
        // НЕ спостереження: grace не росте, вердикт лишається таким, яким був.
        if (cache == null || cache.computeSeq == tracker.seenComputeSeq()) {
            return tracker.verdict();
        }
        tracker.markSeen(cache.computeSeq);

        boolean navReachable = navPath != null && navPath.canReach();
        boolean heightRaw = heightDifference >= 2.0;
        boolean navRaw = !navReachable;
        boolean rawHeightBlocked = heightRaw;
        boolean rawNavBlocked = navRaw;

        // v17: ціль на 2+ блоки вище НЕ "заблокована", якщо до неї є ДОСЯЖНИЙ шлях зі стрибками (паркур): туди можна
        // дострибати, будувати вежу не треба. v18: те саме для ЧАСТКОВОГО маршруту паркуром (A* не дотягнувся до гравця,
        // але попереду є стрибки) - до його кінця Pursuit іде сам; і для звичайного проходу (сходи/схил).
        boolean routeAhead = (heightRaw || navRaw) && parkourRouteAhead(mob, navPath); // є що виправдовувати
        boolean walkWaiver = false;
        if (routeAhead) {
            rawHeightBlocked = false;
            rawNavBlocked = false;
        } else if (rawHeightBlocked && navReachable
                && TerraformRules.isReasonableWalk(navPath.getNodeCount(), horizontalDistance(mob, pathTarget))) {
            rawHeightBlocked = false;
            walkWaiver = true;
        }
        boolean rawBlockedNow = rawHeightBlocked || rawNavBlocked;

        boolean pastGrace = tracker.observe(cache.lastRealComputeTick, rawBlockedNow, PURSUIT_GRACE_PERIOD_TICKS,
                observationGapLimit());
        scheduleFresh(cache, false, tracker, rawBlockedNow, pastGrace);

        // ТИМЧАСОВИЙ DEBUG: раз/сек - показує сирі причини, виправдання й спільний статус grace-періоду.
        if (DebugLog.on(DebugLog.Cat.BUILD) && DebugLog.throttle("ipb|" + mob.getId(), 1000L)) {
            debugMsg(mob, String.format(
                    "[DEBUG isPathBlocked] висота=%.2f (поріг 2.0, сире=%s) навігація(сире)=%s | виправдання: "
                            + "паркур_попереду=%s звичайний_прохід=%s -> висота=%s навігація=%s | "
                            + "заблоковано_тіків=%d/%d минув_grace=%s",
                    heightDifference, heightRaw, navRaw, routeAhead, walkWaiver, rawHeightBlocked, rawNavBlocked,
                    tracker.blockedTicks(mob.tickCount), PURSUIT_GRACE_PERIOD_TICKS, pastGrace));
        }
        return pastGrace;
    }

    /**
     * v18: чи триває паркур для цього моба: GapJumpAssistGoal активний АБО був активний щойно
     * ({@link TerraformRules#PARKOUR_COOLDOWN_TICKS}). Між двома стрибками ланцюжка й після приземлення ціль на 1-3 тіки
     * неактивна - саме в цю щілину раніше вклинювався будівельник.
     */
    public static boolean isParkourBusy(Mob mob) {
        return GapJumpAssistGoal.wasActiveWithin(mob, TerraformRules.PARKOUR_COOLDOWN_TICKS);
    }

    /**
     * v18: чи можна підкласти блок під мобом, що зараз у повітрі (аварійна підлога). Єдине місце правила для
     * {@link BuildPathGoal} і {@link TowerClimbGoal}; логіка - {@link TerraformRules#allowAirborneFloor}.
     *
     * @param pillaring true, якщо моб саме будує вежу (підстрибує на місці) - тоді підлога в польоті дозволена
     */
    public static boolean allowAirborneFloor(Mob mob, boolean pillaring) {
        return TerraformRules.allowAirborneFloor(mob.onGround(), GapJumpAssistGoal.isActive(mob), pillaring,
                mob.getDeltaMovement().y, mob.fallDistance);
    }

    /**
     * v18: центральний гейт постановки блока. Повертає причину відмови або {@code null}, якщо можна.
     * <ul>
     *   <li>під час паркур-стрибка ({@link GapJumpAssistGoal#isActive}) не ставимо НІЧОГО, крім клатча: блок під моба
     *       посеред стрибка ламає сам стрибок;</li>
     *   <li>клатч після нокбеку не потрібен, поки паркур-політ іде за планом (моб ще вище нижньої межі польоту) - це
     *       звичайний політ, а не падіння.</li>
     * </ul>
     */
    static String placementVeto(Mob mob, PlaceReason reason) {
        if (reason == PlaceReason.CLUTCH) {
            return GapJumpAssistGoal.isFlightOnTrack(mob)
                    ? "паркур-політ іде за планом (моб вище нижньої межі польоту) - клатч не потрібен" : null;
        }
        if (GapJumpAssistGoal.isActive(mob)) {
            return "активний паркур-стрибок (GapJumpAssistGoal): будівництво під час нього заборонене";
        }
        return null;
    }

    /**
     * v18: є ЧАСТКОВИЙ чи повний маршрут паркуром ПОПЕРЕДУ моба, і моб по ньому реально рухається. Тоді "заблоковано"
     * не буває: спершу пройти маршрут. Коли стрибки закінчились (мертвий кінець) або моб завис на
     * {@link TerraformRules#PARKOUR_STALL_TICKS} тіків - виправдання знято, і далі працює звичайний grace.
     */
    private static boolean parkourRouteAhead(Mob mob, Path path) {
        if (!GapJumpUtils.hasJumpAhead(path, mob)) {
            TerraformRules.StallDetector old = PARKOUR_STALL.get(mob);
            if (old != null) {
                old.reset();
            }
            return false;
        }
        TerraformRules.StallDetector stall = PARKOUR_STALL.computeIfAbsent(mob, m -> new TerraformRules.StallDetector());
        boolean stalled = stall.stalled(mob.tickCount, mob.getX(), mob.getY(), mob.getZ(),
                TerraformRules.STALL_MIN_MOVE, TerraformRules.PARKOUR_STALL_TICKS, observationGapLimit());
        if (stalled) {
            gateLog(mob, "route-stalled", () -> "маршрут паркуром попереду є, але моб не рухається "
                    + TerraformRules.PARKOUR_STALL_TICKS + "+ тіків: виправдання знято, працює звичайний grace");
            return false;
        }
        gateLog(mob, "route-ahead", () -> "попереду маршрут паркуром (повний чи частковий): не 'заблоковано', "
                + "спершу пройти його");
        return true;
    }

    private static double horizontalDistance(Mob mob, BlockPos pos) {
        double dx = pos.getX() + 0.5 - mob.getX();
        double dz = pos.getZ() + 0.5 - mob.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Найдовша законна перерва між двома спостереженнями (тротлінг) + запас; довша - серія застаріла.
     */
    private static int observationGapLimit() {
        return Config.UNREACHABLE_RECOMPUTE_INTERVAL_TICKS.get() + 20;
    }

    /** Усі наявні обчислення вважаємо баченими (паркур триває - вони не мають права рахуватись у grace). */
    private static void markComputesSeen(Mob mob, TerraformRules.BlockedTracker tracker) {
        NavCache cache = NAV_CACHE.get(mob);
        if (cache != null) {
            tracker.markSeen(cache.computeSeq);
        }
    }

    /**
     * Просить кеш шляху зробити СВІЖЕ обчислення рівно на кінець grace-періоду: інакше тротлінг (інтервал із конфігу)
     * міг би відкласти вердикт на десятки тіків, а при великому інтервалі - на хвилини.
     */
    private static void scheduleFresh(NavCache cache, boolean forNav, TerraformRules.BlockedTracker tracker,
                                      boolean rawBlocked, boolean pastGrace) {
        int wanted = TerraformRules.NO_TICK;
        if (rawBlocked && !pastGrace && tracker.sinceTick() != TerraformRules.NO_TICK) {
            wanted = tracker.sinceTick() + PURSUIT_GRACE_PERIOD_TICKS;
        }
        if (forNav) {
            cache.wantFreshTickNav = wanted;
        } else {
            cache.wantFreshTickPath = wanted;
        }
    }

    /** Найближчий ТІК у майбутньому (після останнього справжнього обчислення), на який просили свіжий шлях. */
    private static int nextWantedTick(NavCache cache) {
        int last = cache.lastRealComputeTick;
        int best = TerraformRules.NO_TICK;
        int p = cache.wantFreshTickPath;
        if (p != TerraformRules.NO_TICK && p > last) {
            best = p;
        }
        int n = cache.wantFreshTickNav;
        if (n != TerraformRules.NO_TICK && n > last && (best == TerraformRules.NO_TICK || n < best)) {
            best = n;
        }
        return best;
    }

    /** Лог рішень гейта: не частіше разу на секунду на (моб, ключ); рядок будується лише коли лог увімкнено. */
    private static void gateLog(Mob mob, String key, Supplier<String> msg) {
        DebugLog.logThrottled(DebugLog.Cat.BUILD, mob, "gate|" + key + "|" + mob.getId(), 1000L, msg);
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
     * хітбокса, не голий floor координати; для гравця в ПОВІТРІ - {@link PursuitTargetResolver}) — АЛЕ тільки коли {@link PursuitEnemyBehavior#isLiveChasing}
     * (інакше під час GOING_TO_LAST_SEEN/SEARCHING це підмінило б НАВМИСНО застиглу точку живою
     * позицією гравця — саме така підміна ламала "переслідування по пам'яті").
     */
    private static BlockPos resolvePathTarget(Mob mob, Vec3 chasePos) {
        BlockPos naiveTarget = BlockPos.containing(chasePos);
        if (!PursuitEnemyBehavior.isLiveChasing(mob)) {
            return naiveTarget;
        }
        Player trackedPlayer = PursuitEnemyBehavior.getTrackedPlayer(mob);
        // v15.2: getOnPos() лишається для гравця на землі, але в ПОВІТРІ він бреше (береться клітинка за центром, що на краю
        // блока опиняється над порожнечею) - тож ціль для стрибаючого гравця стабілізує PursuitTargetResolver.
        return trackedPlayer != null ? PursuitTargetResolver.groundedTarget(mob, trackedPlayer) : naiveTarget;
    }

    /**
     * Прогресивний бекоф: перші {@link #UNREACHABLE_FAST_RETRY_TICKS} тіків недосяжності - кожен
     * тік (раптом перепона щойно зникла), далі - раз на {@link Config#UNREACHABLE_RECOMPUTE_INTERVAL_TICKS}.
     * Немає сенсу робити це складнішим (більше сходинок бекофу) - нам потрібно лише не бити по CPU
     * щотіку заради цілі, яка вже довго недосяжна, а не ідеальну криву витрат.
     */
    private static final int UNREACHABLE_FAST_RETRY_TICKS = 5;
    /**
     * v20: скільки "свіжих шляхів" за тік дозволено на ВЕСЬ сервер (для проби стрибка вежі).
     */
    private static final int MAX_FRESH_PATH_REQUESTS_PER_TICK = 2;

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
     * <p>
     * v18: ті самі правила, що й у {@link #isPathBlocked}: паркур має пріоритет, grace годують лише справжні
     * обчислення шляху, маршрут паркуром попереду виправдовує очікування.
     */
    public static boolean isNavigationBlocked(Mob mob, Vec3 chasePos) {
        TerraformRules.BlockedTracker tracker =
                NAV_BLOCKED_TRACKER.computeIfAbsent(mob, m -> new TerraformRules.BlockedTracker());

        if (isParkourBusy(mob)) {
            tracker.reset();
            markComputesSeen(mob, tracker);
            return false;
        }

        Path path = getOrComputePath(mob, chasePos);
        NavCache cache = NAV_CACHE.get(mob);
        if (cache == null || cache.computeSeq == tracker.seenComputeSeq()) {
            return tracker.verdict();
        }
        tracker.markSeen(cache.computeSeq);

        boolean rawBlocked = path == null || !path.canReach();
        boolean routeAhead = rawBlocked && parkourRouteAhead(mob, path);
        if (routeAhead) {
            rawBlocked = false;
        }
        boolean pastGrace = tracker.observe(cache.lastRealComputeTick, rawBlocked, PURSUIT_GRACE_PERIOD_TICKS,
                observationGapLimit());
        scheduleFresh(cache, true, tracker, rawBlocked, pastGrace);

        // ТИМЧАСОВИЙ DEBUG: тільки коли RAW заблоковано (щоб не спамити щотіку в межах
        // grace-періоду). Дублювання між Goal-ами того самого тіку більше нема - getOrComputePath
        // сам не рахує двічі, а лог "[PATH DEBUG]" усередині нього теж спрацьовує лише раз.
        if (rawBlocked) {
            BlockPos pathTarget = resolvePathTarget(mob, chasePos);
            BlockPos naiveTarget = BlockPos.containing(chasePos);
            Player trackedPlayer = PursuitEnemyBehavior.getTrackedPlayer(mob);
            boolean naivePassable = isPassableColumn(mob.level(), naiveTarget);
            boolean pathTargetPassable = isPassableColumn(mob.level(), pathTarget);
            int duration = tracker.blockedTicks(mob.tickCount);
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

        return pastGrace;
    }

    public static BlockPos findNearestOpenArea(Mob mob, Level level, Vec3 chasePos) {
        // Той самий getOnPos()-фікс, що й у isNavigationBlocked (через resolvePathTarget) - і та
        // сама умова isLiveChasing: не підміняти застиглу GOING_TO_LAST_SEEN/SEARCHING точку
        // живою позицією гравця.
        BlockPos center = resolvePathTarget(mob, chasePos);
        BlockPos mobPos = feetCell(mob);

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
    private static long freshPathBudgetTick = Long.MIN_VALUE;
    private static int freshPathBudgetUsed = 0;

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
        BlockPos mobPos = feetCell(mob);
        int dx = target.getX() - mobPos.getX();
        int dz = target.getZ() - mobPos.getZ();
        if (dx == 0 && dz == 0) return mobPos;

        if (Math.abs(dx) >= Math.abs(dz)) {
            return mobPos.offset(Integer.signum(dx), 0, 0);
        }
        return mobPos.offset(0, 0, Integer.signum(dz));
    }

    /**
     * v19: клітинка, у якій РЕАЛЬНО стоять ноги моба. Для звичайного моба це {@code blockPosition()}, але на блоці НИЖЧОМУ
     * за 1 (мед 15/16, пісок душ 14/16, плита 8/16, грядка, стежка...) ноги лишаються всередині клітинки самої опори:
     * {@code blockPosition()} - це клітинка МЕДУ, а не повітря над ним. Увесь будівельний код брав {@code blockPosition()}
     * за "рівень ніг" і {@code .below()} за "підлогу", тож для моба на меді "підлогою" виходив блок ПІД медом, а міст
     * будувався на блок нижче за мед (моб сходив із меду вниз і стрибав уже з булижника - зі звичайною силою стрибка).
     * <p>
     * На землі рівень ніг береться з ОПОРИ під хітбоксом ({@code getOnPos()}): клітинка над нею, якщо колізія опори
     * вміщується в клітинку. Стовпчик (x,z) лишається тим самим, що в {@code blockPosition()}, тож випадок v16 (центр моба
     * вже за краєм опори) не змінюється. У польоті: якщо ноги ще "в" клітинці з колізією, але на її верхівці або вище
     * (моб щойно відірвався від меду) - теж клітинка над нею. Це те саме, що {@code player.getOnPos().above()}, яким уже
     * рахується ціль гравця ({@link PursuitTargetResolver}), тож моб і ціль порівнюються в одній системі координат.
     */
    public static BlockPos feetCell(Mob mob) {
        BlockPos pos = mob.blockPosition();
        Level level = mob.level();
        int y = pos.getY();
        if (mob.onGround()) {
            BlockPos support = mob.getOnPos();
            VoxelShape shape = level.getBlockState(support).getCollisionShape(level, support);
            double top = shape.isEmpty() ? Double.NaN : shape.bounds().maxY;
            y = TerraformRules.feetCellY(true, pos.getY(), support.getY(), top);
        } else {
            VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (!shape.isEmpty()
                    && TerraformRules.feetAtOrAboveCollisionTop(mob.getY(), pos.getY(), shape.bounds().maxY)) {
                y = pos.getY() + 1;
            }
        }
        return y == pos.getY() ? pos : new BlockPos(pos.getX(), y, pos.getZ());
    }

    /**
     * v19: клітинка під ногами моба, яку треба заповнити, щоб він не впав - або {@code null}, якщо опора вже є (під ногами
     * суцільний блок, АБО моб на/щойно відірвався від меду/піску душ/плити: їхня клітинка - це і є опора).
     */
    public static BlockPos floorCellToFill(Level level, Mob mob) {
        BlockPos below = feetCell(mob).below();
        return level.getBlockState(below).isSolid() ? null : below;
    }

    /**
     * Та сама умова, що раніше жила приватно всередині {@link BuildPathGoal} — винесена сюди,
     * щоб {@link TowerClimbGoal} перевіряла "це підйом?" ІДЕНТИЧНО, а не своєю копією порогу.
     */
    public static boolean needsClimb(Mob mob, BlockPos target) {
        return target.getY() - feetCell(mob).getY() >= CLIMB_HEIGHT_THRESHOLD;
    }

    public static boolean isBreakable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.isSolid()) return false;
        if (state.getDestroySpeed(level, pos) < 0) return false;
        return !state.hasBlockEntity();
    }

    public static void breakBlock(Level level, BlockPos pos, Mob mob) {
        BlockState state = level.getBlockState(pos);

        // v20: блок, який ПОСТАВИВ сам мод (тимчасовий), НЕ потрапляє в "інвентар" моба. Дроп інвентаря при смерті раніше не
        // працював (обробник події був не підписаний - див. modid), а тепер працює: без цієї перевірки моб міг би ставити
        // булижник "з повітря", ламати власний міст - і по смерті висипати його гравцеві (безкоштовний фарм).
        TemporaryBlockData temporary = level instanceof ServerLevel serverLevel ? TemporaryBlockData.get(serverLevel) : null;
        boolean ownTemporaryBlock = temporary != null && temporary.isTracked(pos);

        ItemStack drop = new ItemStack(state.getBlock().asItem());
        if (!ownTemporaryBlock && !drop.isEmpty() && mob instanceof IMobBlockStorage storage) {
            storage.addDugBlock(drop);
        }

        level.playSound(null, pos, state.getSoundType().getBreakSound(), SoundSource.HOSTILE, 1.0F, 1.0F);
        level.destroyBlock(pos, false);
        if (ownTemporaryBlock) {
            temporary.untrack(pos);
        }
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
     * Повертає закешований (на цей тік, для цієї цілі) {@link Path}, рахуючи {@code createPath()}
     * лише якщо ще не рахували. Викликається і з {@link #isNavigationBlocked}, і напряму з
     * {@code PursuitEnemyMeleeBehavior.tick()} — щоб реальний рух ішов по ТОМУ САМОМУ шляху, який
     * щойно перевірили на прохідність, а не рахував свій окремий виклик createPath() поверх.
     */
    public static Path getOrComputePath(Mob mob, Vec3 chasePos) {
        BlockPos pathTarget = resolvePathTarget(mob, chasePos);
        NavCache cache = NAV_CACHE.computeIfAbsent(mob, m -> new NavCache());

        // v14 (v18: для БУДЬ-ЯКОГО польоту, не лише паркурового) - у ПОВІТРІ шлях не рахуємо. Поки моб у повітрі, стартова
        // точка A* (getStart для !onGround) "провалюється" вниз - до ями/землі під розривом, тож шлях виходить про ІНШЕ
        // місце (часто недосяжний; можливо, ванільна навігація в повітрі взагалі не рахує шлях - це не перевірялось). BuildPath/Dig/TowerClimb/
        // Pursuit опитують цей метод щотіку й під час польоту теж, лічильник недосяжності ріс, і ПІСЛЯ приземлення
        // тротлінг (unreachableRecomputeIntervalTicks) віддавав цей застарілий шлях: ланцюжок стрибків обривався, моб
        // стояв до наступного справжнього перерахунку (~1 с), а сигнал "недосяжно" запускав будівництво. У v14 це було
        // закрито лише для GapJumpAssistGoal; але так само отруює кеш звичайний гоп на сходинку, падіння й нокбек.
        // У воді/лаві шлях рахується як і раніше (там createPath() працює).
        if (!mob.onGround() && !mob.isInWaterOrBubble() && !mob.isInLava()) {
            return cache.path;
        }

        // v20: вежа просила свіжий шлях (requestFreshPath) - рівно один справжній перерахунок мимо тіку-кешу й тротлінгу.
        boolean forced = cache.forceFresh;
        cache.forceFresh = false;

        if (!forced && cache.tickComputed == mob.tickCount && pathTarget.equals(cache.pathTarget)) {
            return cache.path;
        }

        // v12 - ТРОТЛІНГ для недосяжної цілі (той самий пункт "Б", що ми давно узгодили, просто
        // руки дійшли тільки зараз - виявлено користувачем через лаг: GapJumpNodeEvaluator на
        // широкому непрохідному розриві щотіку вичерпно обшукує околицю, перш ніж здатися, а
        // createPath() тут рахувався КОЖЕН тік без винятку, поки моб переслідує - 20 повних
        // пошуків на секунду замість одного. targetChanged скидає троттлінг одразу: якщо ціль
        // (округлена до блока) зрушила - стара застигла відповідь може бути геть не про те.
        boolean targetChanged = !pathTarget.equals(cache.pathTarget);
        if (!forced && !targetChanged && cache.consecutiveUnreachableTicks > 0) {
            int interval = recomputeIntervalFor(cache.consecutiveUnreachableTicks);
            // v18: isPathBlocked/isNavigationBlocked просять свіжий шлях рівно на кінець grace-періоду - інакше вердикт
            // чекав би на наступне планове обчислення (інтервал із конфігу, до хвилин).
            int wanted = nextWantedTick(cache);
            if (wanted != TerraformRules.NO_TICK) {
                int untilWanted = wanted - cache.lastRealComputeTick;
                if (untilWanted > 0 && untilWanted < interval) {
                    interval = untilWanted;
                }
            }
            if (mob.tickCount - cache.lastRealComputeTick < interval) {
                cache.tickComputed = mob.tickCount; // той самий тік - не рахувати ще раз із getOrComputePath()
                if (DebugLog.on(DebugLog.Cat.PATH) && DebugLog.throttle("path-skip-" + mob.getId(), 1000L)) {
                    DebugLog.log(DebugLog.Cat.PATH, mob, "перерахунок шляху ПРОПУЩЕНО (тротлінг недосяжної цілі): недосяжна_підряд="
                            + cache.consecutiveUnreachableTicks + " інтервал=" + interval + " минуло="
                            + (mob.tickCount - cache.lastRealComputeTick) + " -> віддаю ЗАСТИГЛИЙ кеш: "
                            + MobDebug.pathBrief(cache.path));
                }
                return cache.path; // та сама (застигла) відповідь, БЕЗ нового createPath()
            }
        }

        Path path = mob.getNavigation().createPath(pathTarget, 0);
        cache.lastRealComputeTick = mob.tickCount;
        cache.computeSeq++;
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
        if (DebugLog.on(DebugLog.Cat.PATH)) {
            MobDebug.logPathIfChanged(mob, pathTarget, path, cache.consecutiveUnreachableTicks);
        }
        return path;
    }

    private static boolean isPassableColumn(Level level, BlockPos pos) {
        return !level.getBlockState(pos).isSolid()
                && !level.getBlockState(pos.above()).isSolid()
                && level.getBlockState(pos.below()).isSolid();
    }

    /**
     * Ставить блок від імені {@code mob}. Перевірки, усі "тихі" (просто нічого не робить,
     * якщо не пройшли) — знайдені живим тестуванням:
     * <ol>
     *   <li>v18: гейт контексту ({@link #placementVeto}) - під час паркур-стрибка нічого, крім клатча, не ставимо;</li>
     *   <li>НЕ переписує вже існуючий (не-replaceable) блок — {@code canBeReplaced()}, той самий
     *       критерій, що й у ванільного розміщення, а не вужчий {@code !isSolid()} (який
     *       пропускав, наприклад, квіти/факели/рейки — вони не "solid", але явно не порожні).</li>
     *   <li>v21: темп - ПЕРШОЮ перевіркою, і залежить від ПРИЧИНИ ({@link #canPlaceNow}, {@link TerraformRules#placeGapTicks}):
     *       міст - випадкові 4-5 тіків, вежа/стіна - {@link Config#TOWER_PLACE_DELAY_TICKS}, підлога піларингу - 1 тік,
     *       решта - {@link Config#MOB_PLACE_DELAY_TICKS}. Блоки ставляться ПО ОДНОМУ: повертає true лише коли блок реально поставлено.</li>
     *   <li>{@link #hasAdjacentSolid} — інакше не ставить.</li>
     *   <li>v19: НЕ у власний хітбокс. Гравець не може поставити блок туди, де стоїть сам; моб теж. Якщо стрибок звільнить
     *       клітинку (верх клітинки не вище ~1 блока над ногами) - моб підстрибує, і ціль повторює спробу в польоті
     *       ({@link #retryPendingPlacement}); якщо ні (стіна по голову) - блок відкладається.</li>
     *   <li>v19: лише те, що моб БАЧИТЬ напряму: промінь від очей до клітинки не мусить упиратись в чужий блок
     *       ({@link #hasDirectSightOfCell}). Це відсікає, зокрема, блок ПІД опорою, на якій моб стоїть.</li>
     * </ol>
     *
     * @param reason навіщо ставимо (див. {@link PlaceReason}); потрапляє в лог, і від нього залежить гейт
     */
    public static boolean placeBlock(ServerLevel level, BlockPos pos, Mob mob, PlaceReason reason) {
        long now = level.getGameTime();
        // v21: темп - найдешевша перевірка, тому ПЕРШОЮ: цілі кличуть placeBlock щотіку, а блок можна не частіше, ніж раз на
        // кілька тіків (вежа/стіна - towerPlaceDelayTicks, міст - 4-5 тіків). Тиха відмова без запису в лог.
        if (!canPlaceNow(mob, reason, now)) {
            return false;
        }
        String veto = placementVeto(mob, reason);
        if (veto != null) {
            buildLog(mob, "placeBlock[" + reason + "](" + pos.toShortString() + ") ВІДМОВА (гейт): " + veto);
            return false;
        }
        // ДЕБАГ: кожна "тиха" відмова тепер лишає слід у лозі - раніше placeBlock мовчки робив return.
        BlockState existing = level.getBlockState(pos);
        if (!existing.canBeReplaced()) {
            buildLog(mob, "placeBlock[" + reason + "](" + pos.toShortString() + ") ВІДМОВА: клітинка не замінювана: " + existing);
            return false;
        }
        if (!hasAdjacentSolid(level, pos)) {
            buildLog(mob, "placeBlock[" + reason + "](" + pos.toShortString() + ") ВІДМОВА: нема суміжного isSolid()-блока для опори");
            return false;
        }

        // v19 (1): власний хітбокс.
        if (mobHitboxOverlaps(mob, pos)) {
            deferForOwnHitbox(mob, pos, reason);
            return false;
        }
        // v19 (2): пряма видимість.
        if (!hasDirectSightOfCell(level, mob, pos)) {
            buildLog(mob, "placeBlock[" + reason + "](" + pos.toShortString()
                    + ") ВІДМОВА: клітинка не на прямій видимості (між очима моба й нею є блок)");
            return false;
        }

        Block block = DigBlockResolver.getDigBlock(level);
        BlockState state = block.defaultBlockState();

        level.setBlock(pos, state, 3);
        level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.HOSTILE, 1.0F, 1.0F);
        LAST_PLACE_TICK.put(mob, now);
        // v21: розрив перед наступним блоком мосту - новий випадковий з [min..max] (щоб ритм не був механічним)
        BRIDGE_GAP_TICKS.put(mob, TerraformRules.rollBridgeGap(Config.BRIDGE_PLACE_DELAY_MIN_TICKS.get(),
                Config.BRIDGE_PLACE_DELAY_MAX_TICKS.get(), mob.getRandom().nextDouble()));
        PendingPlacement pending = PENDING_PLACEMENT.get(mob);
        if (pending != null && pending.pos.equals(pos)) {
            PENDING_PLACEMENT.remove(mob);
        }
        if (DebugLog.on(DebugLog.Cat.BUILD)) {
            DebugLog.log(DebugLog.Cat.BUILD, mob, "placeBlock[" + reason + "] ПОСТАВЛЕНО " + state.getBlock().getDescriptionId()
                    + " у " + pos.toShortString() + " | моб=" + mob.position() + " onGround=" + mob.onGround()
                    + " паркур_активний=" + GapJumpAssistGoal.isActive(mob)
                    + " | поруч: " + MobDebug.cellBrief(level, pos));
        }

        long expireAt = now + (long) Config.PLACED_BLOCK_LIFETIME_SECONDS.get() * 20L;
        TemporaryBlockData.get(level).track(pos, expireAt, state.getBlock()); // v20: пам'ятаємо, ЩО поставили
        return true;
    }

    /**
     * v21: чи минув мінімальний розрив після ПОПЕРЕДНЬОЇ постановки блока цим мобом - для цієї ПРИЧИНИ
     * ({@link TerraformRules#placeGapTicks}). Дешева перевірка без доступу до світу: цілі можуть питати щотіку й не
     * робити зайвої роботи між блоками.
     */
    public static boolean canPlaceNow(Mob mob, PlaceReason reason, long now) {
        Long lastTick = LAST_PLACE_TICK.get(mob);
        if (lastTick == null) {
            return true;
        }
        return now - lastTick >= requiredGapTicks(mob, reason);
    }

    /**
     * v19: чи бачить моб клітинку напряму - промінь від очей до бодай однієї з {@link TerraformRules#LOS_SAMPLES} (центр і
     * по точці біля кожної грані) не впирається в блок. Той самий чистий рейкаст по блоках, що й
     * {@code SearchGrid.hasDirectLineOfSight}: колізія, без рідин.
     */
    static boolean hasDirectSightOfCell(Level level, Mob mob, BlockPos pos) {
        Vec3 eye = mob.getEyePosition();
        for (double[] s : TerraformRules.LOS_SAMPLES) {
            Vec3 to = new Vec3(pos.getX() + s[0], pos.getY() + s[1], pos.getZ() + s[2]);
            ClipContext ctx = new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob);
            if (level.clip(ctx).getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    /**
     * v19: блок не можна поставити, бо клітинку перетинає хітбокс самого моба. Якщо стрибок її звільнить - підстрибуємо й
     * запам'ятовуємо блок: ціль повторить спробу, поки моб у повітрі ({@link #retryPendingPlacement}). Клатч не
     * відкладаємо (він і так у польоті).
     */
    private static void deferForOwnHitbox(Mob mob, BlockPos pos, PlaceReason reason) {
        String head = "placeBlock[" + reason + "](" + pos.toShortString() + ") ";
        double lift = (pos.getY() + 1.0) - mob.getBoundingBox().minY;
        if (reason == PlaceReason.CLUTCH || !TerraformRules.jumpClears(lift)) {
            buildLog(mob, head + "ВІДМОВА: хітбокс моба перетинає клітинку, і стрибок її не звільнить (треба піднятись на "
                    + String.format("%.2f", lift) + " бл)");
            return;
        }
        Integer giveUpUntil = HITBOX_GIVE_UP_UNTIL.get(mob);
        if (giveUpUntil != null && mob.tickCount < giveUpUntil) {
            buildLog(mob, head + "ПАУЗА: стрибки заради хітбокса вже не допомогли, чекаю " + (giveUpUntil - mob.tickCount) + " тіків");
            return;
        }
        PendingPlacement pending = PENDING_PLACEMENT.get(mob);
        if (pending == null || !pending.pos.equals(pos)) {
            pending = new PendingPlacement(pos.immutable(), reason, mob.tickCount);
            PENDING_PLACEMENT.put(mob, pending);
        }
        if (!mob.onGround()) {
            buildLog(mob, head + "ЧЕКАЮ: хітбокс моба ще перетинає клітинку, моб у повітрі (треба піднятись на "
                    + String.format("%.2f", lift) + " бл)");
            return;
        }
        if (pending.jumps >= TerraformRules.PENDING_MAX_JUMPS) {
            PENDING_PLACEMENT.remove(mob);
            HITBOX_GIVE_UP_UNTIL.put(mob, mob.tickCount + TerraformRules.PENDING_GIVE_UP_COOLDOWN_TICKS);
            buildLog(mob, head + "ВІДМОВА: хітбокс моба заважає навіть після " + TerraformRules.PENDING_MAX_JUMPS
                    + " стрибків (пауза " + TerraformRules.PENDING_GIVE_UP_COOLDOWN_TICKS + " тіків)");
            return;
        }
        pending.jumps++;
        // Без спринту: ванільний стрибок у спринті додає поштовх УПЕРЕД, а мобу треба підстрибнути на місці (він може стояти
        // на краю меду - не зістрибнути з нього).
        mob.setSprinting(false);
        mob.getJumpControl().jump();
        buildLog(mob, head + "ВІДКЛАДЕНО: хітбокс моба перетинає клітинку - підстрибую (спроба " + pending.jumps + "), поставлю в польоті");
    }

    /**
     * v19: повторює відкладений через хітбокс блок. Викликається з tick() цілей будівництва ({@link BuildPathGoal},
     * {@link TowerClimbGoal}): у стрибка на меді вікно в повітрі ~5 тіків, а власний кулдаун цілі - 10, тож без цього
     * повтору спроба щоразу потрапляла б на момент, коли моб уже приземлився.
     */
    public static void retryPendingPlacement(ServerLevel level, Mob mob) {
        PendingPlacement pending = PENDING_PLACEMENT.get(mob);
        if (pending == null) {
            return;
        }
        if (mob.tickCount - pending.createdTick > TerraformRules.PENDING_PLACEMENT_TTL_TICKS) {
            PENDING_PLACEMENT.remove(mob);
            return;
        }
        placeBlock(level, pending.pos, mob, pending.reason);
    }

    /**
     * v20: просить, щоб НАСТУПНИЙ {@link #getOrComputePath} порахував шлях справжнім A* - мимо кешу цього тіку й тротлінгу
     * недосяжної цілі (рівно один раз); повертає false, якщо загальний бюджет запитів цього тіку вичерпано. Для вежі: шлях до гравця міг "з'явитись" (стрибком) щойно після нового шару, а
     * тротлінг (до 40 тіків) змусив би моба ще кілька шарів будувати даремно. Вердикт "заблоковано" від цього сам не
     * змінюється - його, як і раніше, дає {@link #isPathBlocked} за новим обчисленням.
     */
    public static boolean requestFreshPath(Mob mob) {
        // Загальний бюджет: проба - це справжній A* зі стрибковим евалюатором, а башт одночасно може бути багато (орда).
        long now = mob.level().getGameTime();
        if (now != freshPathBudgetTick) {
            freshPathBudgetTick = now;
            freshPathBudgetUsed = 0;
        }
        if (freshPathBudgetUsed >= MAX_FRESH_PATH_REQUESTS_PER_TICK) {
            return false; // бюджет цього тіку вичерпано - викликач спробує наступного разу
        }
        freshPathBudgetUsed++;
        NAV_CACHE.computeIfAbsent(mob, m -> new NavCache()).forceFresh = true;
        return true;
    }

    private static int requiredGapTicks(Mob mob, PlaceReason reason) {
        Integer rolled = BRIDGE_GAP_TICKS.get(mob);
        int bridgeGap = rolled != null ? rolled : Config.BRIDGE_PLACE_DELAY_MIN_TICKS.get();
        return TerraformRules.placeGapTicks(reason, Config.MOB_PLACE_DELAY_TICKS.get(),
                Config.TOWER_PLACE_DELAY_TICKS.get(), bridgeGap);
    }

    /**
     * v19: чи перетинає хітбокс моба клітинку {@code pos} (як повний куб - це блок, який ставить мод).
     */
    static boolean mobHitboxOverlaps(Mob mob, BlockPos pos) {
        AABB box = mob.getBoundingBox();
        return TerraformRules.boxesOverlap(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
    }

    /**
     * v21: номер останнього СПРАВЖНЬОГО обчислення шляху цього моба (0 - ще не було). Росте лише на новому createPath() -
     * за ним вежа бачить, що проба стрибка ({@link #requestFreshPath}) уже виконана.
     */
    public static int getComputeSeq(Mob mob) {
        NavCache cache = NAV_CACHE.get(mob);
        return cache == null ? 0 : cache.computeSeq;
    }

    /**
     * v21: клітинка, до якої рахується шлях (ноги гравця на землі) - та сама, що в {@link #isPathBlocked}.
     */
    public static BlockPos pathTargetCell(Mob mob, Vec3 chasePos) {
        return resolvePathTarget(mob, chasePos);
    }

    /** v20: лог проби "чи вже є маршрут стрибком" - у файл (BUILD), без чату, не частіше разу на секунду. */
    public static void logJumpProbe(Mob mob, String text) {
        DebugLog.logThrottled(DebugLog.Cat.BUILD, mob, "jump-probe|" + mob.getId(), 1000L, () -> text);
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
        // v18: лічильник СПРАВЖНІХ обчислень - isPathBlocked/isNavigationBlocked бачать лише нові (див. вище).
        int computeSeq = 0;
        // v18: на який тік (мобів tickCount) isPathBlocked / isNavigationBlocked просять свіже обчислення - рівно на кінець
        // свого grace-періоду, щоб вердикт не залежав від інтервалу тротлінгу. NO_TICK = не просять.
        int wantFreshTickPath = TerraformRules.NO_TICK;
        int wantFreshTickNav = TerraformRules.NO_TICK;
        // v20: наступний getOrComputePath зобов'язаний порахувати шлях СПРАВЖНЬО (див. requestFreshPath).
        boolean forceFresh = false;
    }

    /** v19: блок, відкладений через власний хітбокс моба. */
    private static final class PendingPlacement {
        final BlockPos pos;
        final PlaceReason reason;
        final int createdTick;
        int jumps;

        PendingPlacement(BlockPos pos, PlaceReason reason, int createdTick) {
            this.pos = pos;
            this.reason = reason;
            this.createdTick = createdTick;
        }
    }
}
