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
                for ((attributeId, attributeState) in clusterState.attributeStates) {
                    if (attributeId in GLOBAL_META_ATTRS) continue
                    val v = attributeState.value
                    readings.add(
                        MatterReading(endpointId, clusterId, attributeId, v?.toString() ?: "—")
                    )
                }
            }
        }
        // Stabile, lesbare Reihenfolge: nach Endpoint, dann Cluster, dann Attribut.
        readings.sortWith(compareBy({ it.endpointId }, { it.clusterId }, { it.attributeId }))
        return MatterDeviceModel(switchable, onOffEndpoint, readings)
    }
}
