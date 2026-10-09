package com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.PursuitBehavior;

import com.example.examplemod.Enemy.EnemyBehavior.EnemyPursuit_N_Search.Search_Grid.SearchGrid;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.Run_N_JumpUtils;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Глобальна "пам'ять про ціль" для всіх злих мобів (зомбі, скелети, пігліни, розбійники тощо).
 * <p>
 * Логіка (4 стани для пари моб-гравець):
 * <ol>
 *   <li><b>CHASING</b> — гравець знаходиться в межах ПОВНОГО {@code Attributes.FOLLOW_RANGE}
 *       моба. Незалежно від того, чи є пряма видимість (навіть за стіною) — моб переслідує
 *       ЖИВУ позицію гравця крізь стіни. Лук/арбалет можуть тягнутись (анімація натягу), але
 *       фактичний виліт снаряда лишається заблокованим звичайним canSee — за це відповідає
 *       {@link #isMemoryChasing(Mob)}, який треба перевіряти поряд з canSee в Goal-ах стрільби.
 *       У ЦЬОМУ (і ТІЛЬКИ цьому) стані щотіку перевіряється, чи нема ІНШОГО гравця, який зараз
 *       і БЛИЖЧЕ за поточного відстежуваного, і видимий мобу напряму (canSee, тобто не за
 *       стіною) — якщо є, ціль миттєво перемикається на нього, див. {@link #findCloserVisiblePlayer}.
 *       Гравець за стіною НІКОЛИ не перехоплює агро цим шляхом, навіть якщо геометрично ближче:
 *       "найближчий" тут рахується лише серед видимих напряму. GOING_TO_LAST_SEEN і SEARCHING
 *       нижче цю перевірку не викликають узагалі — перемикання цілі стосується виключно
 *       "режиму атаки", коли моб реально йде бити гравця в межах радіуса.</li>
 *   <li><b>GOING_TO_LAST_SEEN</b> — гравець вийшов за межі ПОВНОГО FOLLOW_RANGE. Моб іде до
 *       ЗАСТИГЛОЇ точки — там, де гравець був у момент виходу (позиція більше НЕ оновлюється
 *       живою позицією гравця).</li>
 *   <li><b>SEARCHING</b> — моб дійшов до застиглої точки і там нікого немає. ТІЛЬКИ для мобів
 *       без накопиченого заряду (скелет/stray/bogged, дроунд із тризубом — НЕ арбалетники, НЕ
 *       блейз/гаст): протягом {@link #SEARCH_DURATION_TICKS} (15с) моб никається через
 *       {@link SearchGrid} — динамічно "стрибає" по колу навколо своєї поточної позиції в
 *       невідвідані напрямки (в межах FOLLOW_RANGE від початкової точки), не відпускаючи
 *       натягнуту тетиву ({@link #shouldDrawBowstring(Mob)} лишається true). Якщо нема куди йти
 *       (вся зона в межах радіуса вичерпана) — забуває ціль одразу, не чекаючи таймера.</li>
 *   <li><b>FORGOTTEN</b> — час пошуку вийшов без повторного бачення. Повне забуття:
 *       {@code mob.setTarget(null)}, контроль повертається звичайному AI.</li>
 * </ol>
 * У БУДЬ-ЯКОМУ з трьох "не-CHASING" станів — якщо моб реально побачить гравця (справжній
 * ванільний canSee) — миттєвий перехід назад в CHASING з живою позицією (тетива вже натягнута,
 * якщо була, тож постріл може статись одразу).
 * <p>
 * Перше "побачив хоч раз" лишається повністю ванільним — цю систему запускає лише той факт,
 * що {@code mob.getTarget()} вже є гравцем (а ціль гравцю ванільно встановлюється тільки коли
 * реальна видимість справді була).
 */
public class PursuitEnemyBehavior extends Goal {

    /**
     * Дистанція (у блоках), на якій моб ще йде до застиглої точки, але вже починає тягнути тетиву.
     */
    private static final double DRAW_BOWSTRING_DISTANCE_SQ = 64.0; // 8 блоків

    /**
     * Тривалість "никання" біля останньої точки, перш ніж остаточне забуття.
     */
    // Було 9999 * 20 (~2.78 год) — саме те тестове число, про яке попереджає коментар у
    // SearchGrid (staleAfterTicks). Джавадок стану SEARCHING вище прямо каже "(15с)" — це
    // й був задум, просто забули повернути після тестів.
    private static final int SEARCH_DURATION_TICKS = 15 * 20;
    /**
     * Запас поверх SEARCH_DURATION_TICKS, який передається в GlobalSearchGrid як staleAfterTicks
     * (див. {@link SearchGrid#SearchGrid(long)}) — щоб позначки "прочекано" не протухали за
     * кілька тіків ДО завершення того самого пошуку, який їх поставив (був баг: раніше
     * GlobalSearchGrid тримав своє власне число, і воно розійшлось із цим після зміни
     * SEARCH_DURATION_TICKS для тестів).
     */
    private static final long STALE_MARGIN_TICKS = 5 * 20;
    /**
     * Стан пам'яті прив'язаний до конкретного моба (WeakHashMap — записи самі зникають,
     * коли моб деспавнився/вивантажився і на нього більше немає сильних посилань).
     */
    private static final Map<Mob, MemoryData> MEMORY = new WeakHashMap<>();
    private final Mob mob;
    private final boolean supportsSearchBehavior;

    public PursuitEnemyBehavior(Mob mob) {
        this(mob, false);
    }

    public PursuitEnemyBehavior(Mob mob, boolean supportsSearchBehavior) {
        this.mob = mob;
        this.supportsSearchBehavior = supportsSearchBehavior;
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    /**
     * Чи зараз цей моб переслідує гравця "крізь стіни" по пам'яті (CHASING або
     * GOING_TO_LAST_SEEN). Виклич це з Goal-ів стрільби поряд з уже наявним canSee: якщо true
     * і canSee == false, лук/арбалет можна тримати натягнутим, але стріляти НЕЛЬЗЯ.
     */
    public static boolean isMemoryChasing(Mob mob) {
        MemoryData data = MEMORY.get(mob);
        return data != null && data.state != State.FORGOTTEN;
    }

    /**
     * Специфічно для терраформінгу (копання/будівництво): ці дії мають діяти під час CHASING і
     * GOING_TO_LAST_SEEN, але НЕ під час SEARCHING (мобу не варто "відволікатись" на прокопку
     * випадкової печери замість того, щоб спокійно пройти й нормально пошукати). Один
     * {@code isMemoryChasing} для цього недостатній — він true для ВСІХ трьох станів одразу.
     */
    public static boolean isSearchModeActive(Mob mob) {
        MemoryData data = MEMORY.get(mob);
        return data != null && data.state == State.SEARCHING;
    }

    /**
     * Чи {@link #getChasePosition} зараз повертає ЖИВУ позицію гравця (стан CHASING), на
     * відміну від застиглої точки (GOING_TO_LAST_SEEN — {@code lastSeenPos}, SEARCHING —
     * {@code searchPoint}). Потрібно там, де хочеться "уточнити" позицію через щось живе
     * (наприклад {@code Player#getOnPos()}) — таке уточнення коректне лише під час CHASING;
     * підставляти живу позицію гравця замість НАВМИСНО застиглої точки під час
     * GOING_TO_LAST_SEEN — це і є той бага, який ламав "переслідування по пам'яті".
     */
    public static boolean isLiveChasing(Mob mob) {
        MemoryData data = MEMORY.get(mob);
        return data != null && data.state == State.CHASING;
    }

    /**
     * Гравець, якого зараз реально тримає ця система пам'яті (тобто {@code data.trackedPlayer}),
     * АБО {@code null}, якщо системи немає чи вона вже в стані FORGOTTEN (тобто "забула" — далі
     * рішення повністю за звичайним ванільним/Brain-таргетингом мобу).
     * <p>
     * Призначення: для мобів, чия БОЙОВА логіка керується НЕ через {@code mob.getTarget()}
     * напряму (Brain-мобів на кшталт Piglin, де реальну ціль тримає memory-модуль
     * {@code ATTACK_TARGET}, а {@code setTarget()} сам по собі бій не перемикає) — щось ЗОВНІ
     * (напр. {@code PursuitBrainBridgeGoal}) повинно явно перечитати це значення і самостійно
     * синхронізувати його у Brain. Для звичайних Goal-based мобів цей метод не потрібен:
     * {@code reinforceTarget} всередині цього ж класу вже підтримує {@code mob.getTarget()}
     * в актуальному стані.
     */
    public static Player getTrackedPlayer(Mob mob) {
        MemoryData data = MEMORY.get(mob);
        return (data != null && data.state != State.FORGOTTEN) ? data.trackedPlayer : null;
    }

    /**
     * Точка, до якої моб має рухатись цього тіку, ігноруючи відсутність прямої видимості:
     * жива позиція гравця (CHASING), застигла остання відома точка (GOING_TO_LAST_SEEN) або
     * поточна точка "никання" (SEARCHING). Повертає null, якщо система не активна — тоді рух
     * лишається повністю на звичайній (canSee-based) логіці конкретного Goal-у моба.
     */
    public static Vec3 getChasePosition(Mob mob) {
        MemoryData data = MEMORY.get(mob);
        if (data == null) {
            return null;
        }
        return switch (data.state) {
            case CHASING -> data.trackedPlayer != null ? data.trackedPlayer.position() : null;
            case GOING_TO_LAST_SEEN -> data.lastSeenPos;
            // +1 до Y: data.searchPoint — це підлога (з columnPoint/findFloorNear), а не висота
            // ока. LookControl нижче (PursuitEnemyMeleeBehavior) додає ще +bbHeight*0.5 зверху
            // на ЦЕ значення, але для floor-level точки цього замало: коли моб підходить
            // впритул, горизонтальна відстань до цілі падає до ~0, а вертикальний перепад
            // (висота ока моба мінус висота точки) лишається тим самим — тангенс кута нахилу
            // росте до майже прямовисного "вниз". Піднявши БАЗУ на 1 блок ще до setLookAt,
            // прибираємо основну частину цього ефекту саме на фінальному підході.
            case SEARCHING -> data.searchPoint == null ? null : data.searchPoint.add(0, 1, 0);
            case FORGOTTEN -> null;
        };
    }

    /**
     * Чи має моб тримати тетиву натягнутою НАВІТЬ БЕЗ canSee — для категорії мобів без
     * накопиченого заряду (скелет/stray/bogged, дроунд із тризубом). True, коли моб уже досить
     * близько до застиглої точки (GOING_TO_LAST_SEEN) або вже у фазі активного пошуку
     * (SEARCHING) — в обох випадках тетива не відпускається, поки не настане FORGOTTEN або не
     * з'явиться справжній canSee (тоді постріл відбувається одразу, бо натяг вже повний).
     */
    public static boolean shouldDrawBowstring(Mob mob) {
        MemoryData data = MEMORY.get(mob);
        if (data == null) {
            return false;
        }
        if (data.state == State.SEARCHING) {
            return true;
        }
        if (data.state == State.GOING_TO_LAST_SEEN && data.lastSeenPos != null) {
            return mob.distanceToSqr(data.lastSeenPos) <= DRAW_BOWSTRING_DISTANCE_SQ;
        }
        return false;
    }

    /**
     * ТИМЧАСОВИЙ DEBUG-ЛОГ: відправляє повідомлення в чат гравцю-цілі.
     * public — щоб {@code EnemyBreak_N_BuildUtils} (інший пакет) міг переюзати цей самий метод
     * замість власної копії. Видалити всі виклики debugMsg (і сам метод) після завершення тестування.
     */
    public static void debugMsg(Player player, String msg) {
        // Дзеркало у файл debug-логу + однакові повідомлення в чат не частіше разу/с (див. DebugLog.chat).
        if (com.example.examplemod.debug.DebugLog.chat(player.getName().getString(), msg)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
        }
    }

    /**
     * Лише чат (без дзеркала у файл) - для викликів, які вже самі записали рядок у лог з тегом моба.
     */
    public static void sendChatOnly(Player player, String msg) {
        if (com.example.examplemod.debug.DebugLog.chatAllowed(msg)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
        }
    }

    @Override
    public boolean canUse() {
        return findCandidatePlayer() != null;
    }

    @Override
    public boolean canContinueToUse() {
        MemoryData data = MEMORY.get(this.mob);
        return (data != null && data.state != State.FORGOTTEN) || findCandidatePlayer() != null;
    }

    @Override
    public boolean isInterruptable() {
        return true; // не заважає вищим за пріоритетом Goal-ам (стрільбі/мелі) працювати одночасно
    }

    /**
     * Гравець, на якого варто звернути увагу цього тіку: або вже відстежуваний гравець (щоб не
     * втратити стан, навіть якщо ванільний targetSelector щойно скинув ціль), або поточна ціль
     * моба, якщо це гравець.
     */
    private Player findCandidatePlayer() {
        MemoryData data = MEMORY.get(this.mob);
        // Додаємо перевірку isValidTarget
        if (data != null && data.state == State.CHASING && isValidTarget(data.trackedPlayer)) {
            return data.trackedPlayer;
        }
        return (this.mob.getTarget() instanceof Player player && isValidTarget(player)) ? player : null;
    }

    private boolean isValidTarget(Player player) {
        if (player == null) return false;
        return !player.isSpectator() && !player.isCreative() && player.isAlive();
    }

    /**
     * Шукає серед УСІХ гравців у вимірі моба того, хто ЗАРАЗ БЛИЖЧЕ до моба, ніж currentTracked,
     * і при цьому моб його реально бачить напряму ({@code Sensing.hasLineOfSight}, тобто НЕ за
     * стіною). Викликається ЛИШЕ зі стану CHASING (див. {@link #tick()}) — саме це і є "режим
     * атаки", де діє правило "агро на найближчого", але тільки серед видимих напряму: гравець за
     * стіною ніколи не перехоплює агро цим шляхом, навіть якщо геометрично ближче за трекнутого.
     * <p>
     * followRangeSq приймається ззовні, а не рахується тут заново: currentTracked вже гарантовано
     * в його межах (це умова входу в CHASING), і кандидат теж повинен туди влізти — гравець
     * далі за FOLLOW_RANGE не повинен красти агро, навіть якщо він єдиний видимий напряму.
     *
     * @return найближчого підхожого гравця, ЯКЩО він строго ближче за currentTracked; {@code null},
     *         якщо кращого кандидата нема (currentTracked і сам лишається найближчим видимим, або
     *         єдиним видимим взагалі) — тоді ціль лишається без змін.
     */
    private Player findCloserVisiblePlayer(Player currentTracked, double followRangeSq) {
        double bestDistSq = this.mob.distanceToSqr(currentTracked);
        Player best = null;
        for (Player candidate : this.mob.level().players()) {
            if (candidate == currentTracked || !isValidTarget(candidate)) {
                continue;
            }
            double distSq = this.mob.distanceToSqr(candidate);
            if (distSq >= bestDistSq || distSq > followRangeSq) {
                continue; // не ближче за поточного лідера АБО поза радіусом атаки моба
            }
            if (!this.mob.getSensing().hasLineOfSight(candidate)) {
                continue; // за стіною (чи будь-якою іншою перепоною) — не може перехопити агро
            }
            best = candidate;
            bestDistSq = distSq;
        }
        return best;
    }

    @Override
    public void tick() {
        // Дефолт бігу: sprint = заагрений в БУДЬ-ЯКОМУ з активних станів (CHASING/
        // GOING_TO_LAST_SEEN/SEARCHING), незалежно від того, яка саме поведінка зараз керує
        // рухом. Читає стан як він був на ПОЧАТОК цього тіку (тобто з попереднього тіку) —
        // затримка в 1 тік (~50мс) на суто візуальному прапорці непомітна й нічого не ламає.
        Run_N_JumpUtils.applyDefaultRun(this.mob);

        boolean isNewEntry = !MEMORY.containsKey(this.mob); // ТИМЧАСОВИЙ DEBUG
        MemoryData data = MEMORY.computeIfAbsent(this.mob, m -> new MemoryData());

        // ТИМЧАСОВИЙ DEBUG: перший тік цього моба у всій системі памʼяті - показує, чи взагалі
        // (і коли) моб потрапляє в цю систему, і чи вже стоїть ванільна mob.getTarget() на той
        // момент (вона потрібна, щоб canUse()/findCandidatePlayer() спрацювали).
        if (isNewEntry) {
            Player vanillaTarget = (this.mob.getTarget() instanceof Player p) ? p : null;
            if (vanillaTarget != null) {
                debugMsg(vanillaTarget, "[DEBUG] Перший тік памʼяті. mob.getTarget()="
                        + vanillaTarget.getName().getString() + " моб=" + this.mob.blockPosition());
            } else if (!this.mob.level().players().isEmpty()) {
                debugMsg(this.mob.level().players().get(0),
                        "[DEBUG] Перший тік памʼяті, АЛЕ mob.getTarget()=null. моб=" + this.mob.blockPosition());
            }
        }

        // GOING_TO_LAST_SEEN і SEARCHING мають свою окрему логіку тіку.
        if (data.state == State.GOING_TO_LAST_SEEN) {
            tickGoingToLastSeen(data);
            return;
        }
        if (data.state == State.SEARCHING) {
            tickSearching(data);
            return;
        }

        Player player = findCandidatePlayer();
        if (player == null) {
            // ТИМЧАСОВИЙ DEBUG: throttled - якщо це спливає довго поки моб явно атакує на
            // вигляд, значить mob.getTarget() (ВАНІЛЬНА ціль) чомусь не встановлена або
            // невалідна (isValidTarget: не спектатор/креатив, живий) - уся система пам'яті
            // залежить від неї як від точки входу.
            if (this.mob.tickCount % 20 == 0 && !this.mob.level().players().isEmpty()) {
                debugMsg(this.mob.level().players().get(0), String.format(
                        "[DEBUG] findCandidatePlayer()=null. mob.getTarget()=%s стан_памяті=%s моб=%s",
                        this.mob.getTarget() != null ? this.mob.getTarget().getName().getString() : "null",
                        data.state, this.mob.blockPosition()));
            }
            return;
        }

        data.trackedPlayer = player;

        double followRange = this.mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        double followRangeSq = followRange * followRange;
        double distSq = this.mob.distanceToSqr(player);

        if (distSq <= followRangeSq) {
            // "Режим атаки" — і ТІЛЬКИ він: перш ніж підтвердити ціль, перевіряємо, чи не
            // з'явився БЛИЖЧИЙ гравець, якого моб реально бачить напряму (не за стіною). Якщо
            // так — перемикаємось на нього ще до входу в CHASING нижче, щоб усі гілки далі
            // (reinforceTarget, lastVisiblePos тощо) відразу відпрацювали для НОВОЇ цілі.
            Player closerVisible = findCloserVisiblePlayer(player, followRangeSq);
            if (closerVisible != null) {
                debugMsg(closerVisible, String.format(
                        "[DEBUG] Re-aggro: closer visible player stole aggro (%.1f -> %.1f blocks)",
                        Math.sqrt(distSq), Math.sqrt(this.mob.distanceToSqr(closerVisible))));
                player = closerVisible;
                distSq = this.mob.distanceToSqr(player);
            }

            // У межах повного радіуса — переслідуємо живу позицію крізь стіни.
            data.state = State.CHASING;
            data.trackedPlayer = player;
            reinforceTarget(player);
            // Оновлюємо lastVisiblePos ТІЛЬКИ коли моб реально бачить гравця (не через стіну).
            if (this.mob.getSensing().hasLineOfSight(player)) {
                data.lastVisiblePos = player.position();
            }

            // ТИМЧАСОВИЙ DEBUG: раз на секунду під час CHASING - жива дистанція проти
            // FOLLOW_RANGE і жива позиція гравця, якою зараз годується getChasePosition().
            // Мета: підтвердити, що стан справді лишається CHASING (а не десь непомітно
            // перескакує в GOING_TO_LAST_SEEN) поки гравець рухається "на тій самій висоті".
            if (this.mob.tickCount % 20 == 0) {
                debugMsg(player, String.format(
                        "[DEBUG] CHASING. dist=%.1f/%.1f(follow) моб=%.1f %.1f %.1f гравець=%.1f %.1f %.1f",
                        Math.sqrt(distSq), followRange,
                        this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                        player.getX(), player.getY(), player.getZ()));
            }
        } else if (data.state == State.CHASING) {
            // Вийшов за межі FOLLOW_RANGE — фіксуємо ОСТАННЮ ВИДИМУ позицію (не поточну!).
            Vec3 fixedPos = data.lastVisiblePos != null ? data.lastVisiblePos : player.position();
            data.state = State.GOING_TO_LAST_SEEN;
            data.lastSeenPos = fixedPos;
            data.trackedPlayer = player;
            // Грід ставимо ВЖЕ ТУТ (а не коли SEARCHING почнеться) — поки моб іде до точки, він
            // легким фоновим скануванням встигає позначити щось по дорозі. Сам SearchGrid більше
            // не прив'язаний до конкретного гравця чи точки — він просто диспетчер над спільним
            // для ВСІХ мобів GlobalSearchGrid.
            if (data.searchGrid != null) {
                data.searchGrid.discardDebugMarker(); // про всяк випадок - не мало б бути ненульовим тут
            }
            data.searchGrid = new SearchGrid(SEARCH_DURATION_TICKS + STALE_MARGIN_TICKS);
            debugMsg(player, String.format("[DEBUG] Entering GOING_TO_LAST_SEEN. Point: %.1f %.1f %.1f",
                    fixedPos.x, fixedPos.y, fixedPos.z));
        }
    }

    /**
     * Поки моб іде до застиглої точки: повернення в CHASING можливе лише через СПРАВЖНЄ ванільне
     * бачення гравця (canSee). Якщо фізично дійшов до точки — або стартуємо SEARCHING (мобам,
     * що підтримують), або одразу FORGOTTEN.
     */
    private void tickGoingToLastSeen(MemoryData data) {
        if (isValidTarget(data.trackedPlayer)) {
            reinforceTarget(data.trackedPlayer);
        } else if (data.trackedPlayer != null) {
            // Якщо гравець перейшов у креатив/спектратор прямо в процесі підходу
            forgetInternal(data);
            return;
        }

        // Тримаємо target живим поки йдемо до точки.
        if (data.trackedPlayer != null && data.trackedPlayer.isAlive()) {
            reinforceTarget(data.trackedPlayer);
        }

        // Справжнє нове ванільне бачення — миттєво повертаємось в CHASING.
        if (tryResumeChasing(data)) {
            return;
        }

        // Легке фонове сканування вже під час підходу — ціль руху лишається lastSeenPos без змін,
        // це лише побічний ефект (позначає щось прочеканим по дорозі).
        if (data.searchGrid != null) {
            data.searchGrid.lightTick(this.mob, this.mob.level().getGameTime());
        }

        // DEBUG: "готуюсь до вистрілу" — раз на секунду коли вже в зоні натягу тетиви (≤8 блоків).
        double distToPoint = Math.sqrt(this.mob.position().distanceToSqr(data.lastSeenPos));
        if (distToPoint <= Math.sqrt(DRAW_BOWSTRING_DISTANCE_SQ) && data.trackedPlayer != null
                && this.mob.tickCount % 20 == 0) {
            debugMsg(data.trackedPlayer, String.format(
                    "[DEBUG] Drawing bowstring. Distance to point: %.1f blocks", distToPoint));
        }

        double reachThresholdSq = 4.0; // ~2 блоки
        if (this.mob.position().distanceToSqr(data.lastSeenPos) <= reachThresholdSq) {
            if (supportsSearchBehavior) {
                startSearching(data);
            } else {
                forgetInternal(data);
            }
        }
    }

    /**
     * Моб "никається" навколо останньої відомої точки протягом SEARCH_DURATION_TICKS (15с):
     * SearchGrid (сканування місцевості) сама вирішує, куди йти далі — див. {@link SearchGrid}.
     * Тетива весь цей час натягнута (shouldDrawBowstring повертає true для SEARCHING).
     * Якщо canSee стає true — миттєво виходимо в CHASING і стріляємо (тетива вже повна).
     * Якщо час вийшов АБО вся зона вже оглянута — FORGOTTEN.
     */
    private void tickSearching(MemoryData data) {
        if (tryResumeChasing(data)) {
            return;
        }

        if (data.trackedPlayer != null && data.trackedPlayer.isAlive()) {
            reinforceTarget(data.trackedPlayer);
        }

        data.searchTicksLeft--;
        if (data.searchTicksLeft <= 0) {
            forgetInternal(data);
            return;
        }

        // DEBUG: таймер пошуку — раз на секунду.
        if (data.searchTicksLeft % 20 == 0) {
            Player debugPlayer = (this.mob.getTarget() instanceof Player p) ? p
                    : (data.trackedPlayer != null ? data.trackedPlayer : null);
            if (debugPlayer != null) {
                int secsLeft = data.searchTicksLeft / 20;
                debugMsg(debugPlayer, "[DEBUG] Still SEARCHING. Time left: " + secsLeft + "s");
            }
        }

        if (data.searchGrid == null) {
            if (data.trackedPlayer != null) {
                debugMsg(data.trackedPlayer, "[DEBUG] Forgetting: searchGrid was null in tickSearching");
            }
            forgetInternal(data); // про всяк випадок, не мало б статись
            return;
        }

        Vec3 prevPoint = data.searchPoint;
        Vec3 newPoint = data.searchGrid.tick(this.mob, this.mob.level().getGameTime());

        if (newPoint == null) {
            // Вся зона в межах FOLLOW_RANGE вже оглянута напряму, гравця нема — нема сенсу
            // чекати до кінця таймера, здаємось одразу.
            if (data.trackedPlayer != null) {
                debugMsg(data.trackedPlayer, "[DEBUG] Forgetting: no reachable frontier point found");
            }
            forgetInternal(data);
            return;
        }

        data.searchPoint = newPoint;
        if (!newPoint.equals(prevPoint)) {
            Player debugPlayer = (this.mob.getTarget() instanceof Player p) ? p
                    : (data.trackedPlayer != null ? data.trackedPlayer : null);
            if (debugPlayer != null) {
                debugMsg(debugPlayer, String.format("[DEBUG] New search point: %.1f %.1f %.1f",
                        newPoint.x, newPoint.y, newPoint.z));
            }
        }
    }

    /**
     * Спільна перевірка "справжній canSee І в межах FOLLOW_RANGE → назад в CHASING".
     * Обидві умови обов'язкові: Sensing.hasLineOfSight() має власний кеш і може "бачити"
     * гравця далі за FOLLOW_RANGE — без перевірки дистанції виникає цикл
     * GOING_TO_LAST_SEEN → CHASING → GOING_TO_LAST_SEEN кожен тік.
     */
    private boolean tryResumeChasing(MemoryData data) {
        Player nearbyPlayer = (this.mob.getTarget() instanceof Player player) ? player
                : (data.trackedPlayer != null ? data.trackedPlayer : null);
        if (!isValidTarget(nearbyPlayer)) {
            return false;
        }
        double followRange = this.mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (this.mob.distanceToSqr(nearbyPlayer) > followRange * followRange) {
            return false; // за межею радіуса — Sensing може "бачити" далі, але нас це не цікавить
        }
        if (!this.mob.getSensing().hasLineOfSight(nearbyPlayer)) {
            return false;
        }
        debugMsg(nearbyPlayer, "[DEBUG] Resumed CHASING (regained line of sight)");
        data.state = State.CHASING;
        data.trackedPlayer = nearbyPlayer;
        data.lastSeenPos = null;
        data.searchPoint = null;
        if (data.searchGrid != null) {
            data.searchGrid.discardDebugMarker();
        }
        data.searchGrid = null;
        reinforceTarget(nearbyPlayer);
        return true;
    }

    private void startSearching(MemoryData data) {
        // trackedPlayer НЕ обнуляємо — він потрібен для reinforceTarget під час пошуку,
        // щоб ванільний targetSelector не скинув mob.getTarget() раніше ніж 15с вийдуть.
        data.state = State.SEARCHING;
        data.searchTicksLeft = SEARCH_DURATION_TICKS;
        if (data.searchGrid == null) {
            // Не мало б статись (грід ставиться ще при вході в GOING_TO_LAST_SEEN) — про всяк
            // випадок, щоб не впасти в NPE.
            data.searchGrid = new SearchGrid(SEARCH_DURATION_TICKS + STALE_MARGIN_TICKS);
        }
        data.searchPoint = data.searchGrid.tick(this.mob, this.mob.level().getGameTime());
        // DEBUG: миттєвий лог старту SEARCHING - не чекаємо секунду до першого періодичного
        // повідомлення, щоб бачити навіть дуже коротке перебування в цьому стані.
        if (data.trackedPlayer != null) {
            if (data.searchPoint != null) {
                debugMsg(data.trackedPlayer, String.format(
                        "[DEBUG] SEARCHING started. First point: %.1f %.1f %.1f",
                        data.searchPoint.x, data.searchPoint.y, data.searchPoint.z));
            } else {
                debugMsg(data.trackedPlayer, "[DEBUG] SEARCHING started but tick() returned null immediately");
            }
        }
    }

    /**
     * Примусово тримає mob.getTarget() == player, поки наш стан CHASING — це захищає від того,
     * що ванільний targetSelector (зі своїм коротшим unseenMemoryTicks) скине ціль занадто рано.
     */
    private void reinforceTarget(Player player) {
        if (this.mob.getTarget() != player) {
            this.mob.setTarget(player);
        }
    }

    private void forgetInternal(MemoryData data) {
        // ТИМЧАСОВИЙ DEBUG: логуємо ДО занулення - це єдина спільна точка входу в FORGOTTEN з
        // усіх гілок (GOING_TO_LAST_SEEN/SEARCHING/тощо), тож без потреби латати кожен виклик
        // forgetInternal(...) окремо. Якщо це спрацьовує часто і одразу після - показує, що
        // моб постійно "забуває" гравця замість стабільного відстеження.
        if (data.trackedPlayer != null) {
            debugMsg(data.trackedPlayer, "[DEBUG] FORGOTTEN. Був стан=" + data.state
                    + " trackedPlayer=" + data.trackedPlayer.getName().getString()
                    + " моб=" + this.mob.blockPosition());
        }
        data.state = State.FORGOTTEN;
        data.trackedPlayer = null;
        data.lastVisiblePos = null;
        data.lastSeenPos = null;
        data.searchPoint = null;
        if (data.searchGrid != null) {
            data.searchGrid.discardDebugMarker();
        }
        data.searchGrid = null;
        if (this.mob.getTarget() instanceof Player) {
            this.mob.setTarget(null);
        }
    }

    private enum State {
        CHASING,
        GOING_TO_LAST_SEEN,
        SEARCHING,
        FORGOTTEN
    }

    private static final class MemoryData {
        State state = State.FORGOTTEN;
        Player trackedPlayer = null;
        /**
         * Остання позиція гравця де моб його РЕАЛЬНО БАЧИВ (canSee + в межах FOLLOW_RANGE).
         * Оновлюється щотіку під час CHASING тільки при hasLineOfSight. Саме ця точка стає
         * lastSeenPos при переході в GOING_TO_LAST_SEEN — а не поточна позиція гравця в момент
         * виходу за радіус (він може вже бути за стіною/далеко в той момент).
         */
        Vec3 lastVisiblePos = null;
        /**
         * Застигла точка куди моб іде після виходу гравця за FOLLOW_RANGE. Не оновлюється.
         */
        Vec3 lastSeenPos = null;
        /**
         * Поточна точка "никання" під час SEARCHING — надається SearchGrid.
         */
        Vec3 searchPoint = null;
        int searchTicksLeft = 0;
        /**
         * Пер-мобовий диспетчер {@link SearchGrid} над спільним {@code GlobalSearchGrid}
         * (без прив'язки до конкретного гравця — точки означають "тут нещодавно перевіряли на
         * будь-якого ворога"). Ставиться ще при вході в GOING_TO_LAST_SEEN (легке фонове
         * сканування вже під час підходу), повноцінно використовується для вибору точок у
         * SEARCHING.
         */
        SearchGrid searchGrid = null;
    }
}