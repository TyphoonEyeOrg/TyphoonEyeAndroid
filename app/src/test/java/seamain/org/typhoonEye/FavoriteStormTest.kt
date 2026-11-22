package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.domain.favorites.FavoriteOpen
import seamain.org.typhoonEye.domain.favorites.canonicalFavoriteId
import seamain.org.typhoonEye.domain.favorites.favoriteFrom
import seamain.org.typhoonEye.domain.favorites.favoriteYear
import seamain.org.typhoonEye.domain.favorites.resolveFavoriteOpen
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.model.Typhoon
import java.time.OffsetDateTime

class FavoriteStormTest {

    private val now = OffsetDateTime.parse("2026-11-22T12:00:00+08:00").toInstant().toEpochMilli()

    @Test
    fun canonicalId_collapsesJuheAndQWeather() {
        assertEquals("NP_2418", canonicalFavoriteId("202418"))
        assertEquals("NP_2418", canonicalFavoriteId("NP_2418"))
        assertEquals("NP_2418", canonicalFavoriteId("np_2418"))
        assertEquals("NP_2418", canonicalFavoriteId(" 2418 "))
        assertEquals("", canonicalFavoriteId("  "))
        assertEquals("BAVI", canonicalFavoriteId("bavi"))
    }

    @Test
    fun year_prefersExplicitThenIdThenStartTime() {
        assertEquals(2024, favoriteYear("202418"))
        assertEquals(2024, favoriteYear("NP_2418"))
        assertEquals(2018, favoriteYear("bavi", explicitYear = 2018))
        assertEquals(2020, favoriteYear("bavi", startTime = "2020-09-01 08:00"))
        assertEquals(0, favoriteYear("bavi"))
    }

    @Test
    fun activeStorm_opensLiveDetail_evenWhenHistoryYearWouldBeOutOfRange() {
        val live = Typhoon(
            id = "202418",
            name = "巴威",
            englishName = "BAVI",
            status = "active",
            startTime = "2024-08-20 08:00"
        )
        val saved = favoriteFrom(live, now)
        assertEquals("NP_2418", saved.id)
        assertEquals(2024, saved.year)
        val opened = resolveFavoriteOpen(saved, listOf(live), now)
        assertTrue(opened is FavoriteOpen.Active)
        assertEquals("202418", (opened as FavoriteOpen.Active).typhoon.id)
    }

    @Test
    fun dissipatedStorm_insideWindow_opensHistory_andKeepsTheFavorite() {
        val saved = favoriteFrom(
            HistoricalStorm("NP_2609", "Bavi", 2026, "NP", false, "202609"),
            now
        )
        val opened = resolveFavoriteOpen(saved, emptyList(), now)
        assertTrue(opened is FavoriteOpen.Historical)
        val storm = (opened as FavoriteOpen.Historical).storm
        assertEquals("NP_2609", storm.id)
        assertEquals(2026, storm.year)
        assertEquals("202609", storm.number)
    }

    @Test
    fun dissipatedStorm_outsideWindow_isOutOfRange_andFavoriteIsUnchanged() {
        val saved = favoriteFrom(
            Typhoon(id = "201822", name = "山竹", englishName = "MANGKHUT", status = "dissipated"),
            now
        )
        assertEquals("NP_1822", saved.id)
        assertEquals(2018, saved.year)
        val opened = resolveFavoriteOpen(
            saved,
            listOf(Typhoon(id = "201822", name = "山竹", englishName = "MANGKHUT", status = "dissipated")),
            now
        )
        assertTrue(opened is FavoriteOpen.OutOfRange)
        assertEquals(saved, (opened as FavoriteOpen.OutOfRange).favorite)
    }

    @Test
    fun sameStorm_fromHistoryId_matchesTheLiveJuheId() {
        val saved = favoriteFrom(
            HistoricalStorm("NP_2610", "Mekkhala", 2026, "NP", true, "202610"),
            now
        )
        val live = Typhoon(id = "202610", name = "美莎克", englishName = "MEKKHALA", status = "active")
        val opened = resolveFavoriteOpen(saved, listOf(live), now)
        assertTrue(opened is FavoriteOpen.Active)
        assertEquals(live, (opened as FavoriteOpen.Active).typhoon)
    }
}
