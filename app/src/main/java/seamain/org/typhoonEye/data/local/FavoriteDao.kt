package seamain.org.typhoonEye.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite_typhoons ORDER BY favoritedAtEpochMs DESC")
    fun observeAll(): Flow<List<FavoriteTyphoonEntity>>

    @Query("SELECT * FROM favorite_typhoons ORDER BY favoritedAtEpochMs DESC")
    suspend fun getAll(): List<FavoriteTyphoonEntity>

    @Query("SELECT * FROM favorite_typhoons WHERE stableId = :stableId LIMIT 1")
    suspend fun getByStableId(stableId: String): FavoriteTyphoonEntity?

    /** Ignores a second insert of the same [FavoriteTyphoonEntity.stableId]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FavoriteTyphoonEntity): Long

    @Query("DELETE FROM favorite_typhoons WHERE stableId = :stableId")
    suspend fun deleteByStableId(stableId: String)
}
