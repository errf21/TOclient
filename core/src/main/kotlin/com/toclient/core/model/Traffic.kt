package com.toclient.core.model

data class TrafficCounter(
    val uploadedBytes: Long = 0,
    val downloadedBytes: Long = 0,
) {
    init {
        require(uploadedBytes >= 0)
        require(downloadedBytes >= 0)
    }
}

data class TaggedTrafficCounter(
    val tag: String,
    val traffic: TrafficCounter,
)

data class TrafficSnapshot(
    val capturedAtMillis: Long,
    val tun: TrafficCounter,
    val engineTotal: TrafficCounter,
    val engineOutbounds: List<TaggedTrafficCounter>,
)