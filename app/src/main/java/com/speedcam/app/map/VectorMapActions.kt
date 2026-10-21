package com.speedcam.app.map

import com.speedcam.core.detect.Camera

/** Screen actions for camera blocking and reporting. */
data class VectorMapActions(
    val onBlockCamera: (Camera) -> Unit = {},
    val onReportCamera: (Camera) -> Unit = {},
)
