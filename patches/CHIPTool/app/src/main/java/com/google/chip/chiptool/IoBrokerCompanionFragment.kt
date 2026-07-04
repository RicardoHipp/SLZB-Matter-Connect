package com.google.chip.chiptool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.InvokeCallback
import chip.devicecontroller.OpenCommissioningCallback
import chip.devicecontroller.ReportCallback
import chip.devicecontroller.UnpairDeviceCallback
import chip.devicecontroller.model.ChipAttributePath
import chip.devicecontroller.model.ChipEventPath
import chip.devicecontroller.model.ChipPathId
import chip.devicecontroller.model.InvokeElement
import chip.devicecontroller.model.NodeState
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.chip.chiptool.util.DeviceIdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import matter.tlv.AnonymousTag
import matter.tlv.TlvReader
import matter.tlv.TlvWriter
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class IoBrokerCompanionFragment : Fragment() {

    private val deviceController: ChipDeviceController
        get() = ChipClient.getDeviceController(requireContext())

    private lateinit var toolbar: Toolbar
    private lateinit var threadInfoCard: MaterialCardView
    private lateinit var threadNameTv: TextView
    private lateinit var threadChannelTv: TextView
    private lateinit var threadKeyTv: TextView
    private lateinit var startCommissioningBtn: MaterialButton

    private lateinit var tabPairingLayout: View
    private lateinit var tabDevicesLayout: RecyclerView
    private lateinit var tabLayout: TabLayout

    private lateinit var deviceAdapter: DeviceAdapter
    private val subscribedNodeIds = mutableSetOf<Long>()

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantResults ->
            if (grantResults.values.any { !it }) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Berechtigungen benötigt")
                    .setMessage(
                        "Ohne Bluetooth- und Standort-Berechtigung kann kein neues Gerät angelernt werden."
                    )
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .setCancelable(false)
                    .show()
            }
        }

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

        if (!hasRequiredPermissions()) {
            permissionRequest.launch(requiredPermissions())
        }

        toolbar = view.findViewById(R.id.toolbar)
        threadInfoCard = view.findViewById(R.id.threadInfoCard)
        threadNameTv = view.findViewById(R.id.threadNameTv)
        threadChannelTv = view.findViewById(R.id.threadChannelTv)
        threadKeyTv = view.findViewById(R.id.threadKeyTv)
        startCommissioningBtn = view.findViewById(R.id.startCommissioningBtn)

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

        // Settings-Zahnrad in der Toolbar
        toolbar.inflateMenu(R.menu.iobroker_menu)
        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            if (item.itemId == R.id.action_settings) {
                showSettingsDialog()
                true
            } else {
                false
            }
        }

        // Load cached IP
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("stick_ip", "192.168.179.148")
        val savedStickPort = prefs.getString("stick_port", "8080")
        val savedIobrokerIp = prefs.getString("iobroker_ip", "")

        startCommissioningBtn.setOnClickListener { startScanning() }

        // Hinweis beim ersten Start bzw. solange ioBroker nicht eingerichtet ist
        // (per view.post, da die View an dieser Stelle noch nicht im Fenster verankert ist)
        if (savedIobrokerIp.isNullOrBlank()) {
            view.post {
                if (isAdded) {
                    Snackbar.make(view, "Bitte zuerst Stick- und ioBroker-Verbindung einrichten", Snackbar.LENGTH_INDEFINITE)
                        .setAction("Einstellungen") { showSettingsDialog() }
                        .show()
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
            fetchThreadCredentials(savedIp, savedStickPort ?: "8080")
        }

        return view
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_iobroker_settings, null)

        val stickIpEd: TextInputEditText = dialogView.findViewById(R.id.stickIpEd)
        val stickPortEd: TextInputEditText = dialogView.findViewById(R.id.stickPortEd)
        val fetchCredentialsBtn: MaterialButton = dialogView.findViewById(R.id.fetchCredentialsBtn)
        val iobrokerIpEd: TextInputEditText = dialogView.findViewById(R.id.iobrokerIpEd)
        val iobrokerPortEd: TextInputEditText = dialogView.findViewById(R.id.iobrokerPortEd)
        val testIobrokerBtn: MaterialButton = dialogView.findViewById(R.id.testIobrokerBtn)

        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        stickIpEd.setText(prefs.getString("stick_ip", "192.168.179.148"))
        stickPortEd.setText(prefs.getString("stick_port", "8080"))
        iobrokerIpEd.setText(prefs.getString("iobroker_ip", ""))
        iobrokerPortEd.setText(prefs.getString("iobroker_port", "8087"))

        fetchCredentialsBtn.setOnClickListener {
            val ip = stickIpEd.text.toString().trim()
            val port = stickPortEd.text.toString().trim()
            if (ip.isBlank() || port.isBlank()) {
                Toast.makeText(requireContext(), "Bitte IP-Adresse und Port eingeben", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fetchThreadCredentials(ip, port)
        }

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

        AlertDialog.Builder(requireContext())
            .setTitle("Verbindungseinstellungen")
            .setView(dialogView)
            .setPositiveButton("Fertig", null)
            .show()
    }

    private fun fetchThreadCredentials(ip: String, port: String) {
        // Cache IP + Port
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("stick_ip", ip).putString("stick_port", port).apply()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = URL("http://$ip:$port/node/dataset/active")
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
                Toast.makeText(requireContext(), "Verbindung fehlgeschlagen (IP/Port prüfen)", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startScanning() {
        if (fetchedChannel == null) {
            Snackbar.make(requireView(), "Bitte zuerst Stick-Daten in den Einstellungen abrufen!", Snackbar.LENGTH_LONG)
                .setAction("Einstellungen") { showSettingsDialog() }
                .show()
            return
        }
        if (!hasRequiredPermissions()) {
            Snackbar.make(requireView(), "Bluetooth-/Standort-Berechtigung fehlt noch", Snackbar.LENGTH_LONG)
                .setAction("Erlauben") { permissionRequest.launch(requiredPermissions()) }
                .show()
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

        // Gespeicherte Namen und Pairing-Codes sofort anzeigen
        // (auch wenn das Gerät gerade schläft/offline ist)
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        nodeIds.forEach { nodeId ->
            prefs.getString("device_name_$nodeId", null)?.let { name ->
                deviceAdapter.updateName(nodeId, name)
            }
            prefs.getString("device_code_$nodeId", null)?.let { code ->
                deviceAdapter.updateCode(nodeId, code)
            }
        }

        nodeIds.forEach { nodeId -> subscribeToDeviceUpdates(nodeId) }
    }

    // Abonniert ALLE Endpoints/Cluster/Attribute des Geräts (Wildcard) statt eines
    // fest einprogrammierten Clusters. Funktioniert dadurch generisch für jeden
    // Thread/Matter-Gerätetyp (Taster, Sensoren, Steckdosen, ...), nicht nur Switches,
    // und dient gleichzeitig als einfacher Live-Test, ob das Thread-Netzwerk steht.
    private fun subscribeToDeviceUpdates(nodeId: Long) {
        if (subscribedNodeIds.contains(nodeId)) return
        subscribedNodeIds.add(nodeId)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val device = withContext(Dispatchers.IO) {
                    ChipClient.getConnectedDevicePointer(requireContext(), nodeId)
                }

                val callback = object : ReportCallback {
                    override fun onReport(nodeState: NodeState?) {
                        if (nodeState == null) return

                        // Geraetename aus Basic Information (EP0/Cluster 40) ziehen:
                        // NodeLabel (Attr 5, vom Nutzer vergeben) bevorzugt, sonst ProductName (Attr 3)
                        val basicInfo = nodeState.getEndpointState(0)?.getClusterState(40L)
                        val nodeLabel = basicInfo?.getAttributeState(5L)?.value as? String
                        val productName = basicInfo?.getAttributeState(3L)?.value as? String
                        val name = when {
                            !nodeLabel.isNullOrBlank() -> nodeLabel
                            !productName.isNullOrBlank() -> productName
                            else -> null
                        }
                        if (name != null) {
                            val prefs = requireActivity()
                                .getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putString("device_name_$nodeId", name).apply()
                            requireActivity().runOnUiThread {
                                deviceAdapter.updateName(nodeId, name)
                            }
                        }

                        var summary: String? = null
                        for ((endpointId, endpointState) in nodeState.endpointStates) {
                            for ((clusterId, clusterState) in endpointState.clusterStates) {
                                for ((attributeId, attributeState) in clusterState.attributeStates) {
                                    summary = "EP$endpointId/Cl$clusterId/Attr$attributeId = ${attributeState.value}"
                                }
                            }
                        }
                        if (summary == null) return
                        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY)
                            .format(java.util.Date())
                        val text = "Update ($time): $summary — Thread-Netzwerk OK"
                        requireActivity().runOnUiThread {
                            deviceAdapter.updateStatus(nodeId, text)
                        }
                    }

                    override fun onError(
                        attributePath: ChipAttributePath?,
                        eventPath: ChipEventPath?,
                        ex: Exception
                    ) {
                        Log.e("Companion", "Subscribe error for $nodeId", ex)
                        requireActivity().runOnUiThread {
                            deviceAdapter.updateStatus(nodeId, "Keine Verbindung zum Gerät")
                        }
                    }
                }

                deviceController.subscribeToAttributePath(
                    { Log.i("Companion", "Wildcard-Subscription für $nodeId etabliert") },
                    callback,
                    device,
                    listOf(
                        ChipAttributePath.newInstance(
                            ChipPathId.forWildcard(),
                            ChipPathId.forWildcard(),
                            ChipPathId.forWildcard()
                        )
                    ),
                    1,
                    30,
                    0
                )
            } catch (e: Exception) {
                Log.e("Companion", "Failed to subscribe device updates for $nodeId", e)
                subscribedNodeIds.remove(nodeId)
            }
        }
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

    // 12-bit discriminator, 0 ausgeschlossen (reserviert)
    private fun generateRandomDiscriminator(): Int = (1..4095).random()

    // Matter-Spec: gueltiger Bereich 1..99999998, bestimmte Trivial-Codes verboten
    private fun generateRandomSetupPinCode(): Long {
        val invalidCodes = setOf(
            11111111L, 22222222L, 33333333L, 44444444L, 55555555L,
            66666666L, 77777777L, 88888888L, 99999999L, 12345678L, 87654321L
        )
        var pin: Long
        do {
            pin = (1..99999998).random().toLong()
        } while (pin in invalidCodes)
        return pin
    }

    private fun shareDevice(nodeId: Long) {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val iobrokerIp = prefs.getString("iobroker_ip", "") ?: ""
        val iobrokerPort = prefs.getString("iobroker_port", "8087") ?: "8087"

        if (iobrokerIp.isBlank()) {
            Snackbar.make(requireView(), "ioBroker ist noch nicht eingerichtet", Snackbar.LENGTH_LONG)
                .setAction("Einstellungen") { showSettingsDialog() }
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val devicePointer = withContext(Dispatchers.IO) {
                    ChipClient.getConnectedDevicePointer(requireContext(), nodeId)
                }
                val testDuration = 180
                val testIteration = 1000
                val testDiscriminator = generateRandomDiscriminator()
                val testSetupPinCode = generateRandomSetupPinCode()
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
                // Gespeicherte Metadaten (Name, Pairing-Code, PIN) mit aufraeumen,
                // damit der Duplikat-Check ein Neu-Anlernen wieder zulaesst
                requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .remove("device_name_$nodeId")
                    .remove("device_code_$nodeId")
                    .remove("device_pin_$nodeId")
                    .apply()
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

    private val statuses = mutableMapOf<Long, String>()
    private val names = mutableMapOf<Long, String>()
    private val codes = mutableMapOf<Long, String>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameTv: TextView = view.findViewById(R.id.deviceNameTv)
        val codeTv: TextView = view.findViewById(R.id.deviceCodeTv)
        val statusTv: TextView = view.findViewById(R.id.deviceStatusTv)
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
        val name = names[nodeId]
        holder.nameTv.text = if (name != null) "$name (ID: $nodeId)" else "Gerät (Node ID: $nodeId)"
        val code = codes[nodeId]
        if (code != null) {
            holder.codeTv.text = "Pairing-Code: $code"
            holder.codeTv.visibility = View.VISIBLE
        } else {
            holder.codeTv.visibility = View.GONE
        }
        holder.statusTv.text = statuses[nodeId] ?: "Noch keine Live-Daten"

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

    fun updateStatus(nodeId: Long, text: String) {
        statuses[nodeId] = text
        val index = devices.indexOf(nodeId)
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    fun updateName(nodeId: Long, name: String) {
        if (names[nodeId] == name) return
        names[nodeId] = name
        val index = devices.indexOf(nodeId)
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    fun updateCode(nodeId: Long, code: String) {
        if (codes[nodeId] == code) return
        codes[nodeId] = code
        val index = devices.indexOf(nodeId)
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }
}
