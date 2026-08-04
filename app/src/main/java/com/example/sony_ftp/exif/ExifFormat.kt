package com.example.sony_ftp.exif

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 纯 Kotlin 的 EXIF 数值格式化工具（不依赖 Android 类，可做 JVM 单元测试）。
 */
object ExifFormat {

    /** 0.005 -> "1/200"; 1.5 -> "1.5s"; 30.0 -> "30s" */
    fun formatShutter(seconds: Double): String? {
        if (seconds <= 0.0 || seconds.isNaN() || seconds.isInfinite()) return null
        return if (seconds < 1.0) {
            "1/${(1.0 / seconds).roundToLong()}"
        } else {
            val rounded = seconds.roundToInt()
            if (abs(seconds - rounded) < 0.01) "${rounded}s" else "${seconds}s"
        }
    }

    /** 50.0 -> "50mm"; 10.5 -> "10.5mm" */
    fun formatFocalLength(mm: Double): String? {
        if (mm <= 0.0 || mm.isNaN()) return null
        val rounded = mm.roundToInt()
        return if (abs(mm - rounded) < 0.05) "${rounded}mm" else "${mm}mm"
    }

    /** 1.8 -> "f1.8"; 8.0 -> "f8" */
    fun formatAperture(fNumber: Double): String? {
        if (fNumber <= 0.0 || fNumber.isNaN()) return null
        val rounded = fNumber.roundToInt()
        return if (abs(fNumber - rounded) < 0.01) "f$rounded" else "f$fNumber"
    }
}
