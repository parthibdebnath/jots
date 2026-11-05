package io.github.parthibdebnath.jots

import android.app.Application
import io.github.parthibdebnath.jots.input.ActionRouter
import io.github.parthibdebnath.jots.notes.NoteRepository
import io.github.parthibdebnath.jots.settings.SettingsStore
import io.github.parthibdebnath.jots.tablet.PenFrame
import io.github.parthibdebnath.jots.tablet.TabletDriver
import io.github.parthibdebnath.jots.tablet.TabletListener
import io.github.parthibdebnath.jots.tablet.TabletParams
import io.github.parthibdebnath.jots.universal.GlobalInput
import io.github.parthibdebnath.jots.universal.UniversalController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JotsApp : Application() {
    val settings by lazy { SettingsStore(this) }
    val notes by lazy { NoteRepository(this) }
    val driver by lazy { TabletDriver(this).also { it.listener = tabletInput } }
    val router by lazy { ActionRouter(settings) }
    val universal by lazy { UniversalController(this) }

    /** Outlives any screen, so a save started as a note closes still finishes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Every note read and write runs here, one at a time and in order, so reopening a note always
     * sees the save that closing it started.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val diskIo = Dispatchers.IO.limitedParallelism(1)

    /** Set by the activity while Jots is on screen; tablet input then goes to it. */
    var inAppInput: TabletListener? = null

    /** Tablet input goes to Jots when it's in front, otherwise to the "other apps" mode. */
    val tabletInput: TabletListener = object : TabletListener {
        private fun target(): TabletListener? = inAppInput ?: universal.takeIf { it.isActive }
        override fun onPen(frame: PenFrame, params: TabletParams) { target()?.onPen(frame, params) }
        override fun onPenButton(index: Int, pressed: Boolean) { target()?.onPenButton(index, pressed) }
        override fun onPadKey(index: Int, pressed: Boolean) { target()?.onPadKey(index, pressed) }
        override fun onDial(delta: Int) { target()?.onDial(delta) }
    }

    override fun onCreate() {
        super.onCreate()
        GlobalInput.init(this)
        appScope.launch {
            settings.state.collect { s ->
                driver.setEnabled(s.driverEnabled)
                universal.sync()
                universal.onSettingsChanged()
            }
        }
        appScope.launch {
            // Shizuku starting or stopping can make the mode possible or impossible.
            GlobalInput.state.collect { universal.sync() }
        }
    }
}
