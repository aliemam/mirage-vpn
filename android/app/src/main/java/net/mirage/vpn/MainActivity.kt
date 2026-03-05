package net.mirage.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import net.mirage.vpn.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var protocolPrefs: ProtocolPreferences
    private var isConnected = false
    private var isConnecting = false

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
                    val connecting = intent.getBooleanExtra(MirageVpnService.EXTRA_IS_CONNECTING, false)
                    updateUI(connected, status, connecting)
                }
                MirageVpnService.ACTION_PROBE_PROGRESS -> {
                    val current = intent.getIntExtra(MirageVpnService.EXTRA_PROBE_CURRENT, 0)
                    val total = intent.getIntExtra(MirageVpnService.EXTRA_PROBE_TOTAL, 0)
                    binding.statusText.text = getString(R.string.status_probing_detail, current, total)
                }
                MirageVpnService.ACTION_HTTP_PROXY_STATUS -> {
                    val running = intent.getBooleanExtra(MirageVpnService.EXTRA_HTTP_PROXY_RUNNING, false)
                    val address = intent.getStringExtra(MirageVpnService.EXTRA_HTTP_PROXY_ADDRESS)
                    updateProxyUI(running, address)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        protocolPrefs = ProtocolPreferences(this)
        setupUI()
        registerReceiver()
    }

    private fun setupUI() {
        // Load server config and update title
        val config = ServerConfig.load(this)
        if (config.serverName.isNotBlank()) {
            binding.titleText.text = config.displayName
        }

        // Version display
        binding.versionText.text = "v${BuildConfig.VERSION_NAME}"

        // Protocol checkboxes — load saved state
        binding.checkVless.isChecked = protocolPrefs.vlessEnabled
        binding.checkVmess.isChecked = protocolPrefs.vmessEnabled
        binding.checkDnstt.isChecked = protocolPrefs.dnsttEnabled

        binding.checkVless.setOnCheckedChangeListener { _, checked ->
            protocolPrefs.vlessEnabled = checked
        }
        binding.checkVmess.setOnCheckedChangeListener { _, checked ->
            protocolPrefs.vmessEnabled = checked
        }
        binding.checkDnstt.setOnCheckedChangeListener { _, checked ->
            protocolPrefs.dnsttEnabled = checked
        }

        // Connect button — 3 states: Connect / Cancel / Disconnect
        binding.connectButton.setOnClickListener {
            when {
                isConnecting -> cancelConnection()
                isConnected -> stopVpn()
                else -> requestVpnPermission()
            }
        }

        // Proxy switch
        binding.proxySwitch.setOnCheckedChangeListener { _, checked ->
            val action = if (checked) {
                MirageVpnService.ACTION_START_HTTP_PROXY
            } else {
                MirageVpnService.ACTION_STOP_HTTP_PROXY
            }
            val intent = Intent(this, MirageVpnService::class.java).apply {
                this.action = action
            }
            startService(intent)
        }

        // Check if VPN is already running
        isConnected = MirageVpnService.isRunning
        updateUI(isConnected, if (isConnected) getString(R.string.status_connected) else getString(R.string.status_disconnected))
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(MirageVpnService.ACTION_STATUS_UPDATE)
            addAction(MirageVpnService.ACTION_PROBE_PROGRESS)
            addAction(MirageVpnService.ACTION_HTTP_PROXY_STATUS)
        }
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
        updateUI(false, getString(R.string.status_connecting), isConnecting = true)

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

    private fun cancelConnection() {
        val intent = Intent(this, MirageVpnService::class.java).apply {
            action = MirageVpnService.ACTION_CANCEL
        }
        startService(intent)
    }

    private fun updateUI(connected: Boolean, status: String, isConnecting: Boolean = false) {
        this.isConnected = connected
        this.isConnecting = isConnecting || (!connected && (
            status.contains("ing") || status.contains("در حال") ||
            status.contains("Finding") || status.contains("یافتن") ||
            status.contains("Testing") || status.contains("تست")
        ))

        binding.statusText.text = status

        // 3-state button: Connect / Cancel / Disconnect
        binding.connectButton.isEnabled = true
        binding.connectButton.text = when {
            this.isConnecting -> getString(R.string.cancel)
            connected -> getString(R.string.disconnect)
            else -> getString(R.string.connect)
        }

        // Protocol checkboxes: disabled when connecting or connected
        val checkboxEnabled = !connected && !this.isConnecting
        binding.checkVless.isEnabled = checkboxEnabled
        binding.checkVmess.isEnabled = checkboxEnabled
        binding.checkDnstt.isEnabled = checkboxEnabled

        // Proxy section: visible only when connected
        binding.proxySection.visibility = if (connected) View.VISIBLE else View.GONE
        if (!connected) {
            binding.proxySwitch.isChecked = false
            binding.proxyAddressText.visibility = View.GONE
        }

        // Status indicator color
        val colorRes = when {
            connected && !status.contains("lost") && !status.contains("قطع شد") -> R.color.connected_green
            this.isConnecting -> R.color.connecting_yellow
            else -> R.color.disconnected_red
        }
        binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, colorRes))
    }

    private fun updateProxyUI(running: Boolean, address: String?) {
        binding.proxySwitch.isChecked = running
        if (running && address != null) {
            binding.proxyAddressText.text = getString(R.string.proxy_address, address)
            binding.proxyAddressText.visibility = View.VISIBLE
        } else {
            binding.proxyAddressText.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }
}
