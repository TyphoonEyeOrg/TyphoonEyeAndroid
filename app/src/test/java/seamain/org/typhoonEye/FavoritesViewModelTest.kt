package seamain.org.typhoonEye

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import seamain.org.typhoonEye.domain.favorites.FavoriteDraft
import seamain.org.typhoonEye.domain.favorites.FavoriteOpenPlan
import seamain.org.typhoonEye.domain.favorites.FavoriteTyphoon
import seamain.org.typhoonEye.domain.favorites.toFavorite
import seamain.org.typhoonEye.domain.favorites.toFavoriteDraft
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.repository.FavoriteRepository
import seamain.org.typhoonEye.domain.util.WallClock
import seamain.org.typhoonEye.domain.util.normalizeTyphoonId
import seamain.org.typhoonEye.ui.FavoritesViewModel
import java.time.Instant

/**
 * Favorites are independent of the live list. Dissipation and an out-of-range
 * year change how a favorite opens; they do not delete it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {

    private val nowMs = Instant.parse("2026-11-22T04:00:00Z").toEpochMilli()
    private val repository = MemoryFavoriteRepository()
    private val clock = WallClock { nowMs }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = FavoritesViewModel(repository, clock)

    @Test
    fun favoriteAndUnfavorite_roundTrip() {
        val vm = viewModel()
        vm.favorite(draft("202609", "Bavi", year = 2026))
        assertEquals(listOf("2609"), vm.favorites.value.map { it.stableId })
        vm.unfavorite("NP_2609")
        assertTrue(vm.favorites.value.isEmpty())
    }

    @Test
    fun sameStormFromLiveAndHistory_isStoredOnce() {
        val vm = viewModel()
        vm.favorite(
            Typhoon(id = "202609", name = "巴威", englishName = "BAVI", status = "active")
                .toFavoriteDraft()
        )
        vm.favorite(
            HistoricalStorm("NP_2609", "巴威", 2026, "NP", active = false, number = "202609")
                .toFavoriteDraft()
        )
        assertEquals(1, vm.favorites.value.size)
        assertEquals("BAVI", vm.favorites.value.single().englishName)
        assertEquals("NP_2609", vm.favorites.value.single().trackId)
    }

    @Test
    fun dissipatedAndOutOfRange_doNotDropTheFavorite() {
        val vm = viewModel()
        vm.favorite(draft("NP_2609", "Bavi", year = 2026, status = "active"))
        vm.favorite(draft("NP_2418", "Hagupit", year = 2024, status = "dissipated"))
        assertEquals(2, vm.favorites.value.size)

        val current = vm.favorites.value.first { it.stableId == "2609" }
        val old = vm.favorites.value.first { it.stableId == "2418" }
        val dissipated = Typhoon(
            id = "202609",
            name = "Bavi",
            englishName = "BAVI",
            status = "dissipated"
        )

        assertTrue(vm.planOpen(current, listOf(dissipated)) is FavoriteOpenPlan.Live)
        assertTrue(vm.planOpen(current, emptyList()) is FavoriteOpenPlan.History)
        val outOfRange = vm.planOpen(old, emptyList())
        assertTrue(outOfRange is FavoriteOpenPlan.OutOfRange)
        assertEquals(2024, (outOfRange as FavoriteOpenPlan.OutOfRange).year)
        assertEquals(2, vm.favorites.value.size)
        assertEquals("Hagupit", old.name)
        assertEquals(2024, old.year)
    }

    private fun draft(
        id: String,
        name: String,
        year: Int,
        status: String = "active"
    ) = FavoriteDraft(sourceId = id, name = name, englishName = name, year = year, status = status)

    private class MemoryFavoriteRepository : FavoriteRepository {
        private val rows = MutableStateFlow<List<FavoriteTyphoon>>(emptyList())
        private var savedAt = 1L

        override fun observeFavorites(): Flow<List<FavoriteTyphoon>> = rows

        override suspend fun add(draft: FavoriteDraft) {
            val favorite = draft.toFavorite(savedAt) ?: return
            if (rows.value.any { it.stableId == favorite.stableId }) return
            savedAt += 1
            rows.value = listOf(favorite) + rows.value
        }

        override suspend fun remove(sourceId: String) {
            val stableId = normalizeTyphoonId(sourceId)
            rows.value = rows.value.filterNot { it.stableId == stableId }
        }
    }
}
