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
import seamain.org.typhoonEye.domain.names.NameColumn
import seamain.org.typhoonEye.domain.names.StormNameEntry
import seamain.org.typhoonEye.domain.names.StormNameFile
import seamain.org.typhoonEye.domain.names.StormNameIndex
import seamain.org.typhoonEye.domain.names.StormNames
import seamain.org.typhoonEye.ui.util.localizedName

/** #19: storm names follow the app language (NMC / CWA / HKO official lists). */
class StormNamesTest {

    private fun juhe(name: String, en: String) =
        JuheActiveTyphoon(tfid = "202618", name = name, enname = en).toDomain()

    private fun qweather(name: String) =
        QWeatherStormInfo(id = "NP_2618", name = name, isActive = "1").toDomain(emptyList(), null, emptyList())

    @Test fun table_has_140_current_names_all_sourced() {
        val current = StormNames.index.file.entries.filter { !it.retired }
        assertEquals(140, current.size)
        assertEquals(140, current.map { it.en.lowercase() }.toSet().size)
        current.forEach {
            assertFalse(it.en, it.zhHans.isNullOrBlank())
            assertFalse(it.en, it.zhHantTW.isNullOrBlank())
            assertFalse(it.en, it.zhHK.isNullOrBlank())
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
        assertEquals("Co-may", juhe("竹节草", "CO-MAY").localizedName(AppLanguage.English))

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
        StormNames.index.file.entries.forEach { e ->
            AppLanguage.entries.filter { it != AppLanguage.System }.forEach { lang ->
                assertTrue(StormNames.localize(e.en, null, lang).isNotBlank())
            }
        }

    @Test fun champi_columns() {
        val e = StormNames.find("CHAMPI")!!
        assertEquals("Champi", e.en)
        assertEquals("蔷琵", e.zhHans)
        assertEquals("薔琵", e.zhHantTW)
        assertEquals("薔琵", e.zhHK)
    }

    @Test fun json_parses_on_jvm_with_schema() {
        val f = StormNames.parse(
            """{"schemaVersion":1,"entries":[{"en":"A","zhHans":"甲","zhHantTW":null,"zhHK":"甲",""" +
                """"retired":false,"sources":{"zhHans":"nmc"},"extra":1}]}"""
        )
        assertEquals(1, f.entries.size)
        assertNull(f.entries[0].zhHantTW)
        assertEquals("nmc", f.entries[0].sources.zhHans)
    }

    private val collisionIndex = StormNameIndex(
        StormNameFile(
            schemaVersion = 1,
            entries = listOf(
                StormNameEntry("Alpha", "同名", "同名甲", "同名甲"),
                StormNameEntry("Bravo", "同名", null, null, retired = true),
                StormNameEntry("Charlie", "独有", "獨有", "獨有"),
            )
        )
    )

    @Test fun reverse_lookup_collision_is_treated_as_not_found() {
        assertNull(collisionIndex.find("", "同名"))
        assertEquals("同名", collisionIndex.localize("", "同名", NameColumn.English))
        assertEquals("同名", collisionIndex.localize("", "同名", NameColumn.ZhHantTW))
        // English key still resolves directly.
        assertEquals("同名甲", collisionIndex.localize("alpha", "同名", NameColumn.ZhHK))
    }

    @Test fun reverse_lookup_unique_hit_and_miss() {
        assertEquals("Charlie", collisionIndex.localize("", "独有", NameColumn.English))
        assertNull(collisionIndex.find("", "没有"))
        assertEquals("没有", collisionIndex.localize("", "没有", NameColumn.English))
        assertEquals("没有", collisionIndex.localize(null, "没有", NameColumn.ZhHK))
    }

    @Test fun real_table_has_no_zh_hans_collisions() =
        StormNames.index.file.entries.filter { !it.zhHans.isNullOrBlank() }
            .groupBy { it.zhHans }.forEach { (zh, l) -> assertEquals(zh, 1, l.size) }

    @Test fun system_locale_column_selection() {
        mapOf(
            "zh-Hant-HK" to NameColumn.ZhHK, "zh-Hant-MO" to NameColumn.ZhHK,
            "zh-HK" to NameColumn.ZhHK, "zh-MO" to NameColumn.ZhHK, "yue" to NameColumn.ZhHK,
            "zh-Hant-TW" to NameColumn.ZhHantTW, "zh-Hant" to NameColumn.ZhHantTW,
            "zh-TW" to NameColumn.ZhHantTW, "zh-CN" to NameColumn.ZhHans,
            "zh-Hans-HK" to NameColumn.ZhHans, "zh-Hans-MO" to NameColumn.ZhHans, "zh-Hans-CN" to NameColumn.ZhHans, "zh" to NameColumn.ZhHans,
            "en-US" to NameColumn.English, "de-DE" to NameColumn.English,
        ).forEach { (tag, col) -> assertEquals(tag, col, StormNames.columnForLocaleTag(tag)) }
        assertEquals("夏浪", juhe("夏浪", "HALONG").localizedName(StormNames.columnForLocaleTag("zh-Hant-HK")))
        assertEquals("哈隆", juhe("夏浪", "HALONG").localizedName(StormNames.columnForLocaleTag("zh-Hant")))
    }

    @Test fun ui_language_resolution_unchanged() {
        assertEquals(AppLanguage.ZhHant, AppLanguage.fromLocaleTags("zh-Hant-HK"))
        assertEquals(AppLanguage.Yue, AppLanguage.fromLocaleTags("zh-HK"))
        assertEquals(AppLanguage.ZhHans, AppLanguage.fromLocaleTags("zh-MO"))
    }

    @Test fun normalization_lowercase_strip_hyphens_and_spaces() {
        assertEquals("Co-may", StormNames.find("Co-May")!!.en)
        assertEquals("Champi", StormNames.find("CHAMPI")!!.en)
        assertEquals("Bang-lang", StormNames.find("BANG LANG")!!.en)
        assertEquals("Bang-lang", StormNames.find("Banglang")!!.en)
        assertEquals("Bang-lang", StormNames.find("  bang-LANG ")!!.en)
    }

    @Test fun bare_zh_hant_uses_cwa_column() {
        assertEquals(NameColumn.ZhHantTW, StormNames.columnForLocaleTag("zh-Hant"))
        assertEquals("米塔", StormNames.localize("Mitag", "米娜", StormNames.columnForLocaleTag("zh-Hant")))
    }

    @Test fun simplified_script_wins_over_hk_mo_region() {
        assertEquals(NameColumn.ZhHans, StormNames.columnForLocaleTag("zh-Hans-HK"))
        assertEquals(NameColumn.ZhHans, StormNames.columnForLocaleTag("zh-Hans-MO"))
        assertEquals(NameColumn.ZhHK, StormNames.columnForLocaleTag("zh-HK"))
        assertEquals("米娜", StormNames.localize("Mitag", "", StormNames.columnForLocaleTag("zh-Hans-HK")))
    }
}
