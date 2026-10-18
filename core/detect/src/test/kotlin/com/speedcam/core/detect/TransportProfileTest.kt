package com.speedcam.core.detect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests the transport selection rule from Plan.md §6.2: Common / Car / Truck / Moto, with
 * automatic switching on Bluetooth headset connection.
 *
 * What each transport actually changes is applied natively in the original and is not
 * recoverable, so there is nothing to test beyond the selection itself.
 */
class TransportProfileTest {
    @Test
    fun `a headset switches the common profile to the car profile`() {
        assertEquals(TransportProfile.CAR, TransportProfile.select(TransportProfile.COMMON, true))
    }

    @Test
    fun `without a headset the common profile stays common`() {
        assertEquals(TransportProfile.COMMON, TransportProfile.select(TransportProfile.COMMON, false))
    }

    @Test
    fun `an explicit choice is never overridden by the headset`() {
        // Only COMMON is automatic. A driver who picked Truck meant it, and silently switching
        // them to Car whenever a headset connects would be worse than the convenience.
        assertEquals(TransportProfile.TRUCK, TransportProfile.select(TransportProfile.TRUCK, true))
        assertEquals(TransportProfile.MOTO, TransportProfile.select(TransportProfile.MOTO, false))
        assertEquals(TransportProfile.CAR, TransportProfile.select(TransportProfile.CAR, true))
    }

    @Test
    fun `the four names are the four from the plan`() {
        assertEquals(
            listOf("COMMON", "CAR", "TRUCK", "MOTO"),
            TransportProfile.entries.map { it.name },
        )
    }

    @Test
    fun `a profile defaults to common`() {
        assertEquals(TransportProfile.COMMON, AlertProfile().transport)
    }
}
