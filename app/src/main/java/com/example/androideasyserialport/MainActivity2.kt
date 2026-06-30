package com.example.androideasyserialport

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import cn.lalaki.SerialPort
import com.google.common.hash.HashCode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity2 : Activity() {
    private var mPorts: List<String>? = null
    private var serialPort: SerialPort? = null
    lateinit var scrollView: NestedScrollView
    lateinit var output: TextView
    lateinit var input: EditText
    lateinit var send: Button
    lateinit var send_byte: Button
    lateinit var su_chmod: Button
    lateinit var url: TextView
    lateinit var serialPorts: Spinner
    lateinit var open: Button
    lateinit var close: Button
    lateinit var clear: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scrollView = findViewById(R.id.scrollView)
        output = findViewById(R.id.output)
        input = findViewById(R.id.input)
        send = findViewById(R.id.send)
        send_byte = findViewById(R.id.send_byte)
        su_chmod = findViewById(R.id.su_chmod)
        url = findViewById(R.id.url)
        serialPorts = findViewById(R.id.serialPorts)
        open = findViewById(R.id.open)
        close = findViewById(R.id.close)
        clear = findViewById(R.id.clear)

        val ports = File("/dev/").listFiles { _, s -> s.contains("ttys", ignoreCase = true) }
            ?.sortedBy { it.name }?.map { it.absolutePath }
        if (ports != null) {
            serialPorts.adapter = ArrayAdapter(this, R.layout.item, ports)
            mPorts = ports
        }
        open.setOnClickListener {
            val port = serialPorts.selectedItem?.toString()?.trim()
            if (port?.isNotEmpty() == true) {
                //BaudRate: B115200, see BaudRate value: https://github.com/torvalds/linux/blob/master/include/uapi/asm-generic/termbits.h
                val b115200 = "0010002".toInt(8)
                serialPort =
                    SerialPort(
                        port,
                        b115200,
                        SerialPort.DataBits.CS8,
                        SerialPort.StopBits.B1,
                        SerialPort.Parity.None,
                        SerialPort.FlowControl.None,
                        rts = false,
                        dtr = false,
                        callback = object : SerialPort.DataCallback {
                            override fun onData(data: ByteArray) {
                                val hexStr = HashCode.fromBytes(data).toString()
                                runOnUiThread {
                                    input.requestFocus()
                                    output.text =
                                        "${mFormat.format(Date())}${hexStr}\n${output.text}"
                                    if (output.length() > 4000) {
                                        output.text =
                                            output.text.substring(0, 3000)
                                    }
                                }
                            }
                        })
                // p0.visibility = View.GONE
                close.visibility = View.VISIBLE
            }
        }
        su_chmod.setOnClickListener {
            thread {
                val ports = mPorts
                if (ports != null) {
                    for (it in ports) {
                        Runtime.getRuntime()
                            .exec(arrayOf("su", "-c", "setenforce 0 && chmod -R 0777 $it"))
                    }
                }
            }
        }

        send_byte.setOnClickListener {
            val finalSerialPort = serialPort
            if (finalSerialPort != null) {
                //Hex encode
                val trimText =
                    input.text.toString().replace("\\s*".toRegex(), "").lowercase()
                try {
                    val hexData = HashCode.fromString(trimText).asBytes()
                    // Write Hex data:
                    val byte = hexData.first()
                    serialPort?.write(byteArrayOf(byte))
                    output.text =
                        "${mFormat.format(Date())}${byte.toHexString()}\n${output.text}"
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(this, "Wrong hex string!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        send.setOnClickListener {
            if (serialPort == null) {
                Toast.makeText(this, "Serial port not open", Toast.LENGTH_SHORT).show()

            }
            //Hex encode
            val trimText =
                input.text.toString().replace("\\s*".toRegex(), "").lowercase()
            try {
                val hexData = HashCode.fromString(trimText).asBytes()
                // Write Hex data:
                serialPort?.write(hexData)
                output.text =
                    "${mFormat.format(Date())}${trimText}\n${output.text}"
            } catch (_: IllegalArgumentException) {
                Toast.makeText(this, "Wrong hex string!", Toast.LENGTH_SHORT).show()
            }
        }
        send.setOnClickListener {
            if (serialPort == null) {
                Toast.makeText(this, "Serial port not open", Toast.LENGTH_SHORT).show()
                //return
            }
            //Hex encode
            val trimText =
                input.text.toString().replace("\\s*".toRegex(), "").lowercase()
            try {
                val hexData = HashCode.fromString(trimText).asBytes()
                // Write Hex data:
                serialPort?.write(hexData)
                output.text =
                    "${mFormat.format(Date())}${trimText}\n${output.text}"
            } catch (_: IllegalArgumentException) {
                Toast.makeText(this, "Wrong hex string!", Toast.LENGTH_SHORT).show()
            }
        }
        clear.setOnClickListener {
            output.text = ""
        }
        close.setOnClickListener {
            serialPort?.close()
            serialPort = null
            close.visibility = View.GONE
            open.visibility = View.VISIBLE
        }
        url.setOnClickListener {
            val url = url.text.toString()
            //Log.e("url", url)
            val intent = Intent.getIntentOld(url).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    private val mFormat = SimpleDateFormat("HH:mm:ss\r\n")
    override fun onDestroy() {
        super.onDestroy()
        serialPort?.close()
        serialPort = null
    }
}