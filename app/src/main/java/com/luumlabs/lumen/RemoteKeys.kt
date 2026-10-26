package com.luumlabs.lumen

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Remote keys that some firmwares leave to the home screen instead of acting on them themselves:
 * their own launcher answers them, so with Lumen in its place they did nothing. Android acts on
 * the power key before any app sees it, so a power key that reaches an app at all is one the
 * system passed on; answering it here never doubles what the TV does by itself.
 */
object RemoteKeys {
    private val POWER = setOf(
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_SOFT_SLEEP,
    )

    /** The remote's dedicated HDMI keys, by port. */
    val HDMI = mapOf(
        KeyEvent.KEYCODE_TV_INPUT_HDMI_1 to 1, KeyEvent.KEYCODE_TV_INPUT_HDMI_2 to 2,
        KeyEvent.KEYCODE_TV_INPUT_HDMI_3 to 3, KeyEvent.KEYCODE_TV_INPUT_HDMI_4 to 4,
    )

    /** Keys that open live TV on the stock launcher. */
    val LIVE_TV = setOf(
        KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_LAST_CHANNEL,
    )

    fun isPower(code: Int) = code in POWER

    @Volatile private var sentAt = 0L
    @Volatile private var sent: String? = null

    /**
     * Puts the TV to sleep through local debugging, as its power key would; with "Full power off"
     * set, the keeper service then powers it down as the screen goes off. [onFail] runs on the main
     * thread, so the caller can offer another way: with false when local debugging can't be
     * reached, with true when it was but the key we sent came straight back (the firmware passes
     * that one on too).
     */
    fun standby(ctx: Context, code: Int, onFail: (reached: Boolean) -> Unit) {
        val now = SystemClock.uptimeMillis()
        val bounced = now - sentAt < BOUNCE_MS
        // KEYCODE_SLEEP is Android's own "go to sleep". If that is the key that reached us, or the
        // one we sent came back, the firmware passes it on, and the power key is tried once.
        val key = when {
            !bounced -> if (code == KeyEvent.KEYCODE_SLEEP || code == KeyEvent.KEYCODE_SOFT_SLEEP) "KEYCODE_POWER" else "KEYCODE_SLEEP"
            sent == "KEYCODE_SLEEP" -> "KEYCODE_POWER"
            else -> {
                sentAt = 0L
                sent = null
                onFail(true)
                return
            }
        }
        sentAt = now
        sent = key
        val app = ctx.applicationContext
        Thread {
            val ok = LocalAdb.shell(app, "input keyevent $key") != null
            if (!ok) Handler(Looper.getMainLooper()).post { sentAt = 0L; onFail(false) }
        }.start()
    }

    private const val BOUNCE_MS = 2500L
}
