package io.github.parthibdebnath.jots.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import io.github.parthibdebnath.jots.JotsApp
import io.github.parthibdebnath.jots.MainActivity

sealed interface Screen {
    data object Home : Screen
    data class Editor(val noteId: String) : Screen
    data object Settings : Screen
    data object Tablet : Screen
}

val LocalApp = staticCompositionLocalOf<JotsApp> { error("No app") }
val LocalMainActivity = staticCompositionLocalOf<MainActivity> { error("No activity") }

/** A tiny back stack; the activity survives rotation (configChanges), so plain state is enough. */
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val top: Screen get() = stack.last()

    fun push(s: Screen) {
        stack.add(s)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Replace everything above Home, e.g. when a note is opened from outside. */
    fun open(s: Screen) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (s != Screen.Home) stack.add(s)
    }
}

@Composable
fun JotsRoot(activity: MainActivity, app: JotsApp, nav: Navigator) {
    val overlays = remember { Overlays() }
    CompositionLocalProvider(LocalApp provides app, LocalMainActivity provides activity, LocalOverlays provides overlays) {
        JotsTheme {
            Box(Modifier.fillMaxSize()) {
                if (nav.stack.size > 1 && nav.top !is Screen.Editor) BackHandler { nav.pop() }
                AnimatedContent(
                    targetState = nav.top,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    contentKey = { s -> if (s is Screen.Editor) "editor:${s.noteId}" else s.toString() },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.Home -> HomeScreen(
                            app,
                            onOpen = { nav.push(Screen.Editor(it)) },
                            onNew = {
                                val s = app.settings.value
                                val meta = app.notes.create(s.defaultPaper, s.defaultDarkPaper)
                                nav.push(Screen.Editor(meta.id))
                            },
                            onSettings = { nav.push(Screen.Settings) },
                            onTablet = { nav.push(Screen.Tablet) },
                        )
                        is Screen.Editor -> EditorScreen(
                            activity, app, screen.noteId,
                            onClose = { nav.pop() },
                            onOpenSettings = { nav.push(Screen.Settings) },
                        )
                        Screen.Settings -> SettingsScreen(app, onBack = { nav.pop() }, onTablet = { nav.push(Screen.Tablet) })
                        Screen.Tablet -> TabletScreen(app, onBack = { nav.pop() })
                    }
                }
                OverlayHost(overlays)
            }
        }
    }
}
