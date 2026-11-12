package seamain.org.typhoonEye.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import java.io.File

data class CachedHistoricalList(
    val year: Int,
    val storms: List<HistoricalStorm>,
    val fetchedAtEpochMs: Long
)

data class CachedHistoricalTrack(
    val id: String,
    val name: String,
    val active: Boolean,
    val strong: String,
    val points: List<TyphoonPoint>,
    val trackMissing: Boolean,
    val windRadiiMissing: Boolean,
    val fetchedAtEpochMs: Long
)

/**
 * Year lists and opened tracks for historical search.
 * Kept out of the active-typhoon table so a history query cannot replace the live feed.
 */
interface HistoricalCache {
    suspend fun readList(year: Int): CachedHistoricalList?
    suspend fun writeList(year: Int, storms: List<HistoricalStorm>, fetchedAtMs: Long)
    suspend fun readTrack(id: String): CachedHistoricalTrack?
    suspend fun writeTrack(track: CachedHistoricalTrack)
}

class MemoryHistoricalCache : HistoricalCache {
    private val lists = mutableMapOf<Int, CachedHistoricalList>()
    private val tracks = mutableMapOf<String, CachedHistoricalTrack>()

    override suspend fun readList(year: Int): CachedHistoricalList? = lists[year]

    override suspend fun writeList(year: Int, storms: List<HistoricalStorm>, fetchedAtMs: Long) {
        lists[year] = CachedHistoricalList(year, storms, fetchedAtMs)
    }

    override suspend fun readTrack(id: String): CachedHistoricalTrack? = tracks[id]

    override suspend fun writeTrack(track: CachedHistoricalTrack) {
        tracks[track.id] = track
    }
}

class FileHistoricalCache(
    context: Context,
    private val json: Json
) : HistoricalCache {
    private val file = File(context.applicationContext.filesDir, "historical-storms.json")
    private val mutex = Mutex()

    override suspend fun readList(year: Int): CachedHistoricalList? = withContext(Dispatchers.IO) {
        mutex.withLock {
            readFile()?.lists?.firstOrNull { it.year == year }?.toDomain()
        }
    }

    override suspend fun writeList(year: Int, storms: List<HistoricalStorm>, fetchedAtMs: Long) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readFile() ?: CacheFile()
                val stored = StoredList(year, fetchedAtMs, storms.map { it.toStored() })
                val lists = current.lists.filterNot { it.year == year } + stored
                writeFile(current.copy(lists = lists))
            }
        }
    }

    override suspend fun readTrack(id: String): CachedHistoricalTrack? = withContext(Dispatchers.IO) {
        mutex.withLock {
            readFile()?.tracks?.firstOrNull { it.id == id }?.toDomain()
        }
    }

    override suspend fun writeTrack(track: CachedHistoricalTrack) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readFile() ?: CacheFile()
                val tracks = (current.tracks.filterNot { it.id == track.id } + track.toStored())
                    .takeLast(MAX_TRACKS)
                writeFile(current.copy(tracks = tracks))
            }
        }
    }

    private fun readFile(): CacheFile? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(CacheFile.serializer(), file.readText()) }.getOrNull()
    }

    private fun writeFile(cache: CacheFile) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(CacheFile.serializer(), cache))
    }

    private companion object {
        const val MAX_TRACKS = 12
    }
}

@Serializable
private data class CacheFile(
    val lists: List<StoredList> = emptyList(),
    val tracks: List<StoredTrack> = emptyList()
)

@Serializable
private data class StoredList(
    val year: Int,
    val fetchedAtEpochMs: Long,
    val storms: List<StoredStorm>
) {
    fun toDomain() = CachedHistoricalList(
        year = year,
        fetchedAtEpochMs = fetchedAtEpochMs,
        storms = storms.map { it.toDomain() }
    )
}

@Serializable
private data class StoredStorm(
    val id: String,
    val name: String = "",
    val year: Int,
    val basin: String = "NP",
    val active: Boolean = false,
    val number: String? = null
) {
    fun toDomain() = HistoricalStorm(id, name, year, basin, active, number)
}

@Serializable
private data class StoredTrack(
    val id: String,
    val name: String = "",
    val active: Boolean = false,
    val strong: String = "",
    val points: List<StoredPoint> = emptyList(),
    val trackMissing: Boolean = false,
    val windRadiiMissing: Boolean = false,
    val fetchedAtEpochMs: Long
) {
    fun toDomain() = CachedHistoricalTrack(
        id = id,
        name = name,
        active = active,
        strong = strong,
        points = points.map { it.toDomain() },
        trackMissing = trackMissing,
        windRadiiMissing = windRadiiMissing,
        fetchedAtEpochMs = fetchedAtEpochMs
    )
}

@Serializable
private data class StoredPoint(
    val time: String,
    val lat: Double,
    val lng: Double,
    val pressure: Int = 0,
    val speed: Int = 0,
    val power: String = "",
    val strong: String = "",
    val moveDirection: String = "",
    val moveSpeed: String = "",
    val radius7: String = "",
    val radius10: String = "",
    val radius12: String = "",
    val pressureReported: Boolean = true,
    val speedReported: Boolean = true
) {
    fun toDomain() = TyphoonPoint(
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
        radius12 = radius12,
        pressureReported = pressureReported,
        speedReported = speedReported
    )
}

private fun HistoricalStorm.toStored() = StoredStorm(id, name, year, basin, active, number)

private fun TyphoonPoint.toStored() = StoredPoint(
    time, lat, lng, pressure, speed, power, strong, moveDirection, moveSpeed,
    radius7, radius10, radius12, pressureReported, speedReported
)

private fun CachedHistoricalTrack.toStored() = StoredTrack(
    id, name, active, strong, points.map { it.toStored() },
    trackMissing, windRadiiMissing, fetchedAtEpochMs
)
