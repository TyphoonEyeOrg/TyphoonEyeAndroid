package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.data.api.GitHubAssetDto
import seamain.org.typhoonEye.data.api.GitHubReleaseDto
import seamain.org.typhoonEye.data.update.UpdateAssetSelector

class UpdateAssetSelectorTest {

    private fun apk(name: String) =
        GitHubAssetDto(name = name, browserDownloadUrl = "https://example.invalid/$name", size = 1L)

    private val arm64Device = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val v7aDevice = listOf("armeabi-v7a", "armeabi")
    private val x8664Device = listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a")

    /** Expected TYP-36 naming: github flavor, per-ABI + universal, plus F-Droid noise. */
    private val splitRelease = listOf(
        apk("TyphoonEye-v1.3.0-app-fdroid-arm64-v8a-release.apk"),
        apk("TyphoonEye-v1.3.0-app-github-split-armeabi-v7a-release.apk"),
        apk("TyphoonEye-v1.3.0-app-github-split-arm64-v8a-release.apk"),
        apk("TyphoonEye-v1.3.0-app-github-split-x86-release.apk"),
        apk("TyphoonEye-v1.3.0-app-github-split-x86_64-release.apk"),
        apk("TyphoonEye-v1.3.0-app-github-release.apk"),
        GitHubAssetDto(name = "mapping.txt", browserDownloadUrl = "https://example.invalid/m")
    )

    @Test
    fun arm64DevicePicksArm64GithubApk() {
        assertEquals(
            "TyphoonEye-v1.3.0-app-github-split-arm64-v8a-release.apk",
            UpdateAssetSelector.selectAsset(splitRelease, arm64Device)?.name
        )
    }

    @Test
    fun armv7DevicePicksV7aApk() {
        assertEquals(
            "TyphoonEye-v1.3.0-app-github-split-armeabi-v7a-release.apk",
            UpdateAssetSelector.selectAsset(splitRelease, v7aDevice)?.name
        )
    }

    @Test
    fun x8664DevicePicksX8664NotX86() {
        assertEquals(
            "TyphoonEye-v1.3.0-app-github-split-x86_64-release.apk",
            UpdateAssetSelector.selectAsset(splitRelease, x8664Device)?.name
        )
    }

    @Test
    fun onlyUniversalFallsBackToUniversal() {
        val assets = listOf(
            apk("TyphoonEye-v1.3.0-app-fdroid-release.apk"),
            apk("TyphoonEye-v1.3.0-app-github-release.apk")
        )
        assertEquals(
            "TyphoonEye-v1.3.0-app-github-release.apk",
            UpdateAssetSelector.selectAsset(assets, arm64Device)?.name
        )
    }

    @Test
    fun onlyFdroidAssetsGivesNothing() {
        val assets = listOf(
            apk("app-fdroid-arm64-v8a-release-signed.apk"),
            apk("app-fdroid-armeabi-v7a-release-signed.apk"),
            apk("app-fdroid-x86-release-signed.apk"),
            apk("app-fdroid-x86_64-release-signed.apk")
        )
        assertNull(UpdateAssetSelector.selectAsset(assets, arm64Device))
    }

    @Test
    fun noMatchingAbiAndNoUniversalGivesNothing() {
        val assets = listOf(apk("TyphoonEye-v1.3.0-app-github-split-x86_64-release.apk"))
        assertNull(UpdateAssetSelector.selectAsset(assets, arm64Device))
    }

    @Test
    fun realV120ReleasePicksGithubNotFirstFdroid() {
        // v1.2.0 lists the fdroid APK first; the old updater installed it.
        val assets = listOf(
            apk("TyphoonEye-v1.2.0-app-fdroid-release.apk"),
            apk("TyphoonEye-v1.2.0-app-github-release.apk")
        )
        assertEquals(
            "TyphoonEye-v1.2.0-app-github-release.apk",
            UpdateAssetSelector.selectAsset(assets, arm64Device)?.name
        )
    }

    @Test
    fun legacyUnflavoredApkStillEligible() {
        val assets = listOf(apk("TyphoonEye-v1.1.1-app-release.apk"))
        assertEquals(
            "TyphoonEye-v1.1.1-app-release.apk",
            UpdateAssetSelector.selectAsset(assets, arm64Device)?.name
        )
    }

    @Test
    fun onlyVTagsAreAppReleases() {
        assertTrue(UpdateAssetSelector.isAppReleaseTag("v1.2.0"))
        assertTrue(UpdateAssetSelector.isAppReleaseTag("V1.3.0-beta1"))
        assertFalse(UpdateAssetSelector.isAppReleaseTag("fdroid-1.2.0"))
        assertFalse(UpdateAssetSelector.isAppReleaseTag("1.2.0"))
        assertFalse(UpdateAssetSelector.isAppReleaseTag("nightly"))
    }

    @Test
    fun selectReleaseIgnoresFdroidReleaseEvenWhenListedFirst() {
        val releases = listOf(
            GitHubReleaseDto(
                tagName = "fdroid-1.2.0",
                assets = listOf(apk("app-fdroid-arm64-v8a-release-signed.apk"))
            ),
            GitHubReleaseDto(
                tagName = "v1.2.0",
                assets = listOf(
                    apk("TyphoonEye-v1.2.0-app-fdroid-release.apk"),
                    apk("TyphoonEye-v1.2.0-app-github-release.apk")
                )
            ),
            GitHubReleaseDto(tagName = "v1.1.1", assets = listOf(apk("TyphoonEye-v1.1.1-app-release.apk")))
        )
        assertEquals("v1.2.0", UpdateAssetSelector.selectRelease(releases)?.tagName)
    }

    @Test
    fun selectReleasePrefersNewestStableAndSkipsDrafts() {
        val releases = listOf(
            GitHubReleaseDto(tagName = "v1.4.0", draft = true, assets = listOf(apk("a-github.apk"))),
            GitHubReleaseDto(tagName = "v1.3.1-beta", prerelease = true, assets = listOf(apk("b-github.apk"))),
            GitHubReleaseDto(tagName = "v1.2.0", assets = listOf(apk("c-github.apk"))),
            GitHubReleaseDto(tagName = "v1.3.0", assets = listOf(apk("d-github.apk")))
        )
        assertEquals("v1.3.0", UpdateAssetSelector.selectRelease(releases)?.tagName)
    }

    @Test
    fun selectReleaseNullWhenOnlyFdroid() {
        val releases = listOf(
            GitHubReleaseDto(tagName = "fdroid-1.2.0", assets = listOf(apk("app-fdroid-x86-release-signed.apk")))
        )
        assertNull(UpdateAssetSelector.selectRelease(releases))
    }

    @Test
    fun abiTokenParsing() {
        assertEquals("x86", UpdateAssetSelector.abiOf("app-github-split-x86-release.apk"))
        assertEquals("x86_64", UpdateAssetSelector.abiOf("app-github-split-x86_64-release.apk"))
        assertNull(UpdateAssetSelector.abiOf("app-github-release.apk"))
    }

    @Test
    fun releaseAssetNames_sortUniversalFirst_forLegacyUpdater() {
        // GitHub lists release assets by file name; <=1.2.0 updaters take the first .apk.
        val names = listOf(
            "TyphoonEye-v1.3.0-app-github-split-arm64-v8a-release.apk",
            "TyphoonEye-v1.3.0-app-github-split-armeabi-v7a-release.apk",
            "TyphoonEye-v1.3.0-app-github-split-x86-release.apk",
            "TyphoonEye-v1.3.0-app-github-split-x86_64-release.apk",
            "TyphoonEye-v1.3.0-app-github-release.apk",
        )
        assertEquals("TyphoonEye-v1.3.0-app-github-release.apk", names.sorted().first())
    }
}
