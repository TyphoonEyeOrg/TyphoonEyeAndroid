package seamain.org.typhoonEye.ui

/**
 * Which source chips the list summary shows.
 * Cached data, including a relay body marked `X-Relay-Stale: 1`, uses [Cached]
 * and does not also claim [Live].
 */
internal enum class SummarySourceChip { Cached, Live, Demo }

internal fun summarySourceChips(fromCache: Boolean, dataMode: DataMode): Set<SummarySourceChip> =
    buildSet {
        if (fromCache) add(SummarySourceChip.Cached)
        if (!fromCache || dataMode != DataMode.Live) {
            add(if (dataMode == DataMode.Live) SummarySourceChip.Live else SummarySourceChip.Demo)
        }
    }
