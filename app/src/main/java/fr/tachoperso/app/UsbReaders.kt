package fr.tachoperso.app

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
import androidx.core.content.ContextCompat
import fr.tachoperso.core.apdu.CardException
import fr.tachoperso.core.ccid.CcidDescriptor
import fr.tachoperso.core.ccid.CcidReader
import fr.tachoperso.core.ccid.UsbBulkPipe
import fr.tachoperso.core.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume

/** Points de terminaison bulk du lecteur, via l'API USB Host d'Android. */
class AndroidBulkPipe(
    private val connection: UsbDeviceConnection,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
) : UsbBulkPipe {
    override fun write(data: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < data.size) {
            val n = connection.bulkTransfer(epOut, data, offset, data.size - offset, timeoutMs)
            if (n <= 0) throw CardException("Écriture USB vers le lecteur impossible")
            offset += n
        }
    }

    override fun read(maxLength: Int, timeoutMs: Int): ByteArray {
        val buffer = ByteArray(maxLength)
        val n = connection.bulkTransfer(epIn, buffer, maxLength, timeoutMs)
        return if (n <= 0) ByteArray(0) else buffer.copyOf(n)
    }
}

class UsbReaderSession(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    val reader: CcidReader,
) : Closeable {
    override fun close() {
        reader.powerOff()
        connection.releaseInterface(usbInterface)
        connection.close()
    }
}

object UsbReaders {
    private const val ACTION_USB_PERMISSION = "fr.tachoperso.app.USB_PERMISSION"

    fun ccidInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CSCID }

    fun findReader(usb: UsbManager): UsbDevice? =
        usb.deviceList.values.firstOrNull { ccidInterface(it) != null }

    fun describe(device: UsbDevice): String {
        val name = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifBlank { "Lecteur" }
        return "%s (%04X:%04X)".format(name, device.vendorId, device.productId)
    }

    suspend fun requestPermission(context: Context, usb: UsbManager, device: UsbDevice): Boolean {
        if (usb.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(usb.hasPermission(device))
                }
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    fun open(usb: UsbManager, device: UsbDevice, log: Log): UsbReaderSession {
        val usbInterface = ccidInterface(device) ?: throw CardException("Ce périphérique n'est pas un lecteur de carte à puce")
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
        }
        val inEp = epIn ?: throw CardException("Point de terminaison USB d'entrée introuvable")
        val outEp = epOut ?: throw CardException("Point de terminaison USB de sortie introuvable")
        val connection = usb.openDevice(device) ?: throw CardException("Impossible d'ouvrir le lecteur USB")
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            throw CardException("Le lecteur est utilisé par une autre application")
        }
        val descriptor = CcidDescriptor.parse(connection.rawDescriptors)
        log.log("Lecteur : ${describe(device)}")
        return UsbReaderSession(connection, usbInterface, CcidReader(AndroidBulkPipe(connection, outEp, inEp), descriptor, log))
    }
}
