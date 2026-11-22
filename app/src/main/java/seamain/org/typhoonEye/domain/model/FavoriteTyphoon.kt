package seamain.org.typhoonEye.domain.model

/**
 * A typhoon the user saved on this device.
 *
 * [id] is the stable storm id (`NP_` + YYNN when the source id can be normalized),
 * so a Juhe tfid and the QWeather id of the same storm share one row.
 * [year] and [name] are kept locally: the list can render after the storm
 * leaves the active feed, and after QWeather no longer serves that year.
 */
data class FavoriteTyphoon(
    val id: String,
    val name: String,
    val englishName: String,
    val year: Int,
    val basin: String,
    val favoritedAtEpochMs: Long
)
