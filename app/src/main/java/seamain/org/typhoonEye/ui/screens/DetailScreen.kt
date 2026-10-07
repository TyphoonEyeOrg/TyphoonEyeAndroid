package seamain.org.typhoonEye.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.ui.components.DemoDataBanner
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.domain.model.UserLocation
import seamain.org.typhoonEye.domain.util.distanceKmFrom
import seamain.org.typhoonEye.domain.util.roundKm
import seamain.org.typhoonEye.ui.components.IntensityBadge
import seamain.org.typhoonEye.ui.components.MetricGrid
import seamain.org.typhoonEye.ui.components.MetricItem
import seamain.org.typhoonEye.ui.components.PointTimelineItem
import seamain.org.typhoonEye.ui.components.StatusChip
import seamain.org.typhoonEye.ui.components.TrackMapCard
import seamain.org.typhoonEye.ui.theme.Motion
import seamain.org.typhoonEye.ui.theme.TyphoonEyeTheme
import seamain.org.typhoonEye.ui.util.currentIntensity
import seamain.org.typhoonEye.ui.util.MapBasemap
import seamain.org.typhoonEye.ui.util.displayLabel
import seamain.org.typhoonEye.ui.util.formatCoordinate
import seamain.org.typhoonEye.ui.util.formatObservationTime
import seamain.org.typhoonEye.ui.util.latestPoint
import seamain.org.typhoonEye.ui.util.moveLabel
import seamain.org.typhoonEye.ui.util.quadrants
import seamain.org.typhoonEye.ui.util.WindRadiiKm
import seamain.org.typhoonEye.ui.util.windRadii10
import seamain.org.typhoonEye.ui.util.windRadii12
import seamain.org.typhoonEye.ui.util.windRadii7
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    typhoon: Typhoon,
    loading: Boolean,
    onBack: () -> Unit,
    onShare: (String) -> Unit,
    shareText: String,
    modifier: Modifier = Modifier,
    userLocation: UserLocation? = null,
    mapBasemap: MapBasemap = MapBasemap.Auto,
    /** Sample storm from demo mode: keep the "not real" marker visible here too. */
    isDemo: Boolean = false
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.overview),
        stringResource(R.string.track_map),
        stringResource(R.string.tab_history),
        stringResource(R.string.tab_forecast)
    )
    val last = typhoon.latestPoint()
    val detailCd = stringResource(R.string.cd_typhoon_detail, typhoon.name)
    val backCd = stringResource(R.string.back)
    val shareCd = stringResource(R.string.share_summary)
    val loadingDetailCd = stringResource(R.string.cd_loading_detail)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = detailCd },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(typhoon.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = typhoon.englishName.ifBlank { typhoon.id },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backCd }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    FilledTonalIconButton(
                        onClick = { onShare(shareText) },
                        modifier = Modifier.semantics { contentDescription = shareCd }
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isDemo) {
                DemoDataBanner(
                    compact = true,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            DetailHero(typhoon = typhoon)

            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = loadingDetailCd }
                )
            }

            // Fixed PrimaryTabRow — 4 short labels fit without scrolling (M3)
            PrimaryTabRow(
                selectedTabIndex = tabIndex,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            AnimatedContent(
                targetState = tabIndex,
                transitionSpec = {
                    if (targetState > initialState) {
                        Motion.sharedAxisXForward()
                    } else {
                        Motion.sharedAxisXBackward()
                    }
                },
                label = "detail-tab",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { index ->
                when (index) {
                    0 -> OverviewTab(
                        typhoon = typhoon,
                        loading = loading,
                        last = last,
                        userLocation = userLocation,
                        modifier = Modifier.fillMaxSize()
                    )
                    1 -> TrackMapCard(
                        history = typhoon.points,
                        forecast = typhoon.forecastPoints,
                        userLocation = userLocation,
                        mapBasemap = mapBasemap,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    2 -> TimelineTab(
                        title = stringResource(R.string.history_track),
                        subtitle = stringResource(R.string.history_points_subtitle, typhoon.points.size),
                        points = typhoon.points.asReversed(),
                        isForecast = false,
                        emptyText = stringResource(R.string.history_empty),
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> TimelineTab(
                        title = stringResource(R.string.forecast_track),
                        subtitle = stringResource(R.string.forecast_points_subtitle, typhoon.forecastPoints.size),
                        points = typhoon.forecastPoints,
                        isForecast = true,
                        emptyText = stringResource(R.string.forecast_empty),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewTab(
    typhoon: Typhoon,
    loading: Boolean,
    last: TyphoonPoint?,
    modifier: Modifier = Modifier,
    userLocation: UserLocation? = null
) {
    val context = LocalContext.current
    val distanceKm = typhoon.distanceKmFrom(userLocation)
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.loading_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        MetricGrid(
            items = buildList {
                add(
                    MetricItem(
                        label = stringResource(R.string.wind_speed),
                        value = last?.let { "${it.speed} m/s" } ?: "—",
                        icon = Icons.Filled.Air
                    )
                )
                add(
                    MetricItem(
                        label = stringResource(R.string.pressure),
                        value = last?.let { "${it.pressure} hPa" } ?: "—",
                        icon = Icons.Filled.Speed
                    )
                )
                add(
                    MetricItem(
                        label = stringResource(R.string.move),
                        value = last?.moveLabel(context) ?: "—",
                        icon = Icons.Filled.Explore
                    )
                )
                add(
                    MetricItem(
                        label = stringResource(R.string.location),
                        value = last?.let { formatCoordinate(it.lat, it.lng) } ?: "—",
                        icon = Icons.Filled.Place
                    )
                )
                if (distanceKm != null) {
                    add(
                        MetricItem(
                            label = stringResource(R.string.label_distance),
                            value = stringResource(R.string.distance_from_you, distanceKm.roundKm()),
                            icon = Icons.Filled.Timeline
                        )
                    )
                }
            }
        )

        last?.let { point ->
            val r7 = point.windRadii7()
            val r10 = point.windRadii10()
            val r12 = point.windRadii12()
            if (r7 != null || r10 != null || r12 != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.wind_radius_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        r7?.let {
                            WindRadiusCardItem(
                                title = stringResource(R.string.wind_radius_7),
                                radii = it
                            )
                        }
                        r10?.let {
                            WindRadiusCardItem(
                                title = stringResource(R.string.wind_radius_10),
                                radii = it
                            )
                        }
                        r12?.let {
                            WindRadiusCardItem(
                                title = stringResource(R.string.wind_radius_12),
                                radii = it
                            )
                        }
                    }
                }
            }
        }

        if (typhoon.forecastText.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.forecast_bulletin),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = typhoon.forecastText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        InfoRow(label = stringResource(R.string.label_id), value = typhoon.id)
        if (typhoon.startTime.isNotBlank()) {
            InfoRow(
                label = stringResource(R.string.label_start_time),
                value = formatObservationTime(typhoon.startTime, context = context)
            )
        }
        if (typhoon.endTime.isNotBlank()) {
            InfoRow(
                label = stringResource(R.string.label_latest_time),
                value = formatObservationTime(typhoon.endTime, context = context, showRelative = true)
            )
        }
        InfoRow(
            label = stringResource(R.string.label_track_points),
            value = stringResource(
                R.string.track_points_summary,
                typhoon.points.size,
                typhoon.forecastPoints.size
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun TimelineTab(
    title: String,
    subtitle: String,
    points: List<TyphoonPoint>,
    isForecast: Boolean,
    emptyText: String,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) {
        Column(modifier = modifier) {
            SectionHeader(title = title, subtitle = subtitle, icon = Icons.Filled.Timeline)
            EmptySection(emptyText)
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            SectionHeader(title = title, subtitle = subtitle, icon = Icons.Filled.Timeline)
        }
        itemsIndexed(points, key = { index, point -> "${point.time}-$index" }) { index, point ->
            PointTimelineItem(
                point = point,
                isForecast = isForecast,
                showConnector = index != points.lastIndex,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun DetailHero(typhoon: Typhoon) {
    val intensity = typhoon.currentIntensity()
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(scheme.primaryContainer, scheme.secondaryContainer)
                )
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IntensityBadge(level = intensity)
                StatusChip(active = typhoon.status == "active")
            }
            // Name lives in TopAppBar — hero only shows location context
            if (typhoon.positionDesc.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = typhoon.positionDesc,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EmptySection(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(showBackground = true, name = "Detail")
@Composable
private fun DetailScreenPreview() {
    TyphoonEyeTheme {
        DetailScreen(
            typhoon = previewTyphoons.first().copy(
                forecastText = "将以每小时20-25公里的速度向西北方向移动，强度变化不大",
                points = previewTyphoons.first().points,
                forecastPoints = previewTyphoons.first().points
            ),
            loading = false,
            onBack = {},
            onShare = {},
            shareText = "preview"
        )
    }
}

@Composable
private fun WindRadiusCardItem(
    title: String,
    radii: WindRadiiKm
) {
    val context = LocalContext.current
    val quadrants = radii.quadrants()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = radii.displayLabel(context),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (!radii.isSymmetric && radii.hasAny) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (q in quadrants.take(2)) {
                        QuadrantChip(
                            label = stringResource(q.labelRes),
                            km = q.km,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (q in quadrants.drop(2).take(2)) {
                        QuadrantChip(
                            label = stringResource(q.labelRes),
                            km = q.km,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuadrantChip(
    label: String,
    km: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (km > 0) "$km km" else "—",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
