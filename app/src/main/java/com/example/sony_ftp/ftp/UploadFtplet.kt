package com.example.sony_ftp.ftp

import android.util.Log
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import java.io.File

/**
 * 监听 FTP 上传生命周期：
 * - onUploadStart: 文件正在传输（不入图库）
 * - onUploadEnd:   传输完成 -> 通知仓库索引（与 FileObserver 双保险）
 */
class UploadFtplet(
    private val rootDir: File,
    private val onUploadStart: (File) -> Unit = {},
    private val onUploadEnd: (File) -> Unit
) : DefaultFtplet() {

    companion object {
        private const val TAG = "UploadFtplet"
    }

    private fun resolveFile(session: FtpSession, request: FtpRequest): File? {
        return try {
            val ftpFile = session.fileSystemView.getFile(request.argument)
            // getAbsolutePath 返回虚拟路径（相对用户主目录），映射到物理路径
            File(rootDir, ftpFile.absolutePath.trimStart('/'))
        } catch (e: Exception) {
            Log.w(TAG, "resolveFile failed: ${request.argument}", e)
            null
        }
    }

    override fun onUploadStart(session: FtpSession, request: FtpRequest): FtpletResult {
        resolveFile(session, request)?.let {
            Log.i(TAG, "upload start: ${it.name}")
            onUploadStart(it)
        }
        return FtpletResult.DEFAULT
    }

    override fun onUploadEnd(session: FtpSession, request: FtpRequest): FtpletResult {
        resolveFile(session, request)?.let {
            Log.i(TAG, "upload end: ${it.name} (${it.length()} bytes)")
            onUploadEnd(it)
        }
        return FtpletResult.DEFAULT
    }

    override fun onUploadUniqueEnd(session: FtpSession, request: FtpRequest): FtpletResult =
        onUploadEnd(session, request)
}
