package com.speedcam.app.map

import com.speedcam.core.data.HazardReportType
import com.speedcam.core.detect.Camera

/** Screen actions for camera blocking, reporting, and 1-tap crowd hazard reporting. */
data class VectorMapActions(
    val onBlockCamera: (Camera) -> Unit = {},
    val onUnblockCamera: (Camera) -> Unit = {},
    val onReportCamera: (Camera) -> Unit = {},
    val onReportHazardType: (HazardReportType) -> Unit = {},
)
