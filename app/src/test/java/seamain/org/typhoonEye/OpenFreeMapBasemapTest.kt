package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.ui.util.OpenFreeMap
import seamain.org.typhoonEye.ui.util.ResolvedBasemap
import seamain.org.typhoonEye.ui.util.basemapAttributionUrl
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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

    @Test
    fun badge_linksToOpenFreeMapWithFullAttribution() {
        assertEquals("https://openfreemap.org", OpenFreeMap.HOME_URL)
        assertEquals(OpenFreeMap.HOME_URL, basemapAttributionUrl(ResolvedBasemap.OpenStreet))
        assertNull(basemapAttributionUrl(ResolvedBasemap.Amap))
        val expected = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW", "values-b+yue")) {
            assertEquals(dir, expected, stringValue(dir, "map_badge_open"))
        }
    }

    private fun stringValue(dir: String, name: String): String {
        val resDir = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(resDir, "$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == name }
            .textContent
    }
}
