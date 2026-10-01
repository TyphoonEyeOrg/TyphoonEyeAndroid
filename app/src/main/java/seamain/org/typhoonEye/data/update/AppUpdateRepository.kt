package seamain.org.typhoonEye.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import seamain.org.typhoonEye.BuildConfig
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.data.api.GitHubReleaseApi
import seamain.org.typhoonEye.data.api.GitHubReleaseDto
import seamain.org.typhoonEye.data.preferences.UserPreferencesRepository
import seamain.org.typhoonEye.domain.model.AppUpdateInfo
import seamain.org.typhoonEye.domain.model.AppUpdateState
import seamain.org.typhoonEye.domain.util.isNewerVersion
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class AppUpdateRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val api: GitHubReleaseApi,
    @Named("github") private val http: OkHttpClient,
    private val preferences: UserPreferencesRepository
) {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val owner = BuildConfig.GITHUB_OWNER
    private val repo = BuildConfig.GITHUB_REPO

    /**
     * Check GitHub latest release.
     * @param force ignore daily throttle used by background auto-check
     */
    suspend fun checkForUpdate(force: Boolean = true): AppUpdateState {
        if (!force && !preferences.shouldAutoCheckUpdate()) {
            return _state.value
        }
        _state.value = AppUpdateState.Checking
        return try {
            val release = withContext(Dispatchers.IO) { fetchBestRelease() }
            if (release == null) {
                // No published releases yet (GitHub returns 404 for /latest).
                _state.value = AppUpdateState.UpToDate
                preferences.markUpdateChecked()
                return _state.value
            }
            val remoteVersion = release.tagName
            val current = BuildConfig.VERSION_NAME
            if (!isNewerVersion(remoteVersion, current)) {
                _state.value = AppUpdateState.UpToDate
                preferences.markUpdateChecked()
                return _state.value
            }
            // Never guess: no APK for this device's ABIs (and no universal) -> apkUrl
            // stays blank and the dialog sends the user to the release page instead.
            val asset = UpdateAssetSelector.selectAsset(
                release.assets,
                Build.SUPPORTED_ABIS.toList()
            )
            val info = AppUpdateInfo(
                versionName = remoteVersion.removePrefix("v").removePrefix("V"),
                tagName = release.tagName,
                releaseNotes = release.body.trim(),
                htmlUrl = release.htmlUrl,
                apkUrl = asset?.browserDownloadUrl.orEmpty(),
                apkName = asset?.name?.ifBlank { null } ?: "TyphoonEye-${remoteVersion}.apk",
                apkSizeBytes = asset?.size ?: 0L
            )
            _state.value = AppUpdateState.Available(info)
            preferences.markUpdateChecked()
            _state.value
        } catch (e: Exception) {
            val msg = humanizeCheckError(e)
            _state.value = AppUpdateState.Error(msg)
            _state.value
        }
    }

    /**
     * List releases and pick the newest `v*` release with an APK this edition can use.
     * `/releases/latest` is deliberately not used: it may point at a non-app release
     * such as `fdroid-*` (F-Droid reference APKs), which must be ignored (TYP-5).
     */
    private suspend fun fetchBestRelease(): GitHubReleaseDto? {
        val listed = api.listReleases(owner, repo, perPage = 30)
        when {
            listed.code() == 404 -> return null
            !listed.isSuccessful -> {
                throw IOException(
                    appContext.getString(R.string.update_error_http, listed.code())
                )
            }
        }
        return UpdateAssetSelector.selectRelease(listed.body().orEmpty())
    }

    private fun humanizeCheckError(e: Exception): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("404") -> appContext.getString(R.string.update_error_no_release)
            raw.contains("403") || raw.contains("rate limit", ignoreCase = true) ->
                appContext.getString(R.string.update_error_rate_limited)
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) ||
                raw.contains("failed to connect", ignoreCase = true) ->
                appContext.getString(R.string.update_error_network)
            raw.isNotBlank() && !raw.startsWith("HTTP ") -> raw
            else -> appContext.getString(R.string.update_error_check_failed)
        }
    }

    suspend fun downloadUpdate(info: AppUpdateInfo): AppUpdateState {
        if (info.apkUrl.isBlank()) {
            val err = AppUpdateState.Error(appContext.getString(R.string.update_no_compatible_apk))
            _state.value = err
            return err
        }
        _state.value = AppUpdateState.Downloading(info, 0)
        return try {
            val file = withContext(Dispatchers.IO) {
                downloadApk(info) { progress ->
                    _state.value = AppUpdateState.Downloading(info, progress)
                }
            }
            val ready = AppUpdateState.ReadyToInstall(info, file.absolutePath)
            _state.value = ready
            ready
        } catch (e: Exception) {
            val msg = e.message?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.update_error_download_failed)
            val err = AppUpdateState.Error(msg)
            _state.value = err
            err
        }
    }

    fun dismiss() {
        when (val s = _state.value) {
            is AppUpdateState.UpToDate,
            is AppUpdateState.Error -> _state.value = AppUpdateState.Idle
            is AppUpdateState.Available -> _state.value = AppUpdateState.Idle
            is AppUpdateState.ReadyToInstall -> _state.value = AppUpdateState.Available(s.info)
            else -> Unit
        }
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun installPermissionSettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun installApk(apkPath: String): Intent {
        val file = File(apkPath)
        require(file.exists()) { appContext.getString(R.string.update_error_apk_missing) }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openReleasePage(info: AppUpdateInfo): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun downloadApk(info: AppUpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        // Wipe previous downloads
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val out = File(dir, sanitizeFileName(info.apkName))

        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", "TyphoonEye/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/octet-stream")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apkSizeBytes
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    var downloaded = 0L
                    var lastPct = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
        }
        onProgress(100)
        if (!out.exists() || out.length() == 0L) {
            throw IOException(appContext.getString(R.string.update_error_download_failed))
        }
        return out
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[^\w.\-]+"""), "_").ifBlank { "update.apk" }
}
