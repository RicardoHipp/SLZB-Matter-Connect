package com.google.chip.chiptool

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
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
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.chip.chiptool.util.DeviceIdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import matter.tlv.AnonymousTag
import matter.tlv.TlvWriter
import matter.tlv.ContextSpecificTag
import matter.tlv.TlvReader
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class IoBrokerCompanionFragment : Fragment() {

    private val deviceController: ChipDeviceController
        get() = ChipClient.getDeviceController(requireContext())

    private lateinit var connectionStatusDot: View
    private lateinit var connectionStatusTv: TextView
    private lateinit var themeToggleButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var helpButton: ImageButton
    
    private lateinit var startCommissioningBtn: MaterialButton
    private lateinit var tabDevicesLayout: RecyclerView

    private lateinit var deviceAdapter: DeviceAdapter
    private val subscribedNodeIds = mutableSetOf<Long>()
    private var connectionStatusDotAnimator: ObjectAnimator? = null
    private var connectionPollJob: kotlinx.coroutines.Job? = null
    private var lastStatusText: String = ""

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantResults ->
            if (grantResults.values.any { !it }) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Berechtigungen benötigt")
                    .setMessage("Ohne Bluetooth- und Standort-Berechtigung kann kein neues Gerät angelernt werden.")
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .setCancelable(false)
                    .show()
            }
        }

    private var fetchedChannel: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_iobroker_companion, container, false)

        if (!hasRequiredPermissions()) {
            permissionRequest.launch(requiredPermissions())
        }

        // Bind Views
        connectionStatusDot = view.findViewById(R.id.connectionStatusDot)
        connectionStatusDot.visibility = View.GONE
        connectionStatusTv = view.findViewById(R.id.connectionStatusTv)
        themeToggleButton = view.findViewById(R.id.themeToggleButton)
        settingsButton = view.findViewById(R.id.settingsButton)
        helpButton = view.findViewById(R.id.helpButton)
        
        startCommissioningBtn = view.findViewById(R.id.startCommissioningBtn)
        tabDevicesLayout = view.findViewById(R.id.tabDevicesLayout)

        // Connection Dot Animation (Pulse)
        connectionStatusDotAnimator = ObjectAnimator.ofFloat(connectionStatusDot, "alpha", 1.0f, 0.2f).apply {
            duration = 1100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }

        // Setup RecyclerView
        tabDevicesLayout.layoutManager = LinearLayoutManager(requireContext())
        deviceAdapter = DeviceAdapter(
            emptyList(),
            onToggle = { nodeId, isChecked -> sendOnOffCommand(nodeId, isChecked) },
            onShare = { nodeId -> shareDevice(nodeId) },
            onUnpair = { nodeId -> unpairDevice(nodeId) },
            onRename = { nodeId -> showRenameDialog(nodeId) }
        )
        tabDevicesLayout.adapter = deviceAdapter

        // Tabs: Meine Geraete / ioBroker
        val mainLayout = view.findViewById<View>(R.id.mainLayout)
        val iobrokerLayout = view.findViewById<View>(R.id.iobrokerLayout)
        val deviceTabLayout = view.findViewById<com.google.android.material.tabs.TabLayout>(R.id.deviceTabLayout)
        deviceTabLayout.addTab(deviceTabLayout.newTab().setText("Meine Geräte"))
        deviceTabLayout.addTab(deviceTabLayout.newTab().setText("ioBroker"))
        deviceTabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                val iob = tab.position == 1
                mainLayout.visibility = if (iob) View.GONE else View.VISIBLE
                iobrokerLayout.visibility = if (iob) View.VISIBLE else View.GONE
                iobTabActive = iob
                if (iob) loadIoBrokerDevices()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        // Theme Toggle Click
        themeToggleButton.setOnClickListener { toggleTheme() }
        helpButton.setOnClickListener { showHelpDialog() }

        // Settings Button Click (Slide-In)
        settingsButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_right,
                    R.anim.slide_out_left,
                    R.anim.slide_in_left,
                    R.anim.slide_out_right
                )
                .replace(R.id.nav_host_fragment, IoBrokerSettingsFragment.newInstance(), "SettingsFragment")
                .addToBackStack(null)
                .commit()
        }

        startCommissioningBtn.setOnClickListener { startScanningFlow() }

        // Initial Load settings & fill device list
        loadSavedConfig()

        return view
    }

    override fun onResume() {
        super.onResume()
        if (iobTabActive) loadIoBrokerDevices()
        loadSavedConfig()
        
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val stickIp = prefs.getString("stick_ip", "")
        val stickPort = prefs.getString("stick_port", "8080")
        if (!stickIp.isNullOrBlank()) {
            fetchThreadCredentialsSilently(stickIp ?: "", stickPort ?: "8080")
        }
        
        // Start connection polling loop every 10 seconds with 1s countdown updates
        connectionPollJob?.cancel()
        connectionPollJob = viewLifecycleOwner.lifecycleScope.launch {
            var secondsLeft = 10
            while (isActive) {
                if (secondsLeft == 10) {
                    if (lastStatusText.isNotEmpty()) {
                        connectionStatusTv.text = Html.fromHtml("$lastStatusText (...)", Html.FROM_HTML_MODE_LEGACY)
                    }
                    testConnectionState()
                    // Immediately re-render with new status and (...) so we don't show a blank gap
                    if (lastStatusText.isNotEmpty()) {
                        connectionStatusTv.text = Html.fromHtml("$lastStatusText (...)", Html.FROM_HTML_MODE_LEGACY)
                    }
                } else {
                    if (lastStatusText.isNotEmpty()) {
                        connectionStatusTv.text = Html.fromHtml("$lastStatusText (in ${secondsLeft}s)", Html.FROM_HTML_MODE_LEGACY)
                    }
                }
                
                kotlinx.coroutines.delay(1000)
                secondsLeft--
                if (secondsLeft < 1) {
                    secondsLeft = 10
                }
            }
        }
        
        // Aktualisiert die Liste falls wir gerade vom Anlernen zurückkommen
        refreshDeviceList()
    }

    override fun onPause() {
        connectionPollJob?.cancel()
        super.onPause()
    }

    override fun onDestroyView() {
        connectionStatusDotAnimator?.cancel()
        super.onDestroyView()
    }

    private fun loadSavedConfig() {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val channel = prefs.getInt("fetched_channel", -1)
        if (channel != -1) {
            fetchedChannel = channel
        } else {
            fetchedChannel = null
        }
    }

    private fun getExceptionMessage(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            e is java.net.SocketTimeoutException -> "Timeout"
            e is java.net.ConnectException || msg.contains("Connection refused") -> "Verbindung verweigert"
            msg.contains("ENETUNREACH") -> "Netzwerk nicht erreichbar"
            msg.contains("EHOSTUNREACH") -> "Host nicht erreichbar"
            else -> e.javaClass.simpleName
        }
    }

    private suspend fun testConnectionState() {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val iobrokerIp = prefs.getString("iobroker_ip", "")
        val iobrokerPort = prefs.getString("iobroker_port", "8082")
        val matterInstance = prefs.getString("matter_instance", "0") ?: "0"
        val stickIp = prefs.getString("stick_ip", "")
        val stickPort = prefs.getString("stick_port", "8080")

        if (iobrokerIp.isNullOrBlank() && stickIp.isNullOrBlank()) {
            setConnectionState(false, false, "Nicht eingerichtet")
            return
        }

        val (ioResult, stickResult) = withContext(Dispatchers.IO) {
            val ioJob = async {
                if (!iobrokerIp.isNullOrBlank()) {
                    val sock = IoBrokerSocket(iobrokerIp, iobrokerPort ?: "8082")
                    try {
                        if (!sock.connect(3000)) {
                            Pair(false, "keine Verbindung")
                        } else {
                            val st = sock.getState("system.adapter.matter.$matterInstance.alive")
                            if (st?.optBoolean("val", false) == true) Pair(true, null)
                            else Pair(false, "Adapter aus")
                        }
                    } catch (e: Exception) {
                        Log.e("Companion", "ioBroker check failed", e)
                        Pair(false, getExceptionMessage(e))
                    } finally {
                        sock.close()
                    }
                } else {
                    Pair(false, "nicht konfiguriert")
                }
            }
            val stickJob = async {
                if (!stickIp.isNullOrBlank()) {
                    try {
                        val process = Runtime.getRuntime().exec("ping -c 1 -w 2 $stickIp")
                        val exitCode = process.waitFor()
                        if (exitCode == 0) {
                            Pair(true, null)
                        } else {
                            Pair(false, "Kein Ping")
                        }
                    } catch (e: Exception) {
                        Log.e("Companion", "Stick ping failed", e)
                        Pair(false, getExceptionMessage(e))
                    }
                } else {
                    Pair(false, "nicht konfiguriert")
                }
            }
            Pair(ioJob.await(), stickJob.await())
        }

        if (isAdded) {
            val iobrokerSuccess = ioResult.first
            val iobrokerErr = ioResult.second
            val stickSuccess = stickResult.first
            val stickErr = stickResult.second

            val greenDot = "<font color='#12897A'>●</font>"
            val redDot = "<font color='#D63A3E'>●</font>"

            val ioSymbol = if (iobrokerSuccess) greenDot else redDot
            val stickSymbol = if (stickSuccess) greenDot else redDot

            val ioText = "ioBroker-Matter: $ioSymbol"
            val stickText = "OTBR-Ping: $stickSymbol"
            val text = "$ioText · $stickText"
            setConnectionState(iobrokerSuccess, stickSuccess, text)
        }
    }

    private fun setConnectionState(iobrokerSuccess: Boolean, stickSuccess: Boolean, text: String) {
        lastStatusText = text
        if (connectionStatusTv.text.isNullOrBlank()) {
            connectionStatusTv.text = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
        }
        if (iobrokerSuccess && stickSuccess) {
            connectionStatusDot.setBackgroundResource(R.drawable.circle_dot_accent)
            connectionStatusDotAnimator?.start()
        } else if (iobrokerSuccess || stickSuccess) {
            connectionStatusDot.setBackgroundResource(R.drawable.circle_dot_warning)
            connectionStatusDotAnimator?.start()
        } else {
            connectionStatusDotAnimator?.cancel()
            connectionStatusDot.setBackgroundResource(R.drawable.circle_dot_gray)
            connectionStatusDot.alpha = 1.0f
        }
    }

    private fun clearThreadCredentials() {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("device_custom_name_stick")
            .remove("fetched_channel")
            .remove("fetched_pan_id")
            .remove("fetched_ext_pan_id")
            .remove("fetched_network_key")
            .apply()
        loadSavedConfig()
    }

    private fun fetchThreadCredentialsSilently(ip: String, port: String) {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
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
                    } else { null }
                } catch (e: Exception) {
                    Log.e("Companion", "Silent credentials fetch failed", e)
                    null
                }
            }

            if (result != null) {
                try {
                    val json = JSONObject(result)
                    val name = json.optString("NetworkName", "OpenThread-Stick")
                    val channel = json.optInt("Channel", 15)
                    val panIdDec = json.optInt("PanId", 4660)
                    val panIdHex = panIdDec.toString(16)
                    val extPanId = json.optString("ExtPanId", "")
                    val networkKey = json.optString("NetworkKey", "")

                    prefs.edit()
                        .putString("device_custom_name_stick", name)
                        .putInt("fetched_channel", channel)
                        .putString("fetched_pan_id", panIdHex)
                        .putString("fetched_ext_pan_id", extPanId)
                        .putString("fetched_network_key", networkKey)
                        .apply()

                    loadSavedConfig()
                } catch (e: Exception) {
                    Log.e("Companion", "Failed to parse silently fetched credentials", e)
                    clearThreadCredentials()
                }
            } else {
                clearThreadCredentials()
            }
        }
    }

    private fun showHelpDialog() {
        val text = "Ablauf: Matter-over-Thread-Gerät in ioBroker anlernen\n\n" +
            "1) Einrichten (Zahnrad oben rechts)\n" +
            "- IP deines ioBroker + WS-Port (Standard 8082) und Matter-Instanz eintragen\n" +
            "- optional die Thread-Daten vom OTBR-Server abrufen\n" +
            "- mit 'Verbindung testen' prüfen\n\n" +
            "2) Neues Gerät anlernen (Button 'Gerät hinzufügen')\n" +
            "- QR-/Kopplungscode des Geräts scannen\n" +
            "- das Handy übernimmt das Bluetooth-Anlernen; das Gerät geht per Thread über den OTBR ins Netz\n" +
            "- danach erscheint es in der Geräteliste\n\n" +
            "3) An ioBroker freigeben (Teilen-Symbol am Gerät)\n" +
            "- die App öffnet am Gerät ein Kopplungsfenster und hält es (falls möglich) wach\n" +
            "- der Kopplungscode wird direkt per WebSocket an ioBroker geschickt\n" +
            "- ioBroker koppelt das Gerät als zweiter Administrator (Multi-Admin); es taucht dann auch im Matter-Adapter auf\n" +
            "- klappt die automatische Kopplung nicht, kannst du den angezeigten Code manuell in ioBroker eingeben\n\n" +
            "Oben: grüner Punkt = ioBroker/Stick erreichbar. Sonne/Mond schaltet Hell/Dunkel. Entkoppeln über das Gerät in der Liste."
        AlertDialog.Builder(requireContext())
            .setTitle("So funktioniert's")
            .setMessage(text)
            .setPositiveButton("Verstanden", null)
            .show()
    }

    private fun toggleTheme() {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val currentMode = AppCompatDelegate.getDefaultNightMode()
        val newMode = if (currentMode == AppCompatDelegate.MODE_NIGHT_YES) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        prefs.edit().putInt("theme_mode", newMode).apply()
        AppCompatDelegate.setDefaultNightMode(newMode)
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

    private fun startScanningFlow() {
        if (fetchedChannel == null) {
            Snackbar.make(requireView(), "Bitte zuerst Stick-Daten in den Einstellungen abrufen!", Snackbar.LENGTH_LONG)
                .setAction("Einstellungen") { settingsButton.performClick() }
                .show()
            return
        }
        if (!hasRequiredPermissions()) {
            Snackbar.make(requireView(), "Bluetooth-/Standort-Berechtigung fehlt noch", Snackbar.LENGTH_LONG)
                .setAction("Erlauben") { permissionRequest.launch(requiredPermissions()) }
                .show()
            return
        }
        
        // Startet das neue Bottom Sheet zum Anlernen
        val sheet = CommissioningSheetFragment.newInstance()
        sheet.show(parentFragmentManager, "CommissioningSheet")
    }

    private var iobAdapter: IobDeviceAdapter? = null
    private var iobSwipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var iobTabActive = false

    // Laedt die ioBroker-Geraete (Nodes inkl. Endpunkte) ueber die DM-API (dm:loadDevices).
    private fun loadIoBrokerDevices() {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("iobroker_ip", "") ?: ""
        val port = prefs.getString("iobroker_port", "8082") ?: "8082"
        val instance = prefs.getString("matter_instance", "0") ?: "0"
        val v = view ?: return
        val placeholder = v.findViewById<TextView>(R.id.iobrokerPlaceholderTv)
        val recycler = v.findViewById<RecyclerView>(R.id.iobrokerDevicesRecycler)
        if (iobSwipe == null) {
            iobSwipe = v.findViewById(R.id.iobrokerSwipeRefresh)
            iobSwipe?.setOnRefreshListener { loadIoBrokerDevices() }
        }
        if (iobAdapter == null) {
            iobAdapter = IobDeviceAdapter()
            recycler.layoutManager = LinearLayoutManager(requireContext())
            recycler.adapter = iobAdapter
        }
        if (ip.isBlank()) {
            iobSwipe?.isRefreshing = false
            placeholder.text = "ioBroker ist nicht eingerichtet — siehe Einstellungen."
            placeholder.visibility = View.VISIBLE
            return
        }
        if (iobSwipe?.isRefreshing != true) {
            placeholder.text = "Lade ioBroker-Geräte…"
            placeholder.visibility = View.VISIBLE
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val nodes = withContext(Dispatchers.IO) {
                val sock = IoBrokerSocket(ip, port)
                try {
                    if (!sock.connect()) return@withContext null
                    val res = sock.dmLoadDevices("matter.$instance")
                    val add = res?.optJSONArray("add") ?: return@withContext emptyList<IobNode>()

                    fun statusOf(d: org.json.JSONObject): Pair<String, Boolean> {
                        val st = d.optJSONObject("status")
                        val conn = st?.optString("connection") == "connected"
                        val extra = mutableListOf<String>()
                        if (st != null) {
                            if (st.has("battery")) extra.add("Akku ${st.optInt("battery")} %")
                            if (st.has("rssi")) extra.add("Signal ${st.optInt("rssi")} dBm")
                        }
                        val text = (if (conn) "● verbunden" else "○ getrennt") +
                            (if (extra.isNotEmpty()) "  ·  " + extra.joinToString("  ·  ") else "")
                        return Pair(text, conn)
                    }

                    // 1) Endpunkte je Node sammeln
                    val childMap = HashMap<String, MutableList<IobChild>>()
                    for (i in 0 until add.length()) {
                        val d = add.optJSONObject(i) ?: continue
                        val id = d.optString("id")
                        if (!id.contains("-")) continue
                        val parent = id.substringBefore("-")
                        val full = d.optString("name", id)
                        val cname = full.substringAfter(" - ", full)
                        val (cstatus, cconn) = statusOf(d)
                        childMap.getOrPut(parent) { mutableListOf() }.add(IobChild(cname, cstatus, cconn))
                    }
                    // 2) Nodes
                    val list = mutableListOf<IobNode>()
                    for (i in 0 until add.length()) {
                        val d = add.optJSONObject(i) ?: continue
                        val id = d.optString("id")
                        if (id.contains("-")) continue
                        val name = d.optString("name", "Gerät $id").removePrefix("Node ")
                        val manu = d.optString("manufacturer", "")
                        val model = d.optString("model", "")
                        val sub = listOf(manu, model).filter { it.isNotBlank() }.joinToString(" · ")
                        val (statusText, connected) = statusOf(d)
                        list.add(IobNode(id, name, sub, statusText, connected, childMap[id] ?: emptyList()))
                    }
                    list
                } catch (e: Exception) {
                    Log.e("Companion", "dm:loadDevices fehlgeschlagen", e)
                    null
                } finally {
                    sock.close()
                }
            }
            if (!isAdded) return@launch
            iobSwipe?.isRefreshing = false
            when {
                nodes == null -> {
                    placeholder.text = "Keine Verbindung zu ioBroker (IP/Port prüfen)."
                    placeholder.visibility = View.VISIBLE
                }
                nodes.isEmpty() -> {
                    placeholder.text = "Keine Matter-Geräte in ioBroker gefunden."
                    placeholder.visibility = View.VISIBLE
                }
                else -> {
                    iobAdapter?.update(nodes)
                    placeholder.visibility = View.GONE
                }
            }
        }
    }

    fun refreshDeviceList() {
        val nodeList = DeviceIdUtil.getCommissionedNodeId(requireContext())
        val nodeIds = nodeList.mapNotNull {
            try { it.toLong(16) } catch (e: Exception) { null }
        }
        deviceAdapter.updateDevices(nodeIds)

        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        nodeIds.forEach { nodeId ->
            val name = prefs.getString("device_custom_name_$nodeId", null)
                ?: prefs.getString("device_name_$nodeId", null)
            name?.let { deviceAdapter.updateName(nodeId, it) }
            prefs.getString("device_code_$nodeId", null)?.let { code ->
                deviceAdapter.updateCode(nodeId, code)
            }
            subscribeToDeviceUpdates(nodeId)
        }
    }

    private fun showRenameDialog(nodeId: Long) {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val currentName = prefs.getString("device_custom_name_$nodeId", null)
            ?: prefs.getString("device_name_$nodeId", null)
            ?: ""

        val input = EditText(requireContext()).apply {
            setText(currentName)
            hint = "Eigener Name"
            setSelection(text.length)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Gerät umbenennen")
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    prefs.edit().putString("device_custom_name_$nodeId", newName).apply()
                    deviceAdapter.updateName(nodeId, newName)
                }
            }
            .setNeutralButton("Zurücksetzen") { _, _ ->
                prefs.edit().remove("device_custom_name_$nodeId").apply()
                val reportedName = prefs.getString("device_name_$nodeId", null)
                deviceAdapter.updateName(nodeId, reportedName ?: "")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun subscribeToDeviceUpdates(nodeId: Long) {
        if (subscribedNodeIds.contains(nodeId)) return
        subscribedNodeIds.add(nodeId)

        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            if (!isActive) return@launch
            try {
                val device = withContext(Dispatchers.IO) {
                    ChipClient.getConnectedDevicePointer(requireContext(), nodeId)
                }

                // Proaktives, gezieltes Read der BasicInformation (Cluster 0x28) direkt nach dem
                // Verbinden -> der richtige Geraetename steht sofort, statt auf die erste
                // spontane Subscription-Meldung des Geraets zu warten.
                deviceController.readAttributePath(
                    object : ReportCallback {
                        override fun onReport(nodeState: NodeState?) {
                            val act = activity ?: return
                            val basicInfo = nodeState?.getEndpointState(0)?.getClusterState(40L)
                            val nodeLabel = basicInfo?.getAttributeState(5L)?.value as? String
                            val productName = basicInfo?.getAttributeState(3L)?.value as? String
                            val vendorName = basicInfo?.getAttributeState(1L)?.value as? String
                            val name = when {
                                !nodeLabel.isNullOrBlank() -> nodeLabel
                                !productName.isNullOrBlank() -> productName
                                !vendorName.isNullOrBlank() -> vendorName
                                else -> null
                            }
                            if (name != null) {
                                val prefs = act.getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
                                prefs.edit().putString("device_name_$nodeId", name).apply()
                                if (prefs.getString("device_custom_name_$nodeId", null) == null) {
                                    act.runOnUiThread { if (isAdded) deviceAdapter.updateName(nodeId, name) }
                                }
                            }
                        }

                        override fun onError(
                            attributePath: ChipAttributePath?,
                            eventPath: ChipEventPath?,
                            ex: java.lang.Exception
                        ) {
                            Log.w("Companion", "BasicInformation-Read fehlgeschlagen fuer $nodeId", ex)
                        }
                    },
                    device,
                    listOf(
                        ChipAttributePath.newInstance(
                            ChipPathId.forId(0L),
                            ChipPathId.forId(40L),
                            ChipPathId.forWildcard()
                        )
                    ),
                    0
                )

                val callback = object : ReportCallback {
                    override fun onReport(nodeState: NodeState?) {
                        if (nodeState == null) return
                        // Report kann auf dem nativen CHIP-Thread eintreffen, waehrend das Fragment
                        // abgeloest ist (Theme-Wechsel, Hintergrund, Teardown) -> nichts tun statt Crash.
                        val act = activity ?: return

                        val basicInfo = nodeState.getEndpointState(0)?.getClusterState(40L)
                        val nodeLabel = basicInfo?.getAttributeState(5L)?.value as? String
                        val productName = basicInfo?.getAttributeState(3L)?.value as? String
                        val name = when {
                            !nodeLabel.isNullOrBlank() -> nodeLabel
                            !productName.isNullOrBlank() -> productName
                            else -> null
                        }
                        if (name != null) {
                            val prefs = act.getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putString("device_name_$nodeId", name).apply()
                            if (prefs.getString("device_custom_name_$nodeId", null) == null) {
                                act.runOnUiThread {
                                    if (isAdded) deviceAdapter.updateName(nodeId, name)
                                }
                            }
                        }

                        // Generisches Modell aus dem NodeState bauen (Fähigkeiten + benannte Werte).
                        val model = MatterModelParser.parse(nodeState)
                        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())
                        act.runOnUiThread {
                            if (isAdded) deviceAdapter.updateModel(nodeId, model, time)
                        }
                    }

                    override fun onError(
                        attributePath: ChipAttributePath?,
                        eventPath: ChipEventPath?,
                        ex: java.lang.Exception
                    ) {
                        Log.e("Companion", "Subscribe error for $nodeId", ex)
                        val act = activity ?: return
                        act.runOnUiThread {
                            if (isAdded) deviceAdapter.updateStatus(nodeId, "Keine Verbindung zum Gerät")
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
                    0,  // minInterval 0 -> jede Änderung sofort melden (keine 1s-Drossel)
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
                val invokeElement = InvokeElement.newInstance(1, 6L, commandId, tlvWriter.getEncoded(), null)
                deviceController.invoke(
                    object : InvokeCallback {
                        override fun onError(ex: java.lang.Exception?) {
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

    private fun generateRandomDiscriminator(): Int = (1..4095).random()
    private fun generateRandomSetupPinCode(): Long {
        val invalidCodes = setOf(
            11111111L, 22222222L, 33333333L, 44444444L, 55555555L,
            66666666L, 77777777L, 88888888L, 99999999L, 12345678L, 87654321L
        )
        var pin: Long
        do { pin = (1..99999998).random().toLong() } while (pin in invalidCodes)
        return pin
    }

    private class ShareProgress(val dialog: AlertDialog, val textView: TextView)

    // Kleiner, nicht abbrechbarer Fortschritts-Dialog mit Spinner + aktualisierbarem Text.
    private fun showShareProgress(initialText: String): ShareProgress {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val size = (34 * density).toInt()
        container.addView(
            android.widget.ProgressBar(ctx).apply { isIndeterminate = true },
            android.widget.LinearLayout.LayoutParams(size, size)
        )
        val tv = TextView(ctx).apply {
            text = initialText
            setPadding(pad, 0, 0, 0)
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.theme_on))
        }
        container.addView(tv)
        val dlg = AlertDialog.Builder(ctx)
            .setTitle("Für ioBroker freigeben")
            .setView(container)
            .setCancelable(false)
            .create()
        dlg.show()
        return ShareProgress(dlg, tv)
    }

    private fun updateShareProgress(p: ShareProgress, text: String) {
        activity?.runOnUiThread { if (isAdded) p.textView.text = text }
    }

    // Von aussen (Commissioning-Sheet) aufrufbar: startet die ioBroker-Freigabe fuer ein Node.
    fun requestShareToIoBroker(nodeId: Long) = shareDevice(nodeId)

    private fun shareDevice(nodeId: Long) {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val iobrokerIp = prefs.getString("iobroker_ip", "") ?: ""
        val iobrokerPort = prefs.getString("iobroker_port", "8082") ?: "8082"
        val matterInstance = prefs.getString("matter_instance", "0") ?: "0"

        // Sofort-Feedback: Spinner-Dialog, damit klar ist dass etwas laeuft (verhindert Doppel-Tippen).
        val progress = showShareProgress("Verbinde mit Gerät…")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val devicePointer = withContext(Dispatchers.IO) {
                    ChipClient.getConnectedDevicePointer(requireContext(), nodeId)
                }
                updateShareProgress(progress, "Öffne Koppelungsfenster am Gerät…")

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
                            activity?.runOnUiThread {
                                progress.dialog.dismiss()
                                if (isAdded) Toast.makeText(requireContext(), "Fehler beim Öffnen des Koppelungsfensters!", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onSuccess(deviceId: Long, manualPairingCode: String?, qrCode: String?) {
                            if (manualPairingCode == null) {
                                activity?.runOnUiThread {
                                    progress.dialog.dismiss()
                                    if (isAdded) showManualPairingDialog("", testSetupPinCode)
                                }
                                return
                            }
                            // ioBroker nicht eingerichtet -> Code direkt zur manuellen Eingabe zeigen
                            // (z.B. fuer Home Assistant oder manuelle Eingabe in ioBroker).
                            if (iobrokerIp.isBlank()) {
                                activity?.runOnUiThread {
                                    progress.dialog.dismiss()
                                    if (isAdded) showManualPairingDialog(
                                        manualPairingCode,
                                        testSetupPinCode,
                                        "ioBroker ist nicht eingerichtet — gib den Code manuell in deiner Matter-Zentrale (ioBroker, Home Assistant, …) ein."
                                    )
                                }
                                return
                            }
                            viewLifecycleOwner.lifecycleScope.launch {
                                // Erst pruefen ob der ioBroker Matter-Adapter ueberhaupt erreichbar/alive ist.
                                // Wenn nicht: gar nicht senden, direkt den Code zur manuellen Eingabe zeigen.
                                updateShareProgress(progress, "Prüfe ioBroker-Matter-Verbindung…")
                                val iobSock = IoBrokerSocket(iobrokerIp, iobrokerPort)
                                val (resultStatus, resultMsg) = withContext(Dispatchers.IO) {
                                    try {
                                        // 1) Verbinden + pruefen, ob der Matter-Adapter erreichbar/alive ist.
                                        val connected = iobSock.connect()
                                        val aliveState = if (connected) iobSock.getState("system.adapter.matter.$matterInstance.alive") else null
                                        if (!connected || aliveState?.optBoolean("val", false) != true) {
                                            Pair("offline", "")
                                        } else {
                                            // 1b) Geraet (v.a. batteriebetrieben) wachhalten, damit ioBroker
                                            //     es waehrend des Koppelns erreichen kann.
                                            updateShareProgress(progress, "Halte Gerät wach…")
                                            val awakeReqMs = 120000L
                                            val (awakeOk, awakePromisedMs) = keepDeviceAwake(devicePointer, awakeReqMs)
                                            activity?.runOnUiThread {
                                                if (isAdded) {
                                                    val awakeMsg = when {
                                                        awakeOk && awakePromisedMs != null ->
                                                            "Gerät für ${awakePromisedMs / 1000} s wachgehalten (${awakeReqMs / 1000} s angefragt)"
                                                        awakeOk ->
                                                            "Gerät wachgehalten (Dauer unbekannt, ${awakeReqMs / 1000} s angefragt)"
                                                        else ->
                                                            "Gerät unterstützt kein aktives Wachhalten (Koppeln läuft normal weiter)"
                                                    }
                                                    Toast.makeText(requireContext(), awakeMsg, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                            // 2) Koppelung anstossen: kommt SOFORT mit pollingId zurueck
                                            //    (kein langer, blockierender Aufruf -> kein falscher Timeout).
                                            updateShareProgress(progress, "Sende Code an ioBroker…")
                                            val startMsg = JSONObject()
                                                .put("manualCode", manualPairingCode)
                                                .put("pollResponse", true)
                                            val start = iobSock.sendTo("matter.$matterInstance", "controllerCommissionDevice", startMsg)
                                            when {
                                                start == null -> Pair("offline", "")
                                                start.has("error") -> Pair("error", start.optString("error"))
                                                else -> {
                                                    val pollingId = start.optJSONObject("result")?.opt("pollingId")
                                                    if (pollingId == null) {
                                                        // Adapter hat direkt (ohne pollResponse) geantwortet.
                                                        evalCommissionResult(start)
                                                    } else {
                                                        // 3) Status pollen bis fertig/Fehler/Timeout (max. 2 Min).
                                                        updateShareProgress(progress, "Angekommen ✓ — ioBroker koppelt jetzt…")
                                                        val deadline = System.currentTimeMillis() + 120_000
                                                        var out: Pair<String, String>? = null
                                                        while (System.currentTimeMillis() < deadline) {
                                                            kotlinx.coroutines.delay(1500)
                                                            val st = iobSock.sendTo(
                                                                "matter.$matterInstance",
                                                                "controllerCommissionDeviceStatus",
                                                                JSONObject().put("pollingId", pollingId)
                                                            ) ?: continue
                                                            if (st.optJSONObject("result")?.optString("status") == "inprogress") continue
                                                            out = evalCommissionResult(st)
                                                            break
                                                        }
                                                        out ?: Pair("timeout", "")
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Pair("error", e.message ?: "Fehler")
                                    } finally {
                                        iobSock.close()
                                    }
                                }

                                // Adapter nicht erreichbar -> Code NICHT gesendet, manueller Weg.
                                if (resultStatus == "offline") {
                                    activity?.runOnUiThread {
                                        progress.dialog.dismiss()
                                        if (isAdded) showManualPairingDialog(
                                            manualPairingCode,
                                            testSetupPinCode,
                                            "Keine Verbindung zum ioBroker Matter-Adapter — der Code wurde NICHT automatisch gesendet."
                                        )
                                    }
                                    return@launch
                                }

                                activity?.runOnUiThread {
                                    progress.dialog.dismiss()
                                    if (!isAdded) return@runOnUiThread
                                    when (resultStatus) {
                                        "success" -> AlertDialog.Builder(requireContext())
                                            .setTitle("✓ Erfolgreich gekoppelt")
                                            .setMessage("Das Gerät wurde erfolgreich mit ioBroker gekoppelt.\n\nCode: $manualPairingCode")
                                            .setPositiveButton("OK", null)
                                            .show()
                                        "error" -> AlertDialog.Builder(requireContext())
                                            .setTitle("✗ Koppelung fehlgeschlagen")
                                            .setMessage("ioBroker meldet einen Fehler:\n$resultMsg\n\nDu kannst den Code auch manuell in ioBroker eingeben:\nCode: $manualPairingCode\nPIN: $testSetupPinCode")
                                            .setPositiveButton("OK", null)
                                            .show()
                                        else -> AlertDialog.Builder(requireContext())
                                            .setTitle("Keine Rückmeldung (Timeout)")
                                            .setMessage("Der Code wurde an ioBroker gesendet, aber es kam innerhalb von 2 Minuten keine Rückmeldung.\n\nBitte in ioBroker (Matter-Adapter) prüfen, ob das Gerät auftaucht.\n\nCode: $manualPairingCode\nPIN: $testSetupPinCode")
                                            .setPositiveButton("OK", null)
                                            .show()
                                    }
                                }
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("Companion", "Sharing failed", e)
                activity?.runOnUiThread {
                    progress.dialog.dismiss()
                    if (isAdded) Toast.makeText(requireContext(), "Freigeben fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Haelt ein ICD-/Batteriegeraet aktiv (ICD-Management-Cluster 0x0046, StayActiveRequest 0x03),
    // damit ioBroker es waehrend des Koppelns erreichen kann.
    // Rueckgabe: (angenommen, versprocheneDauerMs); promisedMs = null wenn Antwort nicht parsebar.
    private suspend fun keepDeviceAwake(devicePtr: Long, requestedMs: Long): Triple<Boolean, Long?, String?> =
        suspendCancellableCoroutine { cont ->
            try {
                val tlvWriter = TlvWriter()
                tlvWriter.startStructure(AnonymousTag)
                tlvWriter.putUnsigned(ContextSpecificTag(0), requestedMs)   // StayActiveDuration (ms)
                tlvWriter.endStructure()
                // ICD Management (0x0046), StayActiveRequest (0x03), Endpoint 0 (Root Node)
                val invokeElement = InvokeElement.newInstance(0, 0x0046L, 0x03L, tlvWriter.getEncoded(), null)
                deviceController.invoke(
                    object : InvokeCallback {
                        override fun onError(ex: java.lang.Exception?) {
                            val reason = ex?.message ?: ex?.javaClass?.simpleName ?: "unbekannter Fehler"
                            Log.w("Companion", "StayActiveRequest fehlgeschlagen: $reason", ex)
                            if (cont.isActive) cont.resume(Triple(false, null, reason))
                        }
                        override fun onResponse(response: InvokeElement?, successCode: Long) {
                            var promised: Long? = null
                            try {
                                val bytes = response?.getTlvByteArray()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    val r = TlvReader(bytes)
                                    r.enterStructure(AnonymousTag)
                                    promised = r.getUInt(ContextSpecificTag(0)).toLong()  // PromisedActiveDuration (ms)
                                    r.exitContainer()
                                }
                            } catch (e: Exception) {
                                Log.w("Companion", "StayActiveResponse nicht parsebar", e)
                            }
                            if (cont.isActive) cont.resume(Triple(true, promised, null))
                        }
                    },
                    devicePtr,
                    invokeElement,
                    0,
                    0
                )
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                Log.w("Companion", "StayActiveRequest Aufbau fehlgeschlagen: $reason", e)
                if (cont.isActive) cont.resume(Triple(false, null, reason))
            }
        }

    // Deutet die Antwort von controllerCommissionDevice / -Status:
    // Erfolg = {"result":true,"nodeId":...}, Fehler = {"error":...}.
    private fun evalCommissionResult(res: JSONObject): Pair<String, String> {
        if (res.has("error")) return Pair("error", res.optString("error"))
        if (res.optBoolean("result", false)) return Pair("success", "")
        val r = res.optJSONObject("result")
        if (r != null && !r.has("error") && r.has("nodeId")) return Pair("success", "")
        return Pair("error", res.optString("error", res.toString()))
    }

    private fun showManualPairingDialog(pairingCode: String, pinCode: Long, reason: String? = null) {
        val prefix = if (!reason.isNullOrBlank()) "$reason\n\n" else ""
        AlertDialog.Builder(requireContext())
            .setTitle("Code manuell in ioBroker eingeben")
            .setMessage("${prefix}Das Koppelungsfenster am Gerät ist geöffnet.\n\nKoppelungscode: $pairingCode\nPIN-Code: $pinCode\n\nGib diesen Code im ioBroker Matter-Adapter (Controller → Gerät hinzufügen) manuell ein.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun unpairDevice(nodeId: Long) {
        AlertDialog.Builder(requireContext())
            .setTitle("Gerät entkoppeln")
            .setMessage("Möchtest du das Gerät (Node ID: $nodeId) wirklich aus der App löschen?")
            .setPositiveButton("Ja") { _, _ ->
                deviceController.unpairDeviceCallback(nodeId, object : UnpairDeviceCallback {
                    override fun onError(status: Int, remoteDeviceId: Long) { Log.e("Companion", "Unpair failed: $status") }
                    override fun onSuccess(remoteDeviceId: Long) { Log.i("Companion", "Unpair success") }
                })
                DeviceIdUtil.removeCommissionedNodeId(requireContext(), nodeId)
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
    private val onUnpair: (Long) -> Unit,
    private val onRename: (Long) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private val statuses = mutableMapOf<Long, String>()
    private val models = mutableMapOf<Long, MatterDeviceModel>()
    private val lastUpdate = mutableMapOf<Long, String>()
    // Akkumulierte Werte pro Gerät (Subscription liefert nach dem Priming nur Deltas).
    private val readingAcc = mutableMapOf<Long, MutableMap<String, MatterReading>>()
    private val switchableAcc = mutableMapOf<Long, Boolean>()
    private val onOffEpAcc = mutableMapOf<Long, Int>()
    private val changedKeys = mutableMapOf<Long, Set<String>>() // zuletzt geänderte Werte je Gerät
    private val names = mutableMapOf<Long, String>()
    private val codes = mutableMapOf<Long, String>()
    private val expandedStates = mutableMapOf<Long, Boolean>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val editBtn: View = view.findViewById(R.id.editDeviceBtn)
        val nameTv: TextView = view.findViewById(R.id.deviceNameTv)
        val idChip: TextView = view.findViewById(R.id.deviceIdChip)
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
        holder.nameTv.text = name ?: "Gerät"
        holder.idChip.text = "ID $nodeId"
        holder.editBtn.setOnClickListener { onRename(nodeId) }
        val code = codes[nodeId]
        if (code != null) {
            holder.codeTv.text = "Pairing-Code: $code"
            holder.codeTv.visibility = View.VISIBLE
        } else {
            holder.codeTv.visibility = View.GONE
        }

        val ctx = holder.itemView.context
        val model = models[nodeId]
        val errorStatus = statuses[nodeId]
        when {
            model != null -> {
                val changed = changedKeys[nodeId] ?: emptySet()
                val isExpanded = expandedStates[nodeId] ?: false
                val header = "Aktualisiert ${lastUpdate[nodeId] ?: ""} · ${model.readings.size} Werte ${if (isExpanded) "▴" else "▾"}"
                val onColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.theme_on)
                val hiColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.theme_accent)
                val subColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.theme_onv)

                val sb = android.text.SpannableStringBuilder()
                val hStart = sb.length
                sb.append(header)
                sb.setSpan(android.text.style.ForegroundColorSpan(subColor), hStart, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                // Cluster, die auf mehreren Endpoints vorkommen -> in der Überschrift disambiguieren.
                val epPerCluster = model.readings.groupBy { it.clusterId }
                    .mapValues { e -> e.value.map { it.endpointId }.toSet().size }

                if (isExpanded) {
                    var lastGroup: Pair<Int, Long>? = null
                    for (r in model.readings) {
                        val g = r.endpointId to r.clusterId
                        if (g != lastGroup) {
                            sb.append("\n\n")
                            val gs = sb.length
                            sb.append(groupTitle(r.clusterId, r.endpointId, (epPerCluster[r.clusterId] ?: 1) > 1))
                            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), gs, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sb.setSpan(android.text.style.ForegroundColorSpan(onColor), gs, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            lastGroup = g
                        }
                        val key = "${r.endpointId}/${r.clusterId}/${r.attributeId}"
                        sb.append("\n   ")
                        val start = sb.length
                        sb.append("${MatterNames.attribute(r.clusterId, r.attributeId)} = ${r.value}")
                        val color = if (key in changed) hiColor else onColor
                        sb.setSpan(android.text.style.ForegroundColorSpan(color), start, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    // Zugeklappt: nur die zuletzt geänderten Werte, kompakt mit Gruppenname.
                    for (r in model.readings.filter { "${it.endpointId}/${it.clusterId}/${it.attributeId}" in changed }) {
                        sb.append("\n")
                        val start = sb.length
                        sb.append("${groupTitle(r.clusterId, r.endpointId, (epPerCluster[r.clusterId] ?: 1) > 1)} · ${MatterNames.attribute(r.clusterId, r.attributeId)} = ${r.value}")
                        sb.setSpan(android.text.style.ForegroundColorSpan(hiColor), start, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                holder.statusTv.text = sb
                holder.statusTv.isClickable = true
                holder.statusTv.setOnClickListener {
                    expandedStates[nodeId] = !isExpanded
                    notifyItemChanged(position)
                }
            }
            errorStatus != null -> {
                holder.statusTv.text = errorStatus
                holder.statusTv.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.theme_onv))
                holder.statusTv.setOnClickListener(null)
                holder.statusTv.isClickable = false
            }
            else -> {
                holder.statusTv.text = "Noch keine Live-Daten"
                holder.statusTv.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.theme_onv))
                holder.statusTv.setOnClickListener(null)
                holder.statusTv.isClickable = false
            }
        }

        // Schalter nur bei schaltbaren Geräten (OnOff-Cluster vorhanden).
        holder.toggleSwitch.visibility = if (model?.switchable == true) View.VISIBLE else View.GONE
        // Listener zuerst abhaengen, damit das Setzen des ECHTEN Zustands keinen Schaltbefehl ausloest.
        holder.toggleSwitch.setOnCheckedChangeListener(null)
        val isOn = model?.readings?.firstOrNull {
            it.clusterId == 6L && it.attributeId == 0L
        }?.value == "An"
        holder.toggleSwitch.isChecked = isOn
        holder.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            onToggle(nodeId, isChecked)
        }

        holder.shareBtn.setOnClickListener { onShare(nodeId) }
        holder.unpairBtn.setOnClickListener { onUnpair(nodeId) }
    }

    // Überschrift einer Wertegruppe: Switch = "Taste N", Mehrfach-Cluster mit Endpoint, sonst Cluster-Name.
    private fun groupTitle(clusterId: Long, endpointId: Int, multiEndpoint: Boolean): String = when {
        clusterId == 59L -> "Taste $endpointId"                                 // Switch (0x003B)
        multiEndpoint -> "${MatterNames.cluster(clusterId)} · Endpoint $endpointId"
        else -> MatterNames.cluster(clusterId)
    }

    override fun getItemCount(): Int = devices.size

    fun updateDevices(newDevices: List<Long>) {
        this.devices = newDevices
        notifyDataSetChanged()
    }

    fun updateStatus(nodeId: Long, text: String) {
        statuses[nodeId] = text
        models.remove(nodeId) // Fehler -> Modell verwerfen, Meldung zeigen
        val index = devices.indexOf(nodeId)
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    fun updateModel(nodeId: Long, model: MatterDeviceModel, time: String) {
        // Delta-Reports mergen statt ersetzen: geänderte Werte aktualisieren, Rest behalten.
        val acc = readingAcc.getOrPut(nodeId) { mutableMapOf() }
        val changed = mutableSetOf<String>()
        for (r in model.readings) {
            val key = "${r.endpointId}/${r.clusterId}/${r.attributeId}"
            val old = acc[key]
            // Nur echte Änderungen eines bereits bekannten Werts markieren
            // (Priming/neue Werte -> nicht hervorheben).
            if (old != null && old.value != r.value) changed.add(key)
            acc[key] = r
        }
        changedKeys[nodeId] = changed
        if (model.switchable) switchableAcc[nodeId] = true
        if (model.onOffEndpoint != null) onOffEpAcc[nodeId] = model.onOffEndpoint

        models[nodeId] = MatterDeviceModel(
            switchable = switchableAcc[nodeId] ?: false,
            onOffEndpoint = onOffEpAcc[nodeId],
            readings = acc.values.sortedWith(
                compareBy({ it.endpointId }, { it.clusterId }, { it.attributeId })
            )
        )
        lastUpdate[nodeId] = time
        statuses.remove(nodeId) // gültige Live-Daten -> evtl. alte Fehlermeldung entfernen
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


data class IobChild(val name: String, val status: String, val connected: Boolean)

data class IobNode(
    val id: String,
    val name: String,
    val sub: String,
    val status: String,
    val connected: Boolean,
    val children: List<IobChild>
)

class IobDeviceAdapter : RecyclerView.Adapter<IobDeviceAdapter.VH>() {
    private var nodes: List<IobNode> = emptyList()
    private val expanded = HashSet<String>()

    private class DisplayRow(
        val isChild: Boolean,
        val nodeId: String,
        val name: String,
        val sub: String,
        val status: String,
        val connected: Boolean,
        val hasChildren: Boolean
    )

    private var rows: List<DisplayRow> = emptyList()

    private fun rebuild() {
        val r = mutableListOf<DisplayRow>()
        for (n in nodes) {
            r.add(DisplayRow(false, n.id, n.name, n.sub, n.status, n.connected, n.children.isNotEmpty()))
            if (expanded.contains(n.id)) {
                for (c in n.children) r.add(DisplayRow(true, n.id, c.name, "", c.status, c.connected, false))
            }
        }
        rows = r
        notifyDataSetChanged()
    }

    fun update(list: List<IobNode>) {
        nodes = list
        rebuild()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.iobNameTv)
        val sub: TextView = v.findViewById(R.id.iobSubTv)
        val status: TextView = v.findViewById(R.id.iobStatusTv)
        val chevron: TextView = v.findViewById(R.id.iobChevronTv)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.iobroker_device_item, parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.name.text = row.name
        holder.sub.text = row.sub
        holder.sub.visibility = if (row.sub.isBlank()) View.GONE else View.VISIBLE
        holder.status.text = row.status
        holder.status.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                holder.itemView.context,
                if (row.connected) R.color.theme_accent else R.color.theme_onv
            )
        )
        val lp = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
        val density = holder.itemView.resources.displayMetrics.density
        lp.marginStart = if (row.isChild) (28 * density).toInt() else 0
        holder.itemView.layoutParams = lp
        if (row.hasChildren) {
            holder.chevron.visibility = View.VISIBLE
            holder.chevron.text = if (expanded.contains(row.nodeId)) "▾" else "▸"
            holder.itemView.setOnClickListener {
                if (expanded.contains(row.nodeId)) expanded.remove(row.nodeId) else expanded.add(row.nodeId)
                rebuild()
            }
        } else {
            holder.chevron.visibility = View.GONE
            holder.itemView.setOnClickListener(null)
            holder.itemView.isClickable = false
        }
    }
}
