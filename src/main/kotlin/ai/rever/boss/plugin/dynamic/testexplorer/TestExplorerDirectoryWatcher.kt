package ai.rever.boss.plugin.dynamic.testexplorer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coroutine-based poll loop that re-parses a watched directory on a fixed cadence.
 *
 * The plugin does not depend on filesystem notifications; a 2-second poll is enough for
 * the typical Gradle test loop (run test -> results land within seconds) and avoids the
 * platform-by-platform edge cases of `WatchService`. Multiple `watch` calls on the same
 * directory are coalesced into a single job so two windows asking for the same directory
 * share one parse.
 *
 * The watcher is owned by the plugin's lifetime scope; cancelling that scope disposes
 * every outstanding watch.
 */
class TestExplorerDirectoryWatcher(
    private val loader: TestExplorerReportLoader,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    private val running = mutableMapOf<String, Job>()
    private val results = mutableMapOf<String, MutableStateFlow<WatchState>>()

    /**
     * Start (or reuse) a watch for [dirPath]. Returns the [StateFlow] the UI and MCP
     * tools observe. The flow starts at [WatchState.Idle] and emits a [WatchState.Loaded]
     * once the first parse finishes; subsequent parses replace the value.
     *
     * The flow is shared: every caller observes the same value, so two windows watching
     * one directory do not each pay a parse.
     */
    fun watch(dirPath: String): StateFlow<WatchState> {
        val flow = synchronized(this) {
            results.getOrPut(dirPath) { MutableStateFlow(WatchState.Idle) }
        }

        synchronized(this) {
            running[dirPath]?.let { existing ->
                if (existing.isActive) return flow.asStateFlow()
                existing.cancel()
            }
            val job = scope.launch {
                while (true) {
                    val parsed: Result<TestRunResult> = runCatching { loader.load(dirPath) }
                    val state: WatchState = parsed.fold(
                        onSuccess = { WatchState.Loaded(it) },
                        onFailure = { WatchState.Error(it.message ?: "Parse failed") },
                    )
                    flow.value = state
                    delay(pollIntervalMs)
                }
            }
            running[dirPath] = job
        }

        return flow.asStateFlow()
    }

    /**
     * Stop watching [dirPath]. The associated flow's last value stays so the UI does not
     * flicker; a new [watch] on the same path replaces it.
     */
    fun stop(dirPath: String) {
        synchronized(this) {
            running.remove(dirPath)?.cancel()
        }
    }

    /**
     * Stop every active watch. Called from [TestExplorerDynamicPlugin.dispose] so the
     * coroutines are cancelled with the plugin scope.
     */
    fun stopAll() {
        synchronized(this) {
            running.values.forEach { it.cancel() }
            running.clear()
        }
    }

    companion object {
        /** Default poll cadence. Two seconds is the gradle-test sweet spot. */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 2_000L
    }
}

/**
 * The state observed by a panel or MCP tool waiting on a watched directory.
 *
 * [Idle] is the initial state, before any parse has completed; [Loaded] carries the
 * aggregate parse result; [Error] carries a single message string for the UI.
 */
sealed class WatchState {
    object Idle : WatchState()
    data class Loaded(val result: TestRunResult) : WatchState()
    data class Error(val message: String) : WatchState()
}
