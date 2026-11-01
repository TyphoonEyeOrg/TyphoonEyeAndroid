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
        // Champi-style mis-pair is Chan-hom's Chinese on Champi. They stay distinct.
        val chanHom = StormNames.find("Chan-hom")!!
        assertEquals("灿鸿", chanHom.zhHans)
        assertEquals("昌鴻", chanHom.zhHantTW)
        assertEquals("燦鴻", chanHom.zhHK)
        assertFalse(e.retired)
        assertFalse(chanHom.retired)
    }

    /**
     * NMC「2000年以来被除名台风及替换名称表」
     * (https://www.nmc.cn/publish/typhoon/typhoon-name/index.html), row order.
     * zhHantTW / zhHK stay null: CWA and HKO do not publish these, and the file
     * does not transliterate. Callers fall back to the data-source Chinese name.
     */
    @Test fun retired_roster_matches_nmc_delisted_table_and_leaves_other_columns_empty() {
        val retired = StormNames.index.file.entries.filter { it.retired }
        assertEquals(nmcDelisted.map { it.first }, retired.map { it.en })
        retired.forEachIndexed { i, e ->
            assertEquals(e.en, nmcDelisted[i].second, e.zhHans)
            assertNull(e.en, e.zhHantTW)
            assertNull(e.en, e.zhHK)
            assertEquals(e.en, "nmc", e.sources.zhHans)
            assertNull(e.en, e.sources.zhHantTW)
            assertNull(e.en, e.sources.zhHK)
        }
        val activeZh = StormNames.index.file.entries.filter { !it.retired }.map { it.zhHans }.toSet()
        nmcDelisted.forEach { (_, zh) -> assertFalse(zh, zh in activeZh) }
    }

    @Test fun retired_null_columns_fall_back_to_data_source_chinese() {
        val t = juhe("龙王", "LONGWANG")
        assertTrue(StormNames.find("Longwang")!!.retired)
        assertEquals("龙王", t.localizedName(AppLanguage.ZhHans))
        assertEquals("龙王", t.localizedName(AppLanguage.ZhHant))
        assertEquals("龙王", t.localizedName(AppLanguage.Yue))
        assertEquals("Longwang", t.localizedName(AppLanguage.English))
        // English-only input has no data-source Chinese to fall back to.
        assertEquals("Longwang", StormNames.localize("Longwang", null, AppLanguage.ZhHant))
    }

    /**
     * TC58 (Jeju, 10–13 March 2026) approved retirement of these eight and asked
     * for replacements. HKO, JMA and NMC lists effective 2026-06-08 still include
     * them, so they stay in the publicity-checked 140 with full columns.
     * https://www.typhooncommittee.org/58th/docs/final/TC58_FINAL.pdf (decision r)
     * https://www.hko.gov.hk/en/informtc/sound/tcname2026e.html
     * https://www.jma.go.jp/jma/jma-eng/jma-center/rsmc-hp-pub-eg/tyname.html
     */
    @Test fun tc58_pending_replacements_stay_on_the_operational_list() {
        listOf("Wipha", "Co-may", "Matmo", "Mitag", "Ragasa", "Bualoi", "Kalmaegi", "Fung-wong").forEach { en ->
            val e = StormNames.find(en)!!
            assertFalse(en, e.retired)
            assertFalse(en, e.zhHans.isNullOrBlank())
            assertFalse(en, e.zhHantTW.isNullOrBlank())
            assertFalse(en, e.zhHK.isNullOrBlank())
            assertEquals(en, "cwa", e.sources.zhHantTW)
            assertEquals(en, "hko", e.sources.zhHK)
        }
    }

    /** NMC spelling-correction table. Old forms are not extra rows, retired or otherwise. */
    @Test fun spelling_corrections_are_not_separate_entries() {
        listOf("Kularb", "Megkhla", "Ramasoon", "Vipa", "Chebi", "Kaemi", "Koni", "Noguri", "Changmi").forEach {
            assertNull(it, StormNames.find(it))
        }
        assertFalse(StormNames.find("Kulap")!!.retired)
        assertTrue(StormNames.find("Jebi")!!.retired)
        assertEquals("飞燕", StormNames.find("Jebi")!!.zhHans)
        assertEquals("天鹅", StormNames.find("Goni")!!.zhHans)
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

    /** NMC delisted English / zh-CN pairs, same order as storm_names.json. */
    private val nmcDelisted = listOf(
        "Hanuman" to "翰文",
        "Sarika" to "莎莉嘉",
        "Kodo" to "库都",
        "Hato" to "天鸽",
        "Chataan" to "查特安",
        "Kai-tak" to "启德",
        "Imbudo" to "伊布都",
        "Tembin" to "天秤",
        "Rusa" to "鹿莎",
        "Mangkhut" to "山竹",
        "Vamei" to "画眉",
        "Rumbia" to "温比亚",
        "Maemi" to "鸣蝉",
        "Faxai" to "法茜",
        "Pongsona" to "凤仙",
        "Hagibis" to "海贝思",
        "Rananim" to "云娜",
        "Kammuri" to "北冕",
        "Sudal" to "苏特",
        "Lekima" to "利奇马",
        "Tingting" to "婷婷",
        "Phanfone" to "巴蓬",
        "Yanyan" to "欣欣",
        "Yutu" to "玉兔",
        "Longwang" to "龙王",
        "Goni" to "天鹅",
        "Matsa" to "麦莎",
        "Linfa" to "莲花",
        "Nabi" to "彩蝶",
        "Molave" to "莫拉菲",
        "Bilis" to "碧利斯",
        "Vamco" to "环高",
        "Chanchu" to "珍珠",
        "Vongfong" to "黄蜂",
        "Durian" to "榴莲",
        "Conson" to "康森",
        "Saomai" to "桑美",
        "Hinnamnor" to "轩岚诺",
        "Xangsane" to "象神",
        "Kompasu" to "圆规",
        "Ketsana" to "凯萨娜",
        "Ma-on" to "马鞍",
        "Morakot" to "莫拉克",
        "Malakas" to "马勒卡",
        "Parma" to "芭玛",
        "Megi" to "鲇鱼",
        "Fanapi" to "凡亚比",
        "Nalgae" to "尼格",
        "Washi" to "天鹰",
        "Noru" to "奥鹿",
        "Bopha" to "宝霞",
        "Rai" to "雷伊",
        "Fitow" to "菲特",
        "Doksuri" to "杜苏芮",
        "Haiyan" to "海燕",
        "Haikui" to "海葵",
        "Sonamu" to "清松",
        "Saola" to "苏拉",
        "Utor" to "尤特",
        "Ewiniar" to "艾云尼",
        "Vicente" to "韦森特",
        "Jebi" to "飞燕",
        "Rammasun" to "威马逊",
        "Kong-rey" to "康妮",
        "Koppu" to "巨爵",
        "Krathon" to "山陀儿",
        "Melor" to "茉莉",
        "Man-yi" to "万宜",
        "Mujigae" to "彩虹",
        "Toraji" to "桃芝",
        "Soudelor" to "苏迪罗",
        "Trami" to "潭美",
        "Haima" to "海马",
        "Usagi" to "天兔",
        "Meranti" to "莫兰蒂",
        "Yagi" to "摩羯",
        "Nockten" to "洛坦",
    )
}
