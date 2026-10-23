package com.firebridge.aiultra

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.firebridge.aiultra.databinding.ActivityMainBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var server: FireBridgeServer? = null
    private lateinit var keyExecutor: KeyCommandExecutor
    private lateinit var bedrockService: BedrockService

    private val mainHandler = Handler(Looper.getMainLooper())
    private var clockTickerRunnable: Runnable? = null
    private var hudDismissJob: Job? = null

    private var localIpAddress: String = "127.0.0.1"
    private var isHudVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Services
        keyExecutor = KeyCommandExecutor(applicationContext) { this }
        bedrockService = BedrockService()

        setupClockTicker()
        setupListeners()
        initializeNetworkAndServer()
    }

    private fun setupListeners() {
        binding.btnDismissHud.setOnClickListener {
            dismissAiHud()
        }
    }

    /**
     * Programmatically discovers local IP, starts FireBridge embedded server,
     * and generates the QR code pointing to the mobile remote URL.
     */
    private fun initializeNetworkAndServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            localIpAddress = NetworkUtils.getLocalIpAddress(applicationContext)
            val remoteUrl = NetworkUtils.getMobileRemoteUrl(localIpAddress, 8080)

            withContext(Dispatchers.Main) {
                binding.tvDeviceIp.text = localIpAddress
                binding.tvLocalUrl.text = remoteUrl
            }

            // Start embedded dual HTTP & WebSocket server
            startFireBridgeServer()

            // Generate high-resolution QR code
            val qrBitmap = generateQrCodeBitmap(remoteUrl)

            withContext(Dispatchers.Main) {
                binding.pbQrLoading.visibility = View.GONE
                if (qrBitmap != null) {
                    binding.ivQrCode.setImageBitmap(qrBitmap)
                }
            }
        }
    }

    /**
     * Instantiates and launches FireBridgeServer on port 8080.
     */
    private fun startFireBridgeServer() {
        server?.stop()
        server = FireBridgeServer(
            port = 8080,
            internalWsPort = 8081,
            htmlContentSupplier = { loadRemoteHtmlFromAssets() },
            onCommandReceived = { command ->
                handleRemoteCommand(command)
            },
            onClientCountChanged = { count ->
                runOnUiThread {
                    updateClientCountUI(count)
                }
            }
        )
        server?.start()
    }

    /**
     * Dispatches commands received from the mobile web controller.
     */
    private fun handleRemoteCommand(command: RemoteCommand) {
        runOnUiThread {
            when (command) {
                is RemoteCommand.Key -> {
                    keyExecutor.executeKey(command.code)
                    binding.tvLastCommandValue.text = "Key: ${command.code}"
                }
                is RemoteCommand.Swipe -> {
                    keyExecutor.executeKey(command.direction)
                    binding.tvLastCommandValue.text = "Touchpad Swipe: ${command.direction}"
                }
                is RemoteCommand.LaunchApp -> {
                    keyExecutor.launchApp(command.packageName)
                    binding.tvLastCommandValue.text = "Launch: ${command.packageName}"
                }
                is RemoteCommand.AiPrompt -> {
                    binding.tvLastCommandValue.text = "AI Query: \"${command.text}\""
                    processBedrockQuery(command.text)
                }
            }
        }
    }

    /**
     * Sends voice or preset prompt to AWS Bedrock, animates the ambient HUD,
     * and broadcasts the parsed response back to the phone.
     */
    private fun processBedrockQuery(prompt: String) {
        // Show AI HUD card with slide-up animation
        showAiHud(prompt)

        lifecycleScope.launch {
            try {
                val result = bedrockService.queryBedrock(prompt)

                binding.layoutAiThinking.visibility = View.GONE
                binding.scrollAiContent.visibility = View.VISIBLE
                binding.tvAiResponseContent.text = result.content
                binding.tvModelMeta.text = "Amazon Bedrock • Model: ${result.modelId}"
                binding.tvAiTimestamp.text =
                    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

                // Broadcast back to mobile web remote
                server?.broadcastAiResponse(result.prompt, result.content, result.modelId)

                // Auto-dismiss HUD after 25 seconds of display
                scheduleHudAutoDismiss(25000L)
            } catch (e: Exception) {
                binding.layoutAiThinking.visibility = View.GONE
                binding.scrollAiContent.visibility = View.VISIBLE
                binding.tvAiResponseContent.text = "Error communicating with AWS Bedrock: ${e.message}"
            }
        }
    }

    /**
     * Displays the Ambient Glassmorphic AI HUD with a slide-up animation.
     */
    private fun showAiHud(prompt: String) {
        hudDismissJob?.cancel()
        isHudVisible = true

        binding.layoutIdleGuide.visibility = View.GONE
        binding.cardAiHud.visibility = View.VISIBLE

        val slideIn = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in)
        binding.cardAiHud.startAnimation(slideIn)

        binding.tvAiUserPrompt.text = "\"$prompt\""
        binding.layoutAiThinking.visibility = View.VISIBLE
        binding.scrollAiContent.visibility = View.GONE
        binding.tvAiTimestamp.text = "Analyzing…"
    }

    /**
     * Dismisses the AI HUD with a slide-down animation and returns to the ambient idle state.
     */
    private fun dismissAiHud() {
        if (!isHudVisible) return
        isHudVisible = false
        hudDismissJob?.cancel()

        val slideOut = AnimationUtils.loadAnimation(this, R.anim.slide_down_fade_out)
        binding.cardAiHud.startAnimation(slideOut)
        binding.cardAiHud.visibility = View.GONE
        binding.layoutIdleGuide.visibility = View.VISIBLE
    }

    private fun scheduleHudAutoDismiss(delayMs: Long) {
        hudDismissJob?.cancel()
        hudDismissJob = lifecycleScope.launch {
            delay(delayMs)
            dismissAiHud()
        }
    }

    /**
     * Updates connected device counter badge on the TV status bar.
     */
    private fun updateClientCountUI(count: Int) {
        val label = when (count) {
            0 -> getString(R.string.zero_devices)
            1 -> getString(R.string.one_device)
            else -> getString(R.string.multiple_devices, count)
        }
        binding.tvConnectedClients.text = label
    }

    /**
     * Generates a high-contrast QR code bitmap tailored for TV screen readability.
     */
    private fun generateQrCodeBitmap(content: String): Bitmap? {
        return try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"

            val bitMatrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                512,
                512,
                hints
            )

            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            val fgColor = getColor(R.color.qr_foreground)
            val bgColor = getColor(R.color.bg_surface_dark)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) fgColor else bgColor)
                }
            }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Reads remote.html from assets.
     */
    private fun loadRemoteHtmlFromAssets(): String {
        return try {
            assets.open("remote.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            "<!DOCTYPE html><html><body><h1>FireBridge AI Ultra</h1><p>Error loading remote.html</p></body></html>"
        }
    }

    /**
     * Digital clock and live date ticker for TV HUD header.
     */
    private fun setupClockTicker() {
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

        clockTickerRunnable = object : Runnable {
            override fun run() {
                val now = Date()
                binding.tvClockTime.text = timeFormat.format(now)
                binding.tvClockDate.text = dateFormat.format(now)
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.post(clockTickerRunnable!!)
    }

    /**
     * Intercepts Fire TV remote keys (e.g. Back button to dismiss HUD if visible).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isHudVisible) {
            dismissAiHud()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        clockTickerRunnable?.let { mainHandler.removeCallbacks(it) }
        hudDismissJob?.cancel()
        server?.stop()
    }
}
