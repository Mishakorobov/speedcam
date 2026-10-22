package com.speedcam.app.map

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.LatLon

/** Data container for objects rendered on the vector map canvas. */
data class VectorMapData(
    val cameras: List<Camera>,
    val sections: List<SectionRecord>,
    val roads: List<VectorRoad>,
    val blocked: List<LatLon> = emptyList(),
)
