package seamain.org.typhoonEye.domain.repository

import kotlinx.coroutines.flow.Flow
import seamain.org.typhoonEye.domain.model.FavoriteTyphoon

/**
 * On-device favorites. Independent of the active-typhoon cache: clearing or
 * replacing that cache must not remove a favorite.
 */
interface FavoriteRepository {
    fun observe(): Flow<List<FavoriteTyphoon>>

    suspend fun getAll(): List<FavoriteTyphoon>

    suspend fun isFavorite(rawId: String): Boolean

    /**
     * Inserts when this stable id is absent. A second save of the same storm
     * (including Juhe vs QWeather ids) does not create another row or refresh
     * the original timestamp.
     */
    suspend fun add(favorite: FavoriteTyphoon): FavoriteTyphoon

    suspend fun remove(rawId: String)

    /** @return true when the storm is favorited after the call. */
    suspend fun toggle(favorite: FavoriteTyphoon): Boolean
}
