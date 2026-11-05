# Jots

A clean, offline handwriting notebook for Android, built for use with a Huion drawing tablet
(developed against the **Inspiroy Dial Q620M**).

## What it does that the stock notes app doesn't

- **Edge scroll bar, usable with the pen.** It's always on the edge of the page, even with all UI hidden.
  - Drag the thumb to scroll fast.
  - Drag anywhere else on the strip to move the page 1:1.
  - Tap above or below the thumb to jump a screen.
- **Fills the screen by default.** A note is an endless page exactly as wide as the screen. There's
  no page setup and no side borders, and you can't zoom out past "fit width".
- **Full tablet support, with every control re-mappable.** Jots has its own user-space USB driver
  for Huion tablets. It enables:
  - both pen barrel buttons
  - all 9 frame keys
  - the dial (scroll, zoom, brush size, undo/redo or colour; a key cycles between them)
- **Hide all UI.** Toolbar and system bars go away; only the page and its scroll bar stay. Bring it
  back with a two-finger double-tap, Back, or a tablet key (key 7 by default).

## Tablet driver: how it works

Out of the box a Huion tablet speaks a reduced HID protocol. Android's generic driver only sees the
pen and one barrel button; the frame keys and dial send reports it can't decode (that's why the
cursor flickers when you press them).

Reading USB string descriptor 200 switches the firmware into its full "vendor" mode. This is what
the Windows driver, Linux's `hid-uclogic` and OpenTabletDriver all do. While Jots is in the
foreground it then:

1. claims the tablet through Android's USB host API (detaching the kernel driver),
2. switches the firmware to vendor mode and decodes every report itself,
3. replays the pen into its own window as normal stylus events, with its own cursor, so the pen
   works on the canvas *and* on every button and menu.

When Jots goes to the background, it resets the tablet and re-attaches the kernel driver, so the pen
works normally in other apps again. If Jots is killed abruptly, unplug and replug the tablet to
restore it.

If the driver is switched off (Settings → Tablet driver), Jots uses Android's normal stylus
support: pressure and one barrel button, plus any keys the tablet sends as keyboard keys (those
can be mapped too).

### In other apps (optional)

**Settings → Tablet in other apps** makes the pen, both barrel buttons, the keys and the dial work
system-wide. Android only lets an app inject input with help, so this uses
[Shizuku](https://shizuku.rikka.app/), which you start once per phone restart via Wireless
debugging.

While it's on:
- a foreground service keeps the tablet when Jots leaves the screen;
- the pen is injected into whatever app is in front as a real stylus, with pressure;
- the lower barrel button is the normal stylus button, and the upper one flips to the eraser;
- a small pointer follows the hovering pen. It's a non-touchable overlay at 80% opacity, so it
  never blocks touches. (Android's own Settings app hides all overlays, so it doesn't show there.)
- keys and the dial get their own mapping: back, home, recents, Ctrl+Z and other shortcuts,
  scrolling, volume, zoom, brush size, or held Ctrl/Shift/Alt/Space.

Inside Jots, everything works as before. If Shizuku stops, the mode stops and the tablet goes back
to Android at once. It resumes when Shizuku is running again. The notification has a Stop button.

### Defaults

| Control | Action |
|---|---|
| Pen lower button | Hold: eraser |
| Pen upper button | Hold: drag to scroll |
| Keys 1–9 | Undo, Redo, Pen, Eraser, Highlighter, Lasso, Hide UI, Fit width, Change dial mode |
| Dial | Scroll |
| Keys 1–9, other apps | Ctrl+Z, Ctrl+Y, Back, Home, Recents, Page up, Page down, Open Jots, Change dial mode |
| Dial, other apps | Scroll (cycles to Volume and Zoom) |

Every one of these can be changed in **Settings → Tablet**. That screen also has:
- a live test that lights up each button as you press it,
- a "Press to set" mode,
- tablet-area settings (rotation, keep proportions, position, size).

The order of the keys in the firmware may not match their physical layout. Use the live test to see
which number each key is.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 37).

```
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest   # protocol, mapping, viewport, scroll bar and eraser tests
```

Release builds are signed with `keystore.properties` if present (storeFile, storePassword, keyAlias,
keyPassword), otherwise with the debug key.

## Layout

- `universal/` — the optional system-wide mode: Shizuku injection, pen event state machine, pointer overlay, service
- `tablet/` — Huion protocol parser, pen proximity tracking, tablet→screen mapping, USB driver, event replay
- `input/` — actions, bindings, and the router that dispatches button presses
- `editor/` — page view (Jetpack Ink for low-latency strokes), viewport, scroll bar, eraser/lasso geometry, undo
- `notes/` — on-disk format, repository, PDF/PNG export
- `ui/` — Compose screens. Dialogs and menus are drawn inside the main window, because replayed pen
  events can't reach popup windows.
