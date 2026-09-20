package ai.rever.boss.plugin.dynamic.testexplorer

/**
 * Status of a single test case as recorded by JUnit XML.
 */
enum class TestStatus {
    PASSED,
    FAILED,
    SKIPPED,
    ERROR;

    companion object {
        fun parse(raw: String?): TestStatus = when {
            raw == null -> PASSED
            raw.equals("passed", ignoreCase = true) -> PASSED
            raw.equals("skipped", ignoreCase = true) -> SKIPPED
            raw.equals("failure", ignoreCase = true) -> FAILED
            raw.equals("error", ignoreCase = true) -> ERROR
            else -> PASSED
        }
    }
}

/**
 * A failure or error attached to a [TestCase].
 *
 * [type] is the throwable class name (e.g. `java.lang.AssertionError`); [message] is the
 * first line of the throwable message; [stackTrace] is the full text including the message
 * (the parser concatenates `<failure>`/`<error>` element text, which is the stack trace in
 * standard JUnit output).
 */
data class TestFailure(
    val type: String?,
    val message: String?,
    val stackTrace: String,
)

/**
 * A single `<testcase>` row.
 */
data class TestCase(
    val classname: String,
    val name: String,
    val timeSeconds: Double,
    val status: TestStatus,
    val failure: TestFailure? = null,
) {
    /** Stable identity used as a key in collections and the tree view. */
    val id: String get() = "$classname#$name"
}

/**
 * A `<testsuite>` row. One suite holds the cases that share a common class name; a Gradle
 * test task typically emits one suite per test class. The parser merges multiple suites
 * that share a [name] into one row.
 */
data class TestSuite(
    val name: String,
    val timeSeconds: Double,
    val cases: List<TestCase>,
)

/**
 * A parsed JUnit XML report. The [sourceFile] records which file the report came from, so
 * the UI can show it and the MCP tools can return it.
 */
data class TestReport(
    val sourceFile: String,
    val suites: List<TestSuite>,
)

/**
 * Aggregate totals across every parsed [TestReport] in the watched directory.
 */
data class TestSummary(
    val tests: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val errored: Int,
    val timeSeconds: Double,
    val failures: List<FailureEntry>,
)

/**
 * One row in the aggregate [TestSummary.failures] list. The MCP `summarize` tool returns
 * this shape so an agent can iterate without parsing the raw report tree.
 */
data class FailureEntry(
    val reportFile: String,
    val suite: String,
    val classname: String,
    val method: String,
    val message: String?,
    val type: String?,
)

/**
 * The full result of parsing every JUnit XML file in a directory tree.
 *
 * [reports] is per-file; [summary] is the aggregate the panel header and `summarize` show.
 */
data class TestRunResult(
    val rootDir: String,
    val reports: List<TestReport>,
    val summary: TestSummary,
)
