package seamain.org.typhoonEye.domain.util

import seamain.org.typhoonEye.domain.model.Typhoon
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Client-side "is this storm still active" check.
 *
 * Upstream lists (Juhe especially) can keep a storm listed long after the last advisory,
 * so a storm whose latest observation is older than [STALE_AFTER_MS] is treated as dissipated.
 * Agencies issue at least every 6 h, so 24 h means 4 missed advisories.
 *
 * Note: Juhe's `endtime` is the latest observation time of an active storm, not an end-of-track
 * marker, so a non-blank `endTime` must NOT be read as "dissipated".
 */
object TyphoonActivity {
    const val STATUS_ACTIVE = "active"
    const val STATUS_DISSIPATED = "dissipated"
    const val STALE_AFTER_MS: Long = 24L * 60 * 60 * 1000

    /** Juhe / CMA times carry no zone and are Beijing time. */
    private val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    private val LOCAL_FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm"
    ).map { DateTimeFormatter.ofPattern(it) }

    fun parseEpochMs(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it }
        for (format in LOCAL_FORMATS) {
            runCatching {
                LocalDateTime.parse(text, format).atZone(CHINA_ZONE).toInstant().toEpochMilli()
            }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Latest observed track time; falls back to [Typhoon.endTime] for list-only snapshots. */
    fun lastObservationMs(typhoon: Typhoon): Long? =
        typhoon.points.mapNotNull { parseEpochMs(it.time) }.maxOrNull()
            ?: parseEpochMs(typhoon.endTime)

    /**
     * Returns the typhoon with [Typhoon.status] downgraded to dissipated when its latest
     * observation is older than 24 h. Unparseable times keep the upstream status.
     */
    fun resolve(typhoon: Typhoon, nowMs: Long): Typhoon {
        if (typhoon.status != STATUS_ACTIVE) return typhoon
        val last = lastObservationMs(typhoon) ?: return typhoon
        return if (nowMs - last > STALE_AFTER_MS) {
            typhoon.copy(status = STATUS_DISSIPATED)
        } else {
            typhoon
        }
    }
}
