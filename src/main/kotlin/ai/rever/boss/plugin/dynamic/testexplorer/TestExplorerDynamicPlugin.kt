package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Test Explorer dynamic plugin entry point.
 *
 * Wires the shared parse + watcher services into the panel factory and the MCP tool
 * provider. The two consumers share the same [TestExplorerReportLoader] and the same
 * [TestExplorerDirectoryWatcher], so "watch this directory" from the MCP and the panel
 * are the same watch.
 *
 * Disposing the plugin cancels every outstanding watch via the plugin scope.
 */
class TestExplorerDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.testexplorer"
    override val displayName: String = "Test Explorer"
    override val version: String = "0.1.0"
    override val description: String = "Structured JUnit XML viewer for BOSS"
    override val author: String = "choksi2212"
    override val url: String = "https://github.com/choksi2212/boss-plugin-test-explorer"

    private var context: PluginContext? = null
    private var loader: TestExplorerReportLoader? = null
    private var watcher: TestExplorerDirectoryWatcher? = null
    private var watchEnabledRef: WatchEnabledRef? = null
    private var activeDirectoryRef: ActiveDirectoryRef? = null

    override fun register(context: PluginContext) {
        this.context = context
        val fs = context.fileSystemDataProvider
        val loader = TestExplorerReportLoader(fs)
        val watcher = TestExplorerDirectoryWatcher(loader, context.pluginScope)
        val watchEnabledRef = WatchEnabledRef()
        val activeDirectoryRef = ActiveDirectoryRef()
        this.loader = loader
        this.watcher = watcher
        this.watchEnabledRef = watchEnabledRef
        this.activeDirectoryRef = activeDirectoryRef

        context.panelRegistry.registerPanel(TestExplorerInfo) { ctx, panelInfo ->
            TestExplorerComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                fileSystemDataProvider = fs,
                pluginScope = context.pluginScope,
            )
        }

        context.registerMcpToolProvider(
            TestExplorerMcpToolProvider(
                providerId = pluginId,
                loader = loader,
                watcher = watcher,
                watchEnabledRef = watchEnabledRef,
                activeDirectoryRef = activeDirectoryRef,
            )
        )
    }

    override fun dispose() {
        watcher?.stopAll()
        context?.unregisterMcpToolProvider(pluginId)
        watcher = null
        loader = null
        watchEnabledRef = null
        activeDirectoryRef = null
        context = null
    }
}
