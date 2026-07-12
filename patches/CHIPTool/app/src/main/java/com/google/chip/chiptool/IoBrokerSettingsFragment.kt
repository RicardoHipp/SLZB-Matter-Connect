package com.google.chip.chiptool

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class IoBrokerSettingsFragment : Fragment() {

    private lateinit var stickIpEd: TextInputEditText
    private lateinit var stickPortEd: TextInputEditText
    private lateinit var iobrokerIpEd: TextInputEditText
    private lateinit var iobrokerPortEd: TextInputEditText
    private lateinit var matterInstanceEd: TextInputEditText

    private lateinit var fetchCredentialsBtn: MaterialButton
    private lateinit var testIobrokerBtn: MaterialButton
    private lateinit var settingsDoneButton: MaterialButton
    private lateinit var settingsBackButton: ImageButton

    private lateinit var settingsThreadInfoLayout: View
    private lateinit var settingsThreadNameTv: TextView
    private lateinit var settingsThreadChannelTv: TextView
    private lateinit var settingsThreadKeyTv: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_iobroker_settings, container, false)

        stickIpEd = view.findViewById(R.id.stickIpEd)
        stickPortEd = view.findViewById(R.id.stickPortEd)
        iobrokerIpEd = view.findViewById(R.id.iobrokerIpEd)
        iobrokerPortEd = view.findViewById(R.id.iobrokerPortEd)
        matterInstanceEd = view.findViewById(R.id.matterInstanceEd)

        fetchCredentialsBtn = view.findViewById(R.id.fetchCredentialsBtn)
        testIobrokerBtn = view.findViewById(R.id.testIobrokerBtn)
        settingsDoneButton = view.findViewById(R.id.settingsDoneButton)
        settingsBackButton = view.findViewById(R.id.settingsBackButton)

        settingsThreadInfoLayout = view.findViewById(R.id.settingsThreadInfoLayout)
        settingsThreadNameTv = view.findViewById(R.id.settingsThreadNameTv)
        settingsThreadChannelTv = view.findViewById(R.id.settingsThreadChannelTv)
        settingsThreadKeyTv = view.findViewById(R.id.settingsThreadKeyTv)

        // Load configs
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        stickIpEd.setText(prefs.getString("stick_ip", ""))
        stickPortEd.setText(prefs.getString("stick_port", "8080"))
        iobrokerIpEd.setText(prefs.getString("iobroker_ip", ""))
        iobrokerPortEd.setText(prefs.getString("iobroker_port", "8082"))
        matterInstanceEd.setText(prefs.getString("matter_instance", "0"))

        loadThreadConfig()

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
            val matterInstance = matterInstanceEd.text.toString().trim().ifBlank { "0" }
            if (ip.isBlank() || port.isBlank()) {
                Toast.makeText(requireContext(), "Bitte IP und Port ausfüllen!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Save state immediately
            prefs.edit().putString("iobroker_ip", ip).putString("iobroker_port", port).putString("matter_instance", matterInstance).apply()

            viewLifecycleOwner.lifecycleScope.launch {
                Toast.makeText(requireContext(), "Teste Verbindung…", Toast.LENGTH_SHORT).show()
                // WebSocket-Test ueber den web-Adapter (socket.io v2), statt simple-api/REST.
                val (success, detail) = withContext(Dispatchers.IO) {
                    val sock = IoBrokerSocket(ip, port)
                    try {
                        if (!sock.connect()) {
                            Pair(false, "keine WebSocket-Verbindung (IP/Port/web-Adapter prüfen)")
                        } else {
                            val aliveState = sock.getState("system.adapter.matter.$matterInstance.alive")
                            val isAlive = aliveState?.optBoolean("val", false) == true
                            if (!isAlive) {
                                // Falsche/inaktive Instanz -> sofort melden. Sonst wuerde der folgende
                                // sendTo an eine nicht existierende Instanz bis zum Timeout haengen.
                                Pair(false, "Matter-Adapter matter.$matterInstance nicht aktiv/vorhanden — Instanz prüfen")
                            } else {
                                // Adapter laeuft -> echter, nur-lesender Message-Test (kurzer Timeout).
                                val br = sock.sendTo("matter.$matterInstance", "controllerThreadBorderRouters", JSONObject(), 8000)
                                if (br != null && br.has("result")) Pair(true, "Matter-Adapter läuft, Message-API antwortet")
                                else Pair(true, "Matter-Adapter läuft (Message-API ohne Antwort)")
                            }
                        }
                    } catch (e: Exception) {
                        Pair(false, e.message ?: "Fehler")
                    } finally {
                        sock.close()
                    }
                }
                if (success) {
                    Toast.makeText(requireContext(), "Verbindung erfolgreich! ($detail)", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), "Verbindung fehlgeschlagen: $detail", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Close on Done or Back arrow
        settingsDoneButton.setOnClickListener { saveAndExit() }
        settingsBackButton.setOnClickListener { saveAndExit() }

        return view
    }

    private fun saveAndExit() {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("stick_ip", stickIpEd.text.toString().trim())
            .putString("stick_port", stickPortEd.text.toString().trim())
            .putString("iobroker_ip", iobrokerIpEd.text.toString().trim())
            .putString("iobroker_port", iobrokerPortEd.text.toString().trim())
            .putString("matter_instance", matterInstanceEd.text.toString().trim().ifBlank { "0" })
            .apply()
            
        parentFragmentManager.popBackStack()
    }

    private fun fetchThreadCredentials(ip: String, port: String) {
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
                    } else { null }
                } catch (e: Exception) {
                    Log.e("CompanionSettings", "Fetch error", e)
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

                    Toast.makeText(requireContext(), "OTBR-Daten geladen!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    prefs.edit()
                        .remove("device_custom_name_stick")
                        .remove("fetched_channel")
                        .remove("fetched_pan_id")
                        .remove("fetched_ext_pan_id")
                        .remove("fetched_network_key")
                        .apply()
                    Toast.makeText(requireContext(), "Fehler beim Parsen der Daten (Alte Daten entfernt)", Toast.LENGTH_SHORT).show()
                }
            } else {
                prefs.edit()
                    .remove("device_custom_name_stick")
                    .remove("fetched_channel")
                    .remove("fetched_pan_id")
                    .remove("fetched_ext_pan_id")
                    .remove("fetched_network_key")
                    .apply()
                Toast.makeText(requireContext(), "Verbindung fehlgeschlagen (Alte Daten entfernt)", Toast.LENGTH_LONG).show()
            }
            loadThreadConfig()
        }
    }

    private fun loadThreadConfig() {
        val prefs = requireActivity().getSharedPreferences("iobroker_prefs", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("stick_ip", "")
        val channel = prefs.getInt("fetched_channel", -1)
        
        if (channel != -1 && !savedIp.isNullOrBlank()) {
            val name = prefs.getString("device_custom_name_stick", "OpenThread-Stick")
            val panId = prefs.getString("fetched_pan_id", "")
            val networkKey = prefs.getString("fetched_network_key", "")

            settingsThreadNameTv.text = "Netzwerkname · $name"
            settingsThreadChannelTv.text = "Kanal · $channel  ·  PAN ID $panId"
            settingsThreadKeyTv.text = networkKey
            settingsThreadInfoLayout.visibility = View.VISIBLE
        } else {
            settingsThreadInfoLayout.visibility = View.GONE
        }
    }

    companion object {
        fun newInstance() = IoBrokerSettingsFragment()
    }
}
