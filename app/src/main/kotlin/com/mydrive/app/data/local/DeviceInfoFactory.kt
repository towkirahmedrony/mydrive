package com.mydrive.app.data.local

import android.os.Build

object DeviceInfoFactory {

    fun brand(): String = Build.BRAND.orEmpty().ifBlank { "Android" }

    fun model(): String = Build.MODEL.orEmpty().ifBlank { "Device" }

    fun androidVersion(): String = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() }

    fun friendlyName(): String {
        val brand = brand()
        val model = model()
        val combined = if (model.contains(brand, ignoreCase = true)) model else "$brand $model"
        return combined.trim().ifBlank { "Android device" }
    }
}
