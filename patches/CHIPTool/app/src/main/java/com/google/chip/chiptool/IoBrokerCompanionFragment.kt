package com.google.chip.chiptool

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.InvokeCallback
import chip.devicecontroller.OpenCommissioningCallback
import chip.devicecontroller.UnpairDeviceCallback
import chip.devicecontroller.model.InvokeElement
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.chip.chiptool.util.DeviceIdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import matter.tlv.AnonymousTag
import matter.tlv.TlvWriter
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class IoBrokerCompanionFragment : Fragment() {

    private val deviceController: ChipDeviceController
        get() = ChipClient.getDeviceController(requireContext())

    private lateinit var stickIpEd: TextInputEditText
    private lateinit var fetchCredentialsBtn: MaterialButton
    private lateinit var threadInfoCard: MaterialCardView
    private lateinit var threadNameTv: TextView
    private lateinit var threadChannelTv: TextView
    private lateinit var threadKeyTv: TextView
    private lateinit var startCommissioningBtn: MaterialButton

    private lateinit var iobrokerIpEd: TextInputEditText
    private lateinit var iobrokerPortEd: TextInputEditText
    private lateinit var testIobrokerBtn: MaterialButton

    private lateinit var tabPairingLayout: View
    private lateinit var tabDevicesLayout: RecyclerView
    private lateinit var tabLayout: TabLayout

    private lateinit var deviceAdapter: DeviceAdapter

    // Gespeicherte Thread-Daten
    private var fetchedChannel: Int? = null
    private var fetchedPanIdHex: String? = null
    private var fetchedExtPanId: String? = null
    private var fetchedNetworkKey: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_iobroker_companion, container, false)

        stickIpEd = view.findViewById(R.id.stickIpEd)
        fetchCredentialsBtn = view.findViewById(R.id.fetchCredentialsBtn)
        threadInfoCard = view.findViewById(R.id.threadInfoCard)
        threadNameTv = view.findViewById(R.id.threadNameTv)
        threadChannelTv = view.findViewById(R.id.threadChannelTv)
        threadKeyTv = view.findViewById(R.id.threadKeyTv)
        startCommissioningBtn = view.findViewById(R.id.startCommissioningBtn)

        iobrokerIpEd = view.findViewById(R.id.iobrokerIpEd)
        iobrokerPortEd = view.findViewById(R.id.iobrokerPortEd)
        testIobrokerBtn = view.findViewById(R.id.testIobrokerBtn)

        tabPairingLayout = view.findViewById(R.id.tabPairingLayout)
        tabDevicesLayout = view.findViewById(R.id.tabDevicesLayout)
        tabLayout = view.findViewById(R.id.tabLayout)

        // Setup RecyclerView
        tabDevicesLayout.layoutManager = LinearLayoutManager(requireContext())
        deviceAdapter = DeviceAdapter(
            emptyList(),
            onToggle = { nodeId, isChecked -> sendOnOffCommand(nodeId, isChecked) },
            onShare = { nodeId -> shareDevice(nodeId) },
            onUnpair = { nodeId -> unpairDevice(nodeId) }
        )
        tabDevicesLayout.adapter = deviceAdapter

        // Load cached IP
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("stick_ip", "192.168.179.148")
        stickIpEd.setText(savedIp)

        val savedIobrokerIp = prefs.getString("iobroker_ip", "")
        val savedIobrokerPort = prefs.getString("iobroker_port", "8087")
        iobrokerIpEd.setText(savedIobrokerIp)
        iobrokerPortEd.setText(savedIobrokerPort)

        // Setup Button Listeners
        fetchCredentialsBtn.setOnClickListener { fetchThreadCredentials() }
        startCommissioningBtn.setOnClickListener { startScanning() }

        testIobrokerBtn.setOnClickListener {
            val ip = iobrokerIpEd.text.toString().trim()
            val port = iobrokerPortEd.text.toString().trim()
            if (ip.isBlank() || port.isBlank()) {
                Toast.makeText(requireContext(), "Bitte IP und Port ausfüllen!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("iobroker_ip", ip).putString("iobroker_port", port).apply()

            viewLifecycleOwner.lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    try {
                        val url = URL("http://$ip:$port/get/system.adapter.matter.0.alive")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        conn.responseCode == 200
                    } catch (e: Exception) {
                        false
                    }
                }
                if (success) {
                    Toast.makeText(requireContext(), "Verbindung erfolgreich! Matter-Adapter läuft.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Verbindung fehlgeschlagen! IP/Port prüfen.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Setup Tabs
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab?.position == 0) {
                    tabPairingLayout.visibility = View.VISIBLE
                    tabDevicesLayout.visibility = View.GONE
                } else {
                    tabPairingLayout.visibility = View.GONE
                    tabDevicesLayout.visibility = View.VISIBLE
                    refreshDeviceList()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Auto-fetch if IP is loaded
        if (!savedIp.isNullOrBlank()) {
            fetchThreadCredentials()
        }

        return view
    }

    private fun fetchThreadCredentials() {
        val ip = stickIpEd.text.toString().trim()
        if (ip.isBlank()) {
            Toast.makeText(requireContext(), "Bitte IP-Adresse eingeben", Toast.LENGTH_SHORT).show()
            return
        }

        // Cache IP
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("stick_ip", ip).apply()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = URL("http://$ip:8080/node/dataset/active")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    if (conn.responseCode == 200) {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e("Companion", "Fetch error", e)
                    null
                }
            }

            if (result != null) {
                try {
                    val json = JSONObject(result)
                    val name = json.optString("NetworkName", "Unbekannt")
                    val channel = json.optInt("Channel", 15)
                    val panIdDec = json.optInt("PanId", 4660)
                    val panIdHex = panIdDec.toString(16)
                    val extPanId = json.optString("ExtPanId", "")
                    val networkKey = json.optString("NetworkKey", "")

                    // Save in variables
                    fetchedChannel = channel
                    fetchedPanIdHex = panIdHex
                    fetchedExtPanId = extPanId
                    fetchedNetworkKey = networkKey

                    // Cache in app preferences for Scanner activity
                    prefs.edit()
                        .putInt("fetched_channel", channel)
                        .putString("fetched_pan_id", panIdHex)
                        .putString("fetched_ext_pan_id", extPanId)
                        .putString("fetched_network_key", networkKey)
                        .apply()

                    threadNameTv.text = "Netzwerkname: $name"
                    threadChannelTv.text = "Kanal: $channel (PAN ID: $panIdHex)"
                    threadKeyTv.text = "Key: $networkKey"
                    threadInfoCard.visibility = View.VISIBLE

                    Toast.makeText(requireContext(), "Stick-Daten geladen!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Fehler beim Parsen der Daten", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Verbindung fehlgeschlagen (IP prüfen)", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startScanning() {
        if (fetchedChannel == null) {
            Toast.makeText(requireContext(), "Bitte zuerst Stick-Daten abrufen!", Toast.LENGTH_SHORT).show()
            return
        }
        val activity = requireActivity() as CHIPToolActivity
        activity.setNetworkType(com.google.chip.chiptool.provisioning.ProvisionNetworkType.THREAD)

        activity.supportFragmentManager
            .beginTransaction()
            .replace(R.id.nav_host_fragment, com.google.chip.chiptool.setuppayloadscanner.BarcodeFragment.newInstance(), "BarcodeFragment")
            .addToBackStack(null)
            .commit()
    }

    private fun refreshDeviceList() {
        val nodeList = DeviceIdUtil.getCommissionedNodeId(requireContext())
        val nodeIds = nodeList.mapNotNull {
            try {
                it.toLong(16)
            } catch (e: Exception) {
                null
            }
        }
        deviceAdapter.updateDevices(nodeIds)
    }

    private fun sendOnOffCommand(nodeId: Long, turnOn: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val devicePointer = withContext(Dispatchers.IO) {
                    ChipClient.getConnectedDevicePointer(requireContext(), nodeId)
                }
                val tlvWriter = TlvWriter()
                tlvWriter.startStructure(AnonymousTag)
                tlvWriter.endStructure()
                val commandId = if (turnOn) 1L else 0L
                val invokeElement = InvokeElement.newInstance(
                    1,
                    6L,
                    commandId,
                    tlvWriter.getEncoded(),
                    null
                )
                deviceController.invoke(
                    object : InvokeCallback {
                        override fun onError(ex: Exception?) {
                            Log.e("Companion", "Switch cmd failed", ex)
                        }
                        override fun onResponse(invokeElement: InvokeElement?, successCode: Long) {
                            Log.i("Companion", "Switch cmd success")
                        }
                    },
                    devicePointer,
                    invokeElement,
                    0,
                    0
                )
            } catch (e: Exception) {
                Log.e("Companion", "OnOff command failed", e)
            }
        }
    }

    private fun shareDevice(nodeId: Long) {
        val iobrokerIp = iobrokerIpEd.text.toString().trim()
        val iobrokerPort = iobrokerPortEd.text.toString().trim()
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("iobroker_ip", iobrokerIp).putString("iobroker_port", iobrokerPort).apply()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val devicePointer = withContext(Dispatchers.IO) {
                    ChipClient.getConnectedDevicePointer(requireContext(), nodeId)
                }
                val testDuration = 180
                val testIteration = 1000
                val testDiscriminator = 3840
                val testSetupPinCode = 20202021L
                deviceController.openPairingWindowWithPINCallback(
                    devicePointer,
                    testDuration,
                    testIteration.toLong(),
                    testDiscriminator,
                    testSetupPinCode,
                    object : OpenCommissioningCallback {
                        override fun onError(status: Int, deviceId: Long) {
                            requireActivity().runOnUiThread {
                                Toast.makeText(requireContext(), "Fehler beim Öffnen des Koppelungsfensters!", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onSuccess(deviceId: Long, manualPairingCode: String?, qrCode: String?) {
                            if (!iobrokerIp.isBlank() && !iobrokerPort.isBlank() && manualPairingCode != null) {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val apiResult = withContext(Dispatchers.IO) {
                                        try {
                                            val url = URL("http://$iobrokerIp:$iobrokerPort/set/javascript.0.matter_pairing_code?value=$manualPairingCode")
                                            val conn = url.openConnection() as HttpURLConnection
                                            conn.connectTimeout = 5000
                                            conn.readTimeout = 5000
                                            if (conn.responseCode == 200) {
                                                conn.inputStream.bufferedReader().use { it.readText() }
                                            } else {
                                                null
                                            }
                                        } catch (e: Exception) {
                                            Log.e("Companion", "API transmission failed", e)
                                            null
                                        }
                                    }

                                    requireActivity().runOnUiThread {
                                        if (apiResult != null) {
                                            Toast.makeText(requireContext(), "Code automatisch an ioBroker gesendet!", Toast.LENGTH_LONG).show()
                                            AlertDialog.Builder(requireContext())
                                                .setTitle("Automatische Koppelung")
                                                .setMessage("Der Koppelungscode wurde erfolgreich an den ioBroker Matter-Adapter übermittelt!\n\nDas Pairing auf ioBroker läuft nun im Hintergrund.\n\nCode: $manualPairingCode\nPIN: $testSetupPinCode")
                                                .setPositiveButton("OK", null)
                                                .show()
                                        } else {
                                            showManualPairingDialog(manualPairingCode, testSetupPinCode)
                                        }
                                    }
                                }
                            } else {
                                requireActivity().runOnUiThread {
                                    showManualPairingDialog(manualPairingCode ?: "", testSetupPinCode)
                                }
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("Companion", "Sharing failed", e)
            }
        }
    }

    private fun showManualPairingDialog(pairingCode: String, pinCode: Long) {
        AlertDialog.Builder(requireContext())
            .setTitle("Für ioBroker freigegeben")
            .setMessage("Das Koppelungsfenster ist geöffnet!\n\nKoppelungscode: $pairingCode\nPIN-Code: $pinCode\n\nGehe jetzt in den ioBroker und gib diesen Code manuell ein.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun unpairDevice(nodeId: Long) {
        AlertDialog.Builder(requireContext())
            .setTitle("Gerät entkoppeln")
            .setMessage("Möchtest du das Gerät (Node ID: $nodeId) wirklich aus der App löschen?")
            .setPositiveButton("Ja") { _, _ ->
                deviceController.unpairDeviceCallback(nodeId, object : UnpairDeviceCallback {
                    override fun onError(status: Int, remoteDeviceId: Long) {
                        Log.e("Companion", "Unpair failed: $status")
                    }
                    override fun onSuccess(remoteDeviceId: Long) {
                        Log.i("Companion", "Unpair success")
                    }
                })
                DeviceIdUtil.removeCommissionedNodeId(requireContext(), nodeId)
                refreshDeviceList()
                Toast.makeText(requireContext(), "Gerät gelöscht!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    companion object {
        fun newInstance() = IoBrokerCompanionFragment()
    }
}

class DeviceAdapter(
    private var devices: List<Long>,
    private val onToggle: (Long, Boolean) -> Unit,
    private val onShare: (Long) -> Unit,
    private val onUnpair: (Long) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameTv: TextView = view.findViewById(R.id.deviceNameTv)
        val toggleSwitch: SwitchCompat = view.findViewById(R.id.deviceToggleSwitch)
        val shareBtn: Button = view.findViewById(R.id.shareDeviceBtn)
        val unpairBtn: Button = view.findViewById(R.id.unpairDeviceBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.device_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val nodeId = devices[position]
        holder.nameTv.text = "Gerät (Node ID: $nodeId)"

        holder.toggleSwitch.setOnCheckedChangeListener(null)
        holder.toggleSwitch.isChecked = false
        holder.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            onToggle(nodeId, isChecked)
        }

        holder.shareBtn.setOnClickListener { onShare(nodeId) }
        holder.unpairBtn.setOnClickListener { onUnpair(nodeId) }
    }

    override fun getItemCount(): Int = devices.size

    fun updateDevices(newDevices: List<Long>) {
        this.devices = newDevices
        notifyDataSetChanged()
    }
}
