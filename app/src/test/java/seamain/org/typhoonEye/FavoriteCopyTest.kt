package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteCopyTest {

    @Test
    fun fourLocales_haveDistinctFavoriteCopy() {
        val ids = listOf(
            R.string.favorites_banner,
            R.string.favorites_empty,
            R.string.favorites_out_of_range_title,
            R.string.favorites_out_of_range_body,
            R.string.cd_open_favorites
        )
        val byLocale = listOf("en", "zh-rCN", "zh-rTW", "b+yue").associateWith { qualifiers ->
            ids.map { text(qualifiers, it) }
        }
        byLocale.forEach { (qualifiers, values) ->
            assertEquals(qualifiers, values.size, values.toSet().size)
            assertTrue(qualifiers, values.all { it.isNotBlank() })
        }
        val banners = byLocale.values.map { it.first() }
        assertEquals(banners.size, banners.toSet().size)
    }

    @Test
    fun englishCopy_saysFavoritesDoNotKeepRemoteData() {
        val banner = text("en", R.string.favorites_banner)
        val body = text("en", R.string.favorites_out_of_range_body)
        assertTrue(banner.contains("previous year"))
        assertTrue(banner.contains("does not keep the remote record available"))
        assertTrue(body.contains("UTC+8"))
        assertTrue(body.contains("does not guarantee"))
        assertFalse(body.contains("1945"))
    }

    private fun text(qualifiers: String, id: Int): String {
        RuntimeEnvironment.setQualifiers(qualifiers)
        return RuntimeEnvironment.getApplication().getString(id)
    }
}
