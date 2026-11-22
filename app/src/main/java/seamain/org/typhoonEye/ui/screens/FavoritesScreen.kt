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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.model.FavoriteTyphoon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    favorites: List<FavoriteTyphoon>,
    onBack: () -> Unit,
    onOpen: (FavoriteTyphoon) -> Unit,
    onRemove: (FavoriteTyphoon) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = context.getString(R.string.favorites_title) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.favorites_title)) },
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
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = stringResource(R.string.favorites_banner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            if (favorites.isEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 24.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.StarBorder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.favorites_empty),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.favorites_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(favorites, key = { it.id }) { favorite ->
                    FavoriteRow(
                        favorite = favorite,
                        onOpen = { onOpen(favorite) },
                        onRemove = { onRemove(favorite) }
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
    onRemove: () -> Unit
) {
    val title = favorite.name.ifBlank { favorite.englishName }.ifBlank { favorite.id }
    val yearText = if (favorite.year > 0) {
        stringResource(R.string.favorites_year, favorite.year)
    } else {
        null
    }
    val english = favorite.englishName.trim()
        .takeUnless { it.isBlank() || it.equals(title, ignoreCase = true) }
    val subtitle = listOfNotNull(
        yearText,
        english,
        favorite.id,
        stringResource(R.string.favorites_row_saved)
    ).joinToString(" · ")
    val openCd = stringResource(R.string.cd_favorites_open, title)
    val removeCd = stringResource(R.string.cd_favorite_remove, title)
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = removeCd }
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
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
    yearLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = stringResource(R.string.favorites_out_of_range_title)
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = title },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.favorites_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = stringResource(R.string.favorites_out_of_range_body, name, yearLabel),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun favoriteYearLabel(year: Int): String = if (year > 0) year.toString() else "—"
