package seamain.org.typhoonEye.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint

class TyphoonLocalDataSource(
    private val dao: TyphoonDao,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) {
    suspend fun getAll(): List<Typhoon> =
        dao.getAll().map { it.toDomain(json) }

    suspend fun getById(id: String): Typhoon? =
        dao.getById(id)?.toDomain(json)

    suspend fun getCachedAtMs(id: String): Long? =
        dao.getById(id)?.cachedAtEpochMs

    suspend fun latestCachedAtMs(): Long? =
        dao.getAll().maxOfOrNull { it.cachedAtEpochMs }

    suspend fun replaceAll(typhoons: List<Typhoon>) {
        val now = System.currentTimeMillis()
        dao.replaceAll(typhoons.map { it.toEntity(json, now) })
    }

    suspend fun upsert(typhoon: Typhoon) {
        dao.upsert(typhoon.toEntity(json, System.currentTimeMillis()))
    }
}

@Serializable
private data class TyphoonPointDto(
    val time: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val pressure: Int = 0,
    val speed: Int = 0,
    val power: String = "",
    val strong: String = "",
    val moveDirection: String = "",
    val moveSpeed: String = "",
    val radius7: String = "",
    val radius10: String = "",
    val radius12: String = ""
)

private fun TyphoonPoint.toDto() = TyphoonPointDto(
    time = time,
    lat = lat,
    lng = lng,
    pressure = pressure,
    speed = speed,
    power = power,
    strong = strong,
    moveDirection = moveDirection,
    moveSpeed = moveSpeed,
    radius7 = radius7,
    radius10 = radius10,
    radius12 = radius12
)

private fun TyphoonPointDto.toDomain() = TyphoonPoint(
    time = time,
    lat = lat,
    lng = lng,
    pressure = pressure,
    speed = speed,
    power = power,
    strong = strong,
    moveDirection = moveDirection,
    moveSpeed = moveSpeed,
    radius7 = radius7,
    radius10 = radius10,
    radius12 = radius12
)

private fun Typhoon.toEntity(json: Json, cachedAt: Long): TyphoonEntity =
    TyphoonEntity(
        id = id,
        name = name,
        englishName = englishName,
        status = status,
        strong = strong,
        positionDesc = positionDesc,
        forecastText = forecastText,
        startTime = startTime,
        endTime = endTime,
        pointsJson = json.encodeToString(points.map { it.toDto() }),
        forecastPointsJson = json.encodeToString(forecastPoints.map { it.toDto() }),
        cachedAtEpochMs = cachedAt
    )

private fun TyphoonEntity.toDomain(json: Json): Typhoon {
    val points = runCatching {
        json.decodeFromString<List<TyphoonPointDto>>(pointsJson).map { it.toDomain() }
    }.getOrDefault(emptyList())
    val forecast = runCatching {
        json.decodeFromString<List<TyphoonPointDto>>(forecastPointsJson).map { it.toDomain() }
    }.getOrDefault(emptyList())
    return Typhoon(
        id = id,
        name = name,
        englishName = englishName,
        status = status,
        strong = strong,
        positionDesc = positionDesc,
        forecastText = forecastText,
        startTime = startTime,
        endTime = endTime,
        points = points,
        forecastPoints = forecast
    )
}
