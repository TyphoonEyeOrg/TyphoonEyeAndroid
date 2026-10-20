package seamain.org.typhoonEye.data.preferences

/**
 * TYP-58: location-based alerts are opt-in on the github flavor, which sends
 * coordinates to QWeather. Pure decision logic, kept free of Android types so it
 * can be unit-tested on the JVM.
 */
object LocationOptIn {

    /**
     * Effective toggle value. An explicit user choice always wins; otherwise the
     * per-flavor default (`BuildConfig.LOCATION_ALERTS_DEFAULT`) applies.
     */
    fun resolveEnabled(stored: Boolean?, flavorDefault: Boolean): Boolean =
        stored ?: flavorDefault

    /**
     * Whether to show the one-time "distance alerts now need to be turned on" notice.
     *
     * Only for upgraders on a build whose default is off: they never touched the
     * toggle (key absent, so the old default made it effectively on) and were
     * actually using it (permission granted or a cached fix from an earlier version).
     * Fresh installs have neither, and users who stored an explicit value see nothing.
     */
    fun shouldShowUpgradeNotice(
        flavorDefault: Boolean,
        locationAlertsStoredValue: Boolean?,
        noticeAlreadyShown: Boolean,
        hasLocationPermission: Boolean,
        hasCachedLocation: Boolean
    ): Boolean {
        if (flavorDefault) return false
        if (locationAlertsStoredValue != null) return false
        if (noticeAlreadyShown) return false
        return hasLocationPermission || hasCachedLocation
    }
}
