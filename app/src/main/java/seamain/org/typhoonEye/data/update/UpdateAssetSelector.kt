package seamain.org.typhoonEye.data.update

import seamain.org.typhoonEye.data.api.GitHubAssetDto
import seamain.org.typhoonEye.data.api.GitHubReleaseDto
import seamain.org.typhoonEye.domain.util.compareVersionLabels

/**
 * Pure release / asset selection for the GitHub-edition in-app updater (TYP-5).
 *
 * Rules:
 * - Only app release tags count: `v1.2.3` / `V1.2.3` (optionally with `-suffix`).
 *   Anything else — notably `fdroid-*` reference-APK releases — is ignored.
 * - F-Droid assets (`*fdroid*`) are never offered: the updater lives only in the
 *   GitHub edition and must never side-load the F-Droid build.
 * - When a release ships flavor-tagged assets, only `*github*` ones are eligible;
 *   older unflavored assets (e.g. `app-release.apk`) remain eligible.
 * - ABI: walk [supportedAbis] in device preference order (`Build.SUPPORTED_ABIS`),
 *   take the first asset built for that ABI; else a universal asset (no ABI token or
 *   `universal`); else `null` → caller sends the user to the release page.
 *
 * Expected asset naming (TYP-36), e.g.:
 * `TyphoonEye-v1.3.0-app-github-arm64-v8a-release.apk`,
 * `TyphoonEye-v1.3.0-app-github-universal-release.apk`.
 */
object UpdateAssetSelector {

    /** Known ABI tokens; order matters (`x86_64` must be tested before `x86`). */
    internal val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    private val APP_TAG = Regex("""^[vV]\d+(\.\d+)*([-+].*)?$""")

    fun isAppReleaseTag(tag: String): Boolean = APP_TAG.matches(tag.trim())

    /** ABI token contained in an asset name, or `null` for universal / unknown. */
    fun abiOf(assetName: String): String? {
        val n = assetName.lowercase()
        return KNOWN_ABIS.firstOrNull { abi ->
            Regex("""(^|[-_.])${Regex.escape(abi)}([-_.]|$)""").containsMatchIn(n)
        }
    }

    private fun isApk(a: GitHubAssetDto) =
        a.name.endsWith(".apk", ignoreCase = true) && a.browserDownloadUrl.isNotBlank()

    private fun isFdroid(a: GitHubAssetDto) = a.name.contains("fdroid", ignoreCase = true)
    private fun isGithub(a: GitHubAssetDto) = a.name.contains("github", ignoreCase = true)

    /** APKs the GitHub edition may install, before ABI matching. */
    fun eligibleAssets(assets: List<GitHubAssetDto>): List<GitHubAssetDto> {
        val apks = assets.filter { isApk(it) && !isFdroid(it) }
        val github = apks.filter(::isGithub)
        return github.ifEmpty { apks }
    }

    fun selectAsset(assets: List<GitHubAssetDto>, supportedAbis: List<String>): GitHubAssetDto? {
        val eligible = eligibleAssets(assets)
        if (eligible.isEmpty()) return null
        for (abi in supportedAbis) {
            eligible.firstOrNull { abiOf(it.name) == abi }?.let { return it }
        }
        return eligible.firstOrNull { abiOf(it.name) == null }
    }

    /**
     * Newest non-draft `v*` release that has at least one eligible APK; stable preferred
     * over prerelease. Returns `null` when nothing qualifies.
     */
    fun selectRelease(releases: List<GitHubReleaseDto>): GitHubReleaseDto? {
        val candidates = releases
            .filter { !it.draft && isAppReleaseTag(it.tagName) && eligibleAssets(it.assets).isNotEmpty() }
        val byVersion = Comparator<GitHubReleaseDto> { a, b -> compareVersionLabels(a.tagName, b.tagName) }
        return candidates.filter { !it.prerelease }.maxWithOrNull(byVersion)
            ?: candidates.maxWithOrNull(byVersion)
    }
}
