package io.github.parthibdebnath.jots.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Dialogs and menus drawn inside the activity's own window instead of in popup windows.
 *
 * This matters for the tablet: in driver mode its pen is replayed into the activity window only,
 * so anything in a separate window (a platform dialog, a dropdown popup) couldn't be tapped with it.
 */
class Overlays {
    var current by mutableStateOf<(@Composable BoxScope.() -> Unit)?>(null)
        private set

    fun show(content: @Composable BoxScope.() -> Unit) {
        current = content
    }

    fun dismiss() {
        current = null
    }
}

val LocalOverlays = staticCompositionLocalOf { Overlays() }

@Composable
fun OverlayHost(overlays: Overlays) {
    val content = overlays.current
    AnimatedVisibility(visible = content != null, enter = fadeIn(), exit = fadeOut()) {
        // Keep showing the last content while fading out.
        val last = remember { mutableStateOf(content) }
        if (content != null) last.value = content
        BackHandler { overlays.dismiss() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { overlays.dismiss() }
                .safeDrawingPadding(),
        ) {
            last.value?.invoke(this)
        }
    }
}

/** Stops clicks inside a panel from reaching the scrim behind it. */
private fun Modifier.swallowClicks() = clickable(interactionSource = MutableInteractionSource(), indication = null) {}

@Composable
fun BoxScope.InlineDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .swallowClicks(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 24.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
            Spacer(Modifier.size(14.dp))
            Column(
                Modifier
                    .padding(end = 8.dp)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
                if (confirmLabel != null && onConfirm != null) {
                    TextButton(onClick = onConfirm) {
                        Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** A small menu card pinned to a corner of the screen. */
@Composable
fun BoxScope.InlineMenu(alignment: Alignment = Alignment.TopEnd, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .align(alignment)
            .padding(horizontal = 8.dp, vertical = 52.dp)
            .widthIn(min = 220.dp, max = 300.dp)
            .swallowClicks(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(vertical = 6.dp).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
fun MenuItem(label: String, icon: ImageVector? = null, danger: Boolean = false, trailing: String? = null, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(14.dp))
        }
        Text(label, color = color, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A radio-style list for picking one option inside an [InlineDialog]. */
@Composable
fun <T> ChoiceList(options: List<T>, selected: T?, label: (T) -> String, onPick: (T) -> Unit) {
    Column {
        for (o in options) {
            val isSel = o == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(o) }
                    .background(if (isSel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label(o), style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
