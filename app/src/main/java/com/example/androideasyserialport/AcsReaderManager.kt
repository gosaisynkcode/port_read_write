package com.example.androideasyserialport

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
import java.io.DataOutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.Executors

/**
 * AcsReaderManager handles communication with ACS ACR1252U (and other ACS USB Smart Card / NFC readers).
 * Vendor ID: 0x072F (1839), Product ID: 0x223E (8766).
 *
 * Supports:
 * 1. Official ACS Smart Card SDK (`com.acs.smartcard.Reader`) via Reflection if acs-smartcard.jar is present.
 * 2. Pure USB CCID / APDU protocol fallback via standard Android UsbManager.
 */
class AcsReaderManager(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onReaderConnected(deviceName: String, vidHex: String, pidHex: String)
        fun onReaderDisconnected()
        fun onCardDetected(uidHex: String, atrHex: String, cardType: String)
        fun onCardRemoved()
        fun onLog(message: String)
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.androideasyserialport.USB_PERMISSION"

        // ACS Vendor ID
        const val ACS_VENDOR_ID = 0x072F // 1839
        const val ACR1252U_PRODUCT_ID = 0x223E // 8766

        // Standard APDU Commands for ACS Readers / ISO14443 Cards
        val APDU_GET_UID = byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00)
        val APDU_GET_ATS = byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x01, 0x00, 0x00)

        // MIFARE Classic 1K/4K Commands
        fun apduLoadKey(key: ByteArray): ByteArray {
            // FF 82 00 00 06 [KEY_6_BYTES]
            val cmd = ByteArray(11)
            cmd[0] = 0xFF.toByte()
            cmd[1] = 0x82.toByte()
            cmd[2] = 0x00
            cmd[3] = 0x00
            cmd[4] = 0x06
            System.arraycopy(key, 0, cmd, 5, 6)
            return cmd
        }

        fun apduAuthBlock(
            blockNumber: Byte,
            keyType: Byte = 0x60 /* Key A */,
            keyNumber: Byte = 0x00
        ): ByteArray {
            // FF 86 00 00 05 01 00 [BlockNo] [KeyType] [KeyNum]
            return byteArrayOf(
                0xFF.toByte(), 0x86.toByte(), 0x00, 0x00, 0x05,
                0x01, 0x00, blockNumber, keyType, keyNumber
            )
        }

        fun apduReadBlock(blockNumber: Byte, length: Byte = 0x10): ByteArray {
            // FF B0 00 [BlockNo] [Length]
            return byteArrayOf(0xFF.toByte(), 0xB0.toByte(), 0x00, blockNumber, length)
        }

        fun bytesToHex(bytes: ByteArray, length: Int = bytes.size): String {
            val sb = StringBuilder()
            for (i in 0 until length.coerceAtMost(bytes.size)) {
                sb.append(String.format("%02X", bytes[i].toInt() and 0xFF))
                if (i < length - 1) sb.append(":")
            }
            return sb.toString()
        }

        fun hexToBytes(hex: String): ByteArray {
            val cleanHex = hex.replace(":", "").replace(" ", "")
            val len = cleanHex.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) +
                        Character.digit(cleanHex[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var currentDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    @Volatile
    private var isPolling = false

    @Volatile
    private var isAcsSdkAvailable = false

    private var acsReaderInstance: Any? = null
    private var acsPowerMethod: Method? = null
    private var acsTransmitMethod: Method? = null
    private var acsOpenMethod: Method? = null
    private var acsCloseMethod: Method? = null

    private var lastScannedUid: String? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    UsbManager.EXTRA_DEVICE,
                                    UsbDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && device != null) {
                            log("USB permission granted for ${device.deviceName}")
                            connectToDevice(device)
                        } else {
                            log("USB permission denied for device")
                        }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log("USB device attached")
                    scanAndConnect()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("USB device detached")
                    disconnect()
                }
            }
        }
    }

    init {
        checkAcsSdkAvailability()
        registerUsbReceiver()
    }

    private fun checkAcsSdkAvailability() {
        try {
            val readerClass = Class.forName("com.acs.smartcard.Reader")
            isAcsSdkAvailable = true
            log("ACS SmartCard SDK detected in classpath.")

            acsOpenMethod = readerClass.getMethod("open", UsbDevice::class.java)
            acsCloseMethod = readerClass.getMethod("close")
            acsPowerMethod = readerClass.getMethod("power", Integer.TYPE, Integer.TYPE)
            acsTransmitMethod = readerClass.getMethod(
                "transmit",
                Integer.TYPE,
                ByteArray::class.java,
                Integer.TYPE,
                ByteArray::class.java,
                Integer.TYPE
            )
        } catch (e: Exception) {
            isAcsSdkAvailable = false
            log("ACS SDK jar not present. Using native USB CCID APDU engine.")
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        disconnect()
        executor.shutdown()
    }

    private fun grantRootUsbPermissions() {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("chmod -R 777 /dev/bus/usb/\n")
            os.writeBytes("chmod 777 /dev/bus/usb/*/*\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
            log("Root permissions applied: chmod 777 /dev/bus/usb/")
        } catch (e: Exception) {
            // Ignore if non-root environment
        }
    }

    fun scanAndConnect() {
        executor.execute {
            grantRootUsbPermissions()
        }

        val deviceList = usbManager.deviceList
        log("Scanning USB devices (${deviceList.size} found)...")

        var acsDevice: UsbDevice? = null

        for (device in deviceList.values) {
            log(
                "Device: ${device.deviceName} | VendorID: 0x${
                    String.format(
                        "%04X",
                        device.vendorId
                    )
                } | ProductID: 0x${String.format("%04X", device.productId)}"
            )
            if (isAcsDevice(device)) {
                acsDevice = device
                break
            }
        }

        if (acsDevice != null) {
            if (usbManager.hasPermission(acsDevice)) {
                log("Permission already available for ${acsDevice.deviceName}")
                connectToDevice(acsDevice)
            } else {
                log("Requesting USB permission for ${acsDevice.deviceName}...")
                val flags =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val permissionIntent = PendingIntent.getBroadcast(
                    context, 0, Intent(ACTION_USB_PERMISSION), flags
                )
                usbManager.requestPermission(acsDevice, permissionIntent)
            }
        } else {
            log("No ACS Smart Card Reader found. Please plug in ACS ACR1252U via USB OTG.")
            listener.onReaderDisconnected()
        }
    }

    private fun isAcsDevice(device: UsbDevice): Boolean {
        // Vendor ID 0x072F is ACS (Advanced Card Systems)
        if (device.vendorId == ACS_VENDOR_ID) return true

        // USB Smart Card / CCID Interface Class check (0x0B)
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_CSCID) {
                return true
            }
        }
        return false
    }

    private fun connectToDevice(device: UsbDevice) {
        currentDevice = device
        val vidHex = String.format("%04X", device.vendorId)
        val pidHex = String.format("%04X", device.productId)

        log("Connecting to ACS Reader: ${device.deviceName} (VID: $vidHex, PID: $pidHex)...")

        if (isAcsSdkAvailable) {
            try {
                val readerClass = Class.forName("com.acs.smartcard.Reader")
                val constructor: Constructor<*> = readerClass.getConstructor(UsbManager::class.java)
                acsReaderInstance = constructor.newInstance(usbManager)
                acsOpenMethod?.invoke(acsReaderInstance, device)
                log("ACS SmartCard SDK initialized successfully.")
            } catch (e: Exception) {
                log("ACS SDK connection error: ${e.message}. Falling back to USB CCID.")
                initNativeCcidConnection(device)
            }
        } else {
            initNativeCcidConnection(device)
        }

        mainHandler.post {
            listener.onReaderConnected(device.deviceName, vidHex, pidHex)
        }

        startCardPollingLoop()
    }

    private fun initNativeCcidConnection(device: UsbDevice) {
        var conn = usbManager.openDevice(device)
        if (conn == null) {
            grantRootUsbPermissions()
            conn = usbManager.openDevice(device)
        }
        if (conn == null) {
            log("Failed to open USB device connection.")
            return
        }

        var ccidIntf: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_CSCID || intf.endpointCount >= 2) {
                ccidIntf = intf
                break
            }
        }

        if (ccidIntf == null && device.interfaceCount > 0) {
            ccidIntf = device.getInterface(0)
        }

        if (ccidIntf == null) {
            log("No suitable USB interface found on device.")
            conn.close()
            return
        }

        if (!conn.claimInterface(ccidIntf, true)) {
            log("Could not claim USB interface ${ccidIntf.id}")
            conn.close()
            return
        }

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null

        for (i in 0 until ccidIntf.endpointCount) {
            val ep = ccidIntf.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN && (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK || ep.type == UsbConstants.USB_ENDPOINT_XFER_INT)) {
                if (epIn == null || ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) epIn = ep
            } else if (ep.direction == UsbConstants.USB_DIR_OUT && (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK || ep.type == UsbConstants.USB_ENDPOINT_XFER_INT)) {
                if (epOut == null || ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) epOut = ep
            }
        }

        this.usbConnection = conn
        this.usbInterface = ccidIntf
        this.endpointIn = epIn
        this.endpointOut = epOut

        log("Native CCID USB Endpoints bound successfully (IN: ${epIn?.endpointNumber}, OUT: ${epOut?.endpointNumber}).")
    }

    private fun startCardPollingLoop() {
        if (isPolling) return
        isPolling = true

        executor.execute {
            log("Starting Card Presence Polling Loop...")
            var seqNum = 1

            while (isPolling) {
                try {
                    val uidHex = readCardUidNative(seqNum++)
                    if (uidHex != null && uidHex.isNotEmpty() && uidHex != "NO_CARD") {
                        if (uidHex != lastScannedUid) {
                            lastScannedUid = uidHex
                            log(">>> CARD TAP DETECTED! UID: $uidHex")
                            val cardType = parseCardTypeFromUid(uidHex)
                            mainHandler.post {
                                listener.onCardDetected(uidHex, "ATS/ATR Available", cardType)
                            }
                        }
                    } else {
                        if (lastScannedUid != null) {
                            lastScannedUid = null
                            log("<<< Card removed from reader.")
                            mainHandler.post {
                                listener.onCardRemoved()
                            }
                        }
                    }
                    Thread.sleep(300)
                } catch (e: Exception) {
                    Thread.sleep(500)
                }
            }
        }
    }

    /**
     * Sends standard APDU to ACR1252U to power up card slot and retrieve Card UID (FF CA 00 00 00).
     */
    fun readCardUid(): String? {
        return readCardUidNative(10)
    }

    private fun readCardUidNative(seq: Int): String {
        if (isAcsSdkAvailable && acsReaderInstance != null) {
            try {
                // slot 0
                val slot = 0
                // power up: Reader.CARD_WARM_RESET = 1
                acsPowerMethod?.invoke(acsReaderInstance, slot, 1)

                val response = ByteArray(256)
                val len = acsTransmitMethod?.invoke(
                    acsReaderInstance,
                    slot,
                    APDU_GET_UID,
                    APDU_GET_UID.size,
                    response,
                    response.size
                ) as? Int ?: 0

                if (len >= 2) {
                    val sw1 = response[len - 2].toInt() and 0xFF
                    val sw2 = response[len - 1].toInt() and 0xFF
                    if (sw1 == 0x90 && sw2 == 0x00) {
                        return bytesToHex(response, len - 2)
                    }
                }
            } catch (e: Exception) {
                // Fallback to CCID
            }
        }

        // CCID Bulk PowerOn & Transmit
        val conn = usbConnection ?: return "NO_CARD"
        val epOut = endpointOut ?: return "NO_CARD"
        val epIn = endpointIn ?: return "NO_CARD"

        synchronized(conn) {
            try {
                // Step 1: CCID PC_to_RDR_IccPowerOn (0x62)
                val powerOnCmd = ByteArray(10)
                powerOnCmd[0] = 0x62.toByte() // PC_to_RDR_IccPowerOn
                powerOnCmd[1] = 0x00 // Length
                powerOnCmd[2] = 0x00
                powerOnCmd[3] = 0x00
                powerOnCmd[4] = 0x00
                powerOnCmd[5] = 0x00 // Slot 0
                powerOnCmd[6] = (seq and 0xFF).toByte() // Sequence
                powerOnCmd[7] = 0x01 // Power Select: Warm Reset / 5V
                powerOnCmd[8] = 0x00
                powerOnCmd[9] = 0x00

                conn.bulkTransfer(epOut, powerOnCmd, powerOnCmd.size, 1000)

                val responseBuf = ByteArray(256)
                val retPower = conn.bulkTransfer(epIn, responseBuf, responseBuf.size, 1000)

                if (retPower < 10) return "NO_CARD"

                // Check CCID status byte
                val statusByte = responseBuf[7].toInt() and 0xFF
                if (statusByte != 0x00 && statusByte != 0x01) {
                    return "NO_CARD"
                }

                // Step 2: CCID PC_to_RDR_XfrBlock (0x6F) with APDU_GET_UID (FF CA 00 00 00)
                val xfrCmd = ByteArray(10 + APDU_GET_UID.size)
                xfrCmd[0] = 0x6F.toByte() // PC_to_RDR_XfrBlock
                xfrCmd[1] = APDU_GET_UID.size.toByte() // Length LSB
                xfrCmd[2] = 0x00
                xfrCmd[3] = 0x00
                xfrCmd[4] = 0x00
                xfrCmd[5] = 0x00 // Slot 0
                xfrCmd[6] = ((seq + 1) and 0xFF).toByte()
                xfrCmd[7] = 0x00 // BWI
                xfrCmd[8] = 0x00 // LevelParameter
                xfrCmd[9] = 0x00
                System.arraycopy(APDU_GET_UID, 0, xfrCmd, 10, APDU_GET_UID.size)

                conn.bulkTransfer(epOut, xfrCmd, xfrCmd.size, 1000)

                val apduRespBuf = ByteArray(256)
                val retApdu = conn.bulkTransfer(epIn, apduRespBuf, apduRespBuf.size, 1000)

                if (retApdu >= 12) {
                    val dataLen =
                        (apduRespBuf[1].toInt() and 0xFF) or ((apduRespBuf[2].toInt() and 0xFF) shl 8)
                    if (dataLen >= 2) {
                        val sw1 = apduRespBuf[10 + dataLen - 2].toInt() and 0xFF
                        val sw2 = apduRespBuf[10 + dataLen - 1].toInt() and 0xFF
                        if (sw1 == 0x90 && sw2 == 0x00) {
                            val uidBytes = ByteArray(dataLen - 2)
                            System.arraycopy(apduRespBuf, 10, uidBytes, 0, dataLen - 2)
                            return bytesToHex(uidBytes)
                        }
                    }
                }
            } catch (e: Exception) {
                // Connection error
            }
        }
        return "NO_CARD"
    }

    /**
     * Transmits a custom hex APDU to the current smart card.
     */
    fun transmitApduHex(apduHex: String): String {
        val apduBytes = hexToBytes(apduHex)
        val conn = usbConnection ?: return "Error: Reader not connected"
        val epOut = endpointOut ?: return "Error: Endpoint OUT missing"
        val epIn = endpointIn ?: return "Error: Endpoint IN missing"

        synchronized(conn) {
            try {
                log("TX APDU >> ${bytesToHex(apduBytes)}")

                val xfrCmd = ByteArray(10 + apduBytes.size)
                xfrCmd[0] = 0x6F.toByte() // PC_to_RDR_XfrBlock
                xfrCmd[1] = apduBytes.size.toByte()
                xfrCmd[2] = 0x00
                xfrCmd[3] = 0x00
                xfrCmd[4] = 0x00
                xfrCmd[5] = 0x00
                xfrCmd[6] = 0x99.toByte()
                xfrCmd[7] = 0x00
                xfrCmd[8] = 0x00
                xfrCmd[9] = 0x00
                System.arraycopy(apduBytes, 0, xfrCmd, 10, apduBytes.size)

                conn.bulkTransfer(epOut, xfrCmd, xfrCmd.size, 1000)

                val responseBuf = ByteArray(256)
                val retLen = conn.bulkTransfer(epIn, responseBuf, responseBuf.size, 1000)

                if (retLen >= 10) {
                    val dataLen =
                        (responseBuf[1].toInt() and 0xFF) or ((responseBuf[2].toInt() and 0xFF) shl 8)
                    if (dataLen > 0) {
                        val payload = ByteArray(dataLen)
                        System.arraycopy(responseBuf, 10, payload, 0, dataLen)
                        val respHex = bytesToHex(payload)
                        log("RX APDU << $respHex")
                        return respHex
                    }
                }
            } catch (e: Exception) {
                log("APDU Transmit error: ${e.message}")
            }
        }
        return "6F 00 (Error Transmitting)"
    }

    private fun parseCardTypeFromUid(uidHex: String): String {
        val cleanUid = uidHex.replace(":", "")
        return when (cleanUid.length) {
            8 -> "MIFARE Classic 1K / 4K (4-Byte UID)"
            14 -> "NTAG213/215/216 / MIFARE Ultralight (7-Byte UID)"
            20 -> "ISO14443-4 Smart Card (10-Byte UID)"
            else -> "ISO 14443 NFC Tag (${cleanUid.length / 2}-Byte UID)"
        }
    }

    fun disconnect() {
        isPolling = false
        if (acsReaderInstance != null && acsCloseMethod != null) {
            try {
                acsCloseMethod?.invoke(acsReaderInstance)
            } catch (e: Exception) {
                // Ignore
            }
        }
        usbConnection?.let { conn ->
            usbInterface?.let { intf -> conn.releaseInterface(intf) }
            conn.close()
        }
        usbConnection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null
        currentDevice = null
        lastScannedUid = null
        log("ACS Reader disconnected.")
    }

    private fun log(message: String) {
        mainHandler.post {
            listener.onLog(message)
        }
    }
}
