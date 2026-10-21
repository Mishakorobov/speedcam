package com.speedcam.core.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages Bluetooth SCO (Hands-Free Profile) audio connections.
 *
 * When an alert is played over [OutputMode.BLUETOOTH_MULTIMEDIA] or [OutputMode.BLUETOOTH_HFP],
 * the vehicle's head unit must treat it as an incoming phone call, interrupting standard FM
 * radio, CD, or USB media.
 *
 * Following the reference architecture, SCO is torn down with a grace delay (1.5-2.0s)
 * after the audio queue finishes, avoiding choppy connection cycling when alerts repeat.
 */
class BluetoothScoController(
    context: Context,
    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java),
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val applicationContext = context.applicationContext

    @Volatile
    var isScoActive: Boolean = false
        private set

    @Volatile
    var isScoConnected: Boolean = false
        private set

    private var receiverRegistered = false
    private val teardownRunnable = Runnable { stopScoInternal() }

    private val scoReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                val state =
                    intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR,
                    )
                isScoConnected = (state == AudioManager.SCO_AUDIO_STATE_CONNECTED)
                Log.d(TAG, "Bluetooth SCO state changed: $state (connected=$isScoConnected)")
            }
        }

    /**
     * Requests Bluetooth SCO routing.
     *
     * Cancels any pending teardown runnable so repeated warnings maintain an open channel.
     */
    fun acquireSco() {
        handler.removeCallbacks(teardownRunnable)
        val am = audioManager ?: return

        if (!isScoActive) {
            registerReceiverIfNeeded()
            runCatching {
                if (!am.isBluetoothScoOn) {
                    am.startBluetoothSco()
                }
                am.isBluetoothScoOn = true
                am.isSpeakerphoneOn = false
                isScoActive = true
                Log.i(TAG, "Acquired Bluetooth SCO channel for alert audio")
            }.onFailure { Log.w(TAG, "Failed to acquire Bluetooth SCO: ${it.message}") }
        }
    }

    /**
     * Schedules release of the SCO channel after [delayMs].
     *
     * If a new alert arrives during the grace window, [acquireSco] cancels this teardown.
     */
    fun releaseSco(delayMs: Long = DEFAULT_SCO_TEARDOWN_DELAY_MS) {
        handler.removeCallbacks(teardownRunnable)
        handler.postDelayed(teardownRunnable, delayMs)
    }

    /** Immediate synchronous teardown on service destruction or engine release. */
    fun forceStop() {
        handler.removeCallbacks(teardownRunnable)
        stopScoInternal()
        unregisterReceiverIfNeeded()
    }

    private fun stopScoInternal() {
        val am = audioManager ?: return
        if (isScoActive) {
            runCatching {
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                }
                am.isBluetoothScoOn = false
                isScoActive = false
                isScoConnected = false
                Log.i(TAG, "Released Bluetooth SCO channel")
            }.onFailure { Log.w(TAG, "Failed to stop Bluetooth SCO: ${it.message}") }
        }
    }

    private fun registerReceiverIfNeeded() {
        if (!receiverRegistered) {
            runCatching {
                val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    applicationContext.registerReceiver(
                        scoReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED,
                    )
                } else {
                    applicationContext.registerReceiver(scoReceiver, filter)
                }
                receiverRegistered = true
            }
        }
    }

    private fun unregisterReceiverIfNeeded() {
        if (receiverRegistered) {
            runCatching { applicationContext.unregisterReceiver(scoReceiver) }
            receiverRegistered = false
        }
    }

    companion object {
        private const val TAG = "BluetoothScoController"
        const val DEFAULT_SCO_TEARDOWN_DELAY_MS = AudioFocusTimings.STOP_SCO_DELAY_MS
    }
}
