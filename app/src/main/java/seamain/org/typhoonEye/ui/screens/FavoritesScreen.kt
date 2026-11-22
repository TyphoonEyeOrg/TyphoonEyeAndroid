package seamain.org.typhoonEye.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.favorites.FavoriteTyphoon
import seamain.org.typhoonEye.domain.names.StormNames

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    favorites: List<FavoriteTyphoon>,
    onBack: () -> Unit,
    onOpen: (FavoriteTyphoon) -> Unit,
    onUnfavorite: (FavoriteTyphoon) -> Unit,
    modifier: Modifier = Modifier
) {
    val title = stringResource(R.string.favorites_title)
    val backCd = stringResource(R.string.back)
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = title },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            contentDescription = backCd
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = stringResource(R.string.favorites_disclaimer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            if (favorites.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.StarBorder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.favorites_empty_title),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.favorites_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(favorites, key = { it.stableId }) { favorite ->
                    FavoriteRow(
                        favorite = favorite,
                        onOpen = { onOpen(favorite) },
                        onUnfavorite = { onUnfavorite(favorite) }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun FavoriteRow(
    favorite: FavoriteTyphoon,
    onOpen: () -> Unit,
    onUnfavorite: () -> Unit
) {
    val title = favorite.localizedTitle()
    val openCd = stringResource(R.string.cd_favorites_open, title)
    val removeCd = stringResource(R.string.cd_favorite_remove, title)
    val yearLabel = favorite.year?.let { stringResource(R.string.favorites_row_year, it) }
        ?: stringResource(R.string.favorites_row_year_unknown)
    val idLabel = favorite.number ?: favorite.sourceId
    ListItem(
        headlineContent = {
            Text(title, fontWeight = FontWeight.SemiBold)
        },
        supportingContent = {
            Column {
                Text(stringResource(R.string.favorites_row_meta, yearLabel, idLabel))
                if (favorite.status == "active" || favorite.status == "dissipated") {
                    val status = stringResource(
                        if (favorite.status == "active") R.string.status_active else R.string.status_dissipated
                    )
                    Text(stringResource(R.string.favorites_last_status, status))
                }
            }
        },
        leadingContent = {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            IconButton(
                onClick = onUnfavorite,
                modifier = Modifier.semantics { contentDescription = removeCd }
            ) {
                Icon(Icons.Outlined.StarBorder, contentDescription = null)
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .clickable(onClick = onOpen)
            .semantics {
                role = Role.Button
                contentDescription = openCd
            }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteUnavailableScreen(
    name: String,
    onBack: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = stringResource(R.string.favorites_out_of_range_title)
    val backCd = stringResource(R.string.back)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            contentDescription = backCd
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.favorites_out_of_range_body, name),
                style = MaterialTheme.typography.bodyLarge
            )
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.favorites_remove))
            }
        }
    }
}

@Composable
fun FavoriteTyphoon.localizedTitle(): String {
    LocalConfiguration.current
    val context = LocalContext.current
    val localized = StormNames.localize(
        englishName.takeIf { it.isNotBlank() },
        name.takeIf { it.isNotBlank() },
        StormNames.currentColumn()
    ).trim()
    if (localized.isNotBlank()) return localized
    return name.ifBlank { englishName }.ifBlank { sourceId }
        .ifBlank { context.getString(R.string.favorites_unnamed) }
}
