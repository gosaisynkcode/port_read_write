package com.example.androideasyserialport

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import cn.lalaki.SerialPort
import java.io.DataOutputStream

class MainActivity2 : Activity() {
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
}
