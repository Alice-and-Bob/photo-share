package com.example.sony_ftp.http

/**
 * HTTP Range 头解析（纯 Kotlin，可 JVM 单元测试）。
 * 支持 "bytes=start-end" / "bytes=start-" / "bytes=-suffix"。
 */
object RangeParser {

    data class ByteRange(val start: Long, val end: Long) {
        val length: Long get() = end - start + 1
    }

    /**
     * @return null 表示无 Range 或格式非法（调用方应返回完整文件 200）
     *         start > fileLength-1 时同样返回 null（应答 416 由调用方判断）
     */
    fun parse(rangeHeader: String?, fileLength: Long): ByteRange? {
        if (rangeHeader == null || fileLength <= 0) return null
        val header = rangeHeader.trim()
        if (!header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        if (spec.isEmpty() || !spec.contains('-')) return null

        val dashIdx = spec.indexOf('-')
        val startStr = spec.substring(0, dashIdx).trim()
        val endStr = spec.substring(dashIdx + 1).trim()

        return try {
            when {
                startStr.isEmpty() && endStr.isNotEmpty() -> {
                    // 后缀范围：最后 N 字节
                    val suffix = endStr.toLong()
                    if (suffix <= 0) return null
                    val start = (fileLength - suffix).coerceAtLeast(0)
                    ByteRange(start, fileLength - 1)
                }
                startStr.isNotEmpty() -> {
                    val start = startStr.toLong()
                    if (start < 0 || start >= fileLength) return null
                    val end = if (endStr.isEmpty()) fileLength - 1
                    else endStr.toLong().coerceAtMost(fileLength - 1)
                    if (end < start) return null
                    ByteRange(start, end)
                }
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
