package seamain.org.typhoonEye

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import seamain.org.typhoonEye.domain.favorites.FavoriteOpen
import seamain.org.typhoonEye.domain.favorites.canonicalFavoriteId
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.model.FavoriteTyphoon
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.repository.FavoriteRepository
import seamain.org.typhoonEye.domain.util.WallClock
import seamain.org.typhoonEye.ui.FavoriteViewModel
import java.time.OffsetDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val jobs = mutableListOf<Job>()
    private val now = OffsetDateTime.parse("2026-11-22T12:00:00+08:00").toInstant().toEpochMilli()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun toggle_dedupesByStableId_andSecondTapRemoves() = runBlocking {
        val repository = InMemoryFavoriteRepository()
        val viewModel = viewModel(repository)
        val juhe = Typhoon(id = "202609", name = "巴威", englishName = "BAVI", status = "active")
        viewModel.toggleTyphoon(juhe)
        assertEquals(listOf("NP_2609"), viewModel.favorites.value.map { it.id })
        assertTrue(viewModel.isFavorite("NP_2609"))
        assertTrue(viewModel.isFavorite("202609"))

        viewModel.toggleTyphoon(juhe.copy(id = "NP_2609", name = "Bavi"))
        assertTrue(viewModel.favorites.value.isEmpty())
        assertFalse(viewModel.isFavorite("202609"))
        assertEquals(1, repository.rowsEverInserted)
    }

    @Test
    fun historicalFavorite_survivesAnEmptyActiveList() = runBlocking {
        val viewModel = viewModel(InMemoryFavoriteRepository())
        viewModel.toggleHistorical(
            HistoricalStorm("NP_2601", "Dujuan", 2026, "NP", false, "202601")
        )
        val saved = viewModel.favorites.value.single()
        val opened = viewModel.openTarget(saved, emptyList())
        assertTrue(opened is FavoriteOpen.Historical)
        assertEquals("NP_2601", (opened as FavoriteOpen.Historical).storm.id)
        assertEquals(listOf("NP_2601"), viewModel.favorites.value.map { it.id })
    }

    @Test
    fun leavingTheActiveFeed_doesNotDropAnOutOfRangeFavorite() = runBlocking {
        val viewModel = viewModel(InMemoryFavoriteRepository())
        val live = Typhoon(
            id = "201822",
            name = "山竹",
            englishName = "MANGKHUT",
            status = "active"
        )
        viewModel.toggleTyphoon(live)
        val saved = viewModel.favorites.value.single()
        assertTrue(viewModel.openTarget(saved, listOf(live)) is FavoriteOpen.Active)

        val after = viewModel.openTarget(saved, emptyList())
        assertTrue(after is FavoriteOpen.OutOfRange)
        assertEquals("山竹", (after as FavoriteOpen.OutOfRange).favorite.name)
        assertEquals(2018, after.favorite.year)
        assertEquals(listOf(saved), viewModel.favorites.value)
    }

    private fun viewModel(repository: FavoriteRepository): FavoriteViewModel {
        val viewModel = FavoriteViewModel(repository, WallClock { now })
        store.put("favorites-under-test", viewModel)
        val scope = CoroutineScope(dispatcher)
        jobs += scope.launch { viewModel.favorites.collect {} }
        jobs += scope.launch { viewModel.favoriteIds.collect {} }
        return viewModel
    }

    private class InMemoryFavoriteRepository : FavoriteRepository {
        private val rows = MutableStateFlow<List<FavoriteTyphoon>>(emptyList())
        var rowsEverInserted: Int = 0

        override fun observe(): Flow<List<FavoriteTyphoon>> = rows

        override suspend fun getAll(): List<FavoriteTyphoon> = rows.value

        override suspend fun isFavorite(rawId: String): Boolean =
            canonicalFavoriteId(rawId) in rows.value.map { it.id }

        override suspend fun add(favorite: FavoriteTyphoon): FavoriteTyphoon {
            val stored = favorite.copy(id = canonicalFavoriteId(favorite.id))
            val existing = rows.value.firstOrNull { it.id == stored.id }
            if (existing != null) return existing
            rowsEverInserted += 1
            rows.value = listOf(stored) + rows.value
            return stored
        }

        override suspend fun remove(rawId: String) {
            val id = canonicalFavoriteId(rawId)
            rows.value = rows.value.filterNot { it.id == id }
        }

        override suspend fun toggle(favorite: FavoriteTyphoon): Boolean {
            val id = canonicalFavoriteId(favorite.id)
            return if (rows.value.any { it.id == id }) {
                remove(id)
                false
            } else {
                add(favorite)
                true
            }
        }
    }
}
