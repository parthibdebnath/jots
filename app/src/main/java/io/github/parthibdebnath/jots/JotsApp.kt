package io.github.parthibdebnath.jots

import android.app.Application
import io.github.parthibdebnath.jots.input.ActionRouter
import io.github.parthibdebnath.jots.notes.NoteRepository
import io.github.parthibdebnath.jots.settings.SettingsStore
import io.github.parthibdebnath.jots.tablet.TabletDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

class JotsApp : Application() {
    val settings by lazy { SettingsStore(this) }
    val notes by lazy { NoteRepository(this) }
    val driver by lazy { TabletDriver(this) }
    val router by lazy { ActionRouter(settings) }

    /** Outlives any screen, so a save started as a note closes still finishes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Every note read and write runs here, one at a time and in order, so reopening a note always
     * sees the save that closing it started.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val diskIo = Dispatchers.IO.limitedParallelism(1)
}
