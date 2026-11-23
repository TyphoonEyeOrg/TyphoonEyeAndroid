package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.domain.favorites.FavoriteDraft
import seamain.org.typhoonEye.domain.favorites.FavoriteOpenPlan
import seamain.org.typhoonEye.domain.favorites.favoriteTrackId
import seamain.org.typhoonEye.domain.favorites.planFavoriteOpen
import seamain.org.typhoonEye.domain.favorites.resolveFavoriteYear
import seamain.org.typhoonEye.domain.favorites.toFavorite
import seamain.org.typhoonEye.domain.favorites.toFavoriteDraft
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.model.Typhoon
import java.time.Instant

class FavoriteMetadataTest {

    private val nowMs: Long = Instant.parse("2026-11-22T04:00:00Z").toEpochMilli()

    @Test
    fun yearAndTrackId_fromJuheAndQWeatherIds() {
        assertEquals(2026, resolveFavoriteYear("202609"))
        assertEquals(2026, resolveFavoriteYear("NP_2609"))
        assertEquals(2024, resolveFavoriteYear("ignored", explicitYear = 2024))
        assertEquals(2025, resolveFavoriteYear("no-digits", startTime = "2025-08-01 00:00"))
        assertNull(resolveFavoriteYear(""))
        assertEquals("NP_2609", favoriteTrackId("202609"))
        assertEquals("NP_2609", favoriteTrackId("np_2609"))
    }

    @Test
    fun toFavorite_dedupesJuheAndQWeatherOntoOneStableId() {
        val juhe = Typhoon(
            id = "202609",
            name = "巴威",
            englishName = "BAVI",
            status = "active"
        ).toFavoriteDraft().toFavorite(nowMs)
        val history = HistoricalStorm(
            id = "NP_2609",
            name = "巴威",
            year = 2026,
            basin = "NP",
            active = false,
            number = "202609"
        ).toFavoriteDraft().toFavorite(nowMs + 1)
        assertEquals("2609", juhe?.stableId)
        assertEquals(juhe?.stableId, history?.stableId)
        assertEquals("NP_2609", juhe?.trackId)
        assertEquals(2026, history?.year)
        assertEquals("202609", history?.number)
    }

    @Test
    fun blankId_isNotAFavorite() {
        assertNull(FavoriteDraft(sourceId = "  ", name = "x").toFavorite(nowMs))
    }

    @Test
    fun planOpen_keepsDissipatedStormAndRefusesYearsOutsideTheWindow() {
        val saved = Typhoon(
            id = "NP_2609",
            name = "Bavi",
            englishName = "BAVI",
            status = "active"
        ).toFavoriteDraft().toFavorite(nowMs)!!
        val dissipated = Typhoon(
            id = "202609",
            name = "Bavi",
            englishName = "BAVI",
            status = "dissipated"
        )
        assertTrue(planFavoriteOpen(saved, listOf(dissipated), nowMs) is FavoriteOpenPlan.Live)

        val history = planFavoriteOpen(saved, emptyList(), nowMs)
        assertTrue(history is FavoriteOpenPlan.History)
        assertEquals("NP_2609", (history as FavoriteOpenPlan.History).storm.id)

        val old = saved.copy(year = 2024, sourceId = "NP_2418", trackId = "NP_2418", stableId = "2418")
        val out = planFavoriteOpen(old, emptyList(), nowMs)
        assertTrue(out is FavoriteOpenPlan.OutOfRange)
        assertEquals(2024, (out as FavoriteOpenPlan.OutOfRange).year)
    }
}
