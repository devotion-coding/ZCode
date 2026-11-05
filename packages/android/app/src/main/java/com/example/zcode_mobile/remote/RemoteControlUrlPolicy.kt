package com.example.zcode_mobile.remote

import android.net.Uri
import com.example.zcode_mobile.BuildConfig

private const val ZCODE_REMOTE_SCHEME = "zcode"
private const val ZCODE_REMOTE_HOST = "remote"
private const val ZCODE_HOSTNAME = "zcode.z.ai"

object RemoteControlUrlPolicy {
    fun resolve(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        return resolve(uri)
    }

    fun resolve(uri: Uri?): String? {
        if (uri == null) {
            return null
        }

        if (uri.scheme == ZCODE_REMOTE_SCHEME && uri.host == ZCODE_REMOTE_HOST) {
            return buildDefaultRemoteUrl(uri)
        }

        if (isAllowedRemoteUrl(uri)) {
            return uri.toString()
        }

        return null
    }

    fun isAllowedRemoteUrl(raw: String?): Boolean {
        val uri = runCatching { Uri.parse(raw?.trim().orEmpty()) }.getOrNull() ?: return false
        return isAllowedRemoteUrl(uri)
    }

    fun isRemotePageUrl(uri: Uri?): Boolean = uri != null && isAllowedRemoteUrl(uri)

    /**
     * 远控页面使用 OAuth 登录：手机必须停留在同一个 WebView 里完成
     * chat.z.ai / bigmodel.cn 的授权跳转，否则回调只会落在外部浏览器，
     * App 的 localStorage 拿不到 token，用户会一直停在登录页。
     */
    fun isAuthFlowUrl(uri: Uri?): Boolean {
        if (uri == null) {
            return false
        }
        if (uri.scheme?.lowercase() != "https") {
            return false
        }
        val host = uri.host?.lowercase().orEmpty()
        if (host == ZCODE_HOSTNAME) {
            return false
        }
        if (host == "chat.z.ai" || host == "bigmodel.cn" || host.endsWith(".bigmodel.cn")) {
            return true
        }
        val redirectUri = uri.getQueryParameter("redirect_uri")
            ?: uri.getQueryParameter("redirect")
        return redirectUri != null &&
            runCatching { Uri.parse(redirectUri) }.getOrNull()?.let(::isRemotePageUrl) == true
    }

    /**
     * 主框架导航是否继续交给 WebView。远控壳不是通用浏览器，但登录期间必须
     * 放行任意 https 跳转（授权页可能还会跳到 Google 等第三方身份提供方），
     * 登录完成回到 /remote 后立即恢复白名单语义。
     */
    fun isAllowedWebViewNavigation(uri: Uri?, authFlowActive: Boolean): Boolean {
        if (uri == null) {
            return false
        }
        if (isRemotePageUrl(uri)) {
            return true
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme == "https" && host == ZCODE_HOSTNAME) {
            return true
        }
        if (isAuthFlowUrl(uri)) {
            return true
        }
        if (authFlowActive && (scheme == "https" || (BuildConfig.DEBUG && scheme == "http"))) {
            return true
        }
        return false
    }

    private fun buildDefaultRemoteUrl(uri: Uri): String {
        val base = Uri.parse(BuildConfig.REMOTE_CONTROL_DEFAULT_URL).buildUpon()
        uri.encodedQuery?.let { base.encodedQuery(it) }
        uri.encodedFragment?.let { base.encodedFragment(it) }
        return base.build().toString()
    }

    private fun isAllowedRemoteUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()

        if (scheme == "https" && host == "zcode.z.ai" && path.startsWith("/remote")) {
            return true
        }

        if (!BuildConfig.DEBUG) {
            return false
        }

        // Bugfix: 开发机真机调试时二维码会指向局域网 Vite 地址。
        // 生产包仍只允许 zcode.z.ai，debug 包才放开测试域、本机和局域网 http/https，避免远控壳变成任意浏览器。
        if (!path.startsWith("/remote")) {
            return false
        }
        if (host == "zcode.z.ai") {
            return scheme == "https"
        }
        return (scheme == "http" || scheme == "https") && isDebugHost(host)
    }

    private fun isDebugHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") {
            return true
        }
        if (host.endsWith(".local")) {
            return true
        }
        if (host.startsWith("192.168.") || host.startsWith("10.")) {
            return true
        }
        return Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\.").containsMatchIn(host)
    }
}
