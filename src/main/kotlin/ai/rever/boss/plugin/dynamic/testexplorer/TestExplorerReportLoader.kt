package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.api.FileSystemDataProvider
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Walks a directory tree looking for JUnit XML files and parses each one.
 *
 * The walker is the second half of the safety story: the [JunitXmlParser] guards each
 * individual file, and this class enforces the total byte budget across the entire pass.
 * Once the budget is exhausted, no further files are opened.
 *
 * Files are read through [FileSystemDataProvider.readFile] so the host's path safety
 * applies: a path that resolves outside the watched root or escapes a symlink is refused
 * by the provider before it ever reaches the parser. The fallback `File` read exists for
 * the unit tests, which do not have a host provider available; the production path is
 * always through the provider.
 */
class TestExplorerReportLoader(
    private val fileSystemDataProvider: FileSystemDataProvider?,
    private val maxBytesPerFile: Long = JunitXmlParser.MAX_XML_BYTES_PER_FILE,
    private val maxBytesTotal: Long = JunitXmlParser.MAX_XML_BYTES_TOTAL,
) {

    /**
     * Parse every JUnit XML file under [rootDir]. The walk is shallow by default - one
     * level deep, which is the Gradle convention of placing XML files in
     * `build/test-results/test/` - but [maxDepth] can be raised for projects that emit
     * results into deeper subdirectories.
     *
     * Returns a [TestRunResult] with the per-file reports and an aggregate summary. The
     * summary is built across every report, so the same answer the panel header shows is
     * what the MCP `summarize` tool returns.
     */
    suspend fun load(rootDir: String, maxDepth: Int = 4): TestRunResult {
        val paths = listXmlFiles(rootDir, maxDepth)
        val reports = mutableListOf<TestReport>()
        var remainingBudget = maxBytesTotal

        for (path in paths) {
            if (remainingBudget <= 0) break
            val outcome = runCatching { parseOne(path, remainingBudget) }.getOrNull() ?: continue
            reports += outcome.report
            remainingBudget -= outcome.bytesConsumed
            // A negative remaining budget means the file was already over the limit; the
            // parser would have thrown. Clamp so the next iteration sees zero.
            if (remainingBudget < 0) remainingBudget = 0
        }

        return TestRunResult(
            rootDir = rootDir,
            reports = reports,
            summary = summarize(reports),
        )
    }

    private suspend fun parseOne(path: String, byteBudget: Long): JunitXmlParser.ParseOutcome {
        val stream = openStream(path, byteBudget)
        try {
            return JunitXmlParser.parse(stream, sourceFile = path, byteBudget = byteBudget)
        } finally {
            try { stream.close() } catch (_: Exception) { /* best effort */ }
        }
    }

    /**
     * Open [path] through the provider when one is available; fall back to a bounded
     * `java.io.File` read when not. The bounded read is a [ByteArrayInputStream] over the
     * first [maxBytesPerFile] + 1 bytes; the +1 lets the parser reject files that are
     * larger than the per-file cap with a clear message rather than a silent truncation.
     */
    private suspend fun openStream(path: String, byteBudget: Long): InputStream {
        val provider = fileSystemDataProvider
        if (provider != null) {
            val read = provider.readFile(path)
            val text = read.getOrElse {
                throw JunitXmlParser.ParseException("Failed to read $path: ${it.message ?: "unknown"}")
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > maxBytesPerFile) {
                throw JunitXmlParser.ParseException(
                    "File $path is ${bytes.size} bytes, exceeds $maxBytesPerFile"
                )
            }
            if (bytes.size > byteBudget) {
                throw JunitXmlParser.ParseException(
                    "File $path is ${bytes.size} bytes, exceeds remaining budget $byteBudget"
                )
            }
            return ByteArrayInputStream(bytes)
        }

        // No provider: read directly from the filesystem. Bounded so the parser never
        // sees a multi-gigabyte blob from a hostile path.
        val file = java.io.File(path)
        if (!file.exists() || !file.isFile) {
            throw JunitXmlParser.ParseException("File $path does not exist")
        }
        if (file.length() > maxBytesPerFile) {
            throw JunitXmlParser.ParseException(
                "File $path is ${file.length()} bytes, exceeds $maxBytesPerFile"
            )
        }
        if (file.length() > byteBudget) {
            throw JunitXmlParser.ParseException(
                "File $path is ${file.length()} bytes, exceeds remaining budget $byteBudget"
            )
        }
        return file.inputStream()
    }

    /**
     * List every file ending in `.xml` under [rootDir] up to [maxDepth]. Depth 0 is the
     * root itself; depth 1 is its direct children.
     *
     * Walks through the provider's directory scan so the host's path safety applies. When
     * the provider is absent (unit tests), the fallback is a plain recursive [java.io.File]
     * walk, with hidden directories skipped to keep the surface tight.
     */
    private suspend fun listXmlFiles(rootDir: String, maxDepth: Int): List<String> {
        val provider = fileSystemDataProvider
        if (provider != null) {
            return listViaProvider(rootDir, maxDepth)
        }
        return listViaFile(rootDir, maxDepth)
    }

    private suspend fun listViaProvider(rootDir: String, maxDepth: Int): List<String> {
        val collected = mutableListOf<String>()
        val root = providerScan(provider = fileSystemDataProvider!!, path = rootDir, depth = 0, maxDepth = maxDepth, sink = collected)
        // root == null when the directory does not exist; the directory watcher interprets
        // an empty list as "no reports yet" rather than an error.
        return collected
    }

    private suspend fun providerScan(
        provider: FileSystemDataProvider,
        path: String,
        depth: Int,
        maxDepth: Int,
        sink: MutableList<String>,
    ) {
        if (depth > maxDepth) return
        val node = provider.scanDirectory(path) ?: return
        for (child in node.children) {
            if (child.isDirectory) {
                providerScan(provider, child.path, depth + 1, maxDepth, sink)
            } else if (child.name.endsWith(".xml", ignoreCase = true)) {
                sink += child.path
            }
        }
    }

    private fun listViaFile(rootDir: String, maxDepth: Int): List<String> {
        val root = java.io.File(rootDir)
        if (!root.exists() || !root.isDirectory) return emptyList()
        val collected = mutableListOf<String>()
        walkFile(root, 0, maxDepth, collected)
        return collected
    }

    private fun walkFile(dir: java.io.File, depth: Int, maxDepth: Int, sink: MutableList<String>) {
        if (depth > maxDepth) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (entry.isHidden) continue
            if (entry.isDirectory) {
                walkFile(entry, depth + 1, maxDepth, sink)
            } else if (entry.name.endsWith(".xml", ignoreCase = true)) {
                sink += entry.absolutePath
            }
        }
    }

    companion object {

        /**
         * Build an aggregate [TestSummary] across every [TestReport] in [reports]. The
         * summary is the data both the panel header and the MCP `summarize` tool display.
         */
        fun summarize(reports: List<TestReport>): TestSummary {
            var tests = 0
            var passed = 0
            var failed = 0
            var skipped = 0
            var errored = 0
            var time = 0.0
            val failures = mutableListOf<FailureEntry>()

            for (report in reports) {
                for (suite in report.suites) {
                    time += suite.timeSeconds
                    for (case in suite.cases) {
                        tests += 1
                        when (case.status) {
                            TestStatus.PASSED -> passed += 1
                            TestStatus.FAILED -> {
                                failed += 1
                                failures += FailureEntry(
                                    reportFile = report.sourceFile,
                                    suite = suite.name,
                                    classname = case.classname,
                                    method = case.name,
                                    message = case.failure?.message,
                                    type = case.failure?.type,
                                )
                            }
                            TestStatus.SKIPPED -> skipped += 1
                            TestStatus.ERROR -> {
                                errored += 1
                                failures += FailureEntry(
                                    reportFile = report.sourceFile,
                                    suite = suite.name,
                                    classname = case.classname,
                                    method = case.name,
                                    message = case.failure?.message,
                                    type = case.failure?.type,
                                )
                            }
                        }
                    }
                }
            }

            return TestSummary(
                tests = tests,
                passed = passed,
                failed = failed,
                skipped = skipped,
                errored = errored,
                timeSeconds = time,
                failures = failures,
            )
        }
    }
}
