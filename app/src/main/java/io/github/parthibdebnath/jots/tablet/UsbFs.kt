package io.github.parthibdebnath.jots.tablet

import android.util.Log
import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * The two Linux usbfs ioctls Android's USB API doesn't expose, issued on the file descriptor of
 * our own [android.hardware.usb.UsbDeviceConnection] (Android's UsbDeviceConnection makes the same
 * kind of calls from the app process under the hood).
 *
 * Why: taking over the tablet detaches the kernel's HID driver and switches the firmware into
 * vendor mode. Android can't undo either, so on release we reset the device (firmware back to its
 * generic mode) and ask the kernel to re-bind its driver, which makes the tablet work as a normal
 * system pen again in other apps.
 */
object UsbFs {
    private const val TAG = "UsbFs"

    private const val IOC_NONE = 0
    private const val IOC_READ_WRITE = 3
    private const val U = 'U'.code

    private fun ioc(dir: Int, nr: Int, size: Int) = (dir shl 30) or (size shl 16) or (U shl 8) or nr

    private val USBDEVFS_RESET = ioc(IOC_NONE, 20, 0)
    private val USBDEVFS_CONNECT = ioc(IOC_NONE, 23, 0)

    @Suppress("unused") // Fields are read reflectively by JNA.
    @Structure.FieldOrder("ifno", "ioctlCode", "data")
    class UsbDevFsIoctl : Structure() {
        @JvmField var ifno: Int = 0
        @JvmField var ioctlCode: Int = 0
        @JvmField var data: Pointer? = null
    }

    private interface LibC : Library {
        @Throws(LastErrorException::class)
        fun ioctl(fd: Int, request: Int, arg: Pointer?): Int

        @Throws(LastErrorException::class)
        fun ioctl(fd: Int, request: Int, arg: UsbDevFsIoctl): Int
    }

    private val libc: LibC? by lazy {
        runCatching { Native.load("c", LibC::class.java) }
            .onFailure { Log.w(TAG, "JNA unavailable", it) }
            .getOrNull()
    }

    /** Resets the device: the firmware returns to its power-on mode. */
    fun reset(fd: Int): Boolean = call("reset") { it.ioctl(fd, USBDEVFS_RESET, null as Pointer?) }

    /** Lets the kernel bind its own driver to [interfaceNumber] again. */
    fun reattachKernelDriver(fd: Int, interfaceNumber: Int): Boolean = call("connect") { lib ->
        val s = UsbDevFsIoctl()
        s.ifno = interfaceNumber
        s.ioctlCode = USBDEVFS_CONNECT
        s.data = null
        val request = ioc(IOC_READ_WRITE, 18, s.size())
        lib.ioctl(fd, request, s)
    }

    private inline fun call(what: String, block: (LibC) -> Int): Boolean {
        val lib = libc ?: return false
        return try {
            block(lib) >= 0
        } catch (e: LastErrorException) {
            // EBUSY from connect just means a driver is already bound (e.g. the reset re-bound it).
            Log.i(TAG, "$what: errno ${e.errorCode}")
            e.errorCode == EBUSY
        } catch (t: Throwable) {
            Log.w(TAG, "$what failed", t)
            false
        }
    }

    private const val EBUSY = 16
}
