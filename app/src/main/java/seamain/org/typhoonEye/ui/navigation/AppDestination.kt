package seamain.org.typhoonEye.ui.navigation

/**
 * Navigation Compose routes for the single-activity app.
 */
object AppDestination {
    const val Home = "home"
    const val Settings = "settings"
    const val Licenses = "licenses"
    const val History = "history"
    const val HistoryDetail = "history/{typhoonId}"
    const val Detail = "detail/{typhoonId}"

    fun detail(typhoonId: String): String = "detail/$typhoonId"

    fun historyDetail(typhoonId: String): String = "history/$typhoonId"

    const val ArgTyphoonId = "typhoonId"
}
