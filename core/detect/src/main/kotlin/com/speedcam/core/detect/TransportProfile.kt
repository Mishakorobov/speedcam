package com.speedcam.core.detect

/**
 * Which vehicle a profile is meant for.
 *
 * Plan.md §6.2 lists four: Common, Car, Truck and Moto, with automatic switching to the car
 * profile when a Bluetooth headset connects. The original's four profiles differ in the
 * capture distance and alert defaults that follow from the vehicle, and the four names come
 * from its settings screen; what each one actually changes is applied natively and is not
 * recoverable from the decompile.
 *
 * Modelled as an enum rather than the bare `Int` it started as so that a profile cannot carry
 * a transport id that this build has no name for.
 */
enum class TransportProfile {
    /** Applies regardless of vehicle. The default. */
    COMMON,

    CAR,
    TRUCK,
    MOTO,
    ;

    companion object {
        /**
         * Picks the active profile for the current conditions.
         *
         * This is the whole of Plan.md §6.2's "automatic switching on Bluetooth headset
         * connection": a connected headset implies a car, and a car implies the car profile.
         * Anything else keeps whatever the user chose.
         *
         * Deliberately ignorant of *why* a headset is connected. The Bluetooth headset state
         * arrives from the service layer, which is the only part entitled to care about audio
         * routing and audio focus.
         *
         * @param configured the transport the user selected.
         * @param headsetConnected whether a Bluetooth hands-free device is connected.
         */
        fun select(
            configured: TransportProfile,
            headsetConnected: Boolean,
        ): TransportProfile =
            when {
                configured != COMMON -> configured
                headsetConnected -> CAR
                else -> COMMON
            }
    }
}
