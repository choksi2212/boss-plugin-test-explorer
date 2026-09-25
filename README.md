# BOSS Test Explorer

A structured JUnit XML viewer for BOSS. Parses test reports from a directory and renders the
result as a tree of suites -> classes -> methods, with status icons, per-test timings,
failure messages, and an MCP surface for agent-driven test triage.

This is the first standalone JUnit-XML viewer for BOSS. Today the only feedback for a test
run is scrollback in the Build & Test log, or a raw XML file nobody opens. This plugin
turns those reports into a navigable tree you can search, filter, and refresh in place.

## What it does

- **Tree view** of suites, classes, and methods with one row each.
- **Header summary**: total tests, passed, failed, skipped, total time.
- **Status icons** per row (passed / failed / errored / skipped) with per-row colour.
- **Search box** that filters by class name, method name, or failure message substring.
- **Status filter chips** (All / Passed / Failed / Skipped).
- **Click a failed test** to expand a panel with the full message, type, and stack trace.
- **Watch toggle**: re-parses the directory every 2 seconds when on, so a fresh test run
  appears without re-entering anything.
- **MCP tools** for agent-driven triage.

## What it parses

Standard JUnit XML produced by Gradle/Kotlin test tasks (and any other JVM test framework
that emits the schema):

```xml
<testsuite name="com.example.FooTest" tests="5" failures="1" errors="0" skipped="0" time="1.234">
  <testcase name="bar" classname="com.example.FooTest" time="0.123">
    <failure message="expected 1 but was 2" type="java.lang.AssertionError">stack trace</failure>
  </testcase>
  <testcase name="baz" classname="com.example.FooTest" time="0.045"/>
  ...
</testsuite>
```

Multiple suites per file, multiple files per directory tree - aggregate across all of them.

## MCP tools

| Tool | Purpose |
|---|---|
| `test_explorer_summarize` | Counts and the list of failures under a directory |
| `test_explorer_failure_messages` | Full failure message + stack trace per failing test |
| `test_explorer_watch_start` | Begin watching a directory (re-parse every 2 seconds) |
| `test_explorer_watch_stop` | Stop watching a directory |

`summarize` and `failure_messages` take the directory path as `dirPath`. The watch tools
toggle the same watcher the panel uses, so a watch started by the MCP is reflected in
the panel and vice versa.

## XML attack defense

The parser is hardened against the usual XML attacks at three layers:

- **DTDs and external entities disabled.** The StAX factory sets `SUPPORT_DTD` and
  `IS_SUPPORTING_EXTERNAL_ENTITIES` to false, so a `billion-laughs` payload that uses
  `<!ENTITY>` to amplify text cannot resolve.
- **Element depth cap (256).** Variants that use deep nesting rather than entities are
  caught here.
- **Byte caps.** Each file is capped at 16 MiB; the aggregate across all files in one
  parse pass is capped at 64 MiB.

Files are read through the host's `FileSystemDataProvider`, so the host's path safety
applies: a path that escapes the watched root or follows a hostile symlink is refused
before the parser ever sees it.

## Requirements

- BOSS >= 9.4.2, boss-plugin-api >= 1.0.93

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-test-explorer-*.jar ~/.boss/plugins/
```

See [AGENTS.md](AGENTS.md) for architecture and conventions.

## Compatibility

| BOSS | boss-plugin-api | Works? |
|---|---|---|
| 9.4.2+ | 1.0.93+ | yes |
| < 9.4.2 | any | no (PluginContext.fileSystemDataProvider missing) |

## Demo

Walkthrough used in the hackathon submission:

1. Build and install (`./gradlew buildPluginJar`, drop the jar into `~/.boss/plugins/dev/<pluginId>/v<ms>/`).
2. Open the panel in the left sidebar's bottom slot and pick a directory containing JUnit XML output (Gradle default is `<module>/build/test-results/test/`).
3. The panel renders a tree: `<testsuite>` rows expand to one row per `<testcase>`, with green/red/grey status icons and per-row timings. The header shows totals (tests, passed, failed, skipped, time).
4. Click a FAILED row to expand the throwable type, message, and full stack trace.
5. Toggle **Watch: On** to re-parse every 2 seconds - the same watcher is shared with the MCP tools, so `test_explorer_watch_start(dirPath)` and the panel toggle are equivalent.
6. From an in-terminal agent, run `mcp__boss__test_explorer_summarize({"dirPath":"..."})` and get back the same counts + the list of failures the panel shows.

The parser defends against XML-bomb variants at three layers: DTD and external-entity resolution disabled (`XMLInputFactory.SUPPORT_DTD = false`), `MAX_ELEMENT_DEPTH = 256`, and per-file / per-pass byte caps (`16 MiB` / `64 MiB`).

Full walkthrough with sample input/output: see [DEMO.md](DEMO.md) at the top of the boss-plugins meta-repo.

## License

MIT.
