package seamain.org.typhoonEye.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Cyclone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import seamain.org.typhoonEye.BuildConfig
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.NoDataSourceConfiguredError
import seamain.org.typhoonEye.domain.model.SourceFailure
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonDataError
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.domain.model.UserLocation
import seamain.org.typhoonEye.domain.util.distanceKmFrom
import seamain.org.typhoonEye.domain.util.roundKm
import seamain.org.typhoonEye.ui.DataMode
import seamain.org.typhoonEye.ui.TyphoonUiState
import seamain.org.typhoonEye.ui.components.DemoDataBanner
import seamain.org.typhoonEye.ui.components.IntensityBadge
import seamain.org.typhoonEye.ui.localizedDetails
import seamain.org.typhoonEye.ui.components.StatusChip
import seamain.org.typhoonEye.ui.theme.Motion
import seamain.org.typhoonEye.ui.theme.TyphoonEyeTheme
import seamain.org.typhoonEye.ui.util.IntensityLevel
import seamain.org.typhoonEye.ui.util.currentIntensity
import seamain.org.typhoonEye.ui.util.formatCoordinate
import seamain.org.typhoonEye.ui.util.formatObservationTime
import seamain.org.typhoonEye.ui.util.formatPressure
import seamain.org.typhoonEye.ui.util.intensityColor
import seamain.org.typhoonEye.ui.util.latestPoint
import seamain.org.typhoonEye.ui.util.localizedLabel
import seamain.org.typhoonEye.ui.util.moveLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: TyphoonUiState,
    filteredTyphoons: List<Typhoon>,
    isRefreshing: Boolean,
    query: String,
    intensityFilter: IntensityLevel?,
    dataMode: DataMode,
    lastUpdated: String?,
    userLocation: UserLocation? = null,
    onQueryChange: (String) -> Unit,
    onFilterChange: (IntensityLevel?) -> Unit,
    onRefresh: () -> Unit,
    onLoadDemo: () -> Unit,
    onOpenSettings: () -> Unit,
    onTyphoonClick: (Typhoon) -> Unit,
    modifier: Modifier = Modifier,
    onExitDemo: () -> Unit = {}
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pullState = rememberPullToRefreshState()
    val settingsCd = stringResource(R.string.settings)
    val refreshCd = stringResource(R.string.refresh)
    val totalCount = when (uiState) {
        is TyphoonUiState.Success -> uiState.typhoons.size
        else -> 0
    }
    val activeCount = when (uiState) {
        is TyphoonUiState.Success -> uiState.typhoons.count { it.status == "active" }
        else -> 0
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        AnimatedVisibility(visible = scrollBehavior.state.collapsedFraction < 0.5f) {
                            Text(
                                text = stringResource(R.string.app_tagline),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    FilledTonalIconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.semantics { contentDescription = settingsCd }
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedContent(
                targetState = uiState,
                contentKey = {
                    when (it) {
                        is TyphoonUiState.Loading -> "loading"
                        is TyphoonUiState.NoDataSource -> "no-data-source"
                        is TyphoonUiState.Error -> "error"
                        is TyphoonUiState.Success -> "success"
                    }
                },
                transitionSpec = { Motion.fadeThrough() },
                label = "home-state",
                modifier = Modifier.fillMaxSize()
            ) { state ->
                when (state) {
                    is TyphoonUiState.Loading -> LoadingState()
                    is TyphoonUiState.NoDataSource -> NoDataSourceState(
                        onViewDemo = onLoadDemo,
                        onRetry = onRefresh
                    )
                    is TyphoonUiState.Error -> ErrorState(
                        failures = state.failures,
                        detail = state.detail,
                        onRetry = onRefresh,
                        onViewDemo = onLoadDemo
                    )
                    is TyphoonUiState.Success -> Column(modifier = Modifier.fillMaxSize()) {
                        // Pinned outside the list so it can never scroll out of view.
                        if (dataMode == DataMode.Demo) {
                            DemoDataBanner(
                                onExit = onExitDemo,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            item(key = "summary") {
                                StatusSummaryRow(
                                    totalCount = totalCount,
                                    activeCount = activeCount,
                                    dataMode = dataMode,
                                    lastUpdated = lastUpdated,
                                    fromCache = state.fromCache,
                                    offline = state.staleReason != null,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            // Banner only when the network actually failed, not for a fresh cache hit.
                            if (state.fromCache && state.staleReason != null) {
                                item(key = "offline-banner") {
                                    OfflineCacheBanner(
                                        reason = state.staleReason,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            item(key = "filters") {
                                SearchAndFilters(
                                    query = query,
                                    intensityFilter = intensityFilter,
                                    onQueryChange = onQueryChange,
                                    onFilterChange = onFilterChange,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }

                            if (filteredTyphoons.isEmpty()) {
                                item(key = "empty") {
                                    EmptyListState(
                                        hasAny = state.typhoons.isNotEmpty(),
                                        onClearFilters = {
                                            onQueryChange("")
                                            onFilterChange(null)
                                        },
                                        onRefresh = onRefresh,
                                        onLoadDemo = onLoadDemo
                                    )
                                }
                            } else {
                                items(
                                    items = filteredTyphoons,
                                    key = { it.id }
                                ) { typhoon ->
                                    TyphoonListCard(
                                        typhoon = typhoon,
                                        userLocation = userLocation,
                                        onClick = { onTyphoonClick(typhoon) },
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineCacheBanner(
    reason: TyphoonDataError?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val message = (reason as? DataSourcesFailedError)?.failures?.localizedDetails(context)
    // Cache left over from a build that had keys: say why it can't refresh.
    val noDataSource = reason is NoDataSourceConfiguredError
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.offline_cache_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = if (noDataSource) {
                    stringResource(R.string.no_data_source_title)
                } else {
                    stringResource(R.string.offline_cache_body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun StatusSummaryRow(
    totalCount: Int,
    activeCount: Int,
    dataMode: DataMode,
    lastUpdated: String?,
    fromCache: Boolean = false,
    offline: Boolean = false,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (fromCache) {
            item {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.data_cached)) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (offline) Icons.Filled.CloudOff else Icons.Filled.History,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }
        item {
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(stringResource(R.string.summary_total_active, totalCount, activeCount))
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Cyclone,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledLeadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        // Cached data gets the 「缓存」 chip above instead of 「实时数据」.
        if (!fromCache || dataMode != DataMode.Live) item {
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        if (dataMode == DataMode.Live) {
                            stringResource(R.string.data_live)
                        } else {
                            stringResource(R.string.data_demo)
                        }
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = if (dataMode == DataMode.Live) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    disabledLabelColor = if (dataMode == DataMode.Live) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    }
                )
            )
        }
        if (lastUpdated != null) {
            item {
                Text(
                    text = stringResource(R.string.updated_at, lastUpdated),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchAndFilters(
    query: String,
    intensityFilter: IntensityLevel?,
    onQueryChange: (String) -> Unit,
    onFilterChange: (IntensityLevel?) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchCd = stringResource(R.string.cd_search_typhoon)
    val clearSearchCd = stringResource(R.string.cd_clear_search)
    Column(modifier = modifier) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = searchCd },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null)
            },
            trailingIcon = {
                AnimatedVisibility(
                    visible = query.isNotEmpty(),
                    enter = Motion.fadeEnter(),
                    exit = Motion.fadeExit()
                ) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = clearSearchCd)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            ),
            shape = MaterialTheme.shapes.extraLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            )
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = intensityFilter == null,
                    onClick = { onFilterChange(null) },
                    label = { Text(stringResource(R.string.filter_all)) }
                )
            }
            items(
                items = IntensityLevel.entries.filter { it != IntensityLevel.UNKNOWN },
                key = { it.name }
            ) { level ->
                val selected = intensityFilter == level
                val accent = intensityColor(level)
                FilterChip(
                    selected = selected,
                    onClick = { onFilterChange(level) },
                    label = { Text(level.shortLabel) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent.copy(alpha = 0.18f),
                        selectedLabelColor = accent,
                        selectedLeadingIconColor = accent
                    )
                )
            }
        }
    }
}

@Composable
fun TyphoonListCard(
    typhoon: Typhoon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    userLocation: UserLocation? = null
) {
    val intensity = typhoon.currentIntensity()
    val last = typhoon.latestPoint()
    val accent = intensityColor(intensity)
    val context = LocalContext.current
    val distanceKm = typhoon.distanceKmFrom(userLocation)
    val cardCd = stringResource(R.string.cd_typhoon_card, typhoon.name, intensity.localizedLabel())

    // Native ripple from OutlinedCard's clickable handles press feedback
    OutlinedCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = cardCd },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .padding(vertical = 12.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(accent)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = typhoon.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = buildList {
                            if (typhoon.englishName.isNotBlank() && typhoon.englishName != typhoon.name) {
                                add(typhoon.englishName)
                            }
                            add(typhoon.id)
                        }.joinToString(" · ")
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IntensityBadge(level = intensity, compact = true)
                        StatusChip(active = typhoon.status == "active")
                    }
                }

                if (typhoon.positionDesc.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = typhoon.positionDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (distanceKm != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.distance_from_you, distanceKm.roundKm()),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (last != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MiniMetric(
                            icon = Icons.Filled.Air,
                            label = stringResource(R.string.label_wind),
                            value = "${last.speed} m/s"
                        )
                        MiniMetric(
                            icon = Icons.Filled.Speed,
                            label = stringResource(R.string.label_pressure),
                            value = formatPressure(last.pressure)
                        )
                        MiniMetric(
                            icon = Icons.Filled.Navigation,
                            label = stringResource(R.string.label_move),
                            value = last.moveLabel(context)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.card_updated_coords,
                            formatObservationTime(last.time),
                            formatCoordinate(last.lat, last.lng)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniMetric(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LoadingState() {
    val loadingCd = stringResource(R.string.cd_loading)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = loadingCd },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.loading_fetching),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyListState(
    hasAny: Boolean,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onLoadDemo: () -> Unit
) {
    val emptyTitle = if (hasAny) {
        stringResource(R.string.empty_no_match)
    } else {
        stringResource(R.string.empty_no_active)
    }
    val emptyHint = if (hasAny) {
        stringResource(R.string.empty_hint_filter)
    } else {
        stringResource(R.string.empty_hint_refresh)
    }
    val loadDemoCd = stringResource(R.string.action_load_demo)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(40.dp)
            .semantics { contentDescription = emptyTitle },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Cyclone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = emptyTitle,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = emptyHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        if (hasAny) {
            OutlinedButton(onClick = onClearFilters) {
                Text(stringResource(R.string.action_clear_filters))
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_reload))
                }
                if (BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.height(10.dp))
                    FilledTonalButton(
                        onClick = onLoadDemo,
                        modifier = Modifier.semantics { contentDescription = loadDemoCd }
                    ) {
                        Text(stringResource(R.string.action_load_demo))
                    }
                }
            }
        }
    }
}

/**
 * Vertically centered when content fits, scrollable when it doesn't
 * (long explanations on small screens / landscape / large font scale).
 * Scrollable content also keeps pull-to-refresh working.
 */
@Composable
private fun CenteredScrollColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val minHeight = maxHeight
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
    }
}

/**
 * Build has no weather API key (F-Droid). Explains why there is no live data and
 * offers clearly labeled demo data. Deliberately not styled as an error.
 */
@Composable
private fun NoDataSourceState(
    onViewDemo: () -> Unit,
    onRetry: () -> Unit
) {
    val title = stringResource(R.string.no_data_source_title)
    CenteredScrollColumn(
        modifier = Modifier.semantics { contentDescription = title }
    ) {
        Icon(
            imageVector = Icons.Filled.Cyclone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.no_data_source_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.no_data_source_authority),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onViewDemo) {
            Text(stringResource(R.string.action_view_demo))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.retry))
        }
    }
}

@Composable
private fun ErrorState(
    failures: List<SourceFailure>,
    detail: String?,
    onRetry: () -> Unit,
    onViewDemo: () -> Unit
) {
    val context = LocalContext.current
    val errorCd = stringResource(R.string.cd_load_failed)
    val failureLines = failures.localizedDetails(context)
    CenteredScrollColumn(
        modifier = Modifier.semantics { contentDescription = errorCd }
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.error_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.error_sources_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (failureLines != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = failureLines,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (!detail.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.error_details_format, detail),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.retry))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onViewDemo) {
            Text(stringResource(R.string.action_view_demo))
        }
    }
}

@Preview(showBackground = true, name = "Home · Success")
@Composable
private fun HomeScreenPreview() {
    TyphoonEyeTheme {
        HomeScreen(
            uiState = TyphoonUiState.Success(previewTyphoons),
            filteredTyphoons = previewTyphoons,
            isRefreshing = false,
            query = "",
            intensityFilter = null,
            dataMode = DataMode.Demo,
            lastUpdated = "14:32",
            onQueryChange = {},
            onFilterChange = {},
            onRefresh = {},
            onLoadDemo = {},
            onOpenSettings = {},
            onTyphoonClick = {}
        )
    }
}

@Preview(showBackground = true, name = "Home · Empty")
@Composable
private fun HomeEmptyPreview() {
    TyphoonEyeTheme {
        HomeScreen(
            uiState = TyphoonUiState.Success(emptyList()),
            filteredTyphoons = emptyList(),
            isRefreshing = false,
            query = "",
            intensityFilter = null,
            dataMode = DataMode.Live,
            lastUpdated = null,
            onQueryChange = {},
            onFilterChange = {},
            onRefresh = {},
            onLoadDemo = {},
            onOpenSettings = {},
            onTyphoonClick = {}
        )
    }
}

internal val previewTyphoons = listOf(
    Typhoon(
        id = "202609",
        name = "巴威",
        englishName = "BAVI",
        status = "active",
        strong = "台风",
        positionDesc = "距离浙闽交界东南方向约890公里",
        points = listOf(
            TyphoonPoint("2026-07-10 14:00", 21.8, 126.9, 960, 40, "13", "台风", "北西", "22")
        )
    ),
    Typhoon(
        id = "202610",
        name = "美莎克",
        englishName = "MEKKHALA",
        status = "active",
        strong = "热带风暴",
        positionDesc = "菲律宾以东洋面",
        points = listOf(
            TyphoonPoint("2026-07-10 14:00", 12.5, 135.2, 998, 18, "8", "热带风暴", "NW", "20")
        )
    )
)
