package seamain.org.typhoonEye.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.history.HistoryPhase
import seamain.org.typhoonEye.domain.history.TrackPhase
import seamain.org.typhoonEye.ui.HistoryUi
import seamain.org.typhoonEye.ui.about.AboutLinks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUi,
    onBack: () -> Unit,
    onYearChange: (Int) -> Unit,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenStorm: (HistoricalStorm) -> Unit,
    onOpenAttribution: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val presentation = state.presentation
    val coverage = stringResource(
        R.string.history_coverage,
        state.years.maxOrNull() ?: state.year,
        state.years.minOrNull() ?: state.year
    )
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = context.getString(R.string.history_title) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            contentDescription = context.getString(R.string.back)
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.semantics {
                            contentDescription = context.getString(R.string.history_reload)
                        }
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = stringResource(R.string.history_banner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            item {
                Text(
                    text = coverage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                val attribution = stringResource(R.string.history_attribution)
                val attributionCd = stringResource(R.string.cd_history_attribution)
                TextButton(
                    onClick = { onOpenAttribution(AboutLinks.QWEATHER_URL) },
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = attributionCd
                    }
                ) {
                    Text(attribution)
                    Spacer(modifier = Modifier.size(6.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.years.forEach { year ->
                        FilterChip(
                            selected = state.year == year,
                            onClick = { onYearChange(year) },
                            label = { Text(year.toString()) },
                            modifier = Modifier
                                .testTag("history-year-$year")
                                .semantics {
                                    contentDescription = context.getString(R.string.cd_history_year, year)
                                }
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = context.getString(R.string.cd_history_search)
                        },
                    singleLine = true,
                    label = { Text(stringResource(R.string.history_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
            }
            if (state.loading) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = context.getString(R.string.history_loading)
                            }
                    )
                }
            }
            item {
                HistoryPhaseMessage(presentation.phase, presentation.showingSavedCopy)
            }
            items(presentation.rows, key = { it.id }) { storm ->
                val title = storm.name.ifBlank { storm.id }
                val number = storm.number ?: storm.id
                val openCd = stringResource(R.string.cd_history_open, title)
                ListItem(
                    headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.history_row_label) + " · " + number)
                            if (storm.active) {
                                Text(stringResource(R.string.history_row_active_note))
                            }
                        }
                    },
                    leadingContent = {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .clickable { onOpenStorm(storm) }
                        .semantics {
                            role = Role.Button
                            contentDescription = openCd
                        }
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailStatus(
    phase: TrackPhase,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message = when (phase) {
        TrackPhase.Loading -> stringResource(R.string.history_loading)
        TrackPhase.MissingPath -> stringResource(R.string.history_track_missing)
        TrackPhase.RateLimited -> stringResource(R.string.history_rate_limit)
        TrackPhase.OfflineUnavailable -> stringResource(R.string.history_offline_empty)
        TrackPhase.UpstreamError -> stringResource(R.string.history_upstream_error)
        TrackPhase.SourceUnavailable -> stringResource(R.string.history_no_source)
        TrackPhase.UnsupportedYear -> stringResource(R.string.history_unsupported_year)
        TrackPhase.Ready, TrackPhase.OfflineReady -> stringResource(R.string.history_detail_banner)
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(padding).padding(16.dp)
        )
    }
}

@Composable
private fun HistoryPhaseMessage(phase: HistoryPhase, showingSavedCopy: Boolean) {
    val text = when (phase) {
        HistoryPhase.Loading, HistoryPhase.Results -> null
        HistoryPhase.NoResults -> stringResource(R.string.history_no_results)
        HistoryPhase.RateLimited -> stringResource(R.string.history_rate_limit)
        HistoryPhase.UpstreamError -> stringResource(R.string.history_upstream_error)
        HistoryPhase.OfflineResults -> stringResource(R.string.history_offline)
        HistoryPhase.OfflineUnavailable -> stringResource(R.string.history_offline_empty)
        HistoryPhase.SourceUnavailable -> stringResource(R.string.history_no_source)
        HistoryPhase.UnsupportedYear -> stringResource(R.string.history_unsupported_year)
    }
    if (text == null && !showingSavedCopy) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (phase == HistoryPhase.Loading && !showingSavedCopy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.history_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = when (phase) {
                    HistoryPhase.NoResults -> MaterialTheme.colorScheme.onSurface
                    HistoryPhase.OfflineResults, HistoryPhase.OfflineUnavailable ->
                        MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }
        if (phase == HistoryPhase.NoResults) {
            Text(
                text = stringResource(R.string.history_no_results_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showingSavedCopy && phase != HistoryPhase.OfflineResults) {
            Text(
                text = stringResource(R.string.history_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
