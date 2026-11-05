package io.github.parthibdebnath.jots.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.parthibdebnath.jots.JotsApp
import io.github.parthibdebnath.jots.input.Bindings
import io.github.parthibdebnath.jots.universal.GlobalAction
import io.github.parthibdebnath.jots.universal.GlobalDialMode
import io.github.parthibdebnath.jots.universal.GlobalInput
import io.github.parthibdebnath.jots.universal.ShizukuState
import io.github.parthibdebnath.jots.universal.UniversalSettings
import io.github.parthibdebnath.jots.universal.UniversalStatus

@Composable
fun UniversalScreen(app: JotsApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val overlays = LocalOverlays.current
    val s by app.settings.state.collectAsState()
    val u = s.universal
    val shizuku by GlobalInput.state.collectAsState()
    val status by app.universal.status.collectAsState()

    // Permissions are granted in other screens; re-check whenever we come back.
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                GlobalInput.refresh()
                resumes++
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val overlayOk = remember(resumes) { Settings.canDrawOverlays(context) }
    val notifOk = remember(resumes) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }

    fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun url(u: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    fun update(f: (UniversalSettings) -> UniversalSettings) = app.settings.update { it.copy(universal = f(it.universal)) }

    ScreenScaffold("Tablet in other apps", onBack) {
        Text(
            "Make the pen, its buttons, the tablet keys and the dial work in every app, not just Jots. " +
                "Android only allows this with help from Shizuku, a free app that lends Jots the permission " +
                "to send input, the same way a computer can over USB debugging.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )

        Section("Setup") {
            Step(
                n = 1, done = shizuku == ShizukuState.READY,
                title = "Shizuku",
                body = when (shizuku) {
                    ShizukuState.NOT_INSTALLED -> "Install Shizuku from the Play Store or its website."
                    ShizukuState.NOT_RUNNING -> "Open Shizuku and start it with Wireless debugging (no computer needed). It has to be started again after the phone restarts."
                    ShizukuState.TOO_OLD -> "Update Shizuku to the latest version."
                    ShizukuState.NO_PERMISSION -> "Shizuku is running. Allow Jots to use it."
                    ShizukuState.READY -> "Running, and Jots is allowed to use it."
                },
            ) {
                when (shizuku) {
                    ShizukuState.NOT_INSTALLED, ShizukuState.TOO_OLD -> {
                        FilledTonalButton(onClick = {
                            if (open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${GlobalInput.SHIZUKU_PACKAGE}"))).isFailure) {
                                url("https://shizuku.rikka.app/download/")
                            }
                        }) { Text(if (shizuku == ShizukuState.TOO_OLD) "Update Shizuku" else "Get Shizuku") }
                    }
                    ShizukuState.NOT_RUNNING -> Row {
                        FilledTonalButton(onClick = {
                            context.packageManager.getLaunchIntentForPackage(GlobalInput.SHIZUKU_PACKAGE)?.let { open(it) }
                        }) { Text("Open Shizuku") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { url("https://shizuku.rikka.app/guide/setup/") }) { Text("How to start it") }
                    }
                    ShizukuState.NO_PERMISSION -> FilledTonalButton(onClick = { GlobalInput.requestPermission() }) { Text("Allow") }
                    ShizukuState.READY -> Unit
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Step(
                n = 2, done = overlayOk,
                title = "Pen pointer",
                body = "Lets Jots draw a small pointer where the pen hovers, since Android hides its own. It never blocks touches. Optional, but you'll want it.",
            ) {
                if (!overlayOk) FilledTonalButton(onClick = {
                    open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                }) { Text("Allow") }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Step(
                    n = 3, done = notifOk,
                    title = "Notification",
                    body = "Shows while the mode is on, with a Stop button. Optional.",
                ) {
                    if (!notifOk) FilledTonalButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                }
            }
        }

        Section("Mode") {
            SwitchRow(
                "Use the tablet in other apps",
                when {
                    !s.driverEnabled -> "Turn on the tablet driver in Settings first"
                    shizuku != ShizukuState.READY -> "Finish step 1 first"
                    else -> when (val st = status) {
                        UniversalStatus.On -> "On. Inside Jots the tablet works as usual."
                        UniversalStatus.Off -> "Off: outside Jots the tablet is Android's basic pen"
                        is UniversalStatus.Failed -> st.reason
                    }
                },
                checked = u.enabled,
            ) { on ->
                if (!on || (shizuku == ShizukuState.READY && s.driverEnabled)) update { it.copy(enabled = on) }
                else if (shizuku != ShizukuState.READY) GlobalInput.refresh()
            }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow("Show pen pointer", if (overlayOk) null else "Needs step 2", u.showCursor) { v -> update { it.copy(showCursor = v) } }
        }

        Section("Buttons in other apps") {
            val penMask by app.router.penButtons.collectAsState()
            val padMask by app.router.padMask.collectAsState()
            NavRow("Pen: lower button", u.penAction(0).label, highlight = penMask and 1 != 0) { overlays.show { GlobalPicker(app, "Pen: lower button", pen = 0) } }
            NavRow("Pen: upper button", u.penAction(1).label, highlight = penMask and 2 != 0) { overlays.show { GlobalPicker(app, "Pen: upper button", pen = 1) } }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            for (i in 0 until Bindings.PAD_KEYS) {
                NavRow("Tablet key ${i + 1}", u.padAction(i).label, highlight = padMask and (1 shl i) != 0) {
                    overlays.show { GlobalPicker(app, "Tablet key ${i + 1}", pad = i) }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = { update { it.copy(pen = UniversalSettings.DEFAULT_PEN, pad = UniversalSettings.DEFAULT_PAD) } }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Reset to defaults")
            }
            Text(
                "Keyboard shortcuts like Ctrl+Z work in apps that support a keyboard; most drawing and note apps do.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
            )
        }

        Section("Dial in other apps") {
            Text("The dial does: ${u.dialMode.label}. A key set to “${GlobalAction.CYCLE_DIAL.label}” steps through the ticked modes.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            for (m in GlobalDialMode.entries) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        update { st ->
                            val next = if (m in st.dialModes) st.dialModes - m else GlobalDialMode.entries.filter { it in st.dialModes || it == m }
                            if (next.isEmpty()) st else st.copy(dialModes = next, dialMode = if (st.dialMode in next) st.dialMode else next.first())
                        }
                    }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(m in u.dialModes, onCheckedChange = null, modifier = Modifier.padding(10.dp))
                    Text(m.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (u.dialMode == m) Text("Now", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 14.dp))
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow("Reverse direction", null, u.dialInvert) { v -> update { it.copy(dialInvert = v) } }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                Text("Scroll speed: ${"%.1f".format(u.scrollSpeed)}×", style = MaterialTheme.typography.bodyMedium)
                Slider(u.scrollSpeed, { v -> update { it.copy(scrollSpeed = v) } }, valueRange = 0.5f..4f)
            }
        }
    }
}

@Composable
private fun Step(n: Int, done: Boolean, title: String, body: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Surface(
            shape = CircleShape,
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(28.dp),
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                if (done) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                else Text("$n", style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

@Composable
private fun BoxScope.GlobalPicker(app: JotsApp, title: String, pen: Int = -1, pad: Int = -1) {
    val overlays = LocalOverlays.current
    val u = app.settings.value.universal
    val current = if (pen >= 0) u.penAction(pen) else u.padAction(pad)
    InlineDialog(title, onDismiss = { overlays.dismiss() }) {
        for (group in GlobalAction.Group.entries) {
            val actions = GlobalAction.entries.filter { it.group == group && (pen >= 0 || !it.penOnly) }
            if (actions.isEmpty()) continue
            Text(group.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp))
            ChoiceList(actions, current, { it.label }) { a ->
                app.settings.update { s ->
                    val un = s.universal
                    s.copy(universal = if (pen >= 0) un.copy(pen = un.pen + (pen to a)) else un.copy(pad = un.pad + (pad to a)))
                }
                overlays.dismiss()
            }
        }
    }
}
