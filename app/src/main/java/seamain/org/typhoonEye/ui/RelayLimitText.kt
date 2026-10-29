package seamain.org.typhoonEye.ui

import androidx.annotation.StringRes
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.model.RelayLimitKind

/** Localized copy for a relay 429/503. [showingCache] mentions that saved data is on screen. */
@StringRes
internal fun RelayLimitKind.userMessageRes(showingCache: Boolean): Int = when (this) {
    RelayLimitKind.Busy ->
        if (showingCache) R.string.relay_busy_cached else R.string.relay_busy
    RelayLimitKind.DailyQuotaExhausted ->
        if (showingCache) R.string.relay_daily_quota_cached else R.string.relay_daily_quota
}
