package seamain.org.typhoonEye.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.annotation.StringRes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import seamain.org.typhoonEye.R

data class OssLibrary(
    val name: String,
    val developer: String,
    val license: String,
    @StringRes val descriptionRes: Int,
    val url: String
)

val openSourceLibraries = listOf(
    OssLibrary(
        name = "TyphoonEye",
        developer = "Seamain & contributors",
        license = "Apache-2.0",
        descriptionRes = R.string.oss_app_desc,
        url = "https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/blob/master/LICENSE"
    ),
    OssLibrary(
        name = "Jetpack Compose & Material 3",
        developer = "Google Open Source",
        license = "Apache-2.0",
        descriptionRes = R.string.oss_compose_desc,
        url = "https://developer.android.com/jetpack/compose"
    ),
    OssLibrary(
        name = "Kotlin Coroutines & Serialization",
        developer = "JetBrains",
        license = "Apache-2.0",
        descriptionRes = R.string.oss_coroutines_desc,
        url = "https://github.com/Kotlin/kotlinx.coroutines"
    ),
    OssLibrary(
        name = "Hilt (Dagger)",
        developer = "Google",
        license = "Apache-2.0",
        descriptionRes = R.string.oss_hilt_desc,
        url = "https://dagger.dev/hilt/"
    ),
    OssLibrary(
        name = "MapLibre Android SDK",
        developer = "MapLibre Organization",
        license = "BSD 2-Clause / Apache-2.0",
        descriptionRes = R.string.oss_maplibre_desc,
        url = "https://github.com/maplibre/maplibre-native"
    ),
    OssLibrary(
        name = "Retrofit & OkHttp",
        developer = "Square, Inc.",
        license = "Apache-2.0",
        descriptionRes = R.string.oss_retrofit_desc,
        url = "https://square.github.io/retrofit/"
    ),
    OssLibrary(
        name = "Android Jetpack (Room / DataStore / WorkManager)",
        developer = "Google Open Source",
        license = "Apache-2.0",
        descriptionRes = R.string.oss_jetpack_desc,
        url = "https://developer.android.com/jetpack"
    ),
    OssLibrary(
        name = "Ed25519-Java",
        developer = "str4d & Contributors",
        license = "CC0-1.0 / Public Domain",
        descriptionRes = R.string.oss_eddsa_desc,
        url = "https://github.com/str4d/ed25519-java"
    ),
    OssLibrary(
        name = "Turbine & Mockito",
        developer = "Cash App & Mockito Contributors",
        license = "Apache-2.0",
        descriptionRes = R.string.oss_test_desc,
        url = "https://github.com/cashapp/turbine"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = context.getString(R.string.open_source_licenses) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.open_source_licenses)) },
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.open_source_licenses_subtitle),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.open_source_licenses_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            items(openSourceLibraries, key = { it.name }) { lib ->
                OssLibraryItem(
                    library = lib,
                    onOpenUrl = { url ->
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun OssLibraryItem(
    library: OssLibrary,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenUrl(library.url) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = library.developer,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = library.license,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                IconButton(onClick = { onOpenUrl(library.url) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = library.name,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(library.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
