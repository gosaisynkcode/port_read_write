package com.example.androideasyserialport

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import cn.lalaki.SerialPort
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : androidx.activity.ComponentActivity() {

    private var mSerialPort: SerialPort? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val commandExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var isRunning = false

    // CRITICAL FIX: Tracks if a bill transaction is mid-flight to stop polling noise
    @Volatile
    private var isProcessingBill = false

    private val serialLock = Any()

    // ICT104U Protocol Hex Commands
    private val CMD_STATUS_POLL = byteArrayOf(0x0C.toByte())
    private val CMD_ACK = byteArrayOf(0x02.toByte())
    private val CMD_ENABLE_ALL_CHANNELS = byteArrayOf(0x3E.toByte())

    lateinit var tvLogs: TextView
    lateinit var bt_allow: Button
    var count: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)

        bt_allow = findViewById<Button>(R.id.bt_allow)
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
        val baudRate = 9600 // ICT104U Standard: 9600, Even Parity, 8 Data, 1 Stop

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
            startLivePollingLoop()

        } catch (e: Exception) {
            e.printStackTrace()
            updateLogs("Serial port open error: ${e.message}")
        }
    }

    private fun startLivePollingLoop() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            safeWrite(CMD_ENABLE_ALL_CHANNELS)
            Thread.sleep(200)

            while (isRunning) {
                try {
                    // Only poll if we are NOT actively validating a bill
                    if (!isProcessingBill) {
                        safeWrite(CMD_STATUS_POLL)
                        runOnUiThread {
                            count++
                            bt_allow.text = "DATA : CMD_STATUS_POLL $count"
                        }
                    }
                    Thread.sleep(150)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleIctResponse(responseByte: Byte) {
        val hexString = String.format("%02X", responseByte)
        updateLogs("DATA : 0x$hexString")
        runOnUiThread {
            val request1 = CoinRequest(
                "hexString  " + hexString + " responseByte  " + responseByte,
                "1 NUM",
                status = "SUCCESS"
            )
            sendCoinData(request1)
        }

        when (responseByte) {
            0x80.toByte() -> {
                updateLogs("Power On")
                isProcessingBill = false
                sendAck()
            }

            0x81.toByte() -> {
                updateLogs("Note verification in progress...")
                isProcessingBill = true // PAUSE POLLING INSTANTLY
                sendAck()
            }

            0x10.toByte() -> {
                updateLogs("Bill successfully stacked in cashbox.")
                isProcessingBill = false // RESUME POLLING
                sendAck()
            }

            0x29.toByte() -> {
                updateLogs("Error: Bill Rejected (0x29)")
                isProcessingBill = false // RESUME POLLING
                sendAck()
            }

            // Currency Channels
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

            0x22.toByte() -> {
                updateLogs("Note jam"); isProcessingBill = false; sendAck()
            }

            0x23.toByte() -> {
                updateLogs("Return note"); isProcessingBill = false; sendAck()
            }

            0x24.toByte() -> updateLogs("Box open")

            0x0E.toByte() -> {
                updateLogs("Status: Machine Disabled. Waking up...")
                enableBillAcceptor()
            }

            0x3E.toByte() -> {
                updateLogs("Status: Machine Ready.")
            }
        }
    }

    private fun enableBillAcceptor() {
        commandExecutor.execute {
            try {
                updateLogs("TX >> Reset (0x30)")
                safeWrite(byteArrayOf(0x30))
                Thread.sleep(2000)

                safeWrite(CMD_ACK)
                Thread.sleep(100)

                safeWrite(CMD_ENABLE_ALL_CHANNELS)
                updateLogs("Sent 0x3E activation command.")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun sendAck() {
        safeWrite(CMD_ACK)
    }

    private fun safeWrite(data: ByteArray) {
        synchronized(serialLock) {
            try {
                mSerialPort?.write(data)
            } catch (e: IOException) {
                e.printStackTrace()
            }
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        executor.shutdown()
        commandExecutor.shutdown()
    }

    data class CoinRequest(
        val message: String,
        val ling_num: String,
        val status: String
    )

    // ---------------- API ----------------
    //INBOX TARGET URL:
    //https://api.webhookinbox.com/i/VbeFfZzP/in/
    interface ApiService {
        @POST("i/7ltzMnRK/in/")
        fun sendCoin(
            @Body request: CoinRequest
        ): Call<ResponseBody>
    }

    // ---------------- SEND API ----------------

    fun sendCoinData(request: CoinRequest) {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.webhookinbox.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = retrofit.create(ApiService::class.java)


        api.sendCoin(request).enqueue(object : Callback<ResponseBody> {

            override fun onResponse(
                call: Call<ResponseBody>,
                response: Response<ResponseBody>
            ) {

                Toast.makeText(
                    this@MainActivity,
                    "Success : ${response.code()}",
                    Toast.LENGTH_LONG
                ).show()

                Log.e("API", "Success")
            }

            override fun onFailure(
                call: Call<ResponseBody>,
                t: Throwable
            ) {

                Toast.makeText(
                    this@MainActivity,
                    t.message,
                    Toast.LENGTH_LONG
                ).show()

                Log.e("API", t.message ?: "Unknown Error")
            }
        })
    }
}
