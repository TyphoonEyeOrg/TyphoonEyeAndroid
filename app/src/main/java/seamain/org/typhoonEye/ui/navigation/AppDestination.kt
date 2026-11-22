package seamain.org.typhoonEye.ui.navigation

import android.net.Uri

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
    const val Favorites = "favorites"
    const val FavoriteUnavailable = "favorites/unavailable/{favoriteId}"

    fun detail(typhoonId: String): String = "detail/$typhoonId"

    fun historyDetail(typhoonId: String): String = "history/$typhoonId"

    fun favoriteUnavailable(favoriteId: String): String =
        "favorites/unavailable/${Uri.encode(favoriteId)}"

    const val ArgTyphoonId = "typhoonId"
    const val ArgFavoriteId = "favoriteId"
}
