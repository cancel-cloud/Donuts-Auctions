package de.lukas.donutsauctions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DonutsAuctionsClientServerGateTest {

    @Test
    fun `allows donuts primary domain`() {
        val host = DonutsAuctionsClient.normalizeServerHost("donutsmp.com")
        assertEquals("donutsmp.com", host)
        assertTrue(DonutsAuctionsClient.isAllowedAutomationHost(host))
    }

    @Test
    fun `allows donuts subdomains`() {
        val host = DonutsAuctionsClient.normalizeServerHost("play.donutsmp.com")
        assertEquals("play.donutsmp.com", host)
        assertTrue(DonutsAuctionsClient.isAllowedAutomationHost(host))
    }

    @Test
    fun `allows donuts host with explicit port`() {
        val host = DonutsAuctionsClient.normalizeServerHost("donutsmp.com:25565")
        assertEquals("donutsmp.com", host)
        assertTrue(DonutsAuctionsClient.isAllowedAutomationHost(host))
    }

    @Test
    fun `blocks singleplayer null and other hosts`() {
        assertFalse(DonutsAuctionsClient.isAllowedAutomationHost(DonutsAuctionsClient.normalizeServerHost(null)))
        assertFalse(DonutsAuctionsClient.isAllowedAutomationHost(DonutsAuctionsClient.normalizeServerHost("singleplayer")))
        assertFalse(DonutsAuctionsClient.isAllowedAutomationHost(DonutsAuctionsClient.normalizeServerHost("hypixel.net")))
        assertFalse(DonutsAuctionsClient.isAllowedAutomationHost(DonutsAuctionsClient.normalizeServerHost("localhost")))
    }
}
