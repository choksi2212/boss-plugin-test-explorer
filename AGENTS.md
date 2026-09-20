# AGENTS.md

## Project Overview

**Test Explorer** (`ai.rever.boss.plugin.dynamic.testexplorer`) is a dynamic plugin for the
BOSS desktop application.

Structured JUnit XML viewer - parses test reports and renders the result as a tree of
suites/classes/methods with status icons, error messages, and an MCP surface for
agent-driven test triage.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.testexplorer`
- **Main Class**: `ai.rever.boss.plugin.dynamic.testexplorer.TestExplorerDynamicPlugin`
- **API Version**: 1.0.93
- **Type**: mixed (panel + MCP tools)

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure
```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.*)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
build.gradle.kts   → Build config + version (single source of truth)
```

### Key Patterns

- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`.
- UI: `PanelComponentWithUI` with `@Composable Content()`.
- State: ViewModel pattern with `StateFlow`.
- File reads go through `FileSystemDataProvider` from `PluginContext`. The host's path
  safety applies to every parse.
- Null-safe provider access: providers may be null, UI must handle gracefully.

### Module Map

| File | Role |
|---|---|
| `TestExplorerDynamicPlugin.kt` | Plugin entry point. Wires shared loader + watcher into panel and MCP providers. |
| `TestExplorerInfo.kt` | Panel metadata: id, display name, icon, slot. |
| `TestExplorerComponent.kt` | Decompose-hosted panel component. Owns the view model. |
| `TestExplorerViewModel.kt` | Local UI state: root dir, watch enabled, search/filter, expansion. |
| `TestExplorerContent.kt` | `@Composable` panel surface (toolbar, header, tree). |
| `TestReport.kt` | Data classes: `TestReport`, `TestSuite`, `TestCase`, `TestSummary`, `FailureEntry`. |
| `JunitXmlParser.kt` | Streaming StAX parser with billion-laughs defense. |
| `TestExplorerReportLoader.kt` | Directory walk + per-file parse; enforces total byte budget. |
| `TestExplorerDirectoryWatcher.kt` | Coroutine-based poll loop; 2-second default. |
| `TestExplorerMcpTools.kt` | Four MCP tools: summarize, failure_messages, watch_start, watch_stop. |

### Dependencies

- **boss-plugin-api**: compileOnly (provided by host app at runtime)
- **Compose Desktop**: UI framework
- **Decompose**: Navigation and component lifecycle
- **Coroutines**: Async operations

## XML Safety

The parser is the second half of the safety story; the host's plugin-loader zip-bomb
defense covers jar reading but not arbitrary XML. Defense lives in three places:

1. `JunitXmlParser` configures `XMLInputFactory` to disable DTDs and external entities, so
   a `billion-laughs` payload cannot resolve.
2. `JunitXmlParser` aborts at 256 element depth.
3. `TestExplorerReportLoader` enforces per-file (16 MiB) and aggregate (64 MiB) byte caps
   and refuses to open any file outside the watched root.

The XML is read as a stream rather than a DOM, so the memory footprint is bounded by the
file size cap rather than by the input itself.

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at build
time. Never manually edit the version in `plugin.json` - only change it in `build.gradle.kts`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully - show fallback UI, never crash
- No Co-Authored-By lines in commits
- No mentions of AI tools, automation, or third-party assistance in commits, PRs, or comments
- Spaced hyphens (` - `) only in prose; em-dashes (U+2014) are forbidden

## CI/CD

Pushes to `main` trigger the release workflow which:
1. Builds the plugin JAR
2. Creates a GitHub release
3. Publishes to the BOSS Plugin Store

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared
workflow in `risa-labs-inc/BossConsole-Releases`. Pull requests run the Tests workflow
defined in `.github/workflows/test.yml`.
