package seamain.org.typhoonEye.domain.repository

import kotlinx.coroutines.flow.Flow
import seamain.org.typhoonEye.domain.favorites.FavoriteDraft
import seamain.org.typhoonEye.domain.favorites.FavoriteTyphoon

/**
 * On-device favorites. No account, no upload. A second add of the same stable
 * storm id is a no-op so the list never contains duplicates.
 */
interface FavoriteRepository {
    fun observeFavorites(): Flow<List<FavoriteTyphoon>>

    suspend fun add(draft: FavoriteDraft)

    suspend fun remove(sourceId: String)
}
