package seamain.org.typhoonEye.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteTyphoonDao {
    @Query("SELECT * FROM favorite_typhoons ORDER BY favoritedAtEpochMs DESC, id ASC")
    fun observeAll(): Flow<List<FavoriteTyphoonEntity>>

    @Query("SELECT * FROM favorite_typhoons ORDER BY favoritedAtEpochMs DESC, id ASC")
    suspend fun getAll(): List<FavoriteTyphoonEntity>

    @Query("SELECT * FROM favorite_typhoons WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FavoriteTyphoonEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(entity: FavoriteTyphoonEntity)

    @Query("DELETE FROM favorite_typhoons WHERE id = :id")
    suspend fun deleteById(id: String)
}
