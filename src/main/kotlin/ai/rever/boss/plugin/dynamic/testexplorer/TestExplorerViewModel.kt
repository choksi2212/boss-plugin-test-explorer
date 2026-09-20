package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.api.FileSystemDataProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for the Test Explorer panel.
 *
 * Holds the local UI state the panel cares about: which directory is being watched,
 * the search filter, and the status filter. The parse results themselves come straight
 * from [TestExplorerDirectoryWatcher.watch]; the panel collects that flow.
 */
class TestExplorerViewModel(
    fileSystemDataProvider: FileSystemDataProvider?,
    pluginScope: kotlinx.coroutines.CoroutineScope? = null,
) {
    private val ownedScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val scope: CoroutineScope = pluginScope ?: ownedScope

    private val loader = TestExplorerReportLoader(fileSystemDataProvider)
    val watcher = TestExplorerDirectoryWatcher(loader, scope)

    private val _rootDir = MutableStateFlow<String?>(null)
    val rootDir: StateFlow<String?> = _rootDir.asStateFlow()

    private val _watchEnabled = MutableStateFlow(false)
    val watchEnabled: StateFlow<Boolean> = _watchEnabled.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _statusFilter = MutableStateFlow(StatusFilter.ALL)
    val statusFilter: StateFlow<StatusFilter> = _statusFilter.asStateFlow()

    private val _expandedClass = MutableStateFlow<Set<String>>(emptySet())
    val expandedClass: StateFlow<Set<String>> = _expandedClass.asStateFlow()

    private val _expandedMethod = MutableStateFlow<Set<String>>(emptySet())
    val expandedMethod: StateFlow<Set<String>> = _expandedMethod.asStateFlow()

    /**
     * The flow for the currently-watched directory, or null when no directory is set.
     * Panels collect this in their @Composable and render its latest value.
     */
    fun watchFlow(): StateFlow<WatchState>? {
        val dir = _rootDir.value ?: return null
        if (!_watchEnabled.value) return null
        return watcher.watch(dir)
    }

    /**
     * Run a one-off parse of [dirPath]. Used by the MCP tools, which do not want a watch.
     */
    suspend fun parseOnce(dirPath: String): TestRunResult = loader.load(dirPath)

    fun setRootDir(path: String?) {
        val previous = _rootDir.value
        if (previous != null && previous != path) watcher.stop(previous)
        _rootDir.value = path
        if (path != null && _watchEnabled.value) {
            watcher.watch(path)
        }
    }

    fun setWatchEnabled(enabled: Boolean) {
        _watchEnabled.value = enabled
        val dir = _rootDir.value
        if (!enabled && dir != null) watcher.stop(dir)
        if (enabled && dir != null) watcher.watch(dir)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setStatusFilter(filter: StatusFilter) {
        _statusFilter.value = filter
    }

    fun toggleClassExpanded(classname: String) {
        _expandedClass.value = _expandedClass.value.let { current ->
            if (classname in current) current - classname else current + classname
        }
    }

    fun toggleMethodExpanded(methodId: String) {
        _expandedMethod.value = _expandedMethod.value.let { current ->
            if (methodId in current) current - methodId else current + methodId
        }
    }

    fun dispose() {
        watcher.stopAll()
    }
}

/**
 * Status filter chips above the tree. Filtering by `ALL` shows every case; the rest
 * filter to one of the recorded states.
 */
enum class StatusFilter(val label: String) {
    ALL("All"),
    PASSED("Passed"),
    FAILED("Failed"),
    SKIPPED("Skipped");

    fun matches(status: TestStatus): Boolean = when (this) {
        ALL -> true
        PASSED -> status == TestStatus.PASSED
        FAILED -> status == TestStatus.FAILED || status == TestStatus.ERROR
        SKIPPED -> status == TestStatus.SKIPPED
    }
}
