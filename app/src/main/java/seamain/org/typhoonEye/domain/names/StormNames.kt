package seamain.org.typhoonEye.domain.names

import seamain.org.typhoonEye.data.preferences.AppLanguage

/** One Typhoon Committee name. Chinese columns are null when no official source was found. */
data class StormNameEntry(
    val english: String,
    val zhHans: String?,
    val zhHantTw: String?,
    val zhHk: String?,
)

/**
 * Storm names in the app language (#19).
 *
 * Lookup key is the English name (Juhe `enname`), normalised: trimmed, case-insensitive,
 * hyphens/spaces ignored ("CO-MAY" == "Co-may", "SonTinh" == "Son-Tinh").
 * QWeather has no English name, so its Chinese name is reverse-matched against the
 * official zh-Hans column (NMC list) to recover the English key.
 *
 * Column per language: ZhHans → NMC/CMA, ZhHant → Taiwan CWA, Yue → Hong Kong Observatory,
 * English → Typhoon Committee spelling.
 *
 * Fallbacks (never blank): table → API name for that language (enname for English,
 * raw Chinese for Chinese UIs) → raw Chinese name → English name.
 */
object StormNames {
    private fun key(s: String): String =
        s.trim().lowercase().filter { it.isLetterOrDigit() }

    private val byEnglish: Map<String, StormNameEntry> = buildMap {
        // current wins over retired for names that were reused.
        StormNameTable.retired.forEach { put(key(it.english), it) }
        StormNameTable.current.forEach { put(key(it.english), it) }
    }

    private val byZhHans: Map<String, StormNameEntry> = buildMap {
        StormNameTable.retired.forEach { e -> e.zhHans?.let { put(it.trim(), e) } }
        StormNameTable.current.forEach { e -> e.zhHans?.let { put(it.trim(), e) } }
    }

    fun find(englishName: String?, chineseName: String? = null): StormNameEntry? {
        val en = englishName?.let(::key).orEmpty()
        if (en.isNotEmpty()) byEnglish[en]?.let { return it }
        val zh = chineseName?.trim().orEmpty()
        if (zh.isNotEmpty()) byZhHans[zh]?.let { return it }
        return null
    }

    /**
     * @param englishName Juhe `enname` (blank for QWeather)
     * @param chineseName provider Chinese name (Juhe `name` / QWeather `name`)
     * @return localized name, or "" only when both inputs are blank.
     */
    fun localize(englishName: String?, chineseName: String?, language: AppLanguage): String {
        val en = englishName?.trim().orEmpty()
        val zh = chineseName?.trim().orEmpty()
        val entry = find(en, zh)
        val fromTable = when (language) {
            AppLanguage.English -> entry?.english
            AppLanguage.ZhHans -> entry?.zhHans
            AppLanguage.ZhHant -> entry?.zhHantTw
            AppLanguage.Yue -> entry?.zhHk
            AppLanguage.System -> return localize(en, zh, AppLanguage.current())
        }
        if (!fromTable.isNullOrBlank()) return fromTable
        val apiName = if (language == AppLanguage.English) en else zh
        return apiName.ifBlank { zh }.ifBlank { en }
    }
}
