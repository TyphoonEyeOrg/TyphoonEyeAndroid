package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.data.model.JuheActiveTyphoon
import seamain.org.typhoonEye.data.model.QWeatherStormInfo
import seamain.org.typhoonEye.data.model.toDomain
import seamain.org.typhoonEye.data.preferences.AppLanguage
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.names.StormNameTable
import seamain.org.typhoonEye.domain.names.StormNames
import seamain.org.typhoonEye.ui.util.localizedName

/** #19: storm names follow the app language (NMC / CWA / HKO official lists). */
class StormNamesTest {

    private fun juhe(name: String, en: String) =
        JuheActiveTyphoon(tfid = "202618", name = name, enname = en).toDomain()

    private fun qweather(name: String) =
        QWeatherStormInfo(id = "NP_2618", name = name, isActive = "1").toDomain(emptyList(), null, emptyList())

    @Test fun table_has_140_current_names_all_sourced() {
        assertEquals(140, StormNameTable.current.size)
        assertEquals(140, StormNameTable.current.map { it.english.lowercase() }.toSet().size)
        StormNameTable.current.forEach {
            assertFalse(it.english, it.zhHans.isNullOrBlank())
            assertFalse(it.english, it.zhHantTw.isNullOrBlank())
            assertFalse(it.english, it.zhHk.isNullOrBlank())
        }
    }

    @Test fun simplified_chinese_uses_nmc_name() =
        assertEquals("夏浪", juhe("夏浪", "HALONG").localizedName(AppLanguage.ZhHans))

    @Test fun traditional_chinese_uses_cwa_name() =
        assertEquals("哈隆", juhe("夏浪", "HALONG").localizedName(AppLanguage.ZhHant))

    @Test fun cantonese_uses_hko_name() =
        assertEquals("夏浪", juhe("夏浪", "HALONG").localizedName(AppLanguage.Yue))

    @Test fun cantonese_and_taiwan_differ_where_agencies_differ() {
        val t = juhe("米娜", "Mitag")
        assertEquals("米塔", t.localizedName(AppLanguage.ZhHant))
        assertEquals("米娜", t.localizedName(AppLanguage.Yue))
        assertEquals("米娜", t.localizedName(AppLanguage.ZhHans))
    }

    @Test fun english_uses_committee_spelling() =
        assertEquals("Co-May", juhe("竹节草", "CO-MAY").localizedName(AppLanguage.English))

    @Test fun lookup_is_trimmed_case_and_hyphen_insensitive() {
        assertEquals("點心", StormNames.localize("  dim sum ", "", AppLanguage.Yue))
        assertEquals("山神", StormNames.localize("SONTINH", "", AppLanguage.ZhHant))
    }

    @Test fun name_not_in_table_falls_back_to_api_names() {
        val t = juhe("测试", "Zzyzx")
        assertEquals("Zzyzx", t.localizedName(AppLanguage.English))
        assertEquals("测试", t.localizedName(AppLanguage.ZhHans))
        assertEquals("测试", t.localizedName(AppLanguage.ZhHant))
        assertEquals("测试", t.localizedName(AppLanguage.Yue))
    }

    @Test fun empty_enname_reverse_matches_chinese_name() {
        val t = juhe("桦加沙", "")
        assertEquals("Ragasa", t.localizedName(AppLanguage.English))
        assertEquals("樺加沙", t.localizedName(AppLanguage.ZhHant))
    }

    @Test fun empty_enname_unknown_name_english_ui_shows_chinese() =
        assertEquals("测试", juhe("测试", "").localizedName(AppLanguage.English))

    @Test fun qweather_fallback_path_localizes_via_chinese_name() {
        val t: Typhoon = qweather("夏浪")
        assertEquals("", t.englishName)
        assertEquals("Halong", t.localizedName(AppLanguage.English))
        assertEquals("哈隆", t.localizedName(AppLanguage.ZhHant))
        assertEquals("夏浪", t.localizedName(AppLanguage.Yue))
        assertEquals("夏浪", t.localizedName(AppLanguage.ZhHans))
    }

    @Test fun qweather_unknown_name_never_blank() =
        AppLanguage.entries.filter { it != AppLanguage.System }.forEach {
            assertEquals("测试", qweather("测试").localizedName(it))
        }

    @Test fun retired_name_has_simplified_only_and_falls_back_for_traditional() {
        val t = juhe("摩羯", "YAGI")
        assertEquals("摩羯", t.localizedName(AppLanguage.ZhHans))
        assertEquals("Yagi", t.localizedName(AppLanguage.English))
        assertEquals("摩羯", t.localizedName(AppLanguage.ZhHant)) // unverified → API name
        assertNotNull(StormNames.find("Yagi"))
    }

    @Test fun unnamed_low_is_not_given_a_name() {
        assertNull(juhe("热带低压", "").copy(strong = "热带低压").localizedName(AppLanguage.English))
        assertNull(juhe("", "NAMELESS").localizedName(AppLanguage.ZhHans))
    }

    @Test fun never_blank_for_any_named_input() =
        StormNameTable.current.forEach { e ->
            AppLanguage.entries.filter { it != AppLanguage.System }.forEach { lang ->
                assertTrue(StormNames.localize(e.english, null, lang).isNotBlank())
            }
        }
}
