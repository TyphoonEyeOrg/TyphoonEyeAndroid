package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the locale layout from F-Droid review !45561:
 * - values/ is the English default and defines every key (no crash, no Chinese fallback)
 * - every translation defines exactly the same keys (no drift)
 * - no stale values-en copy that could diverge from the default
 */
class StringResourcesParityTest {

    private val resDir: File = listOf(File("src/main/res"), File("app/src/main/res"))
        .first { it.isDirectory }

    private fun keys(dir: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(resDir, "$dir/strings.xml"))
        val nodes = doc.documentElement.childNodes
        return (0 until nodes.length).map { nodes.item(it) }
            .filter { it.nodeName in setOf("string", "plurals", "string-array") }
            .map { it.attributes.getNamedItem("name").nodeValue }
            .toSet()
    }

    private fun defaultText(name: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(resDir, "values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == name }
            .textContent
    }

    @Test
    fun translationsMatchDefaultKeys() {
        val base = keys("values")
        assertTrue(base.isNotEmpty())
        for (dir in listOf("values-zh-rCN", "values-zh-rTW", "values-b+yue")) {
            assertEquals("Key mismatch in $dir", base, keys(dir))
        }
    }

    @Test
    fun defaultLocaleIsEnglishAndHasNoCjk() {
        assertEquals("Typhoon Eye", defaultText("app_name"))
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val offenders = keys("values").filter { defaultText(it).contains(cjk) }
        // Proper names of Chinese data providers are the only allowed CJK, if any.
        assertTrue("CJK text in default English strings: $offenders", offenders.isEmpty())
    }

    @Test
    fun noSeparateValuesEnDirectory() {
        assertFalse(File(resDir, "values-en").exists())
    }
}
