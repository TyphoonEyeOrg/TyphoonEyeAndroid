package seamain.org.typhoonEye.ui

import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonPoint

/**
 * DEBUG BUILDS ONLY (src/debug). Extra fictional demo storm whose track crosses the
 * antimeridian, for checking #14 / TYP-55 on a phone without a real storm.
 * The release source set has an empty stub, so release/F-Droid builds never ship it.
 *
 * Open it: Home → "View demo" (empty list / no data source / error state), or
 * `adb shell am start -n seamain.org.typhoonEye/.MainActivity --ez extra_load_demo true`.
 */
object DebugDemoStorms {

    fun extra(): List<Typhoon> = listOf(
        Typhoon(
            id = "202699",
            name = "跨日界线测试 (debug)",
            englishName = "DATELINE-DEBUG",
            status = "active",
            strong = "强台风",
            positionDesc = "Debug-only sample: track crosses 180°",
            forecastText = "Debug-only sample storm for the antimeridian camera/track fix (TYP-55). Not real data.",
            startTime = "2026-10-14 08:00",
            endTime = "2026-10-18 08:00",
            points = listOf(
                TyphoonPoint("2026-10-14 08:00", 16.0, 168.0, 996, 20, "8", "热带风暴", "东北偏东", "18"),
                TyphoonPoint("2026-10-15 08:00", 17.6, 171.5, 985, 28, "10", "强热带风暴", "东北偏东", "20"),
                TyphoonPoint("2026-10-16 08:00", 19.3, 175.2, 965, 38, "13", "台风", "东北偏东", "22"),
                TyphoonPoint("2026-10-17 08:00", 20.8, 178.6, 950, 45, "14", "强台风", "东北偏东", "22"),
                TyphoonPoint(
                    time = "2026-10-18 08:00",
                    lat = 22.0,
                    lng = -178.4,
                    pressure = 945,
                    speed = 48,
                    power = "15",
                    strong = "强台风",
                    moveDirection = "东北偏东",
                    moveSpeed = "24",
                    radius7 = "320|300|260|280",
                    radius10 = "150|130|110|120",
                    radius12 = "60|55|45|50"
                )
            ),
            forecastPoints = listOf(
                TyphoonPoint("2026-10-18 20:00", 23.2, -176.0, 950, 45, "14", "强台风"),
                TyphoonPoint("2026-10-19 08:00", 24.6, -173.4, 960, 40, "13", "台风"),
                TyphoonPoint("2026-10-20 08:00", 27.0, -170.0, 980, 30, "11", "强热带风暴")
            )
        )
    )
}
