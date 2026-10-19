package com.example.androideasyserialport

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import com.example.androideasyserialport.MainActivity2.CoinRequest
import com.example.androideasyserialport.databinding.ActivityPosAvtivityBinding
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

class POS_Avtivity : AppCompatActivity(), AcsReaderManager.Listener {

    private lateinit var binding: ActivityPosAvtivityBinding
    private lateinit var acsReaderManager: AcsReaderManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPosAvtivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        initAcsReader()
    }

    private fun initViews() {
        binding.btnConnect.setOnClickListener {
            updateLogs("Scanning USB ports for ACS ACR1252U...")
            acsReaderManager.scanAndConnect()
        }

        binding.btnReadUid.setOnClickListener {
            val uid = acsReaderManager.readCardUid()
            if (uid != null && uid != "NO_CARD") {
                binding.tvCardUid.text = "CARD UID: $uid"
                updateLogs("Manual Read UID Success >> $uid")
            } else {
                updateLogs("No card detected on reader slot. Please tap a card.")
            }
        }

        binding.btnReadMifare.setOnClickListener {
            readMifareCardBlock()
        }

        binding.btnClearLogs.setOnClickListener {
            binding.tvLogs.text = "Logs cleared.\n"
            val request1 = CoinRequest(
                " Logs cleared.\n",
                "1 NUM",
                status = "SUCCESS"
            )
            sendCoinData(request1)

        }

        binding.tvCardUid.setOnClickListener {
            val text = binding.tvCardUid.text.toString().replace("CARD UID: ", "").trim()
            if (text.isNotEmpty() && text != "--") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Card UID", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied UID: $text", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initAcsReader() {
        acsReaderManager = AcsReaderManager(this, this)
        acsReaderManager.scanAndConnect()
    }

    private fun readMifareCardBlock() {
        updateLogs("--- Starting MIFARE Read Block 4 APDU Transaction ---")

        // 1. Load Authentication Key (Key A = FF FF FF FF FF FF)
        val loadKeyApdu = "FF 82 00 00 06 FF FF FF FF FF FF"
        val loadKeyResp = acsReaderManager.transmitApduHex(loadKeyApdu)
        updateLogs("Load Key Response: $loadKeyResp")

        // 2. Authenticate Sector 1 / Block 4
        val authApdu = "FF 86 00 00 05 01 00 04 60 00"
        val authResp = acsReaderManager.transmitApduHex(authApdu)
        updateLogs("Auth Response: $authResp")

        // 3. Read 16 Bytes from Block 4
        val readApdu = "FF B0 00 04 10"
        val readDataResp = acsReaderManager.transmitApduHex(readApdu)

        updateLogs("Block 4 Data Result: $readDataResp")
    }

    // --- AcsReaderManager.Listener Callbacks ---

    override fun onReaderConnected(deviceName: String, vidHex: String, pidHex: String) {
        binding.tvReaderStatus.text = "POS: Connected ($deviceName)"+"\n"+"VID: 0x$vidHex | PID: 0x$pidHex | Status: READY"
        updateLogs("ACS ACR1252U Reader Connected & Powered ON!")
    }

    override fun onReaderDisconnected() {
        binding.tvReaderStatus.text = "POS: Disconnect"+"\n"+"ACS ACR1252U"
        binding.tvCardUid.text = "CARD UID: --"
        binding.tvCardAtr.text = "ATR / ATS: --"
        binding.tvCardType.text = "Type: Waiting for card scan..."
        updateLogs("Reader Disconnected. Please plug in USB reader.")
    }

    override fun onCardDetected(uidHex: String, atrHex: String, cardType: String) {
        binding.tvCardUid.text = "CARD UID: $uidHex"
        binding.tvCardAtr.text = "ATR / ATS: $atrHex"
        binding.tvCardType.text = "Type: $cardType"
        Toast.makeText(this, "Card Detected: $uidHex", Toast.LENGTH_SHORT).show()
    }

    override fun onCardRemoved() {
        binding.tvCardUid.text = "CARD UID: --"
        binding.tvCardAtr.text = "ATR / ATS: --"
        binding.tvCardType.text = "Type: Waiting for card scan..."
        updateLogs("Card removed from reader surface.")
    }

    override fun onLog(message: String) {
        updateLogs(message)
    }

    private fun updateLogs(message: String) {
        val current = binding.tvLogs.text.toString()
        val lines = current.split("\n")
        val updated = if (lines.size > 150) {
            lines.drop(lines.size - 150).joinToString("\n") + "\n $message"
        } else {
            "$current\n $message"
        }
        binding.tvLogs.text = updated
        binding.scrollViewLogs.post {
            binding.scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN)
        }
        val request1 = CoinRequest(
            " Logs "+updated,
            "2 NUM",
            status = "SUCCESS"
        )
        sendCoinData(request1)
    }

    override fun onDestroy() {
        super.onDestroy()
        acsReaderManager.unregister()
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
      /*  val retrofit = Retrofit.Builder()
            .baseUrl("https://api.webhookinbox.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = retrofit.create(ApiService::class.java)


        api.sendCoin(request).enqueue(object : Callback<ResponseBody> {

            override fun onResponse(
                call: Call<ResponseBody>,
                response: Response<ResponseBody>
            ) {
            }

            override fun onFailure(
                call: Call<ResponseBody>,
                t: Throwable
            ) {


            }
        })*/
    }
}
