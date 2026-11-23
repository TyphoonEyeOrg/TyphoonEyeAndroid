package seamain.org.typhoonEye.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import seamain.org.typhoonEye.data.local.FavoriteDao
import seamain.org.typhoonEye.data.local.toDomain
import seamain.org.typhoonEye.data.local.toEntity
import seamain.org.typhoonEye.domain.favorites.FavoriteDraft
import seamain.org.typhoonEye.domain.favorites.FavoriteTyphoon
import seamain.org.typhoonEye.domain.favorites.toFavorite
import seamain.org.typhoonEye.domain.repository.FavoriteRepository
import seamain.org.typhoonEye.domain.util.WallClock
import seamain.org.typhoonEye.domain.util.normalizeTyphoonId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomFavoriteRepository @Inject constructor(
    private val dao: FavoriteDao,
    private val clock: WallClock
) : FavoriteRepository {

    override fun observeFavorites(): Flow<List<FavoriteTyphoon>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun add(draft: FavoriteDraft) {
        val favorite = draft.toFavorite(clock.nowMs()) ?: return
        if (dao.getByStableId(favorite.stableId) != null) return
        dao.insert(favorite.toEntity())
    }

    override suspend fun remove(sourceId: String) {
        val stableId = normalizeTyphoonId(sourceId)
        if (stableId.isEmpty()) return
        dao.deleteByStableId(stableId)
    }
}
