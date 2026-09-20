package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Composable surface for the Test Explorer panel.
 *
 * Reads the watcher flow, applies the search and status filters, and renders the
 * suite/class/method tree. The toolbar at the top exposes the directory text field,
 * the Watch toggle, and the Refresh button.
 */
@Composable
fun TestExplorerContent(viewModel: TestExplorerViewModel) {
    BossTheme {
        TestExplorerPanel(viewModel)
    }
}

@Composable
private fun TestExplorerPanel(viewModel: TestExplorerViewModel) {
    val rootDir by viewModel.rootDir.collectAsState()
    val watchEnabled by viewModel.watchEnabled.collectAsState()
    val search by viewModel.searchQuery.collectAsState()
    val filter by viewModel.statusFilter.collectAsState()
    val expandedClass by viewModel.expandedClass.collectAsState()
    val expandedMethod by viewModel.expandedMethod.collectAsState()

    // Re-collect the watch flow on every recomposition: the flow identity changes
    // when watchEnabled toggles (the previous job is cancelled and a new one starts),
    // and collectAsState handles that by switching the upstream.
    val watchFlow = remember(rootDir, watchEnabled) { viewModel.watchFlow() }
    val watchState by (watchFlow ?: remember { kotlinx.coroutines.flow.MutableStateFlow(WatchState.Idle) })
        .collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TestExplorerToolbar(
                rootDir = rootDir,
                onRootDirChange = { viewModel.setRootDir(it) },
                watchEnabled = watchEnabled,
                onWatchToggle = { viewModel.setWatchEnabled(!watchEnabled) },
                onRefresh = {
                    val dir = rootDir ?: return@TestExplorerToolbar
                    viewModel.setRootDir(dir)
                },
            )
            Divider(color = BossThemeColors.BorderColor)
            when (val state = watchState) {
                is WatchState.Loaded -> LoadedBody(
                    result = state.result,
                    search = search,
                    onSearchChange = { viewModel.setSearchQuery(it) },
                    filter = filter,
                    onFilterChange = { viewModel.setStatusFilter(it) },
                    expandedClass = expandedClass,
                    onClassToggle = { viewModel.toggleClassExpanded(it) },
                    expandedMethod = expandedMethod,
                    onMethodToggle = { viewModel.toggleMethodExpanded(it) },
                )
                is WatchState.Error -> ErrorBody(state.message)
                WatchState.Idle -> IdleBody(rootDir)
            }
        }
    }
}

@Composable
private fun TestExplorerToolbar(
    rootDir: String?,
    onRootDirChange: (String?) -> Unit,
    watchEnabled: Boolean,
    onWatchToggle: () -> Unit,
    onRefresh: () -> Unit,
) {
    var localText by remember(rootDir) { mutableStateOf(rootDir.orEmpty()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = localText,
            onValueChange = { localText = it },
            placeholder = { Text("Directory of JUnit XML reports") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                backgroundColor = BossThemeColors.SurfaceColor,
                textColor = BossThemeColors.TextPrimary,
                cursorColor = BossThemeColors.AccentColor,
            ),
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = {
            onRootDirChange(localText.takeIf { it.isNotBlank() })
        }) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Parse",
                tint = BossThemeColors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onWatchToggle) {
            Icon(
                imageVector = if (watchEnabled) Icons.Filled.CheckCircle else Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = if (watchEnabled) "Watch on" else "Watch off",
                tint = if (watchEnabled) BossThemeColors.SuccessColor else BossThemeColors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun LoadedBody(
    result: TestRunResult,
    search: String,
    onSearchChange: (String) -> Unit,
    filter: StatusFilter,
    onFilterChange: (StatusFilter) -> Unit,
    expandedClass: Set<String>,
    onClassToggle: (String) -> Unit,
    expandedMethod: Set<String>,
    onMethodToggle: (String) -> Unit,
) {
    val summary = result.summary
    Column(modifier = Modifier.fillMaxSize()) {
        SummaryHeader(summary)
        Divider(color = BossThemeColors.BorderColor.copy(alpha = 0.4f))
        SearchBar(search, onSearchChange)
        StatusFilterChips(filter, onFilterChange)
        Divider(color = BossThemeColors.BorderColor.copy(alpha = 0.4f))
        if (result.reports.isEmpty()) {
            EmptyReports(result.rootDir)
            return@Column
        }
        TestTree(
            result = result,
            search = search,
            filter = filter,
            expandedClass = expandedClass,
            onClassToggle = onClassToggle,
            expandedMethod = expandedMethod,
            onMethodToggle = onMethodToggle,
        )
    }
}

@Composable
private fun SummaryHeader(summary: TestSummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "${summary.tests} tests",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = BossThemeColors.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Row {
                CountPill("Passed", summary.passed, BossThemeColors.SuccessColor)
                Spacer(Modifier.width(6.dp))
                CountPill("Failed", summary.failed, BossThemeColors.ErrorColor)
                Spacer(Modifier.width(6.dp))
                CountPill("Skipped", summary.skipped, BossThemeColors.WarningColor)
            }
        }
        Text(
            text = "%.2fs".format(summary.timeSeconds),
            fontSize = 12.sp,
            color = BossThemeColors.TextSecondary,
        )
    }
}

@Composable
private fun CountPill(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "$label $count",
            fontSize = 11.sp,
            color = BossThemeColors.TextSecondary,
        )
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Filter by class, method, or message") },
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
                tint = BossThemeColors.TextMuted,
                modifier = Modifier.size(14.dp),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear filter",
                        tint = BossThemeColors.TextMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            backgroundColor = BossThemeColors.SurfaceColor,
            textColor = BossThemeColors.TextPrimary,
            cursorColor = BossThemeColors.AccentColor,
        ),
    )
}

@Composable
private fun StatusFilterChips(current: StatusFilter, onChange: (StatusFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        StatusFilter.values().forEach { chip ->
            val selected = chip == current
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) BossThemeColors.AccentColor.copy(alpha = 0.3f)
                        else BossThemeColors.SurfaceColor
                    )
                    .clickable { onChange(chip) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = chip.label,
                    fontSize = 11.sp,
                    color = if (selected) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun TestTree(
    result: TestRunResult,
    search: String,
    filter: StatusFilter,
    expandedClass: Set<String>,
    onClassToggle: (String) -> Unit,
    expandedMethod: Set<String>,
    onMethodToggle: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val matchingClasses = computeMatchingClasses(result, search, filter)
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .lazyListScrollbar(
                    listState = listState,
                    direction = Orientation.Vertical,
                    config = getPanelScrollbarConfig(),
                ),
        ) {
            items(matchingClasses, key = { it.classname }) { row ->
                ClassRow(
                    row = row,
                    expanded = row.classname in expandedClass,
                    onToggle = { onClassToggle(row.classname) },
                    methodExpanded = expandedMethod,
                    onMethodToggle = onMethodToggle,
                )
            }
        }
    }
}

@Composable
private fun ClassRow(
    row: ClassMatch,
    expanded: Boolean,
    onToggle: () -> Unit,
    methodExpanded: Set<String>,
    onMethodToggle: (String) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                contentDescription = null,
                tint = BossThemeColors.TextSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = row.classname,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = BossThemeColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            StatusBadge(passed = row.passed, failed = row.failed, skipped = row.skipped)
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = BossThemeColors.TextMuted,
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            row.cases.forEach { case ->
                MethodRow(
                    case = case,
                    expanded = case.id in methodExpanded,
                    onToggle = { onMethodToggle(case.id) },
                )
            }
        }
    }
}

@Composable
private fun MethodRow(
    case: TestCase,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = statusIcon(case.status),
                contentDescription = null,
                tint = statusColor(case.status),
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = case.name,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = BossThemeColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "%.3fs".format(case.timeSeconds),
                fontSize = 10.sp,
                color = BossThemeColors.TextMuted,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = BossThemeColors.TextMuted,
                modifier = Modifier.size(12.dp),
            )
        }
        if (expanded && case.failure != null) {
            FailurePanel(case.failure)
        }
    }
}

@Composable
private fun FailurePanel(failure: TestFailure) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 2.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(BossThemeColors.ErrorColor.copy(alpha = 0.1f))
            .padding(8.dp),
    ) {
        if (!failure.type.isNullOrBlank()) {
            Text(
                text = failure.type,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = BossThemeColors.ErrorColor,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (!failure.message.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = failure.message,
                fontSize = 11.sp,
                color = BossThemeColors.TextPrimary,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (failure.stackTrace.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = failure.stackTrace.lines().take(40).joinToString("\n"),
                fontSize = 10.sp,
                color = BossThemeColors.TextSecondary,
                fontFamily = FontFamily.Monospace,
                maxLines = 40,
            )
        }
    }
}

@Composable
private fun StatusBadge(passed: Int, failed: Int, skipped: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (failed > 0) {
            Badge(failed.toString(), BossThemeColors.ErrorColor)
            Spacer(Modifier.width(3.dp))
        }
        if (passed > 0) {
            Badge(passed.toString(), BossThemeColors.SuccessColor)
            Spacer(Modifier.width(3.dp))
        }
        if (skipped > 0) {
            Badge(skipped.toString(), BossThemeColors.WarningColor)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@Composable
private fun ErrorBody(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Cancel,
                contentDescription = null,
                tint = BossThemeColors.ErrorColor,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Failed to parse reports",
                fontSize = 13.sp,
                color = BossThemeColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                fontSize = 11.sp,
                color = BossThemeColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun IdleBody(rootDir: String?) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = null,
                tint = BossThemeColors.TextMuted,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No directory watched",
                fontSize = 13.sp,
                color = BossThemeColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (rootDir == null) {
                    "Type a directory containing JUnit XML reports and press the parse button."
                } else {
                    "Toggle Watch on, or press parse, to load reports from $rootDir."
                },
                fontSize = 11.sp,
                color = BossThemeColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun EmptyReports(rootDir: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = BossThemeColors.AccentColor,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No JUnit XML found",
                fontSize = 13.sp,
                color = BossThemeColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Looking in $rootDir",
                fontSize = 11.sp,
                color = BossThemeColors.TextSecondary,
            )
        }
    }
}

private fun statusIcon(status: TestStatus) = when (status) {
    TestStatus.PASSED -> Icons.Filled.CheckCircle
    TestStatus.FAILED -> Icons.Filled.Error
    TestStatus.SKIPPED -> Icons.Filled.SkipNext
    TestStatus.ERROR -> Icons.Filled.Cancel
}

private fun statusColor(status: TestStatus) = when (status) {
    TestStatus.PASSED -> BossThemeColors.SuccessColor
    TestStatus.FAILED -> BossThemeColors.ErrorColor
    TestStatus.SKIPPED -> BossThemeColors.WarningColor
    TestStatus.ERROR -> BossThemeColors.ErrorColor
}

/**
 * The shape consumed by the tree view: one row per class, with its cases attached.
 */
private data class ClassMatch(
    val classname: String,
    val cases: List<TestCase>,
) {
    val passed: Int get() = cases.count { it.status == TestStatus.PASSED }
    val failed: Int get() = cases.count { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
    val skipped: Int get() = cases.count { it.status == TestStatus.SKIPPED }
}

private fun computeMatchingClasses(
    result: TestRunResult,
    search: String,
    filter: StatusFilter,
): List<ClassMatch> {
    val byClass = linkedMapOf<String, MutableList<TestCase>>()
    for (report in result.reports) {
        for (suite in report.suites) {
            for (case in suite.cases) {
                val key = case.classname.ifBlank { suite.name }
                byClass.getOrPut(key) { mutableListOf() } += case
            }
        }
    }
    val needle = search.trim().lowercase()
    return byClass.entries.mapNotNull { (classname, cases) ->
        val filtered = cases.filter { c ->
            filter.matches(c.status) && (
                needle.isEmpty() ||
                    c.classname.lowercase().contains(needle) ||
                    c.name.lowercase().contains(needle) ||
                    (c.failure?.message?.lowercase()?.contains(needle) == true)
                )
        }
        if (filtered.isEmpty()) null else ClassMatch(classname, filtered)
    }.sortedBy { it.classname }
}
