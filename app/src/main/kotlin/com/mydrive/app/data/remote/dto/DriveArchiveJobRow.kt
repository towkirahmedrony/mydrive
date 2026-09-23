package com.mydrive.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DriveArchiveJobRow(
    @SerialName("media_id") val mediaId: String
)
