package com.shymoose.wifiwatchdog

import org.junit.Assert.*
import org.junit.Test

class WifiEnablePromptTest {
    private val prompt = WifiEnablePrompt(
        "Allow Kiosk Watchdog to turn on Wi-Fi?",
        "You can turn off Wi-Fi in Quick Settings",
        "Allow",
        20_000L
    )
    private val texts = listOf(prompt.title, prompt.message, prompt.allow, "Deny")

    @Test fun `system dialog for own active request matches`() {
        assertTrue(prompt.matches(WifiEnablePrompt.PACKAGE, texts, 19_999L))
    }

    @Test fun `another app request is not approved`() {
        assertFalse(prompt.matches(
            WifiEnablePrompt.PACKAGE,
            listOf("Allow Another App to turn on Wi-Fi?", prompt.message, prompt.allow),
            1L
        ))
    }

    @Test fun `lookalike title in another package is not approved`() {
        assertFalse(prompt.matches("example.dialog", texts, 1L))
        assertFalse(prompt.matches(null, texts, 1L))
    }

    @Test fun `expired request is not approved`() {
        assertFalse(prompt.matches(WifiEnablePrompt.PACKAGE, texts, 20_000L))
    }

    @Test fun `title substring is not a match`() {
        assertFalse(prompt.matches(
            WifiEnablePrompt.PACKAGE, listOf("Other ${prompt.title}", prompt.message), 1L
        ))
    }

    @Test fun `different system dialog is not approved`() {
        assertFalse(prompt.matches(WifiEnablePrompt.PACKAGE, listOf(prompt.title, "Different message"), 1L))
    }

    @Test fun `localized text is matched without English assumptions`() {
        val localized = WifiEnablePrompt("Titel", "Nachricht", "Zulassen", 20_000L)
        assertTrue(localized.matches(WifiEnablePrompt.PACKAGE, listOf("Titel", "Nachricht"), 1L))
    }
}
