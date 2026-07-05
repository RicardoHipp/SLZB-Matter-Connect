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

        // Theme Toggle Click
        themeToggleButton.setOnClickListener { toggleTheme() }

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
        val iobrokerPort = prefs.getString("iobroker_port", "8087")
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
                    try {
                        val url = URL("http://$iobrokerIp:$iobrokerPort/get/system.adapter.matter.$matterInstance.alive")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 2000
                        conn.readTimeout = 2000
                        if (conn.responseCode == 200) {
                            val body = conn.inputStream.bufferedReader().use { it.readText() }
                            val alive = body.contains("\"val\":true") || body.trim() == "true"
                            if (alive) {
                                Pair(true, null)
                            } else {
                                Pair(false, "Adapter aus")
                            }
                        } else {
                            Pair(false, "HTTP ${conn.responseCode}")
                        }
                    } catch (e: Exception) {
                        Log.e("Companion", "ioBroker check failed", e)
                        Pair(false, getExceptionMessage(e))
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
            val stickText = "SLZB-Ping: $stickSymbol"
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

                        var summary: String? = null
                        for ((endpointId, endpointState) in nodeState.endpointStates) {
                            for ((clusterId, clusterState) in endpointState.clusterStates) {
                                for ((attributeId, attributeState) in clusterState.attributeStates) {
                                    summary = "EP$endpointId/Cl$clusterId/Attr$attributeId = ${attributeState.value}"
                                }
                            }
                        }
                        if (summary == null) return
                        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())
                        val text = "Update ($time): $summary — Thread-Netzwerk OK"
                        act.runOnUiThread {
                            if (isAdded) deviceAdapter.updateStatus(nodeId, text)
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

    // Pollt 0_userdata.0.matter_connect.pairing_result bis 'success'/'error'/Timeout (max. 2 Min).
    // Rueckgabe: (status, message) mit status in {"success","error","timeout"}.
    private suspend fun pollPairingResult(ip: String, port: String): Pair<String, String> {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(1500)
            val value = withContext(Dispatchers.IO) {
                try {
                    val url = URL("http://$ip:$port/get/0_userdata.0.matter_connect.pairing_result")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        try { JSONObject(body).optString("val", "") } catch (e: Exception) { "" }
                    } else ""
                } catch (e: Exception) { "" }
            }
            when {
                value == "success" -> return Pair("success", "")
                value.startsWith("error") -> return Pair("error", value.removePrefix("error:").trim())
                // "" oder "processing" -> weiter warten
            }
        }
        return Pair("timeout", "")
    }

    private fun shareDevice(nodeId: Long) {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val iobrokerIp = prefs.getString("iobroker_ip", "") ?: ""
        val iobrokerPort = prefs.getString("iobroker_port", "8087") ?: "8087"
        val matterInstance = prefs.getString("matter_instance", "0") ?: "0"

        if (iobrokerIp.isBlank()) {
            Snackbar.make(requireView(), "ioBroker ist noch nicht eingerichtet", Snackbar.LENGTH_LONG)
                .setAction("Einstellungen") { settingsButton.performClick() }
                .show()
            return
        }

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
                            viewLifecycleOwner.lifecycleScope.launch {
                                // Erst pruefen ob der ioBroker Matter-Adapter ueberhaupt erreichbar/alive ist.
                                // Wenn nicht: gar nicht senden, direkt den Code zur manuellen Eingabe zeigen.
                                updateShareProgress(progress, "Prüfe ioBroker-Matter-Verbindung…")
                                val matterAlive = withContext(Dispatchers.IO) {
                                    try {
                                        val u = URL("http://$iobrokerIp:$iobrokerPort/get/system.adapter.matter.$matterInstance.alive")
                                        val c = u.openConnection() as HttpURLConnection
                                        c.connectTimeout = 3000
                                        c.readTimeout = 3000
                                        if (c.responseCode == 200) {
                                            val body = c.inputStream.bufferedReader().use { it.readText() }
                                            body.contains("\"val\":true") || body.trim() == "true"
                                        } else false
                                    } catch (e: Exception) { false }
                                }
                                if (!matterAlive) {
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

                                updateShareProgress(progress, "Sende Code an ioBroker…")
                                val sent = withContext(Dispatchers.IO) {
                                    try {
                                        // Matter-Instanz an ioBroker uebergeben (Script liest sie beim Koppeln aus).
                                        runCatching {
                                            val ci = URL("http://$iobrokerIp:$iobrokerPort/set/0_userdata.0.matter_connect.instance?value=$matterInstance").openConnection() as HttpURLConnection
                                            ci.connectTimeout = 4000; ci.readTimeout = 4000; ci.responseCode
                                        }
                                        // Altes Ergebnis leeren, damit die App keinen Wert der letzten Koppelung liest.
                                        runCatching {
                                            val c0 = URL("http://$iobrokerIp:$iobrokerPort/set/0_userdata.0.matter_connect.pairing_result?value=").openConnection() as HttpURLConnection
                                            c0.connectTimeout = 4000; c0.readTimeout = 4000; c0.responseCode
                                        }
                                        val url = URL("http://$iobrokerIp:$iobrokerPort/set/0_userdata.0.matter_connect.pairing_code?value=$manualPairingCode")
                                        val conn = url.openConnection() as HttpURLConnection
                                        conn.connectTimeout = 5000
                                        conn.readTimeout = 5000
                                        conn.responseCode == 200
                                    } catch (e: Exception) { false }
                                }

                                if (!sent) {
                                    // Code kam nicht an -> manueller Weg als Fallback.
                                    activity?.runOnUiThread {
                                        progress.dialog.dismiss()
                                        if (isAdded) showManualPairingDialog(manualPairingCode, testSetupPinCode)
                                    }
                                    return@launch
                                }

                                updateShareProgress(progress, "Angekommen ✓ — ioBroker koppelt jetzt…")
                                val (resultStatus, resultMsg) = pollPairingResult(iobrokerIp, iobrokerPort)

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

        val fullStatus = statuses[nodeId] ?: "Noch keine Live-Daten"
        if (fullStatus.startsWith("Update (") && fullStatus.contains("):")) {
            val closeParenIdx = fullStatus.indexOf("):")
            val timestamp = fullStatus.substring(0, closeParenIdx + 1)
            val details = fullStatus.substring(closeParenIdx + 2).trim()

            val isExpanded = expandedStates[nodeId] ?: false
            if (isExpanded) {
                holder.statusTv.text = "$timestamp Details ▴\n$details"
                holder.statusTv.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.theme_on))
            } else {
                holder.statusTv.text = "$timestamp Details ▾"
                holder.statusTv.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.theme_accent))
            }

            holder.statusTv.setOnClickListener {
                expandedStates[nodeId] = !isExpanded
                notifyItemChanged(position)
            }
        } else {
            holder.statusTv.text = fullStatus
            holder.statusTv.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.theme_onv))
            holder.statusTv.setOnClickListener(null)
            holder.statusTv.isClickable = false
        }

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
