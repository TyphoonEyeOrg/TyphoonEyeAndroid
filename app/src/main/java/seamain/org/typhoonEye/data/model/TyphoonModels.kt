package seamain.org.typhoonEye.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint

// region Juhe API (apis.juhe.cn/fapigw)
// Active list returns data as array; detail returns data as object.

@Serializable
data class JuheActiveListResponse(
    val reason: String = "",
    val result: JuheActiveListResult? = null,
    @SerialName("error_code") val errorCode: Int = -1
)

@Serializable
data class JuheActiveListResult(
    val data: List<JuheActiveTyphoon> = emptyList()
)

@Serializable
data class JuheActiveTyphoon(
    val tfid: String = "",
    val name: String = "",
    val enname: String = "",
    val starttime: String = "",
    val endtime: String = "",
    val lat: String = "",
    val lng: String = "",
    val movedirection: String = "",
    val movespeed: String = "",
    val power: String = "",
    val pressure: String = "",
    val radius12: String = "",
    val radius10: String = "",
    val radius7: String = "",
    val speed: String = "",
    val strong: String = "",
    val ckposition: String = "",
    val jl: String = ""
)

@Serializable
data class JuheDetailResponse(
    val reason: String = "",
    val result: JuheDetailResult? = null,
    @SerialName("error_code") val errorCode: Int = -1
)

@Serializable
data class JuheDetailResult(
    val data: JuheDetailData? = null
)

@Serializable
data class JuheDetailData(
    val tfid: String = "",
    val name: String = "",
    val enname: String = "",
    val starttime: String = "",
    val endtime: String = "",
    val lat: String = "",
    val lng: String = "",
    val movedirection: String = "",
    val movespeed: String = "",
    val power: String = "",
    val pressure: String = "",
    val radius12: String = "",
    val radius10: String = "",
    val radius7: String = "",
    val speed: String = "",
    val strong: String = "",
    val ckposition: String = "",
    val jl: String = "",
    val land: String? = null,
    val points: List<JuheTrackPoint> = emptyList()
)

@Serializable
data class JuheTrackPoint(
    val time: String = "",
    val lng: String = "",
    val lat: String = "",
    val strong: String = "",
    val power: String = "",
    val speed: String = "",
    val pressure: String = "",
    val movespeed: String = "",
    val movedirection: String = "",
    val radius7: String = "",
    val radius10: String = "",
    val radius12: String = "",
    val forecast: List<JuheForecastAgency> = emptyList()
)

@Serializable
data class JuheForecastAgency(
    val tm: String = "",
    val forecastpoints: List<JuheForecastPoint> = emptyList()
)

@Serializable
data class JuheForecastPoint(
    val time: String = "",
    val lng: String = "",
    val lat: String = "",
    val strong: String = "",
    val power: String = "",
    val speed: String = "",
    val pressure: String = "",
    val tm: String = "",
    val ybsj: String = ""
)

// endregion

// region QWeather API (v7 tropical)

@Serializable
data class QWeatherStormListResponse(
    val code: String = "",
    val updateTime: String = "",
    val storm: List<QWeatherStormInfo> = emptyList()
)

@Serializable
data class QWeatherStormInfo(
    val id: String = "",
    val name: String = "",
    val basin: String = "",
    val year: String = "",
    val isActive: String = "0"
)

@Serializable
data class QWeatherStormTrackResponse(
    val code: String = "",
    val updateTime: String = "",
    val isActive: String = "0",
    val now: QWeatherStormNow? = null,
    val track: List<QWeatherTrackPoint> = emptyList()
)

@Serializable
data class QWeatherStormNow(
    val pubTime: String = "",
    val lat: String = "",
    val lon: String = "",
    val type: String = "",
    val pressure: String = "",
    val windSpeed: String = "",
    val moveSpeed: String = "",
    val moveDir: String = "",
    val move360: String = "",
    val windRadius30: QWeatherWindRadius? = null,
    val windRadius50: QWeatherWindRadius? = null,
    val windRadius64: QWeatherWindRadius? = null
)

@Serializable
data class QWeatherTrackPoint(
    val time: String = "",
    val lat: String = "",
    val lon: String = "",
    val type: String = "",
    val pressure: String = "",
    val windSpeed: String = "",
    val moveSpeed: String = "",
    val moveDir: String = "",
    val move360: String = "",
    val windRadius30: QWeatherWindRadius? = null,
    val windRadius50: QWeatherWindRadius? = null,
    val windRadius64: QWeatherWindRadius? = null
)

@Serializable
data class QWeatherWindRadius(
    val neRadius: String = "",
    val seRadius: String = "",
    val swRadius: String = "",
    val nwRadius: String = ""
)

@Serializable
data class QWeatherStormForecastResponse(
    val code: String = "",
    val updateTime: String = "",
    val forecast: List<QWeatherForecastPoint> = emptyList()
)

@Serializable
data class QWeatherForecastPoint(
    val fxTime: String = "",
    val lat: String = "",
    val lon: String = "",
    val type: String = "",
    val pressure: String = "",
    val windSpeed: String = "",
    val moveSpeed: String = "",
    val moveDir: String = "",
    val move360: String = ""
)

// endregion

// region Mappers

private fun String?.toDoubleOrZero(): Double = this?.toDoubleOrNull() ?: 0.0
private fun String?.toIntOrZero(): Int = this?.toIntOrNull() ?: 0

/** QWeather storm type code → Chinese intensity label. */
fun qWeatherTypeToStrong(type: String): String = when (type.uppercase()) {
    "TD" -> "热带低压"
    "TS" -> "热带风暴"
    "STS" -> "强热带风暴"
    "TY" -> "台风"
    "STY" -> "强台风"
    "SUPERTY" -> "超强台风"
    else -> type
}

fun QWeatherWindRadius?.toPipeString(): String {
    if (this == null) return ""
    return listOf(neRadius, seRadius, swRadius, nwRadius).joinToString("|")
}

// Juhe has no per-storm active flag: mappers report "active" and DefaultTyphoonRepository
// downgrades storms without an observation in 24 h (TyphoonActivity.resolve).
fun JuheActiveTyphoon.toDomain(points: List<TyphoonPoint> = emptyList(), forecastPoints: List<TyphoonPoint> = emptyList()): Typhoon {
    val currentPoint = if (points.isEmpty() && lat.isNotBlank()) {
        listOf(
            TyphoonPoint(
                time = endtime.ifBlank { starttime },
                lat = lat.toDoubleOrZero(),
                lng = lng.toDoubleOrZero(),
                pressure = pressure.toIntOrZero(),
                speed = speed.toIntOrZero(),
                power = power,
                strong = strong,
                moveDirection = movedirection,
                moveSpeed = movespeed,
                radius7 = radius7,
                radius10 = radius10,
                radius12 = radius12
            )
        )
    } else {
        points
    }
    return Typhoon(
        id = tfid,
        name = name,
        englishName = enname,
        status = "active",
        strong = strong,
        positionDesc = ckposition.trim(),
        forecastText = jl.trim(),
        startTime = starttime,
        endTime = endtime,
        points = currentPoint,
        forecastPoints = forecastPoints
    )
}

fun JuheDetailData.toDomain(): Typhoon {
    val trackPoints = points.map { it.toDomain() }
    // Prefer China agency forecast from the latest track point
    val chinaForecast = points.lastOrNull()
        ?.forecast
        ?.firstOrNull { it.tm.contains("中国") && !it.tm.contains("台湾") }
        ?: points.lastOrNull()?.forecast?.firstOrNull()

    val forecastPts = chinaForecast?.forecastpoints
        ?.drop(1) // first point duplicates current observation
        ?.map { it.toDomain() }
        .orEmpty()

    return Typhoon(
        id = tfid,
        name = name,
        englishName = enname,
        status = "active",
        strong = strong,
        positionDesc = ckposition.trim(),
        forecastText = jl.trim(),
        startTime = starttime,
        endTime = endtime,
        points = trackPoints,
        forecastPoints = forecastPts
    )
}

fun JuheTrackPoint.toDomain(): TyphoonPoint = TyphoonPoint(
    time = time,
    lat = lat.toDoubleOrZero(),
    lng = lng.toDoubleOrZero(),
    pressure = pressure.toIntOrZero(),
    speed = speed.toIntOrZero(),
    power = power,
    strong = strong,
    moveDirection = movedirection,
    moveSpeed = movespeed,
    radius7 = radius7,
    radius10 = radius10,
    radius12 = radius12
)

fun JuheForecastPoint.toDomain(): TyphoonPoint = TyphoonPoint(
    time = time,
    lat = lat.toDoubleOrZero(),
    lng = lng.toDoubleOrZero(),
    pressure = pressure.toIntOrZero(),
    speed = speed.toIntOrZero(),
    power = power,
    strong = strong
)

fun QWeatherStormInfo.toDomain(
    track: List<QWeatherTrackPoint>,
    now: QWeatherStormNow?,
    forecast: List<QWeatherForecastPoint>
): Typhoon {
    val trackPoints = track.map { it.toDomain() }
    val points = if (trackPoints.isEmpty() && now != null) {
        listOf(now.toDomain())
    } else {
        trackPoints
    }
    val strongLabel = now?.type?.let { qWeatherTypeToStrong(it) }
        ?: track.lastOrNull()?.type?.let { qWeatherTypeToStrong(it) }
        ?: ""

    return Typhoon(
        id = id,
        name = name,
        englishName = id,
        status = if (isActive == "1") "active" else "dissipated",
        strong = strongLabel,
        points = points,
        forecastPoints = forecast.map { it.toDomain() }
    )
}

fun QWeatherTrackPoint.toDomain(): TyphoonPoint = TyphoonPoint(
    time = time,
    lat = lat.toDoubleOrZero(),
    lng = lon.toDoubleOrZero(),
    pressure = pressure.toIntOrZero(),
    speed = windSpeed.toIntOrZero(),
    power = type,
    strong = qWeatherTypeToStrong(type),
    moveDirection = moveDir,
    moveSpeed = moveSpeed,
    radius7 = windRadius30.toPipeString(),
    radius10 = windRadius50.toPipeString(),
    radius12 = windRadius64.toPipeString()
)

fun QWeatherStormNow.toDomain(): TyphoonPoint = TyphoonPoint(
    time = pubTime,
    lat = lat.toDoubleOrZero(),
    lng = lon.toDoubleOrZero(),
    pressure = pressure.toIntOrZero(),
    speed = windSpeed.toIntOrZero(),
    power = type,
    strong = qWeatherTypeToStrong(type),
    moveDirection = moveDir,
    moveSpeed = moveSpeed,
    radius7 = windRadius30.toPipeString(),
    radius10 = windRadius50.toPipeString(),
    radius12 = windRadius64.toPipeString()
)

fun QWeatherForecastPoint.toDomain(): TyphoonPoint = TyphoonPoint(
    time = fxTime,
    lat = lat.toDoubleOrZero(),
    lng = lon.toDoubleOrZero(),
    pressure = pressure.toIntOrZero(),
    speed = windSpeed.toIntOrZero(),
    power = type,
    strong = qWeatherTypeToStrong(type),
    moveDirection = moveDir,
    moveSpeed = moveSpeed
)

// endregion
