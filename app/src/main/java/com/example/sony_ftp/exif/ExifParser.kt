package com.example.sony_ftp.exif

import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

data class ExifData(
    val focalLength: String? = null,
    val aperture: String? = null,
    val shutter: String? = null,
    val iso: String? = null,
    val dateTime: String? = null,
    val dateTimeMillis: Long? = null,
    val make: String? = null,
    val model: String? = null,
    val orientation: Int = ExifInterface.ORIENTATION_NORMAL
) {
    fun toJson(): String = JSONObject().apply {
        put("focalLength", focalLength ?: JSONObject.NULL)
        put("aperture", aperture ?: JSONObject.NULL)
        put("shutter", shutter ?: JSONObject.NULL)
        put("iso", iso ?: JSONObject.NULL)
        put("dateTime", dateTime ?: JSONObject.NULL)
        put("make", make ?: JSONObject.NULL)
        put("model", model ?: JSONObject.NULL)
    }.toString()
}

/**
 * 使用 Android ExifInterface 解析照片 EXIF。
 * 解析结果序列化为 JSON 存入 Room，后续请求直接读库，避免重复解析。
 */
object ExifParser {

    private val EXIF_DATE = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    fun parse(file: File): ExifData {
        return try {
            val exif = ExifInterface(file)

            val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
            val fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
            val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
            val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED)
            val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val millis = dateStr?.let {
                runCatching { EXIF_DATE.parse(it)?.time }.getOrNull()
            }

            ExifData(
                focalLength = if (focal > 0) ExifFormat.formatFocalLength(focal) else null,
                aperture = if (fNumber > 0) ExifFormat.formatAperture(fNumber) else null,
                shutter = if (exposure > 0) ExifFormat.formatShutter(exposure) else null,
                iso = iso,
                dateTime = dateStr,
                dateTimeMillis = millis,
                make = exif.getAttribute(ExifInterface.TAG_MAKE),
                model = exif.getAttribute(ExifInterface.TAG_MODEL),
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            )
        } catch (e: Exception) {
            ExifData()
        }
    }
}
