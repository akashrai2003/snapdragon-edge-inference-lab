package ai.snapdragon.edge

import ai.snapdragon.edge.service.VoiceWakeService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Foreground interaction and speech playback pause wake capture until they release ownership. */
object AudioGate {
    private val owners = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    val held
        get() = owners.isNotEmpty()

    fun hold(owner: String) {
        owners.add(owner)
    }

    fun release(owner: String) {
        owners.remove(owner)
    }

    fun whenQuiet(callback: (Boolean) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val start = SystemClock.elapsedRealtime()
        val check =
            object : Runnable {
                override fun run() {
                    if (!VoiceWakeService.isCapturing) callback(true)
                    else if (SystemClock.elapsedRealtime() - start > 1500) callback(false)
                    else handler.postDelayed(this, 20)
                }
            }
        handler.post(check)
    }
}
