package de.lukas.donutsauctions.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdleAhAutomationCoordinatorTest {

    private val defaultConfig = IdleAhAutomationCoordinator.Config(
        generalScanPages = 20,
        watchScanPages = 2,
        watchItemsPerWindowCap = 3,
        pageDwellMs = 500L,
        watchCycleIntervalMs = 60_000L,
        generalCycleIntervalMs = 120_000L,
        commandMinIntervalMs = 2_500L,
        pageCaptureTimeoutMs = 2_500L
    )

    @Test
    fun `idle gating blocks scheduling until start is allowed`() {
        val coordinator = IdleAhAutomationCoordinator()
        val blocked = coordinator.tick(
            tickInput(
                now = 1_000L,
                startAllowed = false,
                watchTerms = listOf("Diamond Sword")
            )
        )
        assertEquals(IdleAhAutomationCoordinator.Action.None, blocked)

        val allowed = coordinator.tick(
            tickInput(
                now = 3_000L,
                startAllowed = true,
                watchTerms = listOf("Diamond Sword")
            )
        )
        assertTrue(allowed is IdleAhAutomationCoordinator.Action.SendCommand)
    }

    @Test
    fun `watch cadence runs before general and keeps two to one priority`() {
        val coordinator = IdleAhAutomationCoordinator()
        val cfg = defaultConfig.copy(watchItemsPerWindowCap = 1)

        val firstWatch = coordinator.tick(
            tickInput(
                now = 1_000L,
                config = cfg,
                watchTerms = listOf("A", "B", "C")
            )
        )
        assertEquals("ah A", (firstWatch as IdleAhAutomationCoordinator.Action.SendCommand).commandNoSlash)
        finishSinglePageJob(coordinator, cfg, startNow = 1_000L)

        val secondWatch = coordinator.tick(
            tickInput(
                now = 61_000L,
                config = cfg,
                watchTerms = listOf("A", "B", "C")
            )
        )
        assertEquals("ah B", (secondWatch as IdleAhAutomationCoordinator.Action.SendCommand).commandNoSlash)
        finishSinglePageJob(coordinator, cfg, startNow = 61_000L)

        val thirdWatch = coordinator.tick(
            tickInput(
                now = 121_000L,
                config = cfg,
                watchTerms = listOf("A", "B", "C")
            )
        )
        assertEquals("ah C", (thirdWatch as IdleAhAutomationCoordinator.Action.SendCommand).commandNoSlash)
        finishSinglePageJob(coordinator, cfg, startNow = 121_000L)

        val generalAfterWatch = coordinator.tick(
            tickInput(
                now = 124_000L,
                config = cfg,
                watchTerms = emptyList()
            )
        )
        assertEquals("ah", (generalAfterWatch as IdleAhAutomationCoordinator.Action.SendCommand).commandNoSlash)
    }

    @Test
    fun `round robin honors cap and resumes from prior cursor`() {
        val coordinator = IdleAhAutomationCoordinator()
        val cfg = defaultConfig.copy(watchItemsPerWindowCap = 3)
        val terms = listOf("A", "B", "C", "D", "E")

        val first = coordinator.tick(tickInput(now = 1_000L, config = cfg, watchTerms = terms))
        assertEquals("ah A", (first as IdleAhAutomationCoordinator.Action.SendCommand).commandNoSlash)
        finishSinglePageJob(coordinator, cfg, startNow = 1_000L)

        val second = coordinator.tick(tickInput(now = 4_000L, config = cfg))
        assertEquals("ah B", (second as IdleAhAutomationCoordinator.Action.SendCommand).commandNoSlash)
        finishSinglePageJob(coordinator, cfg, startNow = 4_000L)

        val third = coordinator.tick(tickInput(now = 7_000L, config = cfg))
        assertEquals("ah C", (third as IdleAhAutomationCoordinator.Action.SendCommand).commandNoSlash)
        finishSinglePageJob(coordinator, cfg, startNow = 7_000L)

        val fourth = coordinator.tick(tickInput(now = 61_000L, config = cfg, watchTerms = terms))
        assertEquals("ah D", (fourth as IdleAhAutomationCoordinator.Action.SendCommand).commandNoSlash)
        finishSinglePageJob(coordinator, cfg, startNow = 61_000L)
    }

    @Test
    fun `command min interval delays next job dispatch`() {
        val coordinator = IdleAhAutomationCoordinator()
        val cfg = defaultConfig.copy(watchItemsPerWindowCap = 1)

        val first = coordinator.tick(
            tickInput(
                now = 1_000L,
                config = cfg,
                watchTerms = listOf("Diamond Sword")
            )
        )
        assertTrue(first is IdleAhAutomationCoordinator.Action.SendCommand)
        finishSinglePageJob(coordinator, cfg, startNow = 1_000L)

        val blocked = coordinator.tick(tickInput(now = 3_000L, config = cfg))
        assertEquals(IdleAhAutomationCoordinator.Action.None, blocked)

        val allowed = coordinator.tick(tickInput(now = 3_600L, config = cfg))
        assertTrue(allowed is IdleAhAutomationCoordinator.Action.SendCommand)
    }

    @Test
    fun `movement stop finishes current page then closes`() {
        val coordinator = IdleAhAutomationCoordinator()
        val cfg = defaultConfig

        val send = coordinator.tick(
            tickInput(
                now = 1_000L,
                config = cfg,
                watchTerms = listOf("Netherite Ingot")
            )
        )
        assertTrue(send is IdleAhAutomationCoordinator.Action.SendCommand)

        coordinator.tick(tickInput(now = 1_001L, config = cfg))
        coordinator.tick(
            tickInput(
                now = 1_002L,
                config = cfg,
                inventoryOpen = true
            )
        )

        coordinator.requestStop("movement")
        val stopAction = coordinator.tick(
            tickInput(
                now = 1_700L,
                config = cfg,
                inventoryOpen = true,
                captureObserved = true,
                nextControlAvailable = true
            )
        )
        assertEquals(IdleAhAutomationCoordinator.Action.CloseHandledScreen, stopAction)

        val complete = coordinator.tick(
            tickInput(
                now = 1_701L,
                config = cfg,
                inventoryOpen = false
            )
        )
        assertEquals(IdleAhAutomationCoordinator.Action.None, complete)
        assertEquals(IdleAhAutomationCoordinator.State.IDLE, coordinator.state)
    }

    @Test
    fun `normalizes watch queries by stripping formatting and symbols`() {
        assertEquals("Diamond Sword", IdleAhAutomationCoordinator.normalizeWatchQuery("§a★ Diamond_Sword!!!"))
    }

    @Test
    fun `normalizes watch queries by collapsing whitespace`() {
        assertEquals(
            "Enchanted Golden Apple",
            IdleAhAutomationCoordinator.normalizeWatchQuery("  Enchanted   Golden   Apple ")
        )
    }

    @Test
    fun `normalizes watch queries to empty when only symbols remain`() {
        assertEquals("", IdleAhAutomationCoordinator.normalizeWatchQuery("★★§l!!!"))
    }

    private fun finishSinglePageJob(
        coordinator: IdleAhAutomationCoordinator,
        config: IdleAhAutomationCoordinator.Config,
        startNow: Long
    ) {
        coordinator.tick(tickInput(now = startNow + 1L, config = config))
        coordinator.tick(
            tickInput(
                now = startNow + 2L,
                config = config,
                inventoryOpen = true
            )
        )
        val stopOrClose = coordinator.tick(
            tickInput(
                now = startNow + config.pageDwellMs + 10L,
                config = config,
                inventoryOpen = true,
                captureObserved = true
            )
        )
        assertEquals(IdleAhAutomationCoordinator.Action.CloseHandledScreen, stopOrClose)
        coordinator.tick(
            tickInput(
                now = startNow + config.pageDwellMs + 20L,
                config = config,
                inventoryOpen = false
            )
        )
    }

    private fun tickInput(
        now: Long,
        config: IdleAhAutomationCoordinator.Config = defaultConfig,
        startAllowed: Boolean = true,
        inventoryOpen: Boolean = false,
        captureObserved: Boolean = false,
        nextControlAvailable: Boolean = false,
        watchTerms: List<String> = emptyList()
    ): IdleAhAutomationCoordinator.TickInput {
        return IdleAhAutomationCoordinator.TickInput(
            nowMs = now,
            config = config,
            startAllowed = startAllowed,
            inventoryScreenOpen = inventoryOpen,
            captureObserved = captureObserved,
            nextPageControlAvailable = nextControlAvailable,
            watchTerms = watchTerms
        )
    }
}
