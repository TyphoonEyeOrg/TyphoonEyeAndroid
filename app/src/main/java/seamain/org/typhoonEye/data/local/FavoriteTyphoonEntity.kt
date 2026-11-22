package seamain.org.typhoonEye.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import seamain.org.typhoonEye.domain.favorites.FavoriteTyphoon

@Entity(tableName = "favorite_typhoons")
data class FavoriteTyphoonEntity(
    @PrimaryKey val stableId: String,
    val sourceId: String,
    val trackId: String,
    val name: String,
    val englishName: String,
    val year: Int?,
    val basin: String,
    val number: String?,
    val status: String,
    val strong: String,
    val favoritedAtEpochMs: Long
)

fun FavoriteTyphoonEntity.toDomain(): FavoriteTyphoon = FavoriteTyphoon(
    stableId = stableId,
    sourceId = sourceId,
    trackId = trackId,
    name = name,
    englishName = englishName,
    year = year,
    basin = basin,
    number = number,
    status = status,
    strong = strong,
    favoritedAtEpochMs = favoritedAtEpochMs
)

fun FavoriteTyphoon.toEntity(): FavoriteTyphoonEntity = FavoriteTyphoonEntity(
    stableId = stableId,
    sourceId = sourceId,
    trackId = trackId,
    name = name,
    englishName = englishName,
    year = year,
    basin = basin,
    number = number,
    status = status,
    strong = strong,
    favoritedAtEpochMs = favoritedAtEpochMs
)
