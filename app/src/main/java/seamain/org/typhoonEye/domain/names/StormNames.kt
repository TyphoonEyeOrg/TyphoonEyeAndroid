package seamain.org.typhoonEye.domain.names

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import seamain.org.typhoonEye.data.preferences.AppLanguage
import java.util.Locale

/*
 * Name data lives in app/src/main/resources/storm_names.json (Java resource: on the
 * classpath in the APK and in JVM unit tests). The same file is shared with iOS.
 * Sources (see the JSON "sources" block):
 *  - HKO  https://www.hko.gov.hk/tc/informtc/sound/tcname2026e.html (en, zhHK)
 *  - NMC  https://www.nmc.cn/publish/typhoon/typhoon-name/index.html (zhHans, retired)
 *  - CWA  https://www.cwa.gov.tw/V8/C/K/Encyclopedia/typhoon/typhoon.pdf (zhHantTW)
 *  - JMA  https://www.jma.go.jp/jma/jma-eng/jma-center/rsmc-hp-pub-eg/tyname.html
 */

@Serializable
data class StormNameSources(
    val zhHans: String? = null,
    val zhHantTW: String? = null,
    val zhHK: String? = null,
)

/** One Typhoon Committee name. Chinese columns are null when no official source was found. */
@Serializable
data class StormNameEntry(
    val en: String,
    val zhHans: String? = null,
    val zhHantTW: String? = null,
    val zhHK: String? = null,
    val retired: Boolean = false,
    val sources: StormNameSources = StormNameSources(),
)

@Serializable
data class StormNameFile(
    val schemaVersion: Int,
    val listVersion: String = "",
    val effectiveDate: String = "",
    val entries: List<StormNameEntry>,
)

/** Which official column to show. */
enum class NameColumn { English, ZhHans, ZhHantTW, ZhHK }

/**
 * Lookup over a parsed table (#19).
 *
 * Key: English name (Juhe `enname`), trimmed, case-insensitive, hyphens/spaces ignored.
 * QWeather has no English name, so its Chinese name is reverse-matched against zhHans;
 * a zhHans string matching more than one English entry is ambiguous and treated as not found.
 * Fallbacks (never blank): table → API name for the language → raw Chinese → English.
 */
class StormNameIndex(val file: StormNameFile) {
    private fun key(s: String): String = s.trim().lowercase().filter { it.isLetterOrDigit() }

    private val byEnglish: Map<String, StormNameEntry> = buildMap {
        // current entries win over retired ones with the same English name.
        file.entries.sortedBy { !it.retired }.forEach { put(key(it.en), it) }
    }

    private val byZhHans: Map<String, StormNameEntry> =
        file.entries.filter { !it.zhHans.isNullOrBlank() }
            .groupBy { it.zhHans!!.trim() }
            .filterValues { list -> list.map { key(it.en) }.distinct().size == 1 }
            .mapValues { (_, list) -> list.firstOrNull { !it.retired } ?: list.first() }

    fun find(englishName: String?, chineseName: String? = null): StormNameEntry? {
        val en = englishName?.let(::key).orEmpty()
        if (en.isNotEmpty()) byEnglish[en]?.let { return it }
        val zh = chineseName?.trim().orEmpty()
        return if (zh.isNotEmpty()) byZhHans[zh] else null
    }

    fun localize(englishName: String?, chineseName: String?, column: NameColumn): String {
        val en = englishName?.trim().orEmpty()
        val zh = chineseName?.trim().orEmpty()
        val entry = find(en, zh)
        val fromTable = when (column) {
            NameColumn.English -> entry?.en
            NameColumn.ZhHans -> entry?.zhHans
            NameColumn.ZhHantTW -> entry?.zhHantTW
            NameColumn.ZhHK -> entry?.zhHK
        }
        if (!fromTable.isNullOrBlank()) return fromTable
        val apiName = if (column == NameColumn.English) en else zh
        return apiName.ifBlank { zh }.ifBlank { en }
    }
}

object StormNames {
    const val RESOURCE = "storm_names.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): StormNameFile = json.decodeFromString(StormNameFile.serializer(), text)

    val index: StormNameIndex by lazy {
        val text = requireNotNull(StormNames::class.java.classLoader?.getResourceAsStream(RESOURCE)) {
            "$RESOURCE missing from classpath"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        StormNameIndex(parse(text))
    }

    fun find(englishName: String?, chineseName: String? = null) = index.find(englishName, chineseName)

    fun localize(englishName: String?, chineseName: String?, column: NameColumn): String =
        index.localize(englishName, chineseName, column)

    fun localize(englishName: String?, chineseName: String?, language: AppLanguage): String =
        localize(englishName, chineseName, columnFor(language))

    /** Column for an explicit in-app language. [AppLanguage.System] resolves via the device locale. */
    fun columnFor(language: AppLanguage): NameColumn = when (language) {
        AppLanguage.English -> NameColumn.English
        AppLanguage.ZhHans -> NameColumn.ZhHans
        AppLanguage.ZhHant -> NameColumn.ZhHantTW
        AppLanguage.Yue -> NameColumn.ZhHK
        AppLanguage.System -> columnForLocaleTag(
            (LocaleListCompat.getAdjustedDefault().get(0) ?: Locale.getDefault()).toLanguageTag()
        )
    }

    /**
     * Name column when following the system locale. Only picks the name list; UI language
     * resolution ([AppLanguage.fromLocaleTags]) is unchanged.
     * zh-HK / zh-MO / zh-Hant-HK / zh-Hant-MO / yue → HKO; zh-TW / zh-Hant-TW / zh-Hant → CWA.
     */
    fun columnForLocaleTag(tag: String?): NameColumn {
        val locale = Locale.forLanguageTag(tag.orEmpty().trim().replace('_', '-'))
        val lang = locale.language.lowercase()
        val region = locale.country.uppercase()
        val script = locale.script.lowercase()
        return when {
            lang == "yue" -> NameColumn.ZhHK
            lang == "zh" && (region == "HK" || region == "MO") -> NameColumn.ZhHK
            lang == "zh" && (region == "TW" || script == "hant") -> NameColumn.ZhHantTW
            lang == "zh" -> NameColumn.ZhHans
            else -> columnFor(AppLanguage.resolveSupported(tag).takeIf { it != AppLanguage.System } ?: AppLanguage.English)
        }
    }

    /** Column for the app right now: explicit per-app language, else the device locale. */
    fun currentColumn(): NameColumn {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        return if (!appLocales.isEmpty) columnFor(AppLanguage.current()) else columnFor(AppLanguage.System)
    }
}
