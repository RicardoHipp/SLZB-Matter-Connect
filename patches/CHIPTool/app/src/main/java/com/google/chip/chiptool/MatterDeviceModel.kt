package com.google.chip.chiptool

import chip.devicecontroller.model.NodeState

/** Ein einzelner ausgelesener Wert eines Geräts, mit lesbarem Namen (aus MatterNames). */
data class MatterReading(
    val endpointId: Int,
    val clusterId: Long,
    val attributeId: Long,
    val value: String,
) {
    /** z.B. "On/Off / OnOff" oder Fallback "Cluster 0x0406 / Attr 0x0000". */
    val label: String
        get() = "${MatterNames.cluster(clusterId)} / ${MatterNames.attribute(clusterId, attributeId)}"
}

/** Aus einem NodeState abgeleitetes, generisches Geräte-Modell. */
data class MatterDeviceModel(
    val switchable: Boolean,
    val onOffEndpoint: Int?,
    val readings: List<MatterReading>,
)

object MatterModelParser {
    private const val ONOFF_CLUSTER = 6L // 0x0006

    // Globale Meta-Attribute (0xFFF8..0xFFFD) – technisch, keine echten Werte -> ausblenden.
    private val GLOBAL_META_ATTRS = setOf(65528L, 65529L, 65530L, 65531L, 65532L, 65533L)

    // Infrastruktur-/Verwaltungs-Cluster (kein "echter" Gerätewert) -> komplett ausblenden.
    // Funktionale Cluster (OnOff 6, LevelControl 8, Switch 59, PowerSource 47, Sensoren 0x0400+ …)
    // bleiben erhalten.
    private val UTILITY_CLUSTERS = setOf(
        3L,   // Identify
        4L,   // Groups
        29L,  // Descriptor
        30L,  // Binding
        31L,  // Access Control
        37L,  // Actions
        40L,  // Basic Information (Metadaten; Gerätename kommt bereits oben)
        41L, 42L,             // OTA Provider / Requestor
        43L, 44L, 45L, 46L,   // Localization / Power Source Configuration
        48L, 49L,             // General / Network Commissioning
        50L, 51L, 52L, 53L, 54L, 55L, // Diagnostics (Logs/General/Software/Thread/WiFi/Ethernet)
        56L,  // Time Synchronization
        60L,  // Administrator Commissioning
        62L,  // Operational Credentials
        63L,  // Group Key Management
        64L, 65L,             // Fixed / User Label
        70L,  // ICD Management
    )

    fun parse(nodeState: NodeState): MatterDeviceModel {
        var switchable = false
        var onOffEndpoint: Int? = null
        val readings = ArrayList<MatterReading>()

        for ((endpointId, endpointState) in nodeState.endpointStates) {
            for ((clusterId, clusterState) in endpointState.clusterStates) {
                if (clusterId == ONOFF_CLUSTER) {
                    switchable = true
                    if (onOffEndpoint == null) onOffEndpoint = endpointId
                }
                if (clusterId in UTILITY_CLUSTERS) continue // Infrastruktur/Verwaltung überspringen
                for ((attributeId, attributeState) in clusterState.attributeStates) {
                    if (attributeId in GLOBAL_META_ATTRS) continue
                    val v = attributeState.value
                    readings.add(
                        MatterReading(
                            endpointId, clusterId, attributeId,
                            MatterValueFormatter.format(clusterId, attributeId, v)
                        )
                    )
                }
            }
        }
        // Stabile, lesbare Reihenfolge: nach Endpoint, dann Cluster, dann Attribut.
        readings.sortWith(compareBy({ it.endpointId }, { it.clusterId }, { it.attributeId }))
        return MatterDeviceModel(switchable, onOffEndpoint, readings)
    }
}

/**
 * Macht Roh-Werte menschenlesbar: Einheiten/Skalierung für die gängigen Cluster,
 * Booleans als Ja/Nein bzw. An/Aus. Unbekanntes bleibt roh (kein Bruch).
 */
object MatterValueFormatter {
    private val DE = java.util.Locale.GERMANY

    fun format(clusterId: Long, attributeId: Long, raw: Any?): String {
        if (raw == null) return "—"

        when (clusterId) {
            47L -> when (attributeId) { // Power Source
                12L -> asLong(raw)?.let { return "${it / 2} %" }                       // BatPercentRemaining (0,5 %-Schritte)
                11L -> asLong(raw)?.let { return "%.2f V".format(DE, it / 1000.0) }     // BatVoltage (mV)
            }
            1026L -> if (attributeId == 0L) asLong(raw)?.let { return "%.1f °C".format(DE, it / 100.0) } // TemperatureMeasurement
            1029L -> if (attributeId == 0L) asLong(raw)?.let { return "%.1f %%".format(DE, it / 100.0) } // RelativeHumidity
            1030L -> if (attributeId == 0L) asLong(raw)?.let {                          // OccupancySensing
                return if ((it.toInt() and 1) != 0) "Bewegung: ja" else "Bewegung: nein"
            }
        }

        if (raw is Boolean) {
            return if (clusterId == 6L) { if (raw) "An" else "Aus" } else { if (raw) "Ja" else "Nein" }
        }
        return raw.toString()
    }

    private fun asLong(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Short -> v.toLong()
        is Byte -> v.toLong()
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }
}
