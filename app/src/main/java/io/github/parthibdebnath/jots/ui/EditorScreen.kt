package io.github.parthibdebnath.jots.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.parthibdebnath.jots.JotsApp
import io.github.parthibdebnath.jots.MainActivity
import io.github.parthibdebnath.jots.editor.BrushSpec
import io.github.parthibdebnath.jots.editor.Brushes
import io.github.parthibdebnath.jots.editor.PenType
import io.github.parthibdebnath.jots.editor.PressureCurve
import io.github.parthibdebnath.jots.editor.Tool
import io.github.parthibdebnath.jots.notes.Export
import io.github.parthibdebnath.jots.notes.Paper
import io.github.parthibdebnath.jots.settings.EraserMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.exp

@Composable
fun EditorScreen(activity: MainActivity, app: JotsApp, noteId: String, onClose: () -> Unit, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val overlays = LocalOverlays.current
    val controller = remember(noteId) { EditorController(activity, app, noteId, scope) }
    val focus = activity.focusMode
    val settings by app.settings.state.collectAsState()

    // Wire this editor up to the tablet, keys and hover forwarding while it's on screen.
    DisposableEffect(controller) {
        app.router.target = controller
        activity.editorView = controller.view
        onDispose {
            controller.save()
            if (app.router.target === controller) app.router.target = null
            if (activity.editorView === controller.view) activity.editorView = null
            app.router.releaseAll()
            activity.setFocus(false)
        }
    }
    // Save when the app goes to the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) controller.save() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(settings.scrollbarOnLeft) { controller.view.onSettingsChanged() }

    BackHandler {
        when {
            focus -> activity.setFocus(false)
            controller.hasSelection -> controller.view.clearSelection()
            else -> onClose()
        }
    }

    if (controller.loadError == "deleted") return

    var panel by remember { mutableStateOf<Tool?>(null) }

    Box(Modifier.fillMaxSize().background(Color(if (controller.meta.darkPaper) 0xFF1C1D21 else 0xFFFFFFFF))) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(!focus, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                Toolbar(
                    controller = controller,
                    onBack = onClose,
                    onToolTap = { t -> if (controller.tool == t) panel = if (panel == t) null else t else { controller.selectTool(t); panel = null } },
                    onMore = { overlays.show { MoreMenu(activity, app, controller, onClose, onOpenSettings) } },
                    onRename = { overlays.show { RenameDialog(controller.meta.title) { controller.rename(it); overlays.dismiss() } } },
                    onEditColor = { index -> overlays.show { ColorEditDialog(app, controller, index) } },
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (focus) Modifier else Modifier.navigationBarsPadding()),
            ) {
                AndroidView(factory = { controller.view }, modifier = Modifier.fillMaxSize())

                if (controller.loading) {
                    Box(Modifier.fillMaxSize().clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                        Text("Opening…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                controller.loadError?.let { err ->
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                        Text(err, modifier = Modifier.padding(32.dp), color = MaterialTheme.colorScheme.error)
                    }
                }

                // Selection actions float above the page, even with the UI hidden.
                androidx.compose.animation.AnimatedVisibility(controller.hasSelection, modifier = Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                    SelectionBar(controller)
                }

                if (controller.zoomPercent != 100 && !focus) {
                    Surface(
                        onClick = { controller.view.fitWidth() },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        shadowElevation = 2.dp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                    ) {
                        Text("${controller.zoomPercent}%  ·  Fit width", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }

                FlashMessage(controller, Modifier.align(Alignment.TopCenter).padding(top = if (controller.hasSelection) 64.dp else 16.dp))
            }
        }

        // Tool options drop down below the toolbar, inside this window.
        val open = panel
        if (open != null && !focus) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { panel = null },
            ) {
                Surface(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(top = 112.dp, start = 8.dp, end = 8.dp)
                        .widthIn(max = 460.dp)
                        .fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    shape = RoundedCornerShape(22.dp),
                    shadowElevation = 12.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                        when (open) {
                            Tool.PEN -> PenPanel(app, controller)
                            Tool.HIGHLIGHTER -> HighlighterPanel(app, controller)
                            Tool.ERASER -> EraserPanel(controller) { panel = null; overlays.show { ClearPageDialog(controller) } }
                            Tool.LASSO -> Text("Draw a loop around strokes to select them. Drag the selection to move it, or the corner dot to resize it.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    // First time in focus mode, say how to get back.
    LaunchedEffect(focus) {
        if (focus && !app.settings.value.focusHintShown) {
            controller.showFlash("Two-finger double-tap or Back to show the toolbar")
            app.settings.update { it.copy(focusHintShown = true) }
        }
    }
}

@Composable
private fun Toolbar(
    controller: EditorController,
    onBack: () -> Unit,
    onToolTap: (Tool) -> Unit,
    onMore: () -> Unit,
    onRename: () -> Unit,
    onEditColor: (Int) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text(
                    controller.meta.title.ifBlank { "Title" },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (controller.meta.title.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onRename).padding(horizontal = 8.dp, vertical = 6.dp),
                )
                IconButton(onClick = { controller.view.undo() }, enabled = controller.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                IconButton(onClick = { controller.view.redo() }, enabled = controller.canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
                IconButton(onClick = { controller.perform(io.github.parthibdebnath.jots.input.Action.TOGGLE_FOCUS) }) { Icon(Icons.Outlined.Fullscreen, "Hide all UI") }
                IconButton(onClick = onMore) { Icon(Icons.Outlined.MoreVert, "More") }
            }
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.Outlined.Draw, "Pen", controller.tool == Tool.PEN) { onToolTap(Tool.PEN) }
                ToolButton(Icons.Outlined.BorderColor, "Highlighter", controller.tool == Tool.HIGHLIGHTER) { onToolTap(Tool.HIGHLIGHTER) }
                ToolButton(JotsIcons.Eraser, "Eraser", controller.tool == Tool.ERASER) { onToolTap(Tool.ERASER) }
                ToolButton(JotsIcons.Lasso, "Select", controller.tool == Tool.LASSO) { onToolTap(Tool.LASSO) }
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
                QuickColors(controller, onEditColor)
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { Icon(icon, label) }
}

@Composable
private fun QuickColors(controller: EditorController, onEditColor: (Int) -> Unit) {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val highlighter = controller.tool == Tool.HIGHLIGHTER
    val colors = if (highlighter) s.highlighterPalette else s.palette
    val current = if (highlighter) controller.highlighter.color else controller.pen.color
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        colors.forEachIndexed { i, c ->
            ColorDot(
                color = Color(c),
                selected = c == current,
                onClick = {
                    if (highlighter) controller.updateHighlighter(controller.highlighter.copy(color = c))
                    else {
                        controller.updatePen(controller.pen.copy(color = c))
                        if (controller.tool != Tool.PEN) controller.selectTool(Tool.PEN)
                    }
                },
                onLongClick = { if (!highlighter) onEditColor(i) },
            )
        }
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, size: Int = 26, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val ring = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .size((size + 8).dp)
            .clip(CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.border(2.dp, ring, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (color.alpha < 0.5f) 0.75f else color.alpha))
                .border(1.dp, Color.Black.copy(alpha = 0.12f), CircleShape),
        )
    }
}

@Composable
private fun SelectionBar(controller: EditorController) {
    Surface(
        shape = RoundedCornerShape(50),
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(top = 10.dp),
    ) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.view.deleteSelection() }) { Icon(Icons.Outlined.DeleteOutline, "Delete") }
            IconButton(onClick = { controller.view.duplicateSelection() }) { Icon(Icons.Outlined.ContentCopy, "Duplicate") }
            IconButton(onClick = { controller.view.recolorSelection(controller.pen.color) }) {
                Icon(Icons.Outlined.FormatColorFill, "Apply pen colour", tint = Color(controller.pen.color).let { if (it.luminanceSafe() > 0.92f) MaterialTheme.colorScheme.onSurface else it })
            }
            IconButton(onClick = { controller.view.clearSelection() }) { Icon(Icons.Outlined.Check, "Done") }
        }
    }
}

private fun Color.luminanceSafe(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
private fun FlashMessage(controller: EditorController, modifier: Modifier) {
    val f = controller.flash
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(f) {
        if (f == null) return@LaunchedEffect
        visible = true
        delay(if (f.first.length > 30) 3200 else 1400)
        visible = false
    }
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(shape = RoundedCornerShape(50), color = Color(0xE6202024)) {
            Text(f?.first.orEmpty(), color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
        }
    }
}

// region Panels

@Composable
private fun SizeSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    // Logarithmic, so the thin end (where handwriting lives) gets most of the travel.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        Slider(
            value = ((ln(value) - ln(min)) / (ln(max) - ln(min))).coerceIn(0f, 1f),
            onValueChange = { onChange(exp(ln(min) + it * (ln(max) - ln(min)))) },
            modifier = Modifier.weight(1f),
        )
        Text(if (value < 10) "%.1f".format(value) else value.toInt().toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp).padding(start = 8.dp))
    }
}

@Composable
private fun StrokePreview(spec: BrushSpec) {
    val c = Color(spec.color)
    Canvas(Modifier.fillMaxWidth().height(44.dp).padding(vertical = 4.dp)) {
        val w = size.width
        val h = size.height
        val n = 40
        val scale = w / 380f
        for (i in 0 until n) {
            val t0 = i / n.toFloat()
            val t1 = (i + 1) / n.toFloat()
            // Pressure ramps up then down across the sample, like a real stroke.
            val p = kotlin.math.sin(Math.PI * (t0 + t1) / 2).toFloat()
            val width = when (spec.penType) {
                PenType.MONOLINE -> spec.size
                PenType.BALLPOINT -> spec.size * (1f + 0.3f * p)
                PenType.FOUNTAIN -> spec.size * (0.25f + 1.05f * p)
            }
            drawLine(
                c,
                Offset(w * 0.06f + w * 0.88f * t0, h / 2 + h * 0.25f * kotlin.math.sin(t0 * 6.28f)),
                Offset(w * 0.06f + w * 0.88f * t1, h / 2 + h * 0.25f * kotlin.math.sin(t1 * 6.28f)),
                strokeWidth = (width * scale).coerceIn(1f, h * 0.8f),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ColumnScope.PenPanel(app: JotsApp, controller: EditorController) {
    val pen = controller.pen
    Text("Pen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        PenType.entries.forEachIndexed { i, t ->
            SegmentedButton(selected = pen.penType == t, onClick = { controller.updatePen(pen.copy(penType = t)) }, shape = SegmentedButtonDefaults.itemShape(i, PenType.entries.size)) { Text(t.label) }
        }
    }
    StrokePreview(pen)
    SizeSlider("Size", pen.size, Brushes.MIN_SIZE, 30f) { controller.updatePen(pen.withSize(it)) }
    if (pen.penType == PenType.FOUNTAIN) {
        Spacer(Modifier.height(6.dp))
        Text("Pressure", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PressureCurve.entries.forEachIndexed { i, c ->
                SegmentedButton(selected = pen.curve == c, onClick = { controller.updatePen(pen.copy(curve = c)) }, shape = SegmentedButtonDefaults.itemShape(i, PressureCurve.entries.size)) { Text(c.label) }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val s by app.settings.state.collectAsState()
    Text("Colours (long-press a colour in the toolbar to change it)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        s.palette.forEach { c -> ColorDot(Color(c), c == pen.color, onClick = { controller.updatePen(pen.copy(color = c)) }) }
    }
}

@Composable
private fun ColumnScope.HighlighterPanel(app: JotsApp, controller: EditorController) {
    val h = controller.highlighter
    Text("Highlighter", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    SizeSlider("Size", h.size, 6f, Brushes.MAX_SIZE) { controller.updateHighlighter(h.withSize(it)) }
    Spacer(Modifier.height(8.dp))
    val s by app.settings.state.collectAsState()
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        s.highlighterPalette.forEach { c -> ColorDot(Color(c), c == h.color, onClick = { controller.updateHighlighter(h.copy(color = c)) }) }
    }
}

@Composable
private fun ColumnScope.EraserPanel(controller: EditorController, onClearPage: () -> Unit) {
    Text("Eraser", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        EraserMode.entries.forEachIndexed { i, m ->
            SegmentedButton(selected = controller.eraserMode == m, onClick = { controller.setEraser(mode = m) }, shape = SegmentedButtonDefaults.itemShape(i, EraserMode.entries.size)) { Text(m.label) }
        }
    }
    Spacer(Modifier.height(8.dp))
    SizeSlider("Size", controller.eraserSize, 4f, 120f) { controller.setEraser(size = it) }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onClearPage) { Text("Erase everything on this note", color = MaterialTheme.colorScheme.error) }
}

// endregion

// region Dialogs

@Composable
private fun androidx.compose.foundation.layout.BoxScope.RenameDialog(current: String, onDone: (String) -> Unit) {
    val overlays = LocalOverlays.current
    var text by remember { mutableStateOf(current) }
    InlineDialog("Note title", onDismiss = { overlays.dismiss() }, confirmLabel = "Save", onConfirm = { onDone(text) }) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(120) },
            singleLine = true,
            placeholder = { Text("Untitled note") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone(text) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ClearPageDialog(controller: EditorController) {
    val overlays = LocalOverlays.current
    InlineDialog(
        "Erase everything?",
        onDismiss = { overlays.dismiss() },
        confirmLabel = "Erase",
        destructive = true,
        onConfirm = {
            controller.view.clearAll()
            overlays.dismiss()
        },
    ) { Text("Every stroke on this note will be removed. You can undo this.") }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ColorEditDialog(app: JotsApp, controller: EditorController, index: Int) {
    val overlays = LocalOverlays.current
    val start = app.settings.value.palette.getOrNull(index) ?: return
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(start, it) } }
    var h by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v)))
    InlineDialog("Edit colour", onDismiss = { overlays.dismiss() }, confirmLabel = "Use", onConfirm = {
        val c = color.toArgb()
        app.settings.update { s -> s.copy(palette = s.palette.toMutableList().also { if (index in it.indices) it[index] = c }) }
        controller.updatePen(controller.pen.copy(color = c))
        overlays.dismiss()
    }) {
        Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(color))
        Spacer(Modifier.height(12.dp))
        Text("Hue", style = MaterialTheme.typography.labelLarge)
        Slider(h, { h = it }, valueRange = 0f..360f)
        Text("Saturation", style = MaterialTheme.typography.labelLarge)
        Slider(sat, { sat = it })
        Text("Brightness", style = MaterialTheme.typography.labelLarge)
        Slider(v, { v = it })
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MoreMenu(
    activity: MainActivity,
    app: JotsApp,
    controller: EditorController,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val overlays = LocalOverlays.current
    val scope = rememberCoroutineScope()
    fun export(pdf: Boolean) {
        overlays.dismiss()
        val strokes = controller.view.document.strokes.toList()
        val meta = controller.meta
        controller.showFlash(if (pdf) "Making PDF…" else "Making image…")
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { if (pdf) Export.pdf(activity, meta, strokes) else Export.png(activity, meta, strokes) }
            }
            result.onSuccess { f ->
                activity.startActivity(Export.shareIntent(activity, f, if (pdf) "application/pdf" else "image/png"))
            }.onFailure { Toast.makeText(activity, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    InlineMenu {
        MenuItem("Paper: ${controller.meta.paper.label}", Icons.Outlined.GridOn) {
            overlays.show {
                InlineDialog("Paper", onDismiss = { overlays.dismiss() }) {
                    ChoiceList(Paper.entries, controller.meta.paper, { it.label }) { controller.setPaper(paper = it); overlays.dismiss() }
                }
            }
        }
        MenuItem(if (controller.meta.darkPaper) "Light paper" else "Dark paper", if (controller.meta.darkPaper) Icons.Outlined.LightMode else Icons.Outlined.DarkMode) {
            controller.setPaper(dark = !controller.meta.darkPaper)
            overlays.dismiss()
        }
        MenuItem("Fit width", Icons.Outlined.FitScreen) { controller.view.fitWidth(); overlays.dismiss() }
        MenuItem("Rename", Icons.Outlined.Edit) {
            overlays.show { RenameDialog(controller.meta.title) { controller.rename(it); overlays.dismiss() } }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        MenuItem("Share as PDF", Icons.Outlined.PictureAsPdf) { export(true) }
        MenuItem("Share as image", Icons.Outlined.Image) { export(false) }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        MenuItem("Settings", Icons.Outlined.Settings) { overlays.dismiss(); onOpenSettings() }
        MenuItem("Delete note", Icons.Outlined.DeleteOutline, danger = true) {
            overlays.show {
                InlineDialog("Delete this note?", onDismiss = { overlays.dismiss() }, confirmLabel = "Delete", destructive = true, onConfirm = {
                    overlays.dismiss()
                    controller.deleteNote()
                    onClose()
                }) { Text("This can't be undone.") }
            }
        }
    }
}

// endregion
