package com.mydrive.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileNameUpdate(
    @SerialName("full_name") val fullName: String
)
