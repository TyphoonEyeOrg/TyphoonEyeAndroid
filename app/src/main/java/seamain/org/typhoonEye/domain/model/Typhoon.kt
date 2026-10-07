package seamain.org.typhoonEye.domain.model

/**
 * Domain model representing a typhoon / tropical cyclone.
 */
data class Typhoon(
    val id: String,
    val name: String,
    val englishName: String,
    val status: String, // "active" | "dissipated"
    val strong: String = "",
    val positionDesc: String = "",
    val forecastText: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val points: List<TyphoonPoint> = emptyList(),
    val forecastPoints: List<TyphoonPoint> = emptyList()
)

data class TyphoonPoint(
    val time: String,
    val lat: Double,
    val lng: Double,
    val pressure: Int,
    val speed: Int,
    val power: String,
    val strong: String = "",
    val moveDirection: String = "",
    val moveSpeed: String = "",
    val radius7: String = "",
    val radius10: String = "",
    val radius12: String = ""
)

/**
 * Repository load result with offline-cache metadata.
 */
data class TyphoonFeed(
    val typhoons: List<Typhoon>,
    val fromCache: Boolean = false,
    /** Non-null only when the network fetch failed and the cache is a fallback. */
    val staleMessage: String? = null,
    /** When this data was actually fetched from the network (not when it was read). */
    val fetchedAtEpochMs: Long? = null
)
