package com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build;

/**
 * Чиста логіка рішень "чи можна / чи треба будувати" БЕЗ типів Minecraft - тому її можна перевірити окремо від гри
 * (див. {@code TerraformRulesTest}). Усе, що потребує {@code Mob}/{@code Level}, лишається в
 * {@link EnemyBreak_N_BuildUtils}; тут - лише арифметика й невеликі автомати стану.
 * <p>
 * <b>Навіщо це все (v18).</b> Паркур і будівництво мають однакові флаги (MOVE/LOOK/JUMP) і чергуються через
 * "заблоковано" ({@code isPathBlocked}). Умови входу в будівництво були надто широкі, тож моб ставив блоки посеред
 * паркуру без причини:
 * <ol>
 *   <li>сигнал "недосяжно" брався зі ЗАСТИГЛОЇ (тротлінг 40 тіків) відповіді, а grace-період - лише 30 тіків: стара
 *       відповідь сама "дорощувала" grace, хоч реальність уже змінилась ({@link BlockedTracker});</li>
 *   <li>у повітрі (будь-якому, не лише паркуровому) шлях не має сенсу, але лічильник недосяжності ріс;</li>
 *   <li>частковий маршрут паркуром (A* не дотягнувся до гравця) вважався "заблокованим";</li>
 *   <li>звичайні сходи/схил до гравця на 2+ блоки вище теж вважались "заблокованими" (вежа при наявному проході);</li>
 *   <li>підлога під мобом у польоті ставилась БУДЬ-ЯКОГО польоту, а не лише піларингу ({@link #allowAirborneFloor}).</li>
 * </ol>
 */
final class TerraformRules {

    /**
     * Позначка "тіку нема".
     */
    static final int NO_TICK = Integer.MIN_VALUE;

    /**
     * Скільки тіків після кінця паркуру його ще вважаємо "триває": покриває проміжок між двома стрибками ланцюжка й
     * перерахунок шляху після приземлення, коли GapJumpAssistGoal на 1-3 тіки неактивний.
     */
    static final int PARKOUR_COOLDOWN_TICKS = 10;

    /** Падіння (блоків), яке ще вважаємо "щойно зійшов з краю"; більше - це вже падіння, а не крок з мосту. */
    static final double JUST_LEFT_EDGE_FALL = 1.0;

    /** Скільки тіків без руху моб на маршруті паркуру вважається завислим (тоді маршрут не виправдовує очікування). */
    static final int PARKOUR_STALL_TICKS = 60;

    /** Мінімальне зрушення (блоків), яке вважається рухом. */
    static final double STALL_MIN_MOVE = 0.5;

    /**
     * Звичайний прохід (без стрибків) до цілі на 2+ блоки вище не вимагає вежі, якщо він не довший за
     * {@code FACTOR * горизонтальна_відстань + SLACK} вузлів: сходи/схил - так, обхід через пів карти - ні.
     */
    static final double WALK_DETOUR_FACTOR = 2.0;
    static final int WALK_DETOUR_SLACK = 16;

    /**
     * v19: допуск при порівнянні висоти ніг моба з верхівкою колізії блока. Мед має колізію 15/16 = 0.9375, пісок душ
     * 14/16, плита 8/16 - значення точні, але позиція моба бувала із похибкою у соті частки.
     */
    static final double FEET_EPS = 1.0E-3;

    /**
     * v19: допуск перетину боксів - торкання гранями (нога моба рівно на верхівці блока) НЕ перетин.
     */
    static final double OVERLAP_EPS = 1.0E-4;

    /**
     * v19: на скільки блоків угору стрибок моба звільняє клітинку з-під хітбокса (звичайний стрибок ~1.25, на меді/піску
     * душ - значно менше; беремо з запасом нижню межу). Якщо потрібно підняти більше - стрибок не допоможе.
     */
    static final double MAX_JUMP_LIFT = 1.0;

    /**
     * v19: скільки тіків відкладений (через хітбокс) блок чекає на повторну спробу, поки моб у стрибку.
     */
    static final int PENDING_PLACEMENT_TTL_TICKS = 24;

    /**
     * v19: скільки разів поспіль підстрибуємо заради однієї клітинки, перш ніж здатись (далі повторить сама ціль).
     */
    static final int PENDING_MAX_JUMPS = 3;

    /**
     * v19: пауза (тіків) без нових стрибків заради хітбокса після {@link #PENDING_MAX_JUMPS} невдалих - щоб моб не скакав вічно.
     */
    static final int PENDING_GIVE_UP_COOLDOWN_TICKS = 40;

    /**
     * v19: точки ВСЕРЕДИНІ клітинки (частки 0..1 по x,y,z), у які ціляться променем від очей моба: центр і по одній
     * точці біля кожної з 6 граней. Блок можна ставити, якщо промінь бодай до однієї точки не впирається в чужий блок.
     * Усі точки відступають від межі клітинки на 0.15, щоб промінь не "протікав" крізь щілину в 1/16 по краях
     * неповних блоків (мед має колізію 14x15x14).
     */
    static final double[][] LOS_SAMPLES = {
            {0.5, 0.5, 0.5},
            {0.5, 0.85, 0.5},
            {0.5, 0.15, 0.5},
            {0.15, 0.5, 0.5},
            {0.85, 0.5, 0.5},
            {0.5, 0.5, 0.15},
            {0.5, 0.5, 0.85}
    };

    private TerraformRules() {
    }

    /**
     * v19: ноги моба стоять НА блоці клітинки (на верхівці його колізії або вище), а не всередині нього. Для моба на меді
     * {@code blockPosition()} - це клітинка САМОГО меду (ноги на 15/16, тобто все ще "в" клітинці), і код, що брав
     * {@code blockPosition().below()} за "підлогу", ставив блок ПІД медом. Якщо true - реальна клітинка ніг на 1 вища.
     *
     * @param feetY              висота ніг моба
     * @param cellY              Y клітинки {@code blockPosition()}
     * @param collisionTopInCell найвища точка колізії блока в цій клітинці (0..1+, відносно низу клітинки)
     */
    static boolean feetAtOrAboveCollisionTop(double feetY, int cellY, double collisionTopInCell) {
        return feetY >= cellY + collisionTopInCell - FEET_EPS;
    }

    /**
     * v19: Y клітинки, у якій стоять ноги моба.
     * <ul>
     *   <li>на землі: клітинка НАД опорою ({@code supportY + 1}), якщо колізія опори вміщується в її клітинку (мед 15/16,
     *       пісок душ 14/16, плита 8/16, сходинка, повний блок 1.0). Опору дає ванільний {@code getOnPos()} - блок під
     *       хітбоксом, а не під центром моба, тож випадок "центр уже за краєм опори" (v16) рахується правильно;</li>
     *   <li>опора вища за клітинку (паркан/мур, 1.5) або її нема - ноги вже в клітинці {@code blockPosition()};</li>
     *   <li>у польоті - {@code blockPosition()}.</li>
     * </ul>
     *
     * @param supportCollisionTop верх колізії опори в її клітинці (0..1.5) або {@link Double#NaN}, якщо опори/колізії нема
     */
    static int feetCellY(boolean onGround, int blockPosY, int supportY, double supportCollisionTop) {
        if (!onGround || Double.isNaN(supportCollisionTop) || supportCollisionTop > 1.0 + FEET_EPS) {
            return blockPosY;
        }
        return supportY + 1;
    }

    /**
     * v19: чи перетинаються два паралелепіпеди (торкання гранями не рахується).
     */
    static boolean boxesOverlap(double aMinX, double aMinY, double aMinZ, double aMaxX, double aMaxY, double aMaxZ,
                                double bMinX, double bMinY, double bMinZ, double bMaxX, double bMaxY, double bMaxZ) {
        return aMinX < bMaxX - OVERLAP_EPS && aMaxX > bMinX + OVERLAP_EPS
                && aMinY < bMaxY - OVERLAP_EPS && aMaxY > bMinY + OVERLAP_EPS
                && aMinZ < bMaxZ - OVERLAP_EPS && aMaxZ > bMinZ + OVERLAP_EPS;
    }

    /**
     * v19: чи звільнить стрибок клітинку з-під хітбокса: потрібно підняти моба на {@code liftNeeded} блоків (верх клітинки
     * мінус низ хітбокса). Для клітинки на рівні меду це соті частки; для стіни по голову - 2 блоки (стрибок не допоможе).
     */
    static boolean jumpClears(double liftNeeded) {
        return liftNeeded > 0.0 && liftNeeded <= MAX_JUMP_LIFT;
    }

    /** Чи розумний звичайний прохід до вищої цілі (див. {@link #WALK_DETOUR_FACTOR}). */
    static boolean isReasonableWalk(int nodeCount, double horizontalDistance) {
        return nodeCount <= WALK_DETOUR_FACTOR * horizontalDistance + WALK_DETOUR_SLACK;
    }

    /**
     * Чи можна підкласти блок під мобом, що ЗАРАЗ у повітрі (аварійна підлога).
     * <ul>
     *   <li>на землі - ні (не аварія);</li>
     *   <li>під час паркур-стрибка - ні ніколи: підлога під мобом ламає сам стрибок;</li>
     *   <li>піларинг (моб будує вежу й сам підстрибує) - так: підлога під ногами в польоті і є суть піларингу;</li>
     *   <li>інакше (міст) - лише "щойно зійшов з краю": падає (не летить угору) і впав не більше за
     *       {@link #JUST_LEFT_EDGE_FALL}. Стрибок угору (гоп) або вже довге падіння під це не підпадають.</li>
     * </ul>
     */
    static boolean allowAirborneFloor(boolean onGround, boolean parkourActive, boolean pillaring,
                                      double verticalSpeed, double fallDistance) {
        if (onGround || parkourActive) {
            return false;
        }
        if (pillaring) {
            return true;
        }
        return verticalSpeed <= 0.0 && fallDistance <= JUST_LEFT_EDGE_FALL;
    }

    /**
     * Автомат "заблоковано довше за grace-період". Живить його лише НОВЕ справжнє обчислення шляху ({@link #observe}):
     * застигла (тротлінг/політ) відповідь НЕ є спостереженням і grace не росте. Між спостереженнями вердикт
     * тримається незмінним ({@link #verdict()}).
     * <p>
     * Якщо сире "заблоковано" хоч раз стає false - серія скидається повністю: pursuit отримує свіжий шанс щоразу, коли
     * перепона зникає (так само, як було раніше).
     */
    static final class BlockedTracker {
        private int sinceTick = NO_TICK;
        private int lastObservedTick = NO_TICK;
        private boolean verdict;
        private int seenComputeSeq = -1;

        boolean verdict() {
            return this.verdict;
        }

        int seenComputeSeq() {
            return this.seenComputeSeq;
        }

        void markSeen(int computeSeq) {
            this.seenComputeSeq = computeSeq;
        }

        /** Перший тік поточної серії "заблоковано" або {@link #NO_TICK}. */
        int sinceTick() {
            return this.sinceTick;
        }

        /** Скільки тіків триває поточна серія (0, якщо не заблоковано). */
        int blockedTicks(int nowTick) {
            return this.sinceTick == NO_TICK ? 0 : Math.max(0, nowTick - this.sinceTick);
        }

        /** Скидає серію й вердикт (але не те, які обчислення вже бачили). */
        void reset() {
            this.sinceTick = NO_TICK;
            this.lastObservedTick = NO_TICK;
            this.verdict = false;
        }

        /**
         * Нове справжнє спостереження.
         *
         * @param obsTick     тік, коли шлях РЕАЛЬНО порахували
         * @param rawBlocked  сирий сигнал "заблоковано" за цим обчисленням (з усіма виправданнями вже враховано)
         * @param graceTicks  скільки тіків заблоковано безперервно, перш ніж повернути true
         * @param maxGapTicks якщо між двома спостереженнями минуло більше - попередня серія застаріла (моб довго
         *                    не опитувався, наприклад не переслідував) і рахуємо наново
         * @return чи минув grace-період
         */
        boolean observe(int obsTick, boolean rawBlocked, int graceTicks, int maxGapTicks) {
            if (this.lastObservedTick != NO_TICK && obsTick - this.lastObservedTick > maxGapTicks) {
                this.sinceTick = NO_TICK;
                this.verdict = false;
            }
            this.lastObservedTick = obsTick;
            if (!rawBlocked) {
                this.sinceTick = NO_TICK;
                this.verdict = false;
                return false;
            }
            if (this.sinceTick == NO_TICK) {
                this.sinceTick = obsTick;
            }
            this.verdict = obsTick - this.sinceTick >= graceTicks;
            return this.verdict;
        }
    }

    /**
     * Чи моб "завис": не зрушив на {@code minMove} блоків довше за {@code windowTicks}. Потрібен, щоб маршрут паркуру
     * виправдовував очікування, лише поки моб по ньому реально рухається.
     */
    static final class StallDetector {
        private double ax;
        private double ay;
        private double az;
        private int anchorTick = NO_TICK;
        private int lastTick = NO_TICK;

        boolean stalled(int tick, double x, double y, double z, double minMove, int windowTicks, int maxGapTicks) {
            if (this.anchorTick == NO_TICK || (this.lastTick != NO_TICK && tick - this.lastTick > maxGapTicks)) {
                this.anchor(tick, x, y, z);
            }
            this.lastTick = tick;
            double dx = x - this.ax;
            double dy = y - this.ay;
            double dz = z - this.az;
            if (dx * dx + dy * dy + dz * dz >= minMove * minMove) {
                this.anchor(tick, x, y, z);
            }
            return tick - this.anchorTick >= windowTicks;
        }

        void reset() {
            this.anchorTick = NO_TICK;
            this.lastTick = NO_TICK;
        }

        private void anchor(int tick, double x, double y, double z) {
            this.ax = x;
            this.ay = y;
            this.az = z;
            this.anchorTick = tick;
        }
    }
}
