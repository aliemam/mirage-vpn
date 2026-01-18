package net.mirage.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import net.mirage.vpn.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isConnected = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpn()
        } else {
            Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MirageVpnService.ACTION_STATUS_UPDATE -> {
                    val status = intent.getStringExtra(MirageVpnService.EXTRA_STATUS) ?: return
                    val connected = intent.getBooleanExtra(MirageVpnService.EXTRA_CONNECTED, false)
                    updateUI(connected, status)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        registerReceiver()
    }

    private fun setupUI() {
        binding.connectButton.setOnClickListener {
            if (isConnected) {
                stopVpn()
            } else {
                requestVpnPermission()
            }
        }

        // Check if VPN is already running
        isConnected = MirageVpnService.isRunning
        updateUI(isConnected, if (isConnected) getString(R.string.status_connected) else getString(R.string.status_disconnected))
    }

    private fun registerReceiver() {
        val filter = IntentFilter(MirageVpnService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        updateUI(false, getString(R.string.status_connecting))

        val intent = Intent(this, MirageVpnService::class.java).apply {
            action = MirageVpnService.ACTION_CONNECT
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpn() {
        updateUI(true, getString(R.string.status_disconnecting))

        val intent = Intent(this, MirageVpnService::class.java).apply {
            action = MirageVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun updateUI(connected: Boolean, status: String) {
        isConnected = connected

        binding.statusText.text = status
        binding.connectButton.text = if (connected) {
            getString(R.string.disconnect)
        } else {
            getString(R.string.connect)
        }

        val colorRes = when {
            status.contains("متصل") || status.contains("Connected") -> R.color.connected_green
            status.contains("در حال") || status.contains("ing") -> R.color.connecting_yellow
            else -> R.color.disconnected_red
        }
        binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, colorRes))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }
}
