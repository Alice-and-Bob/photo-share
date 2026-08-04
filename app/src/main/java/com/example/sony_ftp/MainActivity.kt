package com.example.sony_ftp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sony_ftp.ui.MainViewModel
import com.example.sony_ftp.ui.theme.Sony_ftpTheme
import com.example.sony_ftp.util.QrCodeGenerator

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            Sony_ftpTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val running by vm.serverRunning.collectAsState()
    val count by vm.photoCount.collectAsState()
    val ip by vm.ip.collectAsState()
    val config by vm.config.collectAsState()
    val httpPort by vm.httpPort.collectAsState()
    val ftpPort by vm.ftpPort.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as PhotoServerApp

    fun formatUrl(scheme: String, host: String, port: Int?, defaultPort: Int): String? {
        if (port == null) return null
        val suffix = if (port == defaultPort) "" else ":$port"
        return "$scheme://$host$suffix"
    }
    val httpMdns = formatUrl("http", vm.hostName, httpPort, vm.httpDefaultPort)
    val ipVal = ip
    val httpIp = if (ipVal != null) formatUrl("http", ipVal, httpPort, vm.httpDefaultPort) else null
    val ftpIp = if (ipVal != null) formatUrl("ftp", ipVal, ftpPort, vm.ftpDefaultPort) else null

    // 启动服务器前的权限校验：DCIM/PhotoShare 位于共享存储，需「所有文件访问权限」
    fun ensureStoragePermissionThen(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Toast.makeText(
                context,
                "需授予「所有文件访问权限」才能将照片写入 DCIM/PhotoShare",
                Toast.LENGTH_LONG
            ).show()
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        } else {
            action()
        }
    }

    val onToggleServer = {
        if (running) vm.stopServer() else ensureStoragePermissionThen { vm.startServer() }
    }

    // 「清空所有照片」确认弹窗状态
    var showClearDialog by remember { mutableStateOf(false) }
    var clearCounter by remember { mutableStateOf(true) }

    // 响应式适配：平板 / 横屏（宽 >= 600dp）采用双栏布局；手机（窄屏）保持单列。
    // 宽屏下把信息分置于左右两栏，避免单列在大屏上被拉伸失真。
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        val isWide = maxWidth >= 600.dp
        val spacing = 14.dp

        if (isWide) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    StatusCard(
                        running, count, vm.photoDirPath, onToggleServer,
                        onResetCounter = { vm.resyncCounter() },
                        onClearPhotos = { showClearDialog = true }
                    )
                    if (!running) ConfigCard(vm, config, context)
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    if (running) {
                        AccessCard(
                            vm, httpMdns, httpIp, ftpIp,
                            config, httpPort, ftpPort, context
                        )
                    } else {
                        StepsCard()
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                StatusCard(
                    running, count, vm.photoDirPath, onToggleServer,
                    onResetCounter = { vm.resyncCounter() },
                    onClearPhotos = { showClearDialog = true }
                )
                if (running) {
                    AccessCard(
                        vm, httpMdns, httpIp, ftpIp,
                        config, httpPort, ftpPort, context
                    )
                }
                if (!running) {
                    ConfigCard(vm, config, context)
                    StepsCard()
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // 「清空所有照片」二次确认弹窗（防误触），含「同时清空计数器」复选框
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("确认清空所有照片？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "该操作将删除存储目录（DCIM/PhotoShare）下的全部照片及缩略图，且不可恢复。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = clearCounter,
                            onCheckedChange = { clearCounter = it }
                        )
                        Text("同时清空计数器", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.clearPhotos(clearCounter)
                    showClearDialog = false
                }) { Text("确认清空") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    count: Int,
    photoDirPath: String,
    onToggle: () -> Unit,
    onResetCounter: () -> Unit,
    onClearPhotos: () -> Unit
) {
    Card(elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(12.dp)
                        .background(
                            if (running) Color(0xFF38D47A) else Color(0xFF9E9E9E),
                            CircleShape
                        )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (running) "Photo Share 运行中" else "服务器已停止",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text("照片数量：$count", style = MaterialTheme.typography.bodyMedium)
            Text(
                "存储目录：$photoDirPath",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (running) "停止服务器" else "启动服务器")
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onResetCounter,
                    modifier = Modifier.weight(1f)
                ) { Text("重置计数器") }
                OutlinedButton(
                    onClick = onClearPhotos,
                    modifier = Modifier.weight(1f)
                ) { Text("清空照片") }
            }
        }
    }
}

@Composable
private fun AccessCard(
    vm: MainViewModel,
    httpMdns: String?,
    httpIp: String?,
    ftpIp: String?,
    config: MainViewModel.ConfigState,
    httpPort: Int?,
    ftpPort: Int?,
    context: Context
) {
    Card(elevation = CardDefaults.cardElevation(2.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // HTTP（推荐）
            Text("浏览器访问地址（推荐）", style = MaterialTheme.typography.titleSmall)
            UrlEntry(
                url = httpMdns ?: "",
                onCopy = { copyToClipboard(context, httpMdns ?: "") }
            )
            val qr = remember(httpMdns ?: "") {
                QrCodeGenerator.generate(httpMdns ?: "", 512)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                qr?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "访问二维码",
                        modifier = Modifier.size(200.dp)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { vm.refreshIp() },
                    modifier = Modifier.weight(1f)
                ) { Text("刷新 IP") }
                OutlinedButton(
                    onClick = { copyToClipboard(context, httpMdns ?: "") },
                    modifier = Modifier.weight(1f)
                ) { Text("复制地址") }
            }

            UrlEntry(
                url = httpIp ?: "",
                onCopy = { copyToClipboard(context, httpIp ?: "") }
            )

            HorizontalDivider()

            // FTP（仅保留直接 IP 地址，不再提供 mDNS 域名，避免相机经 photoshare.local 上传）
            Text("相机 FTP 上传地址", style = MaterialTheme.typography.titleSmall)
            ftpIp?.let {
                UrlEntry(
                    label = "FTP IP",
                    url = it,
                    onCopy = { copyToClipboard(context, it) }
                )
            }
            InfoRow("用户名", config.ftpUser)
            InfoRow("密码", config.ftpPass)
            InfoRow("模式", "PASV 被动模式")
            InfoRow("当前端口", "HTTP: ${httpPort ?: "-"}    FTP: ${ftpPort ?: "-"}")
        }
    }
}

@Composable
private fun ConfigCard(
    vm: MainViewModel,
    config: MainViewModel.ConfigState,
    context: Context
) {
    Card(elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("服务器设置", style = MaterialTheme.typography.titleSmall)
            var user by remember(config) { mutableStateOf(config.ftpUser) }
            var pass by remember(config) { mutableStateOf(config.ftpPass) }
            var ftpPort by remember(config) { mutableStateOf(config.ftpPort.toString()) }
            var httpPort by remember(config) { mutableStateOf(config.httpPort.toString()) }

            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("FTP 用户名") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("FTP 密码") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ftpPort, onValueChange = { ftpPort = it },
                    label = { Text("FTP 端口") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = httpPort, onValueChange = { httpPort = it },
                    label = { Text("HTTP 端口") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "一般无需修改端口：App 会优先尝试 80/21，失败自动改用 8080/2121。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    val fPort = ftpPort.toIntOrNull() ?: config.ftpPort
                    val hPort = httpPort.toIntOrNull() ?: config.httpPort
                    val err = vm.updateConfig(user, pass, fPort, hPort)
                    if (err != null) {
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存设置") }
        }
    }
}

@Composable
private fun StepsCard() {
    Card(elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("使用步骤", style = MaterialTheme.typography.titleSmall)
            Text(
                "1. 开启本机热点\n" +
                    "2. 启动服务器\n" +
                    "3. 相机 Wi-Fi 连接本机热点\n" +
                    "4. 相机 FTP 设置填入上方地址和账号\n" +
                    "5. 拍摄后照片自动上传\n" +
                    "6. 其他设备连接热点后扫码或输入网址查看照片墙",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scroll)
        )
    }
}

/**
 * 地址行（按使用位置区分复制按钮布局，URL 全程禁止横向滚动、必须单行完整显示）：
 * - 无标签（浏览器访问地址/推荐、IP 回退）：URL 单行完整显示，复制按钮另起一行并铺满整行。
 * - 有标签（相机 FTP 上传地址的域名/IP）：标签与复制按钮同处一行（靠右），URL 在下一行单行完整显示。
 * 任何按钮都不与 URL 同行，杜绝遮挡或挤压导致网址显示不全。
 */
private enum class CopyButtonPlacement { BELOW_FULL_WIDTH, ON_LABEL_RIGHT }

@Composable
private fun UrlEntry(label: String? = null, url: String, onCopy: () -> Unit) {
    val placement: CopyButtonPlacement =
        if (label == null) CopyButtonPlacement.BELOW_FULL_WIDTH else CopyButtonPlacement.ON_LABEL_RIGHT

    val urlText: @Composable () -> Unit = {
        Text(
            url,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = if (label == null) FontWeight.Bold else FontWeight.Medium,
            color = if (label == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (placement) {
            CopyButtonPlacement.ON_LABEL_RIGHT -> {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (label != null) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onCopy) { Text("复制", fontSize = 12.sp) }
                }
                urlText()
            }
            CopyButtonPlacement.BELOW_FULL_WIDTH -> {
                if (label != null) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                urlText()
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("复制", fontSize = 12.sp) }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("url", text))
    Toast.makeText(context, "已复制：$text", Toast.LENGTH_SHORT).show()
}
