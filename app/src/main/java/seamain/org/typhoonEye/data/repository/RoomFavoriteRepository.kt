package seamain.org.typhoonEye.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import seamain.org.typhoonEye.data.local.FavoriteTyphoonDao
import seamain.org.typhoonEye.data.local.toDomain
import seamain.org.typhoonEye.data.local.toEntity
import seamain.org.typhoonEye.domain.favorites.canonicalFavoriteId
import seamain.org.typhoonEye.domain.model.FavoriteTyphoon
import seamain.org.typhoonEye.domain.repository.FavoriteRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomFavoriteRepository @Inject constructor(
    private val dao: FavoriteTyphoonDao
) : FavoriteRepository {

    private val gate = Mutex()

    override fun observe(): Flow<List<FavoriteTyphoon>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAll(): List<FavoriteTyphoon> = dao.getAll().map { it.toDomain() }

    override suspend fun isFavorite(rawId: String): Boolean = gate.withLock {
        val id = canonicalFavoriteId(rawId)
        id.isNotEmpty() && dao.getById(id) != null
    }

    override suspend fun add(favorite: FavoriteTyphoon): FavoriteTyphoon = gate.withLock {
        val stored = favorite.withCanonicalId()
        if (stored.id.isEmpty()) return@withLock stored
        dao.insertIgnoring(stored.toEntity())
        dao.getById(stored.id)?.toDomain() ?: stored
    }

    override suspend fun remove(rawId: String) {
        gate.withLock {
            val id = canonicalFavoriteId(rawId)
            if (id.isEmpty()) return@withLock
            dao.deleteById(id)
        }
    }

    override suspend fun toggle(favorite: FavoriteTyphoon): Boolean = gate.withLock {
        val stored = favorite.withCanonicalId()
        if (stored.id.isEmpty()) return@withLock false
        if (dao.getById(stored.id) != null) {
            dao.deleteById(stored.id)
            false
        } else {
            dao.insertIgnoring(stored.toEntity())
            true
        }
    }

    private fun FavoriteTyphoon.withCanonicalId(): FavoriteTyphoon =
        copy(id = canonicalFavoriteId(id))
}
