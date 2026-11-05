package io.github.parthibdebnath.jots.ui

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.parthibdebnath.jots.BuildConfig
import io.github.parthibdebnath.jots.JotsApp
import io.github.parthibdebnath.jots.input.Action
import io.github.parthibdebnath.jots.input.Bindings
import io.github.parthibdebnath.jots.input.ButtonId
import io.github.parthibdebnath.jots.input.DialMode
import io.github.parthibdebnath.jots.notes.Paper
import io.github.parthibdebnath.jots.tablet.HuionProtocol
import io.github.parthibdebnath.jots.tablet.PenPhase
import io.github.parthibdebnath.jots.tablet.TabletMapper
import io.github.parthibdebnath.jots.tablet.TabletStatus

// region Shared pieces

@Composable
fun ScreenScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            content = content,
        )
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 8.dp),
    )
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
fun NavRow(title: String, value: String? = null, highlight: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (highlight) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)

// endregion

@Composable
fun SettingsScreen(app: JotsApp, onBack: () -> Unit, onTablet: () -> Unit) {
    val s by app.settings.state.collectAsState()
    val status by app.driver.status.collectAsState()
    val overlays = LocalOverlays.current
    ScreenScaffold("Settings", onBack) {
        Section("Drawing tablet") {
            SwitchRow(
                "Tablet driver",
                "Unlocks the second pen button, the tablet keys and the dial on Huion tablets",
                s.driverEnabled,
            ) { on -> app.settings.update { it.copy(driverEnabled = on) } }
            Divider()
            NavRow("Tablet, buttons and dial", statusLabel(status), onClick = onTablet)
        }
        Section("Writing") {
            SwitchRow("Draw with finger", "Off: one finger scrolls, two fingers zoom", s.fingerDrawing) { v -> app.settings.update { it.copy(fingerDrawing = v) } }
            Divider()
            SwitchRow("Scroll bar on the left", "For left-handed writing", s.scrollbarOnLeft) { v -> app.settings.update { it.copy(scrollbarOnLeft = v) } }
        }
        Section("New notes") {
            NavRow("Paper", s.defaultPaper.label) {
                overlays.show {
                    InlineDialog("Paper for new notes", onDismiss = { overlays.dismiss() }) {
                        ChoiceList(Paper.entries, s.defaultPaper, { it.label }) { p -> app.settings.update { it.copy(defaultPaper = p) }; overlays.dismiss() }
                    }
                }
            }
            Divider()
            SwitchRow("Dark paper", null, s.defaultDarkPaper) { v -> app.settings.update { it.copy(defaultDarkPaper = v) } }
        }
        Section("About") {
            Text(
                "Jots ${BuildConfig.VERSION_NAME}\nAn offline handwriting notebook with full support for Huion drawing tablets.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(18.dp),
            )
        }
    }
}

private fun statusLabel(s: TabletStatus) = when (s) {
    TabletStatus.Disabled -> "Off"
    TabletStatus.NoTablet -> "Not connected"
    is TabletStatus.NeedsPermission -> "Waiting for permission"
    is TabletStatus.PermissionDenied -> "Permission denied"
    is TabletStatus.Released -> "Paused"
    is TabletStatus.Active -> "Connected"
    is TabletStatus.Failed -> "Problem"
}

@Composable
fun TabletScreen(app: JotsApp, onBack: () -> Unit) {
    val s by app.settings.state.collectAsState()
    val status by app.driver.status.collectAsState()
    val overlays = LocalOverlays.current
    var learning by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        app.driver.diagnostics = true
        onDispose {
            app.driver.diagnostics = false
            app.router.learner = null
        }
    }

    fun pick(id: ButtonId) {
        overlays.show { ActionPicker(app, id) }
    }

    ScreenScaffold("Tablet", onBack) {
        StatusCard(app, status)

        if (status is TabletStatus.Active) {
            Section("Try it") { LiveTest(app) }
        }

        Section("Buttons") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (learning) "Press the pen button, tablet key or keyboard key you want to set…" else "Rows light up while their button is held.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (learning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    learning = !learning
                    app.router.learner = if (learning) { id ->
                        learning = false
                        app.router.learner = null
                        pick(id)
                    } else null
                }) { Text(if (learning) "Cancel" else "Press to set") }
            }
            Divider()
            val penMask by app.router.penButtons.collectAsState()
            val padMask by app.router.padMask.collectAsState()
            NavRow("Pen: lower button", s.bindings.actionFor(ButtonId.Pen(0)).label, highlight = penMask and 1 != 0) { pick(ButtonId.Pen(0)) }
            NavRow("Pen: upper button", s.bindings.actionFor(ButtonId.Pen(1)).label, highlight = penMask and 2 != 0) { pick(ButtonId.Pen(1)) }
            Divider()
            val keyCount = maxOf(Bindings.PAD_KEYS, (s.bindings.pad.keys.maxOrNull() ?: 0) + 1)
            for (i in 0 until keyCount) {
                NavRow("Tablet key ${i + 1}", s.bindings.actionFor(ButtonId.Pad(i)).label, highlight = padMask and (1 shl i) != 0) { pick(ButtonId.Pad(i)) }
            }
            if (s.bindings.keys.isNotEmpty()) {
                Divider()
                for ((code, action) in s.bindings.keys.toSortedMap()) {
                    NavRow("Key ${KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")}", action.label) { pick(ButtonId.Key(code)) }
                }
            }
            Divider()
            TextButton(onClick = { app.settings.update { it.copy(bindings = it.bindings.copy(pen = Bindings.DEFAULT_PEN, pad = Bindings.DEFAULT_PAD, keys = emptyMap())) } }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Reset buttons to defaults")
            }
        }

        Section("Dial") {
            Text("The dial does: ${s.dialMode.label}. A key set to “${Action.CYCLE_DIAL.label}” steps through the ticked modes.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            for (m in DialMode.entries) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        app.settings.update { st ->
                            val list = st.bindings.dialModes
                            val next = if (m in list) list - m else DialMode.entries.filter { it in list || it == m }
                            if (next.isEmpty()) st else st.copy(bindings = st.bindings.copy(dialModes = next), dialMode = if (st.dialMode in next) st.dialMode else next.first())
                        }
                    }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(m in s.bindings.dialModes, onCheckedChange = null, modifier = Modifier.padding(10.dp))
                    Text(m.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (s.dialMode == m) Text("Now", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 14.dp))
                }
            }
            Divider()
            SwitchRow("Reverse direction", null, s.bindings.dialInvert) { v -> app.settings.update { it.copy(bindings = it.bindings.copy(dialInvert = v)) } }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                Text("Scroll per click: ${(s.dialScrollStep * 100).toInt()}% of the screen", style = MaterialTheme.typography.bodyMedium)
                Slider(s.dialScrollStep, { v -> app.settings.update { it.copy(dialScrollStep = v) } }, valueRange = 0.02f..0.3f)
            }
        }

        Section("Tablet area") { MappingSettings(app) }

        Section("Pen") {
            SwitchRow("Show pointer", "A small cursor while the pen hovers (driver mode)", s.showTabletCursor) { v -> app.settings.update { it.copy(showTabletCursor = v) } }
        }
    }
}

@Composable
private fun StatusCard(app: JotsApp, status: TabletStatus) {
    val (title, body) = when (status) {
        TabletStatus.Disabled -> "Driver off" to "The tablet works as Android's generic pen. Turn the driver on in Settings for all its buttons and the dial."
        TabletStatus.NoTablet -> "No tablet connected" to "Plug the Huion tablet into the phone's USB-C port. If Android asks which app to open, pick Jots and tick “Always” so permission is remembered."
        is TabletStatus.NeedsPermission -> status.name to "Allow Jots to use the tablet in the dialog Android is showing."
        is TabletStatus.PermissionDenied -> status.name to "Jots was not allowed to use the tablet, so only the basic pen works."
        is TabletStatus.Released -> status.name to if (status.restored) "Handed back to Android while Jots is in the background." else "Paused. If the pen doesn't work in other apps, unplug and replug the tablet."
        is TabletStatus.Active -> status.name to buildString {
            append("Driver mode: all buttons and the dial are available.\n")
            if (status.firmware.isNotBlank()) append("Firmware ${status.firmware}\n")
            val p = status.params
            append("Area ${"%.0f".format(p.widthMm)} × ${"%.0f".format(p.heightMm)} mm, ${p.maxPressure + 1} pressure levels")
            if (!status.paramsFromDevice) append("\n(Using built-in Q620M values: the tablet didn't report its own.)")
        }
        is TabletStatus.Failed -> status.name to status.message
    }
    Surface(shape = RoundedCornerShape(22.dp), color = if (status is TabletStatus.Active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (status is TabletStatus.PermissionDenied || status is TabletStatus.Failed) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = { app.driver.retryPermission() }) { Text("Try again") }
            }
            if (status is TabletStatus.Disabled) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = { app.settings.update { it.copy(driverEnabled = true) } }) { Text("Turn on driver") }
            }
        }
    }
}

@Composable
private fun LiveTest(app: JotsApp) {
    val pen by app.driver.lastPen.collectAsState()
    val raw by app.driver.lastRaw.collectAsState()
    val padMask by app.router.padMask.collectAsState()
    val penMask by app.router.penButtons.collectAsState()
    val dial by app.router.dialTotal.collectAsState()
    val status by app.driver.status.collectAsState()
    val params = (status as? TabletStatus.Active)?.params ?: HuionProtocol.Q620M_DEFAULTS
    Column(Modifier.padding(18.dp)) {
        val p = pen
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pressure", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(80.dp))
            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(p?.pressure ?: 0f).height(10.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
        Spacer(Modifier.height(10.dp))
        val state = when (p?.phase) {
            null, PenPhase.HOVER_EXIT, PenPhase.UP -> "Out of range"
            PenPhase.HOVER_ENTER, PenPhase.HOVER_MOVE -> "Hovering"
            PenPhase.DOWN, PenPhase.MOVE -> "Touching"
        }
        Text("Pen: $state${if (p != null) "  ·  x ${p.x}  y ${p.y}  ·  tilt ${p.tiltX}°, ${p.tiltY}°" else ""}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Lamp("Pen 1", penMask and 1 != 0)
            Lamp("Pen 2", penMask and 2 != 0)
            for (i in 0 until Bindings.PAD_KEYS) Lamp("K${i + 1}", padMask and (1 shl i) != 0)
            Lamp("Dial $dial", false)
        }
        Spacer(Modifier.height(10.dp))
        Text(raw.ifEmpty { "–" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("Raw report (useful if something isn't recognised).  Area ${params.maxX} × ${params.maxY}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Lamp(label: String, on: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MappingSettings(app: JotsApp) {
    val s by app.settings.state.collectAsState()
    val m = s.mapping
    val status by app.driver.status.collectAsState()
    val params = (status as? TabletStatus.Active)?.params ?: HuionProtocol.Q620M_DEFAULTS
    val pen by app.driver.lastPen.collectAsState()
    val activity = LocalMainActivity.current
    val screenW = activity.window.decorView.width.toFloat().coerceAtLeast(1f)
    val screenH = activity.window.decorView.height.toFloat().coerceAtLeast(1f)
    fun set(f: (io.github.parthibdebnath.jots.tablet.TabletMapping) -> io.github.parthibdebnath.jots.tablet.TabletMapping) =
        app.settings.update { it.copy(mapping = f(it.mapping)) }

    Column(Modifier.padding(18.dp)) {
        Text("The highlighted part of the tablet covers the whole screen.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        // Diagram: the tablet as you're holding it, with the active area marked.
        val rotated = TabletMapper.normRotation(m.rotation) % 180 != 0
        val aspect = if (rotated) params.maxY.toFloat() / params.maxX else params.maxX.toFloat() / params.maxY
        val primary = MaterialTheme.colorScheme.primary
        val outline = MaterialTheme.colorScheme.outline
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth(if (rotated) 0.55f else 1f).aspectRatio(aspect).border(1.5.dp, outline, RoundedCornerShape(10.dp))) {
                val r = TabletMapper.activeRegion(params, screenW, screenH, m)
                drawRect(primary.copy(alpha = 0.18f), Offset(r[0] * size.width, r[1] * size.height), Size(r[2] * size.width, r[3] * size.height))
                drawRect(primary, Offset(r[0] * size.width, r[1] * size.height), Size(r[2] * size.width, r[3] * size.height), style = Stroke(2.dp.toPx()))
                val p = pen
                if (p != null && p.phase != PenPhase.HOVER_EXIT) {
                    val u = p.x.toFloat() / params.maxX
                    val v = p.y.toFloat() / params.maxY
                    val (a, b) = when (TabletMapper.normRotation(m.rotation)) {
                        90 -> (1 - v) to u
                        180 -> (1 - u) to (1 - v)
                        270 -> v to (1 - u)
                        else -> u to v
                    }
                    drawCircle(primary, 4.dp.toPx(), Offset(a * size.width, b * size.height))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Tablet turned", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(0, 90, 180, 270).forEachIndexed { i, deg ->
                SegmentedButton(selected = TabletMapper.normRotation(m.rotation) == deg, onClick = { set { it.copy(rotation = deg) } }, shape = SegmentedButtonDefaults.itemShape(i, 4)) {
                    Text(if (deg == 0) "Normal" else "$deg°")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep proportions", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(m.keepAspect, { v -> set { it.copy(keepAspect = v) } })
        }
        if (m.keepAspect) {
            Spacer(Modifier.height(8.dp))
            Text("Area position", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(0f to "Start", 0.5f to "Centre", 1f to "End").forEachIndexed { i, (v, l) ->
                    SegmentedButton(selected = m.anchor == v, onClick = { set { it.copy(anchor = v) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Area size: ${(m.areaScale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(m.areaScale, { v -> set { it.copy(areaScale = v) } }, valueRange = 0.3f..1f)
        OutlinedButton(onClick = { set { io.github.parthibdebnath.jots.tablet.TabletMapping() } }) { Text("Reset area") }
    }
}

@Composable
private fun BoxScope.ActionPicker(app: JotsApp, id: ButtonId) {
    val overlays = LocalOverlays.current
    val current = app.settings.value.bindings.actionFor(id)
    val title = when (id) {
        is ButtonId.Pen -> if (id.index == 0) "Pen: lower button" else "Pen: upper button"
        is ButtonId.Pad -> "Tablet key ${id.index + 1}"
        is ButtonId.Key -> "Key ${KeyEvent.keyCodeToString(id.keyCode).removePrefix("KEYCODE_")}"
    }
    InlineDialog(title, onDismiss = { overlays.dismiss() }) {
        for (group in Action.Group.entries) {
            val actions = Action.entries.filter { it.group == group }
            if (actions.isEmpty()) continue
            Text(group.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp))
            ChoiceList(actions, current, { it.label }) { a ->
                app.settings.update { it.copy(bindings = it.bindings.with(id, a)) }
                overlays.dismiss()
            }
        }
    }
}
