package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy

/**
 * Decompose-hosted panel component for the Test Explorer.
 *
 * Owns the [TestExplorerViewModel] for this panel instance. The view model is shared
 * between the panel UI and the MCP tool provider so that "watch this directory" started
 * from one place is visible from the other.
 */
class TestExplorerComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    fileSystemDataProvider: FileSystemDataProvider?,
    pluginScope: kotlinx.coroutines.CoroutineScope?,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = TestExplorerViewModel(fileSystemDataProvider, pluginScope)

    init {
        // Decompose lifecycle: dispose the view model when the panel is removed.
        lifecycle.doOnDestroy {
            viewModel.dispose()
        }
    }

    @Composable
    override fun Content() {
        TestExplorerContent(viewModel)
    }
}
