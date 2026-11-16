package seamain.org.typhoonEye.ui.about

/**
 * Settings → About destinations.
 *
 * The repository URL is the Android project the user-facing link must open.
 * [LICENSE_URL] points at this repo's `LICENSE` file, which is the Apache License 2.0.
 * Version text prefers the version compiled into the build ([displayedVersion]).
 */
object AboutLinks {
    const val REPOSITORY_URL = "https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid"
    const val LICENSE_NAME = "Apache-2.0"
    const val LICENSE_URL =
        "https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/blob/HEAD/LICENSE"

    /** QWeather attribution target. Required when a screen shows their tropical-cyclone data. */
    const val QWEATHER_URL = "https://www.qweather.com"

    fun displayedVersion(buildVersionName: String, packageVersionName: String?): String {
        val build = buildVersionName.trim()
        if (build.isNotEmpty()) return build
        return packageVersionName?.trim().orEmpty()
    }
}
