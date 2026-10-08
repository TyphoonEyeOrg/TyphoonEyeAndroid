package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.ui.util.OpenFreeMap

/** Carto raster tiles now return an "API KEY REQUIRED" watermark; basemap is OpenFreeMap. */
class OpenFreeMapBasemapTest {

    @Test
    fun lightTheme_usesLiberty() {
        assertEquals("https://tiles.openfreemap.org/styles/liberty", OpenFreeMap.styleUrl(darkTheme = false))
    }

    @Test
    fun darkTheme_usesDark() {
        assertEquals("https://tiles.openfreemap.org/styles/dark", OpenFreeMap.styleUrl(darkTheme = true))
    }

    @Test
    fun styleUrl_isHttpsAndKeyless() {
        listOf(true, false).forEach { dark ->
            val url = OpenFreeMap.styleUrl(dark)
            assertTrue(url.startsWith("https://"))
            assertTrue("no key in style URL", !url.contains("key", ignoreCase = true))
        }
    }
}
