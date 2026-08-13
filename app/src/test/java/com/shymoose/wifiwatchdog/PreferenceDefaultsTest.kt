package com.shymoose.wifiwatchdog

import java.io.File
import java.lang.reflect.Modifier
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards the one preference contract the compiler cannot see.
 *
 * `ConfigServer` builds its web form from the `Prefs.DEFAULT_*` constants, so that half
 * of the schema is checked at compile time. `root_preferences.xml` repeats the same
 * defaults as string literals and nothing ties the two together, so drift there ships
 * silently: the on-device settings screen and the browser config page simply disagree,
 * and the difference only surfaces on a device that has never written the key.
 *
 * These assertions read the real XML off disk rather than through Android resources, so
 * they run as plain JVM unit tests with no emulator and no Robolectric.
 */
class PreferenceDefaultsTest {

    /** Keys that deliberately have no entry in the settings XML. */
    private val notInSettingsScreen = setOf(Prefs.KEY_ENABLED)

    /**
     * Every key whose default is duplicated in XML, paired with the Kotlin value that is
     * the source of truth. Booleans are listed as their literal XML spelling because
     * `Prefs` supplies those fallbacks inline in its getters rather than as constants.
     */
    private val expectedDefaults = mapOf(
        Prefs.KEY_PROBE_HOST to Prefs.DEFAULT_HOST,
        Prefs.KEY_PROBE_PORT to Prefs.DEFAULT_PORT,
        Prefs.KEY_INTERVAL to Prefs.DEFAULT_INTERVAL.toString(),
        Prefs.KEY_T_REASSOCIATE to Prefs.DEFAULT_T_REASSOCIATE.toString(),
        Prefs.KEY_T_SOFT to Prefs.DEFAULT_T_SOFT.toString(),
        Prefs.KEY_T_HARD to Prefs.DEFAULT_T_HARD.toString(),
        Prefs.KEY_T_AIRPLANE to Prefs.DEFAULT_T_AIRPLANE.toString(),
        Prefs.KEY_AIRPLANE_DWELL to Prefs.DEFAULT_AIRPLANE_DWELL.toString(),
        Prefs.KEY_NTFY_URL to Prefs.DEFAULT_NTFY_URL,
        Prefs.KEY_HEARTBEAT_INTERVAL to Prefs.DEFAULT_HEARTBEAT_INTERVAL.toString(),
        Prefs.KEY_HARD_ENABLED to "true",
        Prefs.KEY_AIRPLANE_ENABLED to "true"
    )

    @Test
    fun `xml defaults match the Prefs constants`() {
        val byKey = settingsEntries().associateBy { it.key }
        expectedDefaults.forEach { (key, expected) ->
            val entry = byKey[key] ?: error("'$key' is missing from root_preferences.xml")
            assertEquals(
                "root_preferences.xml default for '$key' has drifted from Prefs",
                expected,
                entry.default
            )
        }
    }

    @Test
    fun `every settings key is known to Prefs`() {
        val known = prefsKeys().values.toSet()
        settingsEntries().forEach { entry ->
            assertTrue(
                "root_preferences.xml declares '${entry.key}', which no Prefs constant uses",
                entry.key in known
            )
        }
    }

    @Test
    fun `every Prefs key is reachable from the settings screen`() {
        val declared = settingsEntries().map { it.key }.toSet()
        prefsKeys().forEach { (constant, key) ->
            if (key in notInSettingsScreen) return@forEach
            assertTrue(
                "Prefs.$constant ('$key') has no entry in root_preferences.xml, so the " +
                    "on-device settings screen cannot reach it",
                key in declared
            )
        }
    }

    @Test
    fun `xml declares a default only where Prefs expects one`() {
        settingsEntries().filter { it.hasDefault }.forEach { entry ->
            assertTrue(
                "root_preferences.xml sets a default for '${entry.key}' that Prefs does " +
                    "not mirror, so the settings screen and the web page will disagree",
                entry.key in expectedDefaults
            )
        }
    }

    @Test
    fun `the pinned-host default port matches the gateway probe port`() {
        // Prefs.DEFAULT_PORT documents itself as mirroring NetProbe.GATEWAY_PORT so that a
        // pinned host behaves like the gateway probe. Keep that KDoc honest.
        assertEquals(NetProbe.GATEWAY_PORT.toString(), Prefs.DEFAULT_PORT)
    }

    // ------------------------------------------------------------------ helpers

    private class Entry(val key: String, val hasDefault: Boolean, val default: String)

    /** Every element in the settings XML that declares a preference key. */
    private fun settingsEntries(): List<Entry> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(settingsXml())
        val nodes = document.getElementsByTagName("*")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.hasAttribute(ATTR_KEY) }
            .map {
                Entry(
                    key = it.getAttribute(ATTR_KEY),
                    hasDefault = it.hasAttribute(ATTR_DEFAULT),
                    default = it.getAttribute(ATTR_DEFAULT)
                )
            }
    }

    /**
     * Public `KEY_*` constants on [Prefs], as constant name to stored key. Reflection
     * rather than a hand-written list, so a newly added preference is caught here instead
     * of quietly missing from one of the two settings surfaces.
     */
    private fun prefsKeys(): Map<String, String> =
        Prefs::class.java.declaredFields
            .filter { it.name.startsWith("KEY_") }
            .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }
            .associate { it.name to (it.get(null) as String) }

    private fun settingsXml(): File =
        SETTINGS_XML_CANDIDATES.map(::File).firstOrNull { it.isFile }
            ?: error(
                "root_preferences.xml not found from working directory " +
                    File(".").absolutePath
            )

    private companion object {
        const val ATTR_KEY = "app:key"
        const val ATTR_DEFAULT = "app:defaultValue"

        /** Gradle runs unit tests from the module directory; the second entry covers a repo-root run. */
        val SETTINGS_XML_CANDIDATES = listOf(
            "src/main/res/xml/root_preferences.xml",
            "app/src/main/res/xml/root_preferences.xml"
        )
    }
}
