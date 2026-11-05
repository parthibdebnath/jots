package io.github.parthibdebnath.jots

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.parthibdebnath.jots.tablet.HuionProtocol
import io.github.parthibdebnath.jots.tablet.PenFrame
import io.github.parthibdebnath.jots.tablet.PenPhase

/**
 * Debug builds only. Feeds fake tablet input into the same routing the USB driver uses, e.g.
 * `adb shell am broadcast -n io.github.parthibdebnath.jots/.DebugTablet --es cmd "pen DOWN 0.5 0.5 0.7"`
 * Pen coordinates are 0..1 across the tablet; then optional pressure, barrel1, barrel2 (0/1).
 * Other commands: `key <index> <1|0>`, `penbutton <index> <1|0>`, `dial <delta>`.
 */
class DebugTablet : BroadcastReceiver() {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as JotsApp
        val p = HuionProtocol.Q620M_DEFAULTS
        // Space commands out like a real tablet (~200 reports a second); "wait <ms>" adds more.
        var at = 0L
        for (cmd in intent.getStringExtra("cmd").orEmpty().split(";")) {
            val a = cmd.trim().split(Regex("\\s+"))
            if (a.getOrNull(0) == "wait") { at += a[1].toLong(); continue }
            at += 5
            main.postDelayed({ run(app, p, a) }, at)
        }
    }

    private fun run(app: JotsApp, p: io.github.parthibdebnath.jots.tablet.TabletParams, a: List<String>) {
        run {
            when (a.getOrNull(0)) {
                "pen" -> {
                    val phase = PenPhase.valueOf(a[1])
                    val f = PenFrame(
                        phase,
                        x = (a[2].toFloat() * p.maxX).toInt(),
                        y = (a[3].toFloat() * p.maxY).toInt(),
                        pressure = a.getOrNull(4)?.toFloat() ?: 0f,
                        barrel1 = a.getOrNull(5) == "1",
                        barrel2 = a.getOrNull(6) == "1",
                        tiltX = 0, tiltY = 0,
                    )
                    app.tabletInput.onPen(f, p)
                }
                "key" -> app.tabletInput.onPadKey(a[1].toInt(), a[2] == "1")
                "penbutton" -> app.tabletInput.onPenButton(a[1].toInt(), a[2] == "1")
                "dial" -> app.tabletInput.onDial(a[1].toInt())
            }
        }
    }
}
