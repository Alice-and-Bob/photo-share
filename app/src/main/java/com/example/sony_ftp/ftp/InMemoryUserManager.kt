package com.example.sony_ftp.ftp

import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

/**
 * 简单的内存用户管理器：单用户 + 密码认证，主目录为照片上传目录。
 * 避免依赖 properties 文件，适合 Android 环境。
 */
class InMemoryUserManager(
    private val username: String,
    private val password: String,
    private val homeDir: File,
    private val allowAnonymous: Boolean = false
) : UserManager {

    private fun buildUser(name: String): BaseUser = BaseUser().apply {
        this.name = name
        this.password = this@InMemoryUserManager.password
        homeDirectory = homeDir.absolutePath
        enabled = true
        authorities = listOf<Authority>(
            WritePermission(),
            ConcurrentLoginPermission(10, 10),
            TransferRatePermission(0, 0) // 不限速，支持大文件
        )
        maxIdleTime = 300
    }

    override fun getUserByName(name: String?): User? =
        if (name == username || (allowAnonymous && name == "anonymous")) buildUser(name!!) else null

    override fun getAllUserNames(): Array<String> = arrayOf(username)

    override fun delete(name: String?) = throw UnsupportedOperationException()

    override fun save(user: User?) = throw UnsupportedOperationException()

    override fun doesExist(name: String?): Boolean =
        name == username || (allowAnonymous && name == "anonymous")

    override fun authenticate(authentication: Authentication?): User {
        when (authentication) {
            is UsernamePasswordAuthentication -> {
                if (authentication.username == username && authentication.password == password) {
                    return buildUser(username)
                }
                throw AuthenticationFailedException("Authentication failed")
            }
            is AnonymousAuthentication -> {
                if (allowAnonymous) return buildUser("anonymous")
                throw AuthenticationFailedException("Anonymous not allowed")
            }
            else -> throw AuthenticationFailedException("Unsupported authentication")
        }
    }

    override fun getAdminName(): String = username

    override fun isAdmin(name: String?): Boolean = name == username
}
