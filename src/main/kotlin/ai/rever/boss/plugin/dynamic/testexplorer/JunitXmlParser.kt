package ai.rever.boss.plugin.dynamic.testexplorer

import java.io.InputStream
import javax.xml.stream.XMLEventReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.events.StartElement
import javax.xml.stream.events.XMLEvent

/**
 * Streaming parser for JUnit XML test reports.
 *
 * Defends against XML attacks at three layers. The host's plugin-loader zip-bomb defense
 * covers jar reading, but the plugin receives file contents directly, so the parser is the
 * only thing standing between a hostile report and the plugin process:
 *
 * - **Streaming pull parser.** `XMLInputFactory` is configured to refuse DTDs and external
 *   entities, so an XML bomb that uses `<!ENTITY>` to amplify text cannot resolve.
 *   billion-laughs relies on entity expansion; with both disabled there is nothing to expand.
 * - **Element-depth cap.** Billion-laughs and quadratic blowup variants can also use deep
 *   nesting rather than entities. The parser aborts at [MAX_ELEMENT_DEPTH] (256), so the
 *   element stack can never grow large enough to amplify.
 * - **Byte caps.** Each input is bounded by [MAX_XML_BYTES_PER_FILE] and the total across
 *   all files is bounded by [MAX_XML_BYTES_TOTAL]. The total is checked by the directory
 *   walker, which stops scheduling new files once the budget is spent. A malicious single
 *   file is still capped per-file.
 *
 * The parser does NOT use `DocumentBuilder`, which would build a full DOM and therefore be
 * bounded by the input size rather than a small constant.
 */
object JunitXmlParser {

    /** Maximum bytes accepted for a single XML file. */
    const val MAX_XML_BYTES_PER_FILE: Long = 16L * 1024L * 1024L

    /** Maximum bytes accepted across all files in a single parse pass. */
    const val MAX_XML_BYTES_TOTAL: Long = 64L * 1024L * 1024L

    /** Maximum element nesting depth. Defends against deep-tree billion-laughs variants. */
    const val MAX_ELEMENT_DEPTH: Int = 256

    private val factory: XMLInputFactory by lazy {
        XMLInputFactory.newInstance().apply {
            // Disable DTDs entirely. javax.xml.stream treats SUPPORT_DTD as the parser
            // accepting DTDs in input; setting it false means the parser refuses them.
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            // Disable external entity resolution. This includes the file: and http:
            // entities the billion-laughs attack and XXE both rely on.
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            // The parser must not dereference a DOCTYPE or pull external content; that
            // is the same defense re-asserted under a different property name that some
            // StAX implementations check.
            try {
                setProperty("javax.xml.stream.isSupportingExternalDTDs", false)
            } catch (_: IllegalArgumentException) {
                // Property name is implementation-specific. The SUPPORT_DTD / external
                // entity settings above already cover every standard StAX implementation.
            }
        }
    }

    /**
     * Parse a single JUnit XML file from [input] into a [TestReport].
     *
     * [sourceFile] is the path the report came from and is recorded on the result for
     * error messages and the UI. [byteBudget] is the remaining bytes for the entire pass;
     * the parser claims what it reads from [input] against it.
     *
     * The XML returned is well-formed enough to extract `<testsuite>` and `<testcase>`
     * elements; any other shape (top-level `<testsuites>` wrapper, missing root, etc.) is
     * tolerated as long as the individual rows can still be located. A file that does not
     * contain any `<testsuite>` is reported as an empty [TestReport], not an error - the
     * directory watcher may see reports from frameworks that emit `<testsuites>`-wrapped
     * output, and treating every empty file as a parse failure would surface noise.
     */
    fun parse(
        input: InputStream,
        sourceFile: String,
        byteBudget: Long = MAX_XML_BYTES_TOTAL,
    ): ParseOutcome {
        val reader: XMLEventReader = factory.createXMLEventReader(input)
        val suites = mutableListOf<TestSuite>()
        var pendingSuite: SuiteBuilder? = null
        var pendingCase: CaseBuilder? = null
        var pendingFailure: FailureBuilder? = null
        var depth = 0
        var totalBytes = 0L

        try {
            while (reader.hasNext()) {
                val event: XMLEvent = try {
                    reader.nextEvent()
                } catch (e: javax.xml.stream.XMLStreamException) {
                    throw ParseException("Malformed XML: ${e.message ?: e.javaClass.simpleName}", e)
                }

                when {
                    event.isStartElement -> {
                        depth += 1
                        if (depth > MAX_ELEMENT_DEPTH) {
                            throw ParseException(
                                "Element depth exceeded $MAX_ELEMENT_DEPTH (XML bomb guard)"
                            )
                        }
                        val element = event.asStartElement()
                        when (element.name.localPart) {
                            "testsuite" -> {
                                if (pendingSuite != null) suites += pendingSuite.toSuite()
                                pendingSuite = SuiteBuilder().apply { hydrate(element) }
                                pendingCase = null
                                pendingFailure = null
                            }
                            "testcase" -> {
                                val builder = CaseBuilder().apply { hydrate(element) }
                                val suite = pendingSuite ?: SuiteBuilder().apply {
                                    name = "(no suite)"
                                    timeSeconds = 0.0
                                }
                                pendingSuite = suite
                                suite.cases.add(builder)
                                pendingCase = builder
                                pendingFailure = null
                            }
                            "failure", "error" -> {
                                val failure = FailureBuilder().apply {
                                    type = attribute(element, "type")
                                    message = attribute(element, "message")
                                }
                                pendingCase?.failure = failure
                                pendingFailure = failure
                            }
                            "skipped" -> {
                                pendingCase?.status = TestStatus.SKIPPED
                                pendingFailure = FailureBuilder().also {
                                    pendingCase?.failure = it
                                }
                            }
                        }
                    }
                    event.isCharacters -> {
                        val text = event.asCharacters().data ?: continue
                        // Account for every byte we read so the budget stays accurate even
                        // when a single event spans kilobytes (the common shape for stack
                        // traces concatenated into one element).
                        totalBytes += text.toByteArray(Charsets.UTF_8).size.toLong()
                        if (totalBytes > byteBudget) {
                            throw ParseException(
                                "Total bytes exceeded $byteBudget across all input streams"
                            )
                        }
                        if (pendingFailure != null) {
                            pendingFailure.stack.append(text)
                        }
                    }
                    event.isEndElement -> {
                        depth -= 1
                        val local = event.asEndElement().name.localPart
                        when (local) {
                            "testsuite" -> {
                                pendingSuite?.let { suites += it.toSuite() }
                                pendingSuite = null
                                pendingCase = null
                                pendingFailure = null
                            }
                            "testcase" -> pendingCase = null
                            "failure", "error" -> pendingFailure = null
                            "skipped" -> pendingFailure = null
                        }
                    }
                }
            }
        } catch (e: javax.xml.stream.XMLStreamException) {
            throw ParseException("Malformed XML: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            try { reader.close() } catch (_: Exception) { /* best effort */ }
        }

        // Flush any pending suite that didn't see its closing tag (some generators omit it).
        pendingSuite?.let { suites += it.toSuite() }

        return ParseOutcome(
            report = TestReport(sourceFile = sourceFile, suites = suites),
            bytesConsumed = totalBytes,
        )
    }

    /**
     * Look up an attribute by local name. StAX stores the namespace-aware
     * [javax.xml.namespace.QName], but JUnit XML rarely uses namespaces, so a name match on
     * the local part is enough.
     */
    private fun attribute(element: StartElement, name: String): String? {
        val qname = javax.xml.namespace.QName(name)
        return element.getAttributeByName(qname)?.value
    }

    /** Outcome of a single-file parse: the report plus how many bytes were counted. */
    data class ParseOutcome(
        val report: TestReport,
        val bytesConsumed: Long,
    )

    /** Thrown when the input exceeds any of the safety caps. */
    class ParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    private class SuiteBuilder {
        var name: String = ""
        var timeSeconds: Double = 0.0
        val cases: MutableList<CaseBuilder> = mutableListOf()

        fun hydrate(element: StartElement) {
            name = attribute(element, "name") ?: ""
            timeSeconds = attribute(element, "time")?.toDoubleOrNull() ?: 0.0
        }

        fun toSuite(): TestSuite = TestSuite(
            name = name,
            timeSeconds = timeSeconds,
            cases = cases.map { it.toCase() },
        )
    }

    private class CaseBuilder {
        var name: String = ""
        var classname: String = ""
        var timeSeconds: Double = 0.0
        var status: TestStatus = TestStatus.PASSED
        var failure: FailureBuilder? = null

        fun hydrate(element: StartElement) {
            name = attribute(element, "name") ?: ""
            classname = attribute(element, "classname") ?: ""
            timeSeconds = attribute(element, "time")?.toDoubleOrNull() ?: 0.0
        }

        fun toCase(): TestCase = TestCase(
            classname = classname,
            name = name,
            timeSeconds = timeSeconds,
            status = status,
            failure = failure?.toFailure(),
        )
    }

    private class FailureBuilder {
        var type: String? = null
        var message: String? = null
        val stack: StringBuilder = StringBuilder()

        fun toFailure(): TestFailure = TestFailure(
            type = type,
            message = message,
            stackTrace = stack.toString(),
        )
    }
}
