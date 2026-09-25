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

    // કમાન્ડ મોકલવા માટે અલગ થ્રેડ
    private val commandExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var isRunning = false

    // ICT104U પ્રોટોકોલ હેક્સ કમાન્ડ્સ
    private val CMD_STATUS_POLL = byteArrayOf(0x0C.toByte())
    private val CMD_ACK = byteArrayOf(0x02.toByte())
    private val CMD_ENABLE_ALL_CHANNELS = byteArrayOf(0x3E.toByte())

    lateinit var tvLogs: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
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
                            // સીરીયલ ડેટામાં ક્યારેક એકસાથે બાઇટ્સ આવી શકે છે, તેથી લૂપ ફરજિયાત છે
                            for (b in data) {
                                handleIctResponse(b)
                            }
                        }
                    }
                }
            )

            updateLogs("$portPath port open done.")
            //Log.d(TAG, "$portPath port open done.")
            startLivePollingLoop()

        } catch (e: Exception) {
           // Log.e(TAG, "Serial port open error: ${e.message}")
            updateLogs("Serial port open error: ${e.message}")
        }
    }

    // સુધારેલું લૂપ: વેરિફિકેશન વખતે પણ પોલિંગ ક્યારેય અટકશે નહીં
    private fun startLivePollingLoop() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            // પ્રારંભિક અવસ્થામાં મશીનને જગાડવા માટે એકવાર 0x3E મોકલો
            mSerialPort?.write(CMD_ENABLE_ALL_CHANNELS)

            while (isRunning) {
                try {
                    // કરન્સી દાખલ થાય ત્યારે પણ 0x0C પોલિંગ સતત ચાલુ જ રહેશે
                    mSerialPort?.write(CMD_STATUS_POLL)
                    updateLogs("DATA : CMD_STATUS_POLL")
                    Thread.sleep(150) // સ્ટાન્ડર્ડ ૨૦૦ms નો વેઇટ ટાઇમ
                } catch (e: Exception) {
                    e.printStackTrace()
                  //  Log.e(TAG, "Poll Error: ${e.message}")
                }
            }
        }
    }

    private fun handleIctResponse(responseByte: Byte) {
        val hexString = String.format("%02X", responseByte)
        //Log.d(TAG, "મળેલ ડેટા: 0x$hexString")
        updateLogs("DATA : 0x$hexString")

        when (responseByte) {
            0x80.toByte() -> {
                updateLogs("Power On")
                sendAck()
            }

            0x81.toByte() -> {
                updateLogs("Note verification in progress...")
                sendAck() // વેરિફિકેશન પ્રોસેસને એક્નોલેજ (ACK) કરો
            }

            0x10.toByte() -> {
                updateLogs("Bill successfully stacked in cashbox.")
                sendAck()
            }

            0x29.toByte() -> {
                updateLogs("Error: Bill Rejected (0x29)")
                sendAck() // રીજેક્શન રિસ્પોન્સને પણ ACK આપો જેથી મશીન આઇડલ મોડમાં આવે
            }

            // દરેક કરન્સી ચેનલ ઓળખાયા પછી તાત્કાલિક sendAck() આપવું અનિવાર્ય છે
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
                updateLogs("Status: Machine Disabled. Waking up...")
                enableBillAcceptor()
            }

            0x3E.toByte() -> {
                updateLogs("Status: Machine Ready (Solid Light).")

            }
        }
    }

    private fun enableBillAcceptor() {
        commandExecutor.execute {
            try {
                //New Code Add
                updateLogs("TX >> Reset (0x30)")
                mSerialPort?.write(byteArrayOf(0x30))

                // CRITICAL: Wait 2 seconds for the validator to fully boot up
                Thread.sleep(2000)

                //New Code End

                mSerialPort?.write(CMD_ACK)
                Thread.sleep(60) // સેફ ગેપ 60
                // 0x0E મળવા પર મશીનને એક્ટિવેટ કરવા સીધો 0x3E ફાયર કરો
                mSerialPort?.write(CMD_ENABLE_ALL_CHANNELS)

                updateLogs("Sent 0x3E activation command.")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun sendAck() {
        try {
            mSerialPort?.write(CMD_ACK)
        } catch (e: IOException) {
            e.printStackTrace()
            //Log.e(TAG, "ACK Error: ${e.message}")
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
        //Log.i(TAG, "--> $amount add")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            mSerialPort?.close()
        } catch (_: Exception) {
        }
        executor.shutdown()
        commandExecutor.shutdown()
    }
}
