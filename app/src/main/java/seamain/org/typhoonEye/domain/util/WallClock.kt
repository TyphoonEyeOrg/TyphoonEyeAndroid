package seamain.org.typhoonEye.domain.util

/** Injectable wall clock so freshness / staleness logic is testable. */
fun interface WallClock {
    fun nowMs(): Long

    companion object {
        val System: WallClock = WallClock { java.lang.System.currentTimeMillis() }
    }
}
