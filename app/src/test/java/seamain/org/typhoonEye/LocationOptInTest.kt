package seamain.org.typhoonEye

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.data.preferences.LocationOptIn
import seamain.org.typhoonEye.data.preferences.UserPreferencesRepository
import seamain.org.typhoonEye.data.preferences.UserSettings
import seamain.org.typhoonEye.data.repository.DefaultWarningRepository
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.domain.model.UserLocation

/** TYP-58: location-based alerts are opt-in on the github flavor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class LocationOptInTest {

    private val storm = Typhoon(
        id = "202601",
        name = "Test",
        englishName = "Test",
        status = "active",
        points = listOf(
            TyphoonPoint(time = "2026-10-19 08:00", lat = 18.234, lng = 128.567, pressure = 980, speed = 30, power = "11")
        )
    )
    private val user = UserLocation(latitude = 22.5431, longitude = 114.0579, label = "")

    // --- buildWatchPoints: no user coordinate unless location alerts resolved a location ---

    @Test
    fun watchPoints_withoutUserLocation_containNoUserCoordinate() {
        val points = DefaultWarningRepository.buildWatchPoints(listOf(storm), userLocation = null)
        assertTrue(points.isNotEmpty())
        assertTrue(points.none { it.first == 22.54 && it.second == 114.06 && it.third == "me" })
        assertTrue(points.none { it.third == "me" })
        // Storm centre + fixed coastal watchpoints only.
        assertTrue(points.any { it.first == 18.23 && it.second == 128.57 })
    }

    @Test
    fun watchPoints_withoutStormsOrUser_areFixedCoastalPointsOnly() {
        val points = DefaultWarningRepository.buildWatchPoints(emptyList(), userLocation = null)
        assertTrue(points.isNotEmpty())
        assertTrue(points.none { it.third == "me" })
    }

    @Test
    fun watchPoints_withUserLocation_putRoundedUserPointFirst() {
        val points = DefaultWarningRepository.buildWatchPoints(listOf(storm), userLocation = user)
        assertEquals(Triple(22.54, 114.06, "me"), points.first())
    }

    // --- per-flavor default ---

    @Test
    fun resolveEnabled_explicitChoiceWins_elseFlavorDefault() {
        assertFalse(LocationOptIn.resolveEnabled(stored = null, flavorDefault = false))
        assertTrue(LocationOptIn.resolveEnabled(stored = null, flavorDefault = true))
        assertTrue(LocationOptIn.resolveEnabled(stored = true, flavorDefault = false))
        assertFalse(LocationOptIn.resolveEnabled(stored = false, flavorDefault = true))
    }

    @Test
    fun repository_unsetToggle_resolvesToInjectedDefault() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val githubLike = UserPreferencesRepository(context, locationAlertsDefault = false)
        val fdroidLike = UserPreferencesRepository(context, locationAlertsDefault = true)
        val github = githubLike.settings.first()
        val fdroid = fdroidLike.settings.first()
        assertFalse(github.locationAlertsEnabled)
        assertTrue(fdroid.locationAlertsEnabled)
        assertEquals(null, github.locationAlertsStoredValue)
        assertFalse(github.locationOptInNoticeShown)
    }

    @Test
    fun repository_defaultFollowsBuildConfig() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            BuildConfig.LOCATION_ALERTS_DEFAULT,
            UserPreferencesRepository(context).locationAlertsDefault
        )
    }

    @Test
    fun inMemorySettingsPlaceholder_neverEnablesLocation() {
        assertFalse(UserSettings().locationAlertsEnabled)
    }

    // --- one-time upgrade notice ---

    private fun notice(
        flavorDefault: Boolean = false,
        stored: Boolean? = null,
        shown: Boolean = false,
        permission: Boolean = false,
        cached: Boolean = false
    ) = LocationOptIn.shouldShowUpgradeNotice(flavorDefault, stored, shown, permission, cached)

    @Test
    fun notice_shownToGithubUpgraderWhoUsedLocation() {
        assertTrue(notice(permission = true))
        assertTrue(notice(cached = true))
        assertTrue(notice(permission = true, cached = true))
    }

    @Test
    fun notice_notShownToFreshInstall() {
        assertFalse(notice(permission = false, cached = false))
    }

    @Test
    fun notice_notShownWhenUserStoredAValue() {
        assertFalse(notice(stored = true, permission = true, cached = true))
        assertFalse(notice(stored = false, permission = true, cached = true))
    }

    @Test
    fun notice_onlyOnce() {
        assertFalse(notice(shown = true, permission = true, cached = true))
    }

    @Test
    fun notice_neverOnFlavorWhoseDefaultIsOn() {
        assertFalse(notice(flavorDefault = true, permission = true, cached = true))
    }
}
