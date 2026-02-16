package de.lukas.donutsauctions.scanner

class IdleAhAutomationCoordinator {

    enum class State {
        IDLE,
        STARTING_QUERY,
        WAITING_FOR_SCREEN,
        CAPTURING_PAGE,
        CLICKING_NEXT,
        STOPPING
    }

    enum class JobType {
        GENERAL,
        WATCH
    }

    data class Config(
        val generalScanPages: Int,
        val watchScanPages: Int,
        val watchItemsPerWindowCap: Int,
        val pageDwellMs: Long,
        val watchCycleIntervalMs: Long,
        val generalCycleIntervalMs: Long,
        val commandMinIntervalMs: Long,
        val pageCaptureTimeoutMs: Long
    )

    data class TickInput(
        val nowMs: Long,
        val config: Config,
        val startAllowed: Boolean,
        val inventoryScreenOpen: Boolean,
        val captureObserved: Boolean,
        val nextPageControlAvailable: Boolean,
        val watchTerms: List<String> = emptyList()
    )

    data class ActiveJobSnapshot(
        val type: JobType,
        val query: String,
        val currentPage: Int,
        val targetPages: Int,
        val pageStartedAtMs: Long
    )

    data class SchedulerSnapshot(
        val state: State,
        val hasActiveJob: Boolean,
        val pendingWatchJobs: Int,
        val commandCooldownRemainingMs: Long,
        val nextWatchDueInMs: Long,
        val nextGeneralDueInMs: Long
    )

    sealed interface Action {
        data object None : Action
        data class SendCommand(
            val commandNoSlash: String,
            val jobType: JobType,
            val query: String,
            val targetPages: Int
        ) : Action
        data object ClickNextPage : Action
        data object CloseHandledScreen : Action
    }

    private data class ActiveJob(
        val type: JobType,
        val query: String,
        val targetPages: Int,
        var currentPage: Int = 1,
        var pageStartedAtMs: Long = 0L,
        var captureObserved: Boolean = false
    )

    private data class QueuedJob(
        val type: JobType,
        val query: String,
        val targetPages: Int
    )

    var state: State = State.IDLE
        private set

    var lastRunAtMs: Long = 0L
        private set

    var lastReason: String? = null
        private set

    private var stateEnteredAtMs: Long = 0L
    private var stopRequested: Boolean = false
    private var stopReason: String? = null
    private var lastCommandSentAtMs: Long = -1L
    private var nextWatchCycleAtMs: Long = 0L
    private var nextGeneralCycleAtMs: Long = 0L
    private var watchRoundRobinCursor: Int = 0
    private val pendingWatchJobs = ArrayDeque<QueuedJob>()
    private var activeJob: ActiveJob? = null

    fun isActive(): Boolean {
        return state != State.IDLE || activeJob != null
    }

    fun activeJobSnapshot(): ActiveJobSnapshot? {
        val job = activeJob ?: return null
        return ActiveJobSnapshot(
            type = job.type,
            query = job.query,
            currentPage = job.currentPage,
            targetPages = job.targetPages,
            pageStartedAtMs = job.pageStartedAtMs
        )
    }

    fun schedulerSnapshot(nowMs: Long, commandMinIntervalMs: Long): SchedulerSnapshot {
        initializeDueTimers(nowMs)
        val commandCooldownRemainingMs = if (lastCommandSentAtMs < 0L) {
            0L
        } else {
            (commandMinIntervalMs - (nowMs - lastCommandSentAtMs)).coerceAtLeast(0L)
        }
        return SchedulerSnapshot(
            state = state,
            hasActiveJob = activeJob != null,
            pendingWatchJobs = pendingWatchJobs.size,
            commandCooldownRemainingMs = commandCooldownRemainingMs,
            nextWatchDueInMs = (nextWatchCycleAtMs - nowMs).coerceAtLeast(0L),
            nextGeneralDueInMs = (nextGeneralCycleAtMs - nowMs).coerceAtLeast(0L)
        )
    }

    fun requestStop(reason: String) {
        if (!isActive()) {
            return
        }
        stopRequested = true
        stopReason = reason
        lastReason = reason
    }

    fun shouldLoadWatchTerms(nowMs: Long, config: Config, startAllowed: Boolean): Boolean {
        if (!startAllowed) {
            return false
        }
        initializeDueTimers(nowMs)
        return state == State.IDLE &&
            activeJob == null &&
            pendingWatchJobs.isEmpty() &&
            nowMs >= nextWatchCycleAtMs &&
            config.watchItemsPerWindowCap > 0
    }

    fun tick(input: TickInput): Action {
        initializeDueTimers(input.nowMs)
        return when (state) {
            State.IDLE -> tickIdle(input)
            State.STARTING_QUERY -> tickStartingQuery(input)
            State.WAITING_FOR_SCREEN -> tickWaitingForScreen(input)
            State.CAPTURING_PAGE -> tickCapturingPage(input)
            State.CLICKING_NEXT -> tickClickingNext(input)
            State.STOPPING -> tickStopping(input)
        }
    }

    private fun tickIdle(input: TickInput): Action {
        if (!input.startAllowed) {
            return Action.None
        }

        if (lastCommandSentAtMs >= 0L && (input.nowMs - lastCommandSentAtMs) < input.config.commandMinIntervalMs) {
            return Action.None
        }

        val next = nextQueuedJob(input) ?: return Action.None

        activeJob = ActiveJob(
            type = next.type,
            query = next.query,
            targetPages = next.targetPages.coerceAtLeast(1)
        )
        stopRequested = false
        stopReason = null
        transitionTo(State.STARTING_QUERY, input.nowMs)

        val command = if (next.query.isBlank()) "ah" else "ah ${next.query}"
        lastCommandSentAtMs = input.nowMs
        return Action.SendCommand(
            commandNoSlash = command,
            jobType = next.type,
            query = next.query,
            targetPages = next.targetPages
        )
    }

    private fun tickStartingQuery(input: TickInput): Action {
        transitionTo(State.WAITING_FOR_SCREEN, input.nowMs)
        return Action.None
    }

    private fun tickWaitingForScreen(input: TickInput): Action {
        if (!input.inventoryScreenOpen) {
            if (stopRequested && (input.nowMs - stateEnteredAtMs) >= input.config.pageCaptureTimeoutMs) {
                completeActiveJob(input.nowMs, stopReason ?: "Automation stopped before menu opened")
            } else if ((input.nowMs - stateEnteredAtMs) >= input.config.pageCaptureTimeoutMs) {
                completeActiveJob(input.nowMs, "Automation timeout: auction menu did not open")
            }
            return Action.None
        }

        val job = activeJob
        if (job != null) {
            job.currentPage = 1
            job.pageStartedAtMs = input.nowMs
            job.captureObserved = false
            transitionTo(State.CAPTURING_PAGE, input.nowMs)
        } else {
            transitionTo(State.IDLE, input.nowMs)
        }
        return Action.None
    }

    private fun tickCapturingPage(input: TickInput): Action {
        val job = activeJob
        if (job == null) {
            transitionTo(State.IDLE, input.nowMs)
            return Action.None
        }

        if (!input.inventoryScreenOpen) {
            completeActiveJob(input.nowMs, "Automation stopped: menu closed before page completion")
            return Action.None
        }

        if (input.captureObserved) {
            job.captureObserved = true
        }

        val elapsedMs = input.nowMs - job.pageStartedAtMs
        val dwellDone = elapsedMs >= input.config.pageDwellMs
        val timeoutReached = elapsedMs >= input.config.pageCaptureTimeoutMs
        val readyToAdvance = dwellDone && (job.captureObserved || timeoutReached)
        if (!readyToAdvance) {
            return Action.None
        }

        if (stopRequested) {
            return enterStopping(input.nowMs, stopReason ?: "Automation stopped while idle condition changed", input.inventoryScreenOpen)
        }

        if (job.currentPage >= job.targetPages) {
            return enterStopping(input.nowMs, "Automation completed ${job.type.name.lowercase()} job", input.inventoryScreenOpen)
        }

        if (!input.nextPageControlAvailable) {
            return enterStopping(input.nowMs, "Automation stopped early: no next-page control found", input.inventoryScreenOpen)
        }

        transitionTo(State.CLICKING_NEXT, input.nowMs)
        return Action.None
    }

    private fun tickClickingNext(input: TickInput): Action {
        val job = activeJob
        if (job == null) {
            transitionTo(State.IDLE, input.nowMs)
            return Action.None
        }

        if (!input.inventoryScreenOpen) {
            completeActiveJob(input.nowMs, "Automation stopped: menu closed before next page click")
            return Action.None
        }

        if (stopRequested) {
            return enterStopping(input.nowMs, stopReason ?: "Automation stopping after current page", input.inventoryScreenOpen)
        }

        job.currentPage += 1
        job.pageStartedAtMs = input.nowMs
        job.captureObserved = false
        transitionTo(State.CAPTURING_PAGE, input.nowMs)
        return Action.ClickNextPage
    }

    private fun tickStopping(input: TickInput): Action {
        if (input.inventoryScreenOpen) {
            return Action.CloseHandledScreen
        }

        val reason = stopReason ?: "Automation stopped"
        completeActiveJob(input.nowMs, reason)
        return Action.None
    }

    private fun enterStopping(nowMs: Long, reason: String, inventoryOpen: Boolean): Action {
        stopReason = reason
        transitionTo(State.STOPPING, nowMs)
        return if (inventoryOpen) Action.CloseHandledScreen else {
            completeActiveJob(nowMs, reason)
            Action.None
        }
    }

    private fun nextQueuedJob(input: TickInput): QueuedJob? {
        if (pendingWatchJobs.isNotEmpty()) {
            return pendingWatchJobs.removeFirst()
        }

        if (input.nowMs >= nextWatchCycleAtMs) {
            nextWatchCycleAtMs = input.nowMs + input.config.watchCycleIntervalMs
            scheduleWatchCycle(input.watchTerms, input.config.watchItemsPerWindowCap, input.config.watchScanPages)
            if (pendingWatchJobs.isNotEmpty()) {
                return pendingWatchJobs.removeFirst()
            }
        }

        if (input.nowMs >= nextGeneralCycleAtMs) {
            nextGeneralCycleAtMs = input.nowMs + input.config.generalCycleIntervalMs
            return QueuedJob(
                type = JobType.GENERAL,
                query = "",
                targetPages = input.config.generalScanPages
            )
        }

        return null
    }

    private fun scheduleWatchCycle(watchTerms: List<String>, cap: Int, watchPages: Int) {
        pendingWatchJobs.clear()
        if (watchTerms.isEmpty()) {
            return
        }

        val terms = watchTerms
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        if (terms.isEmpty()) {
            return
        }

        val boundedCap = cap.coerceIn(1, terms.size)
        val normalizedCursor = watchRoundRobinCursor.mod(terms.size)
        for (offset in 0 until boundedCap) {
            val index = (normalizedCursor + offset) % terms.size
            pendingWatchJobs.addLast(
                QueuedJob(
                    type = JobType.WATCH,
                    query = terms[index],
                    targetPages = watchPages
                )
            )
        }
        watchRoundRobinCursor = (normalizedCursor + boundedCap) % terms.size
    }

    private fun transitionTo(newState: State, nowMs: Long) {
        state = newState
        stateEnteredAtMs = nowMs
    }

    private fun initializeDueTimers(nowMs: Long) {
        if (nextWatchCycleAtMs == 0L) {
            nextWatchCycleAtMs = nowMs
        }
        if (nextGeneralCycleAtMs == 0L) {
            nextGeneralCycleAtMs = nowMs
        }
    }

    private fun completeActiveJob(nowMs: Long, reason: String) {
        if (activeJob != null) {
            lastRunAtMs = nowMs
        }
        lastReason = reason
        activeJob = null
        stopRequested = false
        stopReason = null
        transitionTo(State.IDLE, nowMs)
    }

    companion object {
        private val colorCodeRegex = Regex("§[0-9A-FK-OR]", RegexOption.IGNORE_CASE)
        private val disallowedWatchCharRegex = Regex("[^\\p{L}\\p{N}\\s]")
        private val whitespaceRegex = Regex("\\s+")

        fun normalizeWatchQuery(displayName: String): String {
            val noFormatting = displayName.replace(colorCodeRegex, "")
            val stripped = noFormatting.replace(disallowedWatchCharRegex, " ")
            return stripped.replace(whitespaceRegex, " ").trim()
        }
    }
}
