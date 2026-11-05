# Android 远程控制壳

`packages/android` 当前是 ZCode 手机远控的 Android 壳。第一版不重写 ZCode Agent、relay 或
RPC 协议，而是用原生入口承载现有 `/remote/v4` 手机 Web 远控页面。

## 入口

- 粘贴或分享 `https://zcode.z.ai/remote/v4?...` 链接；输入框支持从剪贴板一键粘贴。
- 扫描桌面端生成的远控二维码。
- 通过 `zcode://remote?...` deep link 打开。
- 首页会保存最近 5 条成功打开过的远控链接，App 重启后可以直接点开。列表只展示设备名、host
  和时间；实际完整链接保存在 App 私有 `SharedPreferences` 中。

生产包只允许打开 `https://zcode.z.ai/remote...`。Debug 包额外允许 localhost 和局域网
`/remote`，方便真机调试本地 Vite 页面。

## 登录与导航

远控页第一次使用会进入 OAuth。`RemoteControlUrlPolicy` 在保持「远控壳不是通用浏览器」的前提下：

- 允许 `zcode.z.ai` 主框架导航；
- 识别 `chat.z.ai`、`bigmodel.cn`（含子域）以及带 `redirect_uri`/`redirect` 指回远控页的
  授权 URL，并放行；
- 进入授权流程后暂时允许任意 HTTPS 主框架跳转（授权页可能继续跳到第三方身份提供方），
  回到 `/remote` 后立即恢复白名单。

这些导航必须留在同一个 WebView 内，否则 OAuth 回调只会落在外部浏览器，App 的
localStorage 拿不到 token，用户会一直停在登录页。外部普通链接仍交给系统浏览器打开。

## 手机交互

- `MainActivity` 处理 `orientation|screenSize|screenLayout|smallestScreenSize|keyboard|
  keyboardHidden|uiMode|density|locale|layoutDirection`，转屏、分屏、深色模式和语言切换不会重建
  Activity，也不会重新加载远控页。
- `windowSoftInputMode=adjustResize`；Compose 只增加「键盘或导航栏中较高者」的底部间距，避免
  输入框被键盘遮挡，也避免键盘弹出时重复叠加导航栏高度。
- WebView 仅在远控链接变化或渲染进程重建时创建；不再用 `view.url != remoteUrl` 在
  `update` 中反复 `loadUrl`，SPA 内跳转、hash 变化和重定向不会导致页面重载；新链接会重置
  上一台设备残留的加载错误覆盖层。
- `WebChromeClient.onShowFileChooser` 接入系统文件选择器，支持网页附件和多选。
- `RECORD_AUDIO` + `WebChromeClient.onPermissionRequest` 支持网页语音输入。
- `WebViewClient.onRenderProcessGone` 销毁旧 WebView 并重建页面，避免渲染进程崩溃时杀掉 App。
- 旧 WebView 在换链接/页面销毁时调用 `destroy()`，避免泄漏渲染进程。
- 横屏三键导航栏贴右侧时，顶栏与 WebView 只避开横向导航栏 inset，Close 等按钮不会被压住。
- 前台保活服务推迟到首帧之后启动，并用生命周期检查避免从后台启动；冷启动慢设备不会触发
  `startForegroundService()` 5 秒超时的 `RemoteServiceException`。
- 单例 Activity；任务移除时停止前台服务；退出时清理保活通知。

## 扫码

扫码使用 CameraX 预览和 ML Kit Barcode Scanning。扫描器只识别 QR Code，扫到内容后仍交给
`RemoteControlUrlPolicy` 校验，非法链接不会进入 WebView。

## 后台稳定性

远控页面打开后，App 会启动前台服务并保持屏幕常亮。前台服务的作用是降低 Android 在后台回收进程
或收紧网络的概率；它不改变业务事实源。手机端断线或恢复仍依赖现有 Web 远控的
`web-remote-replayable` 语义、bridge 重建和 task snapshot 对齐。

Android App 不创建独立 Agent runtime、local host 或 SSH/WSL/Docker session。手机端仍只能 attach
到桌面窗口已经存在的 host。
