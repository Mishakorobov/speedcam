package com.speedcam.service

import com.speedcam.core.detect.AlertContext
import com.speedcam.core.detect.AlertDecision
import com.speedcam.core.detect.AlertPolicy
import com.speedcam.core.detect.AlertProfile
import com.speedcam.core.detect.AverageSpeedTracker
import com.speedcam.core.detect.CameraQuery
import com.speedcam.core.detect.DetectionEngine
import com.speedcam.core.detect.Fix
import com.speedcam.core.detect.Hazard
import com.speedcam.core.detect.SectionEvent
import com.speedcam.core.detect.SectionQuery

/** The outcome of running one fix through the whole chain. */
data class PipelineResult(
    /** The fix after arbitration: this, not the raw one, is what was detected against. */
    val fix: Fix,
    /** Cameras considered worth warning about, nearest first. */
    val hazards: List<Hazard>,
    /** What to do about each of them. Empty when nothing should be said. */
    val decisions: List<AlertDecision>,
    /** Average-speed corridor transition or progress on this fix, if any. */
    val sectionEvent: SectionEvent? = null,
) {
    val hasAlerts: Boolean
        get() =
            decisions.isNotEmpty() ||
                sectionEvent is SectionEvent.Entered ||
                sectionEvent is SectionEvent.Exited
}

/**
 * Ties arbitration, detection, average-speed tracking and policy together.
 *
 * The stages are individually pure and individually tested; what is easy to get wrong is the
 * wiring between them, and that wiring is what this class exists to make testable. It holds
 * no clock and no Android types — the caller passes in the profile and the ambient context —
 * so a whole drive can be replayed from a list of fixes.
 */
class DetectionPipeline(
    query: CameraQuery,
    sections: SectionQuery = SectionQuery { emptyList() },
) {
    private val engine = DetectionEngine(query)
    private val sectionTracker = AverageSpeedTracker(sections)

    /**
     * Runs one fix through the chain.
     *
     * @param fix the raw fix from the provider, before arbitration.
     * @param previous the last accepted fix, or null on the first one.
     */
    fun process(
        fix: Fix,
        profile: AlertProfile,
        previous: Fix?,
        context: AlertContext = AlertContext(),
    ): PipelineResult {
        // Arbitration first: a candidate that FixArbiter rejects must never reach the
        // detector, or it would be compared against a position the vehicle cannot have been at.
        val accepted = FixArbiter.better(previous, fix)

        val hazards = engine.detect(accepted, profile)
        val decisions = hazards.mapNotNull { AlertPolicy.evaluate(it, profile, context) }
        val sectionEvent = if (previous == null || accepted != previous) sectionTracker.onFix(accepted) else null

        return PipelineResult(
            fix = accepted,
            hazards = hazards,
            decisions = decisions,
            sectionEvent = sectionEvent,
        )
    }

    /** Aborts any in-progress average-speed corridor when GPS signal is lost. */
    fun onGpsLost(): SectionEvent.Aborted? = sectionTracker.onGpsLost()
}
