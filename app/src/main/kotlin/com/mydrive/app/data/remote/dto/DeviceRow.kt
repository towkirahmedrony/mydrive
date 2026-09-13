package com.mydrive.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceRow(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("device_uid") val deviceUid: String,
    @SerialName("device_name") val deviceName: String? = null,
    val brand: String? = null,
    val model: String? = null,
    @SerialName("android_version") val androidVersion: String? = null,
    val status: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class DeviceInsert(
    @SerialName("user_id") val userId: String,
    @SerialName("device_uid") val deviceUid: String,
    @SerialName("device_name") val deviceName: String,
    val brand: String,
    val model: String,
    @SerialName("android_version") val androidVersion: String,
    val status: String,
    @SerialName("last_seen_at") val lastSeenAt: String
)

@Serializable
data class DeviceLastSeenUpdate(
    @SerialName("last_seen_at") val lastSeenAt: String
)
