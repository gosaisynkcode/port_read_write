package com.example.androideasyserialport

/*class MainActivity2 : Activity() {
    private var serialPort: SerialPort? = null
    private lateinit var tvLogs: TextView

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLogs = findViewById(R.id.tvLogs)
        val btnEnable = findViewById<Button>(R.id.btnEnable)
        val btnDisable = findViewById<Button>(R.id.btnDisable)
        val bt_permison = findViewById<Button>(R.id.bt_permison)
        val  btnnext = findViewById<Button>(R.id.btnnext)
        btnnext.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }

        bt_permison.setOnClickListener {
            // Request root and set permissions before opening the port
            initSerialPort()

        }

        btnEnable.setOnClickListener {
            enableBillAcceptor()
        }

        btnDisable.setOnClickListener {
            disableBillAcceptor()
        }
    }

    private fun initSerialPort() {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("chmod 777 /dev/ttyS4\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()

            log("Opening serial port /dev/ttyS4 (9600, N, 8, 1)...")
            serialPort = SerialPort(
                path = "/dev/ttyS4",
                speed = 9600,
                dataBits = SerialPort.DataBits.CS8,
                stopBits = SerialPort.StopBits.B1,
                parity = SerialPort.Parity.None, // Most L704 units use None parity for RS232
                flowControl = SerialPort.FlowControl.None,
                rts = false,
                dtr = false,
                callback = object : SerialPort.DataCallback {
                    override fun onData(data: ByteArray) {
                        runOnUiThread {
                            handleIncomingData(data)
                        }
                    }
                }
            )
            log("Serial port opened.")

            // Send Reset and Enable in a separate thread to avoid freezing UI
            Thread {
                try {
                    log("TX >> Reset (0x30)")
                    serialPort?.write(byteArrayOf(0x30))
                    
                    Thread.sleep(1500) // Wait 1.5 seconds for machine to finish rebooting
                    
                    log("TX >> Enable (0x3E)")
                    serialPort?.write(byteArrayOf(0x3E))
                } catch (e: Exception) {
                    log("Handshake failed: ${e.message}")
                }
            }.start()

        } catch (e: Exception) {
            log("Error opening serial port: ${e.message}")
        }
    }

    private fun handleIncomingData(data: ByteArray) {
        val hex = data.joinToString(" ") { String.format("%02X", it) }
        log("RX << $hex")

        // AED mapping for ICT L70/L704 (ICT-002 / ICT104U Protocol)
        for (byte in data) {
            val code = byte.toInt() and 0xFF
            when (code) {
                // Command Responses
                0x02 -> log(">> Result: ACK (Success)")
                0x05 -> log(">> Result: NAK (Failed/Rejected)")
                0x0E -> log(">> Status: DISABLED (Inhibited)")

                // Denomination Values (AED)
                0x81, 0x40 -> log(">> AED Accepted: 5 Dirhams")
                0x82, 0x41 -> log(">> AED Accepted: 10 Dirhams")
                0x83, 0x42 -> log(">> AED Accepted: 20 Dirhams")
                0x84, 0x43 -> log(">> AED Accepted: 50 Dirhams")
                0x85, 0x44 -> log(">> AED Accepted: 100 Dirhams")
                0x86, 0x45 -> log(">> AED Accepted: 200 Dirhams")
                0x87, 0x46 -> log(">> AED Accepted: 500 Dirhams")
                0x88, 0x47 -> log(">> AED Accepted: 1000 Dirhams")

                // Device Status Codes
                0x80 -> log("Status: Power Up / Ready")
                0x21 -> log("Status: Bill accepted/stacked")
                0x10 -> log("Status: Bill is in escrow position")
                0x22 -> log("Error: Bill Jam")
                0x24 -> log("Error: Stacker Open/Removed")
            }
        }
    }

    private fun enableBillAcceptor() {
        log("TX >> Sending Enable command (0x3E)...")
        serialPort?.write(byteArrayOf(0x3E))
    }

    private fun disableBillAcceptor() {
        log("TX >> Sending Disable command (0x5E)...")
        serialPort?.write(byteArrayOf(0x5E))
    }

    private fun log(message: String) {
        runOnUiThread {
            val currentText = tvLogs.text.toString()
            tvLogs.text = "$currentText\n$message"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serialPort?.close()
    }
}*/

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import cn.lalaki.SerialPort
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity2 : androidx.activity.ComponentActivity() {

    private val TAG = "ICT_L170_Lalaki"
    private var mSerialPort: SerialPort? = null

    // સિંગલ સીરીયલ થ્રેડ એક્ઝિક્યુટર
    private val singleThreadExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var isRunning = false

    // પ્રોટોકોલ સિંક્રોનાઇઝેશન ફ્લેગ્સ
    private val requestActivation = AtomicBoolean(false)
    @Volatile
    private var isMachineReady = false

    // ICT104U સત્તાવાર હેક્સ કમાન્ડ્સ
    private val CMD_STATUS_POLL = byteArrayOf(0x0C.toByte())
    private val CMD_ACK = byteArrayOf(0x02.toByte())
    private val CMD_ENABLE_ALL_CHANNELS = byteArrayOf(0x3E.toByte())

    lateinit var tvLogs: TextView
    lateinit var bt_allow: Button
    var count: Int = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bt_allow = findViewById<Button>(R.id.bt_allow)
        tvLogs = findViewById(R.id.tvLogs)

        val btnnext = findViewById<Button>(R.id.btnnext)

        btnnext.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        bt_allow.setOnClickListener {
            initLalakiSerial()
        }
    }

    private fun initLalakiSerial() {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("chmod 777 /dev/ttyS4\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
            updateLogs("Root permission allowed.")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val portPath = "/dev/ttyS4"
        val baudRate = 9600

        try {
            mSerialPort = SerialPort(
                portPath,
                baudRate,
                SerialPort.DataBits.CS8,
                SerialPort.StopBits.B1,
                SerialPort.Parity.Even,
                SerialPort.FlowControl.None,
                false,
                false,
                object : SerialPort.DataCallback {
                    override fun onData(data: ByteArray) {
                        if (data != null && data.isNotEmpty()) {
                            for (b in data) {
                                handleIctResponse(b)
                            }
                        }
                    }
                }
            )

            updateLogs("$portPath port open done.")
            startUnifiedSerialLoop()

        } catch (e: Exception) {
            updateLogs("Serial port open error: ${e.message}")
        }
    }

    // સુધારેલું સિંક્રોનાઇઝ્ડ લૂપ
    private fun startUnifiedSerialLoop() {
        if (isRunning) return
        isRunning = true
        isMachineReady = false // શરૂઆતમાં મશીન રેડી મોડની રાહ જોશે

        singleThreadExecutor.execute {
            // પ્રારંભિક જાગૃતિ માટે કમાન્ડ મોકલો
            mSerialPort?.write(CMD_ENABLE_ALL_CHANNELS)
            Thread.sleep(200)

            while (isRunning) {
                try {
                    // જો 0x0E મળ્યો હોય તો જ ઇનલાઇન એક્ટિવેશન રન થશે
                    if (requestActivation.compareAndSet(true, false)) {
                        mSerialPort?.write(CMD_ACK)
                        Thread.sleep(80)
                        mSerialPort?.write(CMD_ENABLE_ALL_CHANNELS)
                        updateLogs("TX >> Enable Channels (0x3E)")
                        Thread.sleep(150)
                    }

                    // પોલિંગ કમાન્ડ (માત્ર મશીન તૈયાર થયા પછી જ ચાલુ થશે જેથી ડેટા ઓવરલેપ ન થાય)
                    mSerialPort?.write(CMD_STATUS_POLL)
                    runOnUiThread {
                        count = count+1
                        bt_allow.text = "CMD_STATUS_POLL " + count
                    }

                    // ICT104U સ્ટાન્ડર્ડ ટાઇમિંગ ગેપ
                    Thread.sleep(130)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleIctResponse(responseByte: Byte) {
        val hexString = String.format("%02X", responseByte)
        updateLogs("DATA : 0x$hexString")

        when (responseByte) {
            0x80.toByte() -> {
                updateLogs("Status: Power On")
                sendAck()
            }

            0x81.toByte() -> {
                updateLogs("Note verification in progress...")
                sendAck() // વેરિફિકેશન પ્રોસેસને તાત્કાલિક સ્વીકારો
            }

            0x10.toByte() -> {
                updateLogs("Bill successfully stacked.")
                sendAck()
            }

            0x29.toByte() -> {
                updateLogs("Error: Bill Rejected (0x29)")
                sendAck() // રિજેક્શન ઇવેન્ટને ACK આપી સિસ્ટમ ક્લિયર કરો
            }

            // કરન્સી ચેનલો ઓળખાયા પછી ફરજિયાત ACK
            0x40.toByte() -> {
                showDenomination("5 AED"); sendAck()
            }

            0x41.toByte() -> {
                showDenomination("10 AED"); sendAck()
            }

            0x42.toByte() -> {
                showDenomination("20 AED"); sendAck()
            }

            0x43.toByte() -> {
                showDenomination("50 AED"); sendAck()
            }

            0x44.toByte() -> {
                showDenomination("100 AED"); sendAck()
            }

            0x45.toByte() -> {
                showDenomination("200 AED"); sendAck()
            }

            0x46.toByte() -> {
                showDenomination("500 AED"); sendAck()
            }

            0x47.toByte() -> {
                showDenomination("1000 AED"); sendAck()
            }

            0x22.toByte() -> updateLogs("Note jam")
            0x23.toByte() -> updateLogs("Return note")
            0x24.toByte() -> updateLogs("Box open")

            0x0E.toByte() -> {
                updateLogs("Status: Machine Disabled. Flagging activation...")
                requestActivation.set(true)
            }

            0x3E.toByte() -> {
                isMachineReady = true
                updateLogs("Status: Machine Ready (Solid Light).")
            }
        }
    }

    private fun sendAck() {
        try {
            mSerialPort?.write(CMD_ACK)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun updateLogs(message: String) {
        runOnUiThread {
            val currentText = tvLogs.text.toString()
            val lines = currentText.split("\n")
            val newText = if (lines.size > 20) {
                lines.drop(1).joinToString("\n") + "\n $message"
            } else {
                "$currentText\n $message"
            }
            tvLogs.text = newText
        }
    }

    private fun showDenomination(amount: String) {
        runOnUiThread {
            Toast.makeText(this, "Payment done : $amount", Toast.LENGTH_LONG).show()
        }
        updateLogs("Payment done : $amount")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            mSerialPort?.close()
        } catch (_: Exception) {
        }
        singleThreadExecutor.shutdown()
    }
}
