package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import seamain.org.typhoonEye.data.credentials.DataStoreUserKeyStore
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * allowBackup is on: the user's API key DataStore must be excluded from cloud backup
 * (both rule formats) and from device-to-device transfer.
 */
class BackupRulesTest {

    private fun xml(name: String): Element {
        val file = listOf("src/main/res/xml/$name", "app/src/main/res/xml/$name")
            .map(::File).first { it.exists() }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.excludes(): List<Pair<String, String>> {
        val nodes = getElementsByTagName("exclude")
        return (0 until nodes.length).map {
            val e = nodes.item(it) as Element
            e.getAttribute("domain") to e.getAttribute("path")
        }
    }

    private val keyFile = "file" to DataStoreUserKeyStore.FILE_PATH

    @Test
    fun storeFilePathMatchesDataStoreNaming() {
        assertEquals("datastore/data_source_keys.preferences_pb", DataStoreUserKeyStore.FILE_PATH)
    }

    @Test
    fun fullBackupContent_excludesKeyStore() {
        val root = xml("backup_rules.xml")
        assertEquals("full-backup-content", root.tagName)
        assertTrue(keyFile in root.excludes())
    }

    @Test
    fun dataExtractionRules_excludeKeyStore_fromCloudBackupAndDeviceTransfer() {
        val root = xml("data_extraction_rules.xml")
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val nodes = root.getElementsByTagName(section)
            assertEquals("$section present", 1, nodes.length)
            assertTrue("$section excludes key store", keyFile in (nodes.item(0) as Element).excludes())
        }
    }
}
