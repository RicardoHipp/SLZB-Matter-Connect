package com.google.chip.chiptool

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import chip.devicecontroller.AttestationInfo
import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.CommissionParameters
import chip.devicecontroller.DeviceAttestationDelegate
import chip.devicecontroller.ICDRegistrationInfo
import chip.devicecontroller.NetworkCredentials
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.chip.chiptool.bluetooth.BluetoothManager
import com.google.chip.chiptool.setuppayloadscanner.CHIPDeviceInfo
import com.google.chip.chiptool.util.DeviceIdUtil
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import matter.onboardingpayload.OnboardingPayload
import matter.onboardingpayload.OnboardingPayloadParser
import matter.onboardingpayload.UnrecognizedQrCodeException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CommissioningSheetFragment : BottomSheetDialogFragment() {

    private val deviceController: ChipDeviceController
        get() = ChipClient.getDeviceController(requireContext())

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    // Layout views
    private lateinit var sheetTitle: TextView
    private lateinit var dotStep1: View
    private lateinit var dotStep2: View
    private lateinit var dotStep3: View
    private lateinit var layoutStepScan: View
    private lateinit var layoutStepConnect: View
    private lateinit var layoutStepDone: View
    private lateinit var cameraPreviewView: PreviewView
    private lateinit var scanline: View
    private lateinit var scanbox: View
    private lateinit var manualCodeEditText: EditText
    private lateinit var manualCodeBtn: Button
    private lateinit var sheetActionButton: MaterialButton
    private lateinit var commissionedDeviceNameTv: TextView

    private var scanlineAnimator: ObjectAnimator? = null
    private var gatt: BluetoothGatt? = null
    private lateinit var scope: CoroutineScope
    private var deviceInfo: CHIPDeviceInfo? = null
    private var globalTimeoutJob: kotlinx.coroutines.Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_commissioning_sheet, container, false)
        scope = viewLifecycleOwner.lifecycleScope
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Bind Views
        sheetTitle = view.findViewById(R.id.sheetTitle)
        dotStep1 = view.findViewById(R.id.dotStep1)
        dotStep2 = view.findViewById(R.id.dotStep2)
        dotStep3 = view.findViewById(R.id.dotStep3)
        layoutStepScan = view.findViewById(R.id.layoutStepScan)
        layoutStepConnect = view.findViewById(R.id.layoutStepConnect)
        layoutStepDone = view.findViewById(R.id.layoutStepDone)
        cameraPreviewView = view.findViewById(R.id.cameraPreviewView)
        scanline = view.findViewById(R.id.scanline)
        scanbox = view.findViewById(R.id.scanbox)
        manualCodeEditText = view.findViewById(R.id.manualCodeEditText)
        manualCodeBtn = view.findViewById(R.id.manualCodeBtn)
        sheetActionButton = view.findViewById(R.id.sheetActionButton)
        commissionedDeviceNameTv = view.findViewById(R.id.commissionedDeviceNameTv)

        // Start laser animation in scanbox
        scanbox.post {
            val height = scanbox.height.toFloat()
            scanlineAnimator = ObjectAnimator.ofFloat(scanline, "translationY", 0f, height - 10f).apply {
                duration = 1800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
            }
            scanlineAnimator?.start()
        }

        // Start CameraX preview
        startCamera()

        // Manual code submit
        manualCodeBtn.setOnClickListener {
            val code = manualCodeEditText.text.toString().trim()
            if (code.isNotBlank()) {
                handleInputCode(code)
            }
        }

        return view
    }

    override fun onDestroyView() {
        Log.i(DBG, "onDestroyView: raeume Sheet ab und setze BLE-Guard zurueck")
        globalTimeoutJob?.cancel()
        scanlineAnimator?.cancel()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
        forceCleanupAllBleConnections()
        // Sicherheitsnetz: Auch bei Abbruch/Timeout (dann lief onCommissioningDone nie)
        // den ChipDeviceController.connectionId-Guard zuruecksetzen, damit der naechste
        // Anlernvorgang nicht mit "Bluetooth connection already in use" abgewiesen wird.
        resetBleGuard("onDestroyView")
        super.onDestroyView()
    }

    /**
     * Setzt ueber deviceController.close() das private Feld ChipDeviceController.connectionId
     * zurueck auf 0. Das ist der eigentliche Fix gegen "zweites Geraet haengt im Timeout":
     * close() fasst NUR die BLE-connectionId an - CASE-Sessions und Subscriptions zu bereits
     * gekoppelten Thread-Geraeten bleiben unberuehrt (die laufen ueber IP, nicht ueber BLE).
     */
    private fun resetBleGuard(from: String) {
        try {
            deviceController.close()
            Log.i(DBG, "$from: deviceController.close() OK -> connectionId sollte jetzt 0 sein")
        } catch (e: Exception) {
            Log.e(DBG, "$from: deviceController.close() fehlgeschlagen", e)
        }
    }

    private fun forceCleanupAllBleConnections() {
        try {
            val platform = ChipClient.getAndroidChipPlatform(requireContext())
            val bleMgr = platform.bleManager
            
            // Per Reflection auf die private Liste "mConnections" im SDK zugreifen
            val field = bleMgr.javaClass.getDeclaredField("mConnections")
            field.isAccessible = true
            @SuppressWarnings("unchecked")
            val connections = field.get(bleMgr) as? MutableList<BluetoothGatt?>
            
            if (connections != null) {
                Log.i(DBG, "forceCleanupAllBleConnections: Reflection-Cleanup, Eintraege in mConnections=${connections.size}")
                Log.i(TAG, "Starte erzwungene BLE-Bereinigung. Einträge in Liste: ${connections.size}")
                for (gattObj in connections) {
                    if (gattObj != null) {
                        try {
                            gattObj.disconnect()
                            gattObj.close()
                            Log.i(TAG, "GATT Verbindung manuell geschlossen: ${gattObj.device?.address}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Fehler beim Schließen von GATT-Objekt", e)
                        }
                    }
                }
                connections.clear()
                Log.i(TAG, "Interne Verbindungsliste des Matter-SDKs erfolgreich geleert.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei der erzwungenen BLE-Bereinigung (Reflection)", e)
        }
        
        try {
            gatt?.disconnect()
            gatt?.close()
            Log.i(TAG, "Bluetooth GATT sauber getrennt und geschlossen")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Trennen von GATT", e)
        }
        gatt = null
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - 4.0 / 3.0) <= abs(previewRatio - 16.0 / 9.0)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireActivity())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                val metrics = DisplayMetrics().also { cameraPreviewView.display?.getRealMetrics(it) }
                val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
                
                val preview = Preview.Builder()
                    .setTargetAspectRatio(screenAspectRatio)
                    .build()
                preview.setSurfaceProvider(cameraPreviewView.surfaceProvider)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(screenAspectRatio)
                    .build()
                val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient()
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(barcodeScanner, imageProxy)
                }

                try {
                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                } catch (exc: Exception) {
                    Log.e(TAG, "Use case binding failed", exc)
                }
            },
            ContextCompat.getMainExecutor(requireActivity())
        )
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(barcodeScanner: BarcodeScanner, imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        if (rawValue != null) {
                            requireActivity().runOnUiThread {
                                handleInputCode(rawValue)
                            }
                            break
                        }
                    }
                }
                .addOnFailureListener {
                    Log.e(TAG, "Barcode scanning failed", it)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun handleInputCode(code: String) {
        Log.i(DBG, "handleInputCode: neuer Anlernversuch gestartet (Code wird nicht geloggt)")
        try {
            val parsedPayload = OnboardingPayloadParser().parseQrCode(code.uppercase())
            val devInfo = CHIPDeviceInfo.fromSetupPayload(parsedPayload)
            this.deviceInfo = devInfo
            
            // Duplicate Check
            val devicePrefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
            val existingNodeIds = DeviceIdUtil.getCommissionedNodeId(requireContext()).mapNotNull {
                try { it.toLong(16) } catch (e: Exception) { null }
            }
            val duplicateNodeId = existingNodeIds.firstOrNull { nodeId ->
                devicePrefs.getString("device_pin_$nodeId", null) == devInfo.setupPinCode.toString()
            }
            if (duplicateNodeId != null) {
                val knownName = devicePrefs.getString("device_name_$duplicateNodeId", null) ?: "Node $duplicateNodeId"
                Toast.makeText(requireContext(), "Gerät bereits als \"$knownName\" angelernt!", Toast.LENGTH_LONG).show()
                dismiss()
                return
            }

            // Deriving manual pairing code
            try {
                val manualCode = OnboardingPayloadParser().getManualPairingCodeFromPayload(devInfo.toSetupPayload())
                devicePrefs.edit()
                    .putString("pending_pairing_code", manualCode)
                    .putString("pending_pin", devInfo.setupPinCode.toString())
                    .apply()
            } catch (e: Exception) {
                devicePrefs.edit()
                    .remove("pending_pairing_code")
                    .putString("pending_pin", devInfo.setupPinCode.toString())
                    .apply()
            }

            // Stop Camera and go to step 2
            cameraProvider?.unbindAll()
            scanlineAnimator?.cancel()
            
            sheetTitle.text = "Verbinde…"
            layoutStepScan.visibility = View.GONE
            layoutStepConnect.visibility = View.VISIBLE
            dotStep1.setBackgroundResource(R.drawable.stepper_dot_inactive)
            dotStep2.setBackgroundResource(R.drawable.stepper_dot_active)

            // Connect
            startConnecting(devInfo)

        } catch (e: UnrecognizedQrCodeException) {
            Toast.makeText(requireContext(), "Ungültiger Matter QR-Code", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Code processing failed", e)
        }
    }

    private fun startConnecting(devInfo: CHIPDeviceInfo) {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val channel = prefs.getInt("fetched_channel", -1)
        val panIdHex = prefs.getString("fetched_pan_id", null)
        val extPanId = prefs.getString("fetched_ext_pan_id", null)
        val networkKey = prefs.getString("fetched_network_key", null)

        if (channel != -1 && panIdHex != null && extPanId != null && networkKey != null) {
            try {
                val opDataset = makeThreadOperationalDataset(
                    channel,
                    panIdHex.toInt(16),
                    extPanId.hexToByteArray(),
                    networkKey.hexToByteArray()
                )
                
                // Start global commissioning timeout of 60 seconds
                globalTimeoutJob = scope.launch {
                    kotlinx.coroutines.delay(60000)
                    if (isAdded && layoutStepConnect.visibility == View.VISIBLE) {
                        Log.w(TAG, "Commissioning timed out after 60 seconds")
                        Toast.makeText(requireContext(), "Koppelung überschritt Zeitlimit (Timeout)!", Toast.LENGTH_LONG).show()
                        try { gatt?.disconnect() } catch (e: Exception) {}
                        gatt = null
                        dismiss()
                    }
                }

                scope.launch {
                    val bluetoothManager = BluetoothManager()
                    val device = bluetoothManager.getBluetoothDevice(
                        requireContext(),
                        devInfo.discriminator,
                        devInfo.isShortDiscriminator
                    ) ?: run {
                        Log.e(DBG, "startConnecting: BLE-Scan fand kein Geraet (discriminator=${devInfo.discriminator}, short=${devInfo.isShortDiscriminator}) -> Symptom 1")
                        globalTimeoutJob?.cancel()
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "Gerät über Bluetooth nicht gefunden!", Toast.LENGTH_LONG).show()
                            dismiss()
                        }
                        return@launch
                    }
                    Log.i(DBG, "startConnecting: BLE-Geraet gefunden (${device.address}), verbinde GATT ...")

                    // Wrap GATT connection with a 15-second timeout
                    val gattResult = kotlinx.coroutines.withTimeoutOrNull(15000) {
                        bluetoothManager.connect(requireContext(), device)
                    }

                    if (gattResult == null) {
                        Log.e(DBG, "startConnecting: GATT-Connect Timeout (15s) fuer ${device.address}")
                        globalTimeoutJob?.cancel()
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "BLE Verbindung hängengeblieben (Timeout)!", Toast.LENGTH_LONG).show()
                            dismiss()
                        }
                        return@launch
                    }

                    gatt = gattResult
                    deviceController.setCompletionListener(ConnectionCallback())
                    setAttestationDelegate()

                    val deviceId = DeviceIdUtil.getNextAvailableId(requireContext())
                    val connId = bluetoothManager.connectionId
                    val network = NetworkCredentials.forThread(NetworkCredentials.ThreadCredentials(opDataset))
                    
                    val icdRegistrationInfo = if (devInfo.isLIT) {
                        ICDRegistrationInfo.createForDeferredConfiguration()
                    } else { null }

                    val params = CommissionParameters.Builder()
                        .setCsrNonce(null)
                        .setNetworkCredentials(network)
                        .setICDRegistrationInfo(icdRegistrationInfo)
                        .build()

                    Log.i(DBG, "startConnecting: rufe pairDeviceThroughBLE auf (deviceId=$deviceId, connId=$connId). Kommt danach KEIN 'onPairingComplete'/'onCommissioningComplete', wurde der Aufruf mit 'connection already in use' abgewiesen -> siehe onError.")
                    deviceController.pairDeviceThroughBLE(gatt, connId, deviceId, devInfo.setupPinCode, params)
                    DeviceIdUtil.setNextAvailableId(requireContext(), deviceId + 1)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to build pre-fetched operational dataset or start BLE", e)
                Toast.makeText(requireContext(), "Fehler beim Starten der Koppelung", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        } else {
            Toast.makeText(requireContext(), "Fehlende Thread-Daten. Bitte zuerst Stick abrufen.", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun setAttestationDelegate() {
        deviceController.setDeviceAttestationDelegate(15000) { devicePtr, _, errorCode ->
            Log.i(TAG, "Device attestation errorCode: $errorCode")
            requireActivity().runOnUiThread {
                // Attestation errors automatically accepted (for Ikea, etc.)
                deviceController.continueCommissioning(devicePtr, true)
            }
        }
    }

    private fun makeThreadOperationalDataset(
        channel: Int,
        panId: Int,
        xpanId: ByteArray,
        masterKey: ByteArray
    ): ByteArray {
        var dataset = byteArrayOf(0.toByte(), 3.toByte())
        dataset += 0x00.toByte()
        dataset += (channel.shr(8) and 0xFF).toByte()
        dataset += (channel and 0xFF).toByte()

        dataset += 1.toByte()
        dataset += 2.toByte()
        dataset += (panId.shr(8) and 0xFF).toByte()
        dataset += (panId and 0xFF).toByte()

        dataset += 2.toByte()
        dataset += 8.toByte()
        dataset += xpanId

        dataset += 5.toByte()
        dataset += 16.toByte()
        dataset += masterKey

        return dataset
    }

    private fun String.hexToByteArray(): ByteArray {
        return chunked(2).map { byteStr -> byteStr.toUByte(16).toByte() }.toByteArray()
    }

    private fun onCommissioningDone(nodeId: Long) {
        Log.i(DBG, "onCommissioningDone: Geraet erfolgreich gekoppelt (nodeId=$nodeId)")
        val devicePrefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val editor = devicePrefs.edit()
        
        devicePrefs.getString("pending_pairing_code", null)?.let {
            editor.putString("device_code_$nodeId", it)
        }
        devicePrefs.getString("pending_pin", null)?.let {
            editor.putString("device_pin_$nodeId", it)
        }
        editor.remove("pending_pairing_code").remove("pending_pin").apply()
        
        globalTimeoutJob?.cancel()
        DeviceIdUtil.setCommissionedNodeId(requireContext(), nodeId)
        
        // BLE-Verbindung sofort trennen und freigeben, da das Pairing über BLE abgeschlossen ist
        forceCleanupAllBleConnections()

        // KERN-FIX: den ChipDeviceController.connectionId-Guard auf 0 zuruecksetzen, damit
        // das naechste pairDeviceThroughBLE nicht mit "Bluetooth connection already in use"
        // abgewiesen wird. Gezielt hier direkt nach Erfolg, unabhaengig davon wann das Sheet
        // geschlossen wird.
        resetBleGuard("onCommissioningDone")

        // UI Update: Step 3
        requireActivity().runOnUiThread {
            sheetTitle.text = "Fertig!"
            layoutStepConnect.visibility = View.GONE
            layoutStepDone.visibility = View.VISIBLE
            dotStep2.setBackgroundResource(R.drawable.stepper_dot_inactive)
            dotStep3.setBackgroundResource(R.drawable.stepper_dot_active)
            val devicePrefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
            val reportedName = devicePrefs.getString("device_name_$nodeId", null)
            val devName = reportedName ?: (deviceInfo?.let { "Gerät ${it.productId}" } ?: "Gerät")
            commissionedDeviceNameTv.text = "$devName ist jetzt verbunden"
            
            sheetActionButton.visibility = View.VISIBLE
            sheetActionButton.setOnClickListener {
                // Refresh list on Companion Fragment and dismiss
                val navHost = parentFragmentManager.findFragmentByTag("IoBrokerCompanionFragment")
                if (navHost is IoBrokerCompanionFragment) {
                    navHost.refreshDeviceList()
                }
                dismiss()
            }
        }
    }

    inner class ConnectionCallback : com.google.chip.chiptool.GenericChipDeviceListener() {
        override fun onConnectDeviceComplete() {
            Log.i(DBG, "onConnectDeviceComplete")
        }

        override fun onStatusUpdate(status: Int) {
            Log.i(DBG, "Pairing status update: $status")
        }

        override fun onCommissioningComplete(nodeId: Long, errorCode: Long) {
            globalTimeoutJob?.cancel()
            if (errorCode == 0L) {
                Log.i(DBG, "onCommissioningComplete: ERFOLG (nodeId=$nodeId)")
                onCommissioningDone(nodeId)
            } else {
                Log.e(DBG, "onCommissioningComplete: FEHLER errorCode=$errorCode")
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Koppelung fehlgeschlagen! Code: $errorCode", Toast.LENGTH_LONG).show()
                    dismiss()
                }
            }
        }

        override fun onPairingComplete(code: Long) {
            Log.i(DBG, "onPairingComplete: code=$code")
            if (code != 0L) {
                globalTimeoutJob?.cancel()
                Log.e(DBG, "onPairingComplete: FEHLER code=$code")
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Koppelung fehlgeschlagen! Code: $code", Toast.LENGTH_LONG).show()
                    dismiss()
                }
            }
        }

        // WICHTIG: Genau hier landete bisher unbemerkt der Fall
        // "Bluetooth connection already in use." beim zweiten Geraet. Ohne diese Ueberschreibung
        // passierte sichtbar nichts -> Sheet haengt bis zum 60s-globalTimeoutJob.
        override fun onError(error: Throwable?) {
            globalTimeoutJob?.cancel()
            Log.e(DBG, "ConnectionCallback.onError: ${error?.message}", error)
            if (isAdded) {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Koppelung abgebrochen: ${error?.message}", Toast.LENGTH_LONG).show()
                    dismiss()
                }
            }
        }
    }

    companion object {
        private const val TAG = "CommissioningSheet"
        // Eigener Tag fuer die Fehlersuche via:  adb logcat -s SLZBPair
        private const val DBG = "SLZBPair"
        fun newInstance() = CommissioningSheetFragment()
    }
}
