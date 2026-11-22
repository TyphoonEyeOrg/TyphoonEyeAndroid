package seamain.org.typhoonEye.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import seamain.org.typhoonEye.domain.model.FavoriteTyphoon

/**
 * Room row for a favorite. Column names match the property names, same as [TyphoonEntity].
 * Schema version 2, migration 1 → 2. foss and master share this version number.
 */
@Entity(tableName = "favorite_typhoons")
data class FavoriteTyphoonEntity(
    @PrimaryKey val id: String,
    val name: String,
    val englishName: String,
    val year: Int,
    val basin: String,
    val favoritedAtEpochMs: Long
)

fun FavoriteTyphoonEntity.toDomain(): FavoriteTyphoon = FavoriteTyphoon(
    id = id,
    name = name,
    englishName = englishName,
    year = year,
    basin = basin,
    favoritedAtEpochMs = favoritedAtEpochMs
)

fun FavoriteTyphoon.toEntity(): FavoriteTyphoonEntity = FavoriteTyphoonEntity(
    id = id,
    name = name,
    englishName = englishName,
    year = year,
    basin = basin,
    favoritedAtEpochMs = favoritedAtEpochMs
)
