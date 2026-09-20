package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.runBlocking

/**
 * MCP tool surface for the Test Explorer.
 *
 * Four tools. Two read summaries (`summarize`, `failure_messages`) for an agent triaging
 * a failure; two control the directory watcher (`watch_start` / `watch_stop`) so an
 * agent can opt into continuous refresh.
 *
 * The tools share the same [TestExplorerReportLoader] the panel uses, so MCP answers are
 * always what the user sees. The shared [TestExplorerDirectoryWatcher] is reused for
 * `watch_*` so toggling the watch from the panel or the MCP produces one parse job per
 * directory, not two.
 */
internal class TestExplorerMcpToolProvider(
    override val providerId: String,
    private val loader: TestExplorerReportLoader,
    private val watcher: TestExplorerDirectoryWatcher,
    private val watchEnabledRef: WatchEnabledRef,
    private val activeDirectoryRef: ActiveDirectoryRef,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "test_explorer_summarize",
            description = "Parse every JUnit XML file under a directory and return a summary: " +
                "test counts (passed/failed/skipped), total time, and the list of failures " +
                "with class, method, message and type.",
            inputSchema = SUMMARIZE_SCHEMA,
            handler = McpToolHandler { args ->
                val dir = args.string("dirPath")
                    ?: return@McpToolHandler McpToolResult(
                        "Missing required argument: dirPath",
                        isError = true,
                    )
                summarize(dir)
            },
        ),
        McpToolDefinition(
            name = "test_explorer_failure_messages",
            description = "Return the full failure message and stack trace for every failing " +
                "test under a directory. One entry per failure, formatted as " +
                "`report | suite | class#method | message`.",
            inputSchema = SUMMARIZE_SCHEMA,
            handler = McpToolHandler { args ->
                val dir = args.string("dirPath")
                    ?: return@McpToolHandler McpToolResult(
                        "Missing required argument: dirPath",
                        isError = true,
                    )
                failureMessages(dir)
            },
        ),
        McpToolDefinition(
            name = "test_explorer_watch_start",
            description = "Begin watching a directory: re-parse every 2 seconds and emit a fresh " +
                "summary as the underlying JUnit XML files change. Idempotent; calling on an " +
                "already-watched directory is a no-op.",
            inputSchema = SUMMARIZE_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val dir = args.string("dirPath")
                    ?: return@McpToolHandler McpToolResult(
                        "Missing required argument: dirPath",
                        isError = true,
                    )
                watcher.watch(dir)
                activeDirectoryRef.value = dir
                watchEnabledRef.value = true
                McpToolResult("Watching $dir")
            },
        ),
        McpToolDefinition(
            name = "test_explorer_watch_stop",
            description = "Stop watching a directory previously started by watch_start. The " +
                "panel's manual parse is unaffected.",
            inputSchema = SUMMARIZE_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val dir = args.string("dirPath")
                    ?: return@McpToolHandler McpToolResult(
                        "Missing required argument: dirPath",
                        isError = true,
                    )
                watcher.stop(dir)
                if (activeDirectoryRef.value == dir) {
                    activeDirectoryRef.value = null
                    watchEnabledRef.value = false
                }
                McpToolResult("Stopped watching $dir")
            },
        ),
    )

    private fun summarize(dirPath: String): McpToolResult = runBlocking {
        val result: Result<TestRunResult> = runCatching { loader.load(dirPath) }
        result.fold(
            onSuccess = { McpToolResult(formatSummary(it.summary, dirPath)) },
            onFailure = { McpToolResult("Failed to parse $dirPath: ${it.message}", isError = true) },
        )
    }

    private fun failureMessages(dirPath: String): McpToolResult = runBlocking {
        val result: Result<TestRunResult> = runCatching { loader.load(dirPath) }
        result.fold(
            onSuccess = { McpToolResult(formatFailures(it)) },
            onFailure = { McpToolResult("Failed to parse $dirPath: ${it.message}", isError = true) },
        )
    }

    private fun formatSummary(summary: TestSummary, rootDir: String): String = buildString {
        appendLine("Directory: $rootDir")
        appendLine("Tests: ${summary.tests}  Passed: ${summary.passed}  " +
            "Failed: ${summary.failed}  Skipped: ${summary.skipped}  " +
            "Errored: ${summary.errored}  Time: %.2fs".format(summary.timeSeconds))
        if (summary.failures.isEmpty()) return@buildString
        appendLine()
        appendLine("Failures (${summary.failures.size}):")
        for (entry in summary.failures) {
            appendLine("- ${entry.classname}#${entry.method}  [${entry.suite}]")
            if (!entry.type.isNullOrBlank()) appendLine("  type: ${entry.type}")
            if (!entry.message.isNullOrBlank()) appendLine("  message: ${entry.message}")
        }
    }

    private fun formatFailures(result: TestRunResult): String {
        if (result.summary.failures.isEmpty()) {
            return "No failures under ${result.rootDir}"
        }
        return buildString {
            appendLine("Failures under ${result.rootDir}:")
            for (entry in result.summary.failures) {
                appendLine("---")
                appendLine("File: ${entry.reportFile}")
                appendLine("Suite: ${entry.suite}")
                appendLine("Class: ${entry.classname}")
                appendLine("Method: ${entry.method}")
                if (!entry.type.isNullOrBlank()) appendLine("Type: ${entry.type}")
                if (!entry.message.isNullOrBlank()) appendLine("Message: ${entry.message}")
            }
        }
    }

    private companion object {
        const val SUMMARIZE_SCHEMA =
            """{"type":"object","properties":{"dirPath":{"type":"string","description":"Absolute path to a directory containing JUnit XML reports."}},"required":["dirPath"]}"""
    }
}

/**
 * Holder for the watch-enabled flag the MCP tools flip when `watch_start` is called.
 *
 * The MCP tools and the panel share the same backing store so toggling either flips both.
 * A small holder avoids exposing the [TestExplorerViewModel] itself across the API
 * surface, since the tool provider should depend on the loader, not the UI.
 */
class WatchEnabledRef(initial: Boolean = false) {
    @Volatile var value: Boolean = initial
}

/**
 * Holder for the currently-watched directory. Same pattern as [WatchEnabledRef]: a small
 * shared reference that lets the MCP tools and the panel observe the same state.
 */
class ActiveDirectoryRef(initial: String? = null) {
    @Volatile var value: String? = initial
}
