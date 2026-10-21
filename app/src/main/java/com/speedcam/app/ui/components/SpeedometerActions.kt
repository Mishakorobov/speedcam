package com.speedcam.app.ui.components

/** Actions dispatched by the speedometer dashboard controls. */
data class SpeedometerActions(
    val onEnableChange: (Boolean) -> Unit,
    val onToggleMute: () -> Unit,
    val onReportHazard: () -> Unit,
    val onToggleHud: () -> Unit,
)
