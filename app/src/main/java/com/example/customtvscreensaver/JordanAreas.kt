package com.example.customtvscreensaver

/**
 * Coordinates for the areas the official timetable covers.
 *
 * The feed is keyed by area *name* and carries no coordinates, but they are still needed for the
 * adhan fallback used whenever a month has not been published yet (only a rolling window is
 * available) or an area has no file at all. Falling back on Amman for every area would put Aqaba
 * roughly 300 km off, so the known areas are mapped here; anything the feed adds later falls
 * back to [City.DEFAULT] until it is added.
 *
 * Display names always come from the feed itself - this table is never shown to the user.
 */
object JordanAreas {
    private val COORDINATES: Map<String, Pair<Double, Double>> = mapOf(
        "عمان، البلقاء، الزرقاء، مادبا" to (31.9539 to 35.9106),
        "اربد" to (32.5556 to 35.8500),
        "الكرك" to (31.1854 to 35.7048),
        "الطفيلة" to (30.8375 to 35.6047),
        "معان" to (30.1962 to 35.7341),
        "العقبة" to (29.5321 to 35.0063),
        "الأغوار الشمالية" to (32.5486 to 35.6203),
        "جرش وعجلون" to (32.2808 to 35.8994),
        "المفرق" to (32.3403 to 36.2080),
        "ذيبان" to (31.5008 to 35.7803),
        "الأزرق" to (31.8833 to 36.8167),
        "الأغوار الوسطى" to (31.9000 to 35.6100),
        "الشوبك والبتراء" to (30.3286 to 35.4419),
        "الظليل والهاشمية" to (32.1667 to 36.1000),
        "الرويشد" to (32.5000 to 38.2000),
        "القدس" to (31.7683 to 35.2137)
    )

    /** Coordinates to calculate with when the official timetable cannot answer. */
    fun fallbackCoordinates(area: String?): Pair<Double, Double> =
        COORDINATES[area] ?: (City.DEFAULT.latitude to City.DEFAULT.longitude)
}
