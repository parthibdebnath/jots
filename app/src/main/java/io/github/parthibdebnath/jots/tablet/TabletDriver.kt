package io.github.parthibdebnath.jots.tablet

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the driver is doing, for the UI. */
sealed interface TabletStatus {
    /** Driver mode is switched off in settings. */
    data object Disabled : TabletStatus

    data object NoTablet : TabletStatus

    data class NeedsPermission(val name: String) : TabletStatus

    data class PermissionDenied(val name: String) : TabletStatus

    /** The app is in the background, so the tablet has been handed back to Android. */
    data class Released(val name: String, val restored: Boolean) : TabletStatus

    data class Active(
        val name: String,
        val firmware: String,
        val params: TabletParams,
        val paramsFromDevice: Boolean,
    ) : TabletStatus

    data class Failed(val name: String, val message: String) : TabletStatus
}

/** Receives input from the tablet, always on the main thread. */
interface TabletListener {
    fun onPen(frame: PenFrame, params: TabletParams)
    fun onPenButton(index: Int, pressed: Boolean)
    fun onPadKey(index: Int, pressed: Boolean)
    fun onDial(delta: Int)
}

/**
 * A user-space driver for Huion tablets over Android's USB host API.
 *
 * While the app is in the foreground it owns the tablet: it claims interface 0 (detaching the
 * kernel's generic HID driver), switches the firmware to vendor mode, and decodes every report
 * itself. That's what makes the second barrel button, the frame keys and the dial usable. When the
 * app goes to the background [release] hands the tablet back to Android.
 */
class TabletDriver(private val context: Context) {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow<TabletStatus>(TabletStatus.NoTablet)
    val status: StateFlow<TabletStatus> = _status

    /** The last raw report (hex) and pen frame, for the tablet screen's live test. */
    private val _lastRaw = MutableStateFlow("")
    val lastRaw: StateFlow<String> = _lastRaw
    private val _lastPen = MutableStateFlow<PenFrame?>(null)
    val lastPen: StateFlow<PenFrame?> = _lastPen

    /** Set while the tablet screen is open; costs a little per report otherwise. */
    @Volatile var diagnostics = false

    var listener: TabletListener? = null

    private var enabled = true
    private var wanted = false // The app is in the foreground and wants the tablet.
    private var session: Session? = null
    private var permissionAsked: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) acquire()
                    else if (isTablet(device)) _status.value = TabletStatus.PermissionDenied(device.label())
                }
                // Wait a moment: if Android is also asking "open Jots for this tablet?", accepting
                // that grants permission, and asking ourselves at the same time would stack dialogs.
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (isTablet(device)) main.postDelayed({ acquire() }, 1200)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (session?.device?.deviceName == device.deviceName) {
                        session?.close(restoreSystem = false)
                        session = null
                    }
                    permissionAsked = null
                    if (isTablet(device)) _status.value = if (enabled) TabletStatus.NoTablet else TabletStatus.Disabled
                    acquire()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        if (on) acquire() else {
            release()
            _status.value = TabletStatus.Disabled
        }
    }

    /** The app came to the foreground. */
    fun onForeground() {
        wanted = true
        acquire()
    }

    /** The app left the foreground: give the tablet back to the system. */
    fun onBackground() {
        wanted = false
        release()
    }

    /** Called when the app was launched by plugging the tablet in (permission is then implied). */
    fun onDeviceIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) acquire()
    }

    /** Asks again after the user denied permission. */
    fun retryPermission() {
        permissionAsked = null
        acquire()
    }

    private fun acquire() {
        if (!enabled) {
            _status.value = TabletStatus.Disabled
            return
        }
        if (!wanted || session != null) return
        val device = usb.deviceList.values.firstOrNull(::isTablet)
        if (device == null) {
            _status.value = TabletStatus.NoTablet
            return
        }
        if (!usb.hasPermission(device)) {
            if (permissionAsked != device.deviceName) {
                permissionAsked = device.deviceName
                _status.value = TabletStatus.NeedsPermission(device.label())
                val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
            } else if (_status.value !is TabletStatus.PermissionDenied) {
                _status.value = TabletStatus.NeedsPermission(device.label())
            }
            return
        }
        session = Session.open(usb, device, this)?.also { it.start() }
    }

    private fun release() {
        val s = session ?: return
        session = null
        val restored = s.close(restoreSystem = true)
        _status.value = TabletStatus.Released(s.device.label(), restored)
    }

    internal fun setStatus(s: TabletStatus) {
        _status.value = s
    }

    internal fun onSessionFailed(s: Session, message: String) {
        if (session === s) {
            session = null
            s.close(restoreSystem = true)
        }
        _status.value = TabletStatus.Failed(s.device.label(), message)
    }

    internal fun publishPen(f: PenFrame) {
        if (diagnostics) _lastPen.value = f
    }

    internal fun publishRaw(data: ByteArray, len: Int) {
        if (!diagnostics) return
        val hex = (0 until len).joinToString(" ") { "%02X".format(data[it]) }
        _lastRaw.value = hex
    }

    /**
     * One claim of one tablet: from [open] until [close].
     */
    internal class Session private constructor(
        val device: UsbDevice,
        private val connection: UsbDeviceConnection,
        private val iface: UsbInterface,
        private val endpoint: UsbEndpoint,
        private val params: TabletParams,
        private val driver: TabletDriver,
    ) {
        private val main = Handler(Looper.getMainLooper())

        @Volatile private var running = false
        private var thread: Thread? = null

        private var keyMask = 0
        private val tracker = PenTracker(
            maxPressure = params.maxPressure,
            emit = {
                driver.publishPen(it)
                driver.listener?.onPen(it, params)
            },
            onButton = { i, p -> driver.listener?.onPenButton(i, p) },
        )
        private val timeout = Runnable { checkTimeout() }

        fun start() {
            running = true
            thread = Thread({ readLoop() }, "huion-reader").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
                start()
            }
        }

        private fun readLoop() {
            val size = endpoint.maxPacketSize.coerceAtLeast(64)
            var errors = 0
            while (running) {
                val buf = ByteArray(size)
                val started = SystemClock.uptimeMillis()
                val n = connection.bulkTransfer(endpoint, buf, size, READ_TIMEOUT_MS)
                if (!running) break
                if (n > 0) {
                    errors = 0
                    val report = HuionProtocol.parse(buf, n)
                    driver.publishRaw(buf, n)
                    if (report != null) main.post { dispatch(report) }
                } else if (SystemClock.uptimeMillis() - started < READ_TIMEOUT_MS / 2) {
                    // Failed fast rather than timing out: the device has gone or broken.
                    if (++errors > 50) {
                        main.post { driver.onSessionFailed(this, "Lost contact with the tablet") }
                        break
                    }
                    SystemClock.sleep(5)
                }
            }
        }

        private fun dispatch(r: HuionReport) {
            if (!running) return
            val l = driver.listener
            when (r) {
                is HuionReport.Pen -> {
                    tracker.onReport(r, SystemClock.uptimeMillis())
                    main.removeCallbacks(timeout)
                    val d = tracker.deadline
                    if (d > 0) main.postAtTime(timeout, d + 1)
                }
                is HuionReport.Keys -> {
                    val changed = keyMask xor r.mask
                    keyMask = r.mask
                    if (l != null && changed != 0) {
                        for (i in 0 until 24) {
                            val bit = 1 shl i
                            if (changed and bit != 0) l.onPadKey(i, r.mask and bit != 0)
                        }
                    }
                }
                is HuionReport.Dial -> if (r.delta != 0) l?.onDial(r.delta)
                is HuionReport.Strip -> Unit
            }
        }

        private fun checkTimeout() {
            if (!tracker.onTimeout(SystemClock.uptimeMillis())) {
                val d = tracker.deadline
                if (d > 0) main.postAtTime(timeout, d + 1)
            }
        }

        /** Stops reading and lets go. Returns true if the tablet was handed back to the system. */
        fun close(restoreSystem: Boolean): Boolean {
            running = false
            thread?.join(READ_TIMEOUT_MS * 3L)
            thread = null
            main.removeCallbacks(timeout)
            tracker.forceOut()
            val l = driver.listener
            if (l != null && keyMask != 0) for (i in 0 until 24) if (keyMask and (1 shl i) != 0) l.onPadKey(i, false)
            keyMask = 0

            var restored = false
            runCatching { connection.releaseInterface(iface) }
            if (restoreSystem) {
                val fd = connection.fileDescriptor
                if (fd >= 0) {
                    val reset = UsbFs.reset(fd)
                    val attached = UsbFs.reattachKernelDriver(fd, iface.id)
                    restored = reset && attached
                    Log.i(TAG, "release: reset=$reset reattach=$attached")
                }
            }
            runCatching { connection.close() }
            return restored
        }

        companion object {
            fun open(usb: UsbManager, device: UsbDevice, driver: TabletDriver): Session? {
                val (iface, ep) = findPenInterface(device) ?: run {
                    driver.setStatus(TabletStatus.Failed(device.label(), "No pen interface found on this device"))
                    return null
                }
                val conn = usb.openDevice(device) ?: run {
                    driver.setStatus(TabletStatus.Failed(device.label(), "Android refused to open the tablet"))
                    return null
                }
                // Claim first, so the kernel driver is gone before the firmware changes modes and
                // can't misread vendor-mode reports as stray system pen input.
                if (!conn.claimInterface(iface, true)) {
                    conn.close()
                    driver.setStatus(TabletStatus.Failed(device.label(), "Couldn't claim the tablet (is another app using it?)"))
                    return null
                }
                val firmware = readString(conn, HuionProtocol.FIRMWARE_STRING_INDEX).orEmpty()
                val raw = ByteArray(64)
                val n = conn.controlTransfer(
                    UsbConstants.USB_DIR_IN, // standard, device recipient
                    GET_DESCRIPTOR,
                    (STRING_DESCRIPTOR shl 8) or HuionProtocol.PARAMS_STRING_INDEX,
                    LANG_EN_US,
                    raw,
                    32,
                    1000,
                )
                val parsed = if (n > 0) HuionProtocol.parseParams(raw, n) else null
                if (parsed == null) {
                    Log.w(TAG, "No vendor parameters (n=$n); using Q620M defaults")
                }
                val params = parsed ?: HuionProtocol.Q620M_DEFAULTS
                Log.i(TAG, "Opened ${device.label()} fw=$firmware params=$params")
                driver.setStatus(TabletStatus.Active(device.label(), firmware, params, parsed != null))
                return Session(device, conn, iface, ep, params, driver)
            }

            private fun findPenInterface(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
                val candidates = (0 until device.interfaceCount).map { device.getInterface(it) }
                    .mapNotNull { i ->
                        val ep = (0 until i.endpointCount).map { i.getEndpoint(it) }.firstOrNull {
                            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
                        }
                        ep?.let { i to it }
                    }
                // Huion puts the pen (and, in vendor mode, everything else) on interface 0.
                return candidates.firstOrNull { it.first.id == 0 } ?: candidates.firstOrNull()
            }

            private fun readString(conn: UsbDeviceConnection, index: Int): String? {
                val buf = ByteArray(255)
                val n = conn.controlTransfer(
                    UsbConstants.USB_DIR_IN, GET_DESCRIPTOR, (STRING_DESCRIPTOR shl 8) or index, LANG_EN_US, buf, buf.size, 1000,
                )
                return if (n > 2) HuionProtocol.decodeStringDescriptor(buf, n) else null
            }
        }
    }

    companion object {
        private const val TAG = "TabletDriver"
        private const val ACTION_PERMISSION = "io.github.parthibdebnath.jots.USB_PERMISSION"
        private const val GET_DESCRIPTOR = 0x06
        private const val STRING_DESCRIPTOR = 0x03
        private const val LANG_EN_US = 0x0409
        private const val READ_TIMEOUT_MS = 100

        fun isTablet(d: UsbDevice) = d.vendorId == HuionProtocol.VENDOR_ID

        private fun UsbDevice.label(): String =
            runCatching { listOfNotNull(manufacturerName, productName).joinToString(" ") }.getOrNull()
                ?.ifBlank { null } ?: "Huion tablet"

        private fun Intent.usbDevice(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
