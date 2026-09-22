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
    private val executor = Executors.newSingleThreadExecutor()
    private var isRunning = false

    // ICT104U પ્રોટોકોલ હેક્સ કમાન્ડ્સ
    private val CMD_STATUS_POLL = byteArrayOf(0x0C.toByte())
    private val CMD_ACK = byteArrayOf(0x02.toByte())
    lateinit var tvLogs: TextView
    private val CMD_ENABLE_ALL_CHANNELS = byteArrayOf(0x3E.toByte()) // Wakes the device up
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)

        val bt_allow = findViewById<Button>(R.id.bt_allow)
        tvLogs = findViewById(R.id.tvLogs)
        bt_allow.setOnClickListener {
            // ૧. લાલકી લાઇબ્રેરીની મદદથી સિરીયલ પોર્ટ ઓપન કરો
            initLalakiSerial()
        }
    }

    private fun initLalakiSerial() {
        val process = Runtime.getRuntime().exec("su")
        val os = DataOutputStream(process.outputStream)
        os.writeBytes("chmod 777 /dev/ttyS4\n")
        os.writeBytes("exit\n")
        os.flush()
        process.waitFor()
        val currentText = tvLogs.text.toString()
        tvLogs.text = "$currentText\n allow root permison"
        // વિન્ડોઝનું COM4 એન્ડ્રોઇડમાં સામાન્ય રીતે /dev/ttyS4 અથવા /dev/ttyUSB0 હોઈ શકે છે
        val portPath = "/dev/ttyS4"
        val baudRate = 9600

        val rts = false
        val dtr = false

        try {
            // ઇવેન્ટ ડ્રાઇવન ડેટા કોલબેક સાથે ઓબ્જેક્ટ બનાવો
            mSerialPort = SerialPort(
                portPath,
                baudRate,
                SerialPort.DataBits.CS8,
                SerialPort.StopBits.B1,
                SerialPort.Parity.Even,
                SerialPort.FlowControl.None,
                rts,
                dtr,
                object : SerialPort.DataCallback {
                    override fun onData(data: ByteArray) {
                        if (data != null && data.isNotEmpty()) {
                            // જ્યારે પણ બિલ એક્સેપ્ટર ડેટા મોકલશે ત્યારે આ રન થશે
                            handleIctResponse(data[0])
                        }
                    }
                }
            )
            runOnUiThread {
                val currentText = tvLogs.text.toString()
                tvLogs.text = "$currentText\n $portPath port open done ."
            }
            Log.d(TAG, "$portPath port open done .")
            startLivePollingLoop()

        } catch (e: Exception) {
            Log.e(TAG, "Serial port open  error: ${e.message}")
            runOnUiThread {
                val currentText = tvLogs.text.toString()
                tvLogs.text = "$currentText\n Serial port open  error: ${e.message}"
            }
        }
    }

    // ૨. દર ૨૦૦ મિલિસેકન્ડે બિલ એક્સેપ્ટરને પોલ કરવાનું લૂપ
    private fun startLivePollingLoop() {
        isRunning = true
        executor.execute {
            while (isRunning) {
                try {
                    // મશીનને જગાડવા માટે 0x0C કમાન્ડ રાઇટ કરો
                    mSerialPort?.write(CMD_STATUS_POLL)

                    // ૨૦૦ms નો વેઇટ ટાઇમ
                    Thread.sleep(2000)
                } catch (e: Exception) {
                    Log.e(TAG, "Poll Error: ${e.message}")
                    runOnUiThread {
                        val currentText = tvLogs.text.toString()
                        tvLogs.text = "$currentText\n Poll Error : " + { e.message }
                    }
                }
            }
        }
    }

    // ૩. ICT104U પ્રોટોકોલ બાઈટ હેન્ડલર (AED વેરિફિકેશન)
    private fun handleIctResponse(responseByte: Byte) {
        val hexString = String.format("%02X", responseByte)
        Log.d(TAG, "મળેલ ડેટા: 0x$hexString")

        runOnUiThread {
            val currentText = tvLogs.text.toString()
            tvLogs.text = "$currentText\n DATA : 0x$hexString"
        }
        when (responseByte) {
            0x80.toByte() -> {
                Log.d(TAG, "Power On")
                runOnUiThread {
                    val currentText = tvLogs.text.toString()
                    tvLogs.text = "$currentText\n Power On"
                }
                sendAck()
            }

            0x81.toByte() -> {
                Log.d(TAG, "Note verification in progress ")
                runOnUiThread {
                    val currentText = tvLogs.text.toString()
                    tvLogs.text = "$currentText\n Note verification in progress"
                }
                // લાલકી લાઈબ્રેરી ડેટા સ્ટ્રીમમાં આગળનો બાઈટ આપમેળે ઓનડેટા (onData) માં મોકલશે
                // તે બાઈટ જો 0x40 થી 0x47 ની વચ્ચે હોય તો તે નોટની કિંમત દર્શાવે છે
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

            0x22.toByte() -> {
                Log.w(TAG, "Note jam")
                runOnUiThread {
                    val currentText = tvLogs.text.toString()
                    tvLogs.text = "$currentText\n Note jam"
                }
            }

            0x23.toByte() -> {
                Log.d(TAG, "return note")
                runOnUiThread {
                    val currentText = tvLogs.text.toString()
                    tvLogs.text = "$currentText\n return note"
                }
            }

            0x24.toByte() -> {
                Log.w(TAG, "Box open")
                runOnUiThread {
                    val currentText = tvLogs.text.toString()
                    tvLogs.text = "$currentText\n Box open"
                }
            }

            0x0E.toByte() -> {
                Log.w(TAG, "Status: Machine is INHIBITED (Disabled). Sending wake-up command...")
                // The machine is asleep. Send the enable command to turn lights ON and accept bills.
                enableBillAcceptor()
            }

            0x3E.toByte() -> {
                Log.i(TAG, "Status: Machine is ENABLED and READY to accept AED bills.")
                // Standard standby echo response to 0x0C polling
            }
        }
    }

    private fun showDenomination(amount: String) {
        runOnUiThread {
            Toast.makeText(this, "Payment done : $amount", Toast.LENGTH_LONG).show()
            val currentText = tvLogs.text.toString()
            tvLogs.text = "$currentText\n Payment done : $amount"
        }

        Log.i(TAG, "--> $amount add")
    }

    // 3. Add this function to transmit the wake-up instruction
    private fun enableBillAcceptor() {
        try {
            mSerialPort?.write(byteArrayOf(0x02.toByte()))
            Thread.sleep(100)
            // Sends 0x3E to clear the 0x0E inhibit lock
            mSerialPort?.write(CMD_ENABLE_ALL_CHANNELS)
            Log.d(TAG, "Sent 0x3E activation command to serial line.")
            val currentText = tvLogs.text.toString()
            tvLogs.text = "$currentText\n Sent 0x3E activation command to serial line."
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send enable command: ${e.message}")
            val currentText = tvLogs.text.toString()
            tvLogs.text = "$currentText\n Failed to send enable command: ${e.message}"
        }
    }

    private fun sendAck() {
        try {
            mSerialPort?.write(CMD_ACK)
        } catch (e: IOException) {
            Log.e(TAG, "ACK Error: ${e.message}")
            runOnUiThread {
                val currentText = tvLogs.text.toString()
                tvLogs.text = "$currentText\n ACK Error: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            mSerialPort?.close() // પોર્ટ બંધ કરો
        } catch (_: Exception) {
        }
        executor.shutdown()
    }
}
