package com.speedcam.app.cameras

import com.speedcam.core.geo.BoundingBox
import com.speedcam.core.geo.LatLon

/**
 * Geographical region presets for narrowing down the camera list.
 */
data class CameraRegion(
    val id: String,
    val name: String,
    val shortName: String,
    val bounds: BoundingBox? = null,
) {
    /** True when this region contains the given position or if this region represents 'All'. */
    fun contains(position: LatLon): Boolean = bounds?.contains(position) ?: true

    companion object {
        val ALL = CameraRegion("all", "Все регионы", "Все")
        val MOSCOW =
            CameraRegion("msk", "Москва и область", "Москва", BoundingBox(54.0, 35.0, 57.0, 40.5))
        val SPB =
            CameraRegion("spb", "Санкт-Петербург и ЛО", "СПб", BoundingBox(58.0, 27.5, 61.5, 36.0))
        val CENTRAL =
            CameraRegion("central", "Центральная Россия", "Центр", BoundingBox(49.5, 30.5, 59.5, 48.0))
        val SOUTH =
            CameraRegion("south", "Юг и Кавказ", "Юг", BoundingBox(41.0, 36.5, 47.5, 50.5))
        val VOLGA =
            CameraRegion("volga", "Поволжье", "Поволжье", BoundingBox(50.0, 42.0, 61.5, 60.5))
        val URALS =
            CameraRegion("urals", "Урал", "Урал", BoundingBox(53.0, 56.0, 68.0, 68.0))
        val SIBERIA =
            CameraRegion("siberia", "Сибирь", "Сибирь", BoundingBox(49.0, 75.0, 77.5, 115.0))
        val FAR_EAST =
            CameraRegion("fareast", "Дальний Восток", "ДВ", BoundingBox(42.0, 115.0, 77.0, 180.0))
        val NORTH_WEST =
            CameraRegion("northwest", "Северо-Запад", "СЗ", BoundingBox(57.0, 28.0, 70.0, 66.0))

        val DEFAULT_REGIONS: List<CameraRegion> =
            listOf(
                ALL,
                MOSCOW,
                SPB,
                CENTRAL,
                SOUTH,
                VOLGA,
                URALS,
                SIBERIA,
                FAR_EAST,
                NORTH_WEST,
            )
    }
}
