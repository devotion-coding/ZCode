package com.example.zcode_mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import com.example.zcode_mobile.remote.RemoteControlKeepAliveService
import com.example.zcode_mobile.remote.RemoteControlRecentLink
import com.example.zcode_mobile.remote.RemoteControlRecentLinksStore
import com.example.zcode_mobile.remote.RemoteControlUrlPolicy
import com.example.zcode_mobile.ui.RemoteControlApp
import com.example.zcode_mobile.ui.theme.ZcodemobileTheme

class MainActivity : ComponentActivity() {
    private var remoteUrl by mutableStateOf<String?>(null)
    private var recentLinks by mutableStateOf<List<RemoteControlRecentLink>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        recentLinks = RemoteControlRecentLinksStore.read(this)

        val restoredRemoteUrl = savedInstanceState?.getString(KEY_REMOTE_URL)
        val launchRemoteUrl = restoredRemoteUrl ?: resolveRemoteUrlFromIntent(intent)
        launchRemoteUrl?.let { openRemoteUrl(it) }

        setContent {
            ZcodemobileTheme {
                RemoteControlApp(
                    remoteUrl = remoteUrl,
                    recentLinks = recentLinks,
                    onOpenUrl = ::openRemoteUrl,
                    onCloseRemote = ::closeRemote,
                    onClearRecentLinks = ::clearRecentLinks,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveRemoteUrlFromIntent(intent)?.let { openRemoteUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        // 如果首帧还没画完就被切到后台，post 里的启动会被生命周期检查跳过；
        // 回到前台时补一次，保证远控页有保活通知。
        scheduleKeepAliveStart()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        remoteUrl?.let { outState.putString(KEY_REMOTE_URL, it) }
    }

    override fun onDestroy() {
        // 用户真正退出 App（而不是转屏/分屏重建）时结束保活通知，避免留下孤儿前台服务。
        if (isFinishing) {
            RemoteControlKeepAliveService.stop(this)
        }
        super.onDestroy()
    }

    private fun openRemoteUrl(rawUrl: String): Boolean {
        val resolvedUrl = RemoteControlUrlPolicy.resolve(rawUrl) ?: return false
        recentLinks = RemoteControlRecentLinksStore.record(this, resolvedUrl)
        remoteUrl = resolvedUrl
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestNotificationPermissionIfNeeded()

        // Bugfix: 冷启动时主线程紧接着要构建 Compose/WebView。如果在这里直接
        // startForegroundService，服务的 onStartCommand 可能 5 秒内排不上主线程，
        // Android 会抛 RemoteServiceException 并杀掉进程。推迟到首帧之后启动。
        scheduleKeepAliveStart()
        return true
    }

    private fun scheduleKeepAliveStart() {
        val url = remoteUrl ?: return
        window.decorView.post {
            // 首帧前被切到后台时不要从后台启动前台服务（Android 12+ 会拒绝）；
            // 下一次 onResume 会重新调度。
            if (remoteUrl != url || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return@post
            }
            RemoteControlKeepAliveService.start(this, url)
        }
    }

    private fun closeRemote() {
        remoteUrl = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RemoteControlKeepAliveService.stop(this)
    }

    private fun clearRecentLinks() {
        recentLinks = RemoteControlRecentLinksStore.clear(this)
    }

    private fun resolveRemoteUrlFromIntent(intent: Intent?): String? {
        if (intent == null) {
            return null
        }

        intent.getStringExtra(RemoteControlKeepAliveService.EXTRA_REMOTE_URL)?.let { raw ->
            RemoteControlUrlPolicy.resolve(raw)?.let { return it }
        }
        intent.data?.let { uri ->
            RemoteControlUrlPolicy.resolve(uri)?.let { return it }
        }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { raw ->
            RemoteControlUrlPolicy.resolve(raw)?.let { return it }
        }

        return null
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    companion object {
        private const val KEY_REMOTE_URL = "remoteUrl"
        private const val REQUEST_NOTIFICATIONS = 4001
    }
}
