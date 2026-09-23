package com.example.androideasyserialport

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import cn.lalaki.SerialPort
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : androidx.activity.ComponentActivity() {

    private val TAG = "ICT_L170_Lalaki"
    private var mSerialPort: SerialPort? = null
    // પોલિંગ માટે અલગ થ્રેડ
    private val executor = Executors.newSingleThreadExecutor()
    // કમાન્ડ મોકલવા માટે અલગ થ્રેડ (જેથી લૂપ બ્લોક ન થાય)
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private var isRunning = false

    // ICT104V પ્રોટોકોલ હેક્સ કમાન્ડ્સ
    private val CMD_STATUS_POLL = byteArrayOf(0x0C.toByte())
    private val CMD_ACK = byteArrayOf(0x02.toByte())
    private val CMD_ENABLE_ALL_CHANNELS = byteArrayOf(0x3E.toByte())

    lateinit var tvLogs: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        super.onCreate(savedInstanceState)
        super.onCreate(savedInstanceState)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)

        val bt_allow = findViewById<Button>(R.id.bt_allow)
        tvLogs = findViewById(R.id.tvLogs)

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
            Log.e(TAG, "Root Error: ${e.message}")
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
                            handleIctResponse(data[0])
                        }
                    }
                }
            )

            updateLogs("$portPath port open done.")
            Log.d(TAG, "$portPath port open done.")
            startLivePollingLoop()

        } catch (e: Exception) {
            Log.e(TAG, "Serial port open error: ${e.message}")
            updateLogs("Serial port open error: ${e.message}")
        }
    }

    // સુધારેલું લૂપ: હવે દર ૨૦૦ મિલિસેકન્ડે (200ms) પરફેક્ટ રન થશે
    private fun startLivePollingLoop() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            while (isRunning) {
                try {
                    mSerialPort?.write(CMD_STATUS_POLL)
                    // ICT સ્ટાન્ડર્ડ મુજબ ૨૦૦ms નો વેઇટ ટાઇમ ફરજિયાત છે
                    Thread.sleep(200)
                } catch (e: Exception) {
                    Log.e(TAG, "Poll Error: ${e.message}")
                }
            }
        }
    }

    private fun handleIctResponse(responseByte: Byte) {
        val hexString = String.format("%02X", responseByte)
        Log.d(TAG, "મળેલ ડેટા: 0x$hexString")
        updateLogs("DATA : 0x$hexString")

        when (responseByte) {
            0x80.toByte() -> {
                updateLogs("Power On")
                sendAck()
            }

            0x81.toByte() -> {
                updateLogs("Note verification in progress")
                sendAck()
            }

            0x40.toByte() -> showDenomination("5 AED")
            0x41.toByte() -> showDenomination("10 AED")
            0x42.toByte() -> showDenomination("20 AED")
            0x43.toByte() -> showDenomination("50 AED")
            0x44.toByte() -> showDenomination("100 AED")
            0x45.toByte() -> showDenomination("200 AED")
            0x46.toByte() -> showDenomination("500 AED")
            0x47.toByte() -> showDenomination("1000 AED")

            0x22.toByte() -> updateLogs("Note jam")
            0x23.toByte() -> updateLogs("Return note")
            0x24.toByte() -> updateLogs("Box open")

            0x0E.toByte() -> {
                updateLogs("Status: Machine Disabled. Waking up...")
                enableBillAcceptor()
            }

            0x3E.toByte() -> {
                Log.i(TAG, "Status: Machine Ready (Solid Light).")
            }
        }
    }

    // નવું ફંક્શન: બેકગ્રાઉન્ડ થ્રેડમાં સેફલી કમાન્ડ ફાયર કરશે
    private fun enableBillAcceptor() {
        commandExecutor.execute {
            try {
                mSerialPort?.write(CMD_ACK)
                Thread.sleep(60) // સેફ ગેપ
                mSerialPort?.write(CMD_ENABLE_ALL_CHANNELS)
                Log.d(TAG, "Sent 0x3E activation command.")
                updateLogs("Sent 0x3E activation command.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send enable command: ${e.message}")
            }
        }
    }

    private fun sendAck() {
        try {
            mSerialPort?.write(CMD_ACK)
        } catch (e: IOException) {
            Log.e(TAG, "ACK Error: ${e.message}")
        }
    }

    private fun updateLogs(message: String) {
        runOnUiThread {
            val currentText = tvLogs.text.toString()
            // લોગ લાઈન લિમિટ સેટ કરો જેથી મેમરી ફૂલ ન થાય
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
        Log.i(TAG, "--> $amount add")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            mSerialPort?.close()
        } catch (_: Exception) {}
        executor.shutdown()
        commandExecutor.shutdown()
    }
}
