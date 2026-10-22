package com.speedcam.app.map

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.SectionRecord

/**
 * Synthesizes road vectors from confirmed database camera sections.
 *
 * Guarantees that arterial highways monitored by SPECS average-speed systems are rendered
 * on the vector map even in complete offline mode with zero Overpass road tiles cached.
 */
object SyntheticRoadCorridors {
    fun createSectionCorridors(
        sections: List<SectionRecord>,
        cameras: List<Camera> = emptyList(),
    ): List<VectorRoad> {
        if (sections.isEmpty()) return emptyList()

        return sections.mapIndexed { index, sec ->
            VectorRoad(
                id = -20000L - index,
                name = "Участок КСС (${sec.limitKmh} км/ч)",
                kind = RoadClass.MOTORWAY,
                points = listOf(sec.from, sec.to),
            )
        }
    }
}
