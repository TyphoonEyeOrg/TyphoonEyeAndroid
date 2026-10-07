package seamain.org.typhoonEye.data.preferences

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * In-app languages. [nativeLabel] stays in the language's own script
 * so users can always recognize the option in Settings.
 *
 * [System] clears the per-app locale override so UI **and launcher label**
 * follow the device language (Android resource resolution).
 */
enum class AppLanguage(val tag: String, val nativeLabel: String) {
    /** Follow device / system language (default). */
    System("", "System"),

    ZhHans("zh-CN", "中文简体"),
    ZhHant("zh-TW", "中文繁體"),
    Yue("yue", "粵語"),
    English("en", "English");

    companion object {
        fun fromStorage(value: String?): AppLanguage =
            when {
                value.isNullOrBlank() -> System
                value.equals("System", ignoreCase = true) ||
                    value.equals("auto", ignoreCase = true) ||
                    value.equals("default", ignoreCase = true) -> System
                else -> entries.find {
                    it != System && (it.name.equals(value, ignoreCase = true) || it.tag.equals(value, ignoreCase = true))
                } ?: fromLocaleTags(value) ?: System
            }

        fun fromLocaleTags(tags: String?): AppLanguage? {
            if (tags.isNullOrBlank()) return null
            val primary = tags.split(",").firstOrNull()?.trim().orEmpty()
            if (primary.isBlank()) return null
            val locale = Locale.forLanguageTag(primary.replace('_', '-'))
            return when {
                locale.language.equals("yue", ignoreCase = true) -> Yue
                locale.language.equals("en", ignoreCase = true) -> English
                locale.language.equals("zh", ignoreCase = true) &&
                    (
                        locale.country.equals("TW", ignoreCase = true) ||
                            locale.script.equals("Hant", ignoreCase = true)
                        ) -> ZhHant
                locale.language.equals("zh", ignoreCase = true) &&
                    locale.country.equals("HK", ignoreCase = true) -> Yue
                locale.language.equals("zh", ignoreCase = true) -> ZhHans
                else -> entries.find {
                    it != System && primary.startsWith(it.tag, ignoreCase = true)
                }
            }
        }

        /**
         * Effective UI language for logic that needs a concrete locale
         * (basemap region, formatting). Resolves [System] to the device language.
         */
        fun current(): AppLanguage {
            val appLocales = AppCompatDelegate.getApplicationLocales()
            if (!appLocales.isEmpty) {
                fromLocaleTags(appLocales.toLanguageTags())?.let { return it }
            }
            return fromSystemDefault()
        }

        fun fromSystemDefault(): AppLanguage {
            val sys = LocaleListCompat.getAdjustedDefault().get(0) ?: Locale.getDefault()
            return resolveSupported(sys.toLanguageTag())
        }

        /**
         * Maps any locale tag to a supported UI language. Unsupported languages
         * (de, fr, ja, …) resolve to [English], matching the English default
         * `values/` resources; they must not be treated as Simplified Chinese
         * (that also used to force the 高德 basemap for e.g. German users).
         */
        fun resolveSupported(tag: String?): AppLanguage = fromLocaleTags(tag) ?: English

        fun apply(language: AppLanguage) {
            val locales = if (language == System || language.tag.isBlank()) {
                // Empty list = follow system (launcher label + resources).
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language.tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
