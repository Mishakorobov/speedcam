package com.speedcam.app.cameras

import com.speedcam.core.data.HazardReportType

/** Actions dispatchable from the camera list screen. */
data class CameraListActions(
    val onUnblock: (CameraRow) -> Unit,
    val onReportCamera: ((CameraRow, HazardReportType, Int?, String?) -> Unit)? = null,
    val onViewOnMap: ((CameraRow) -> Unit)? = null,
)
