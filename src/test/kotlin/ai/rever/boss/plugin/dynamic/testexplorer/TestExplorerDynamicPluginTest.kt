package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.TestExplorerDynamicPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestExplorerDynamicPluginTest {
    @Test
    fun `plugin class is instantiable and exposes the documented id`() {
        val plugin = TestExplorerDynamicPlugin()
        assertTrue(plugin.pluginId.isNotBlank(), "pluginId must not be blank")
        assertTrue(plugin.pluginId.contains('.'), "pluginId must be reverse-domain: " + plugin.pluginId)
    }

    @Test
    fun `plugin class exposes a non-blank display name and semver version`() {
        val plugin = TestExplorerDynamicPlugin()
        assertTrue(plugin.displayName.isNotBlank(), "displayName must not be blank")
        assertTrue(plugin.version.isNotBlank(), "version must not be blank")
        assertTrue(plugin.version.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+")), "version must be semver: " + plugin.version)
    }

    @Test
    fun `plugin author attribution is choksi2212 - not Risa Labs or empty`() {
        val plugin = TestExplorerDynamicPlugin()
        assertEquals("choksi2212", plugin.author, "plugin author must be choksi2212, was '" + plugin.author + "'")
    }
}
