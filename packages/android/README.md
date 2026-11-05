# ZCode Android 远控壳

这个 APK 是 ZCode 的手机远控端：手机通过 `https://zcode.z.ai/remote/v4` 连接到**正在运行的桌面端 ZCode**，复用桌面窗口已有的 host、会话和 Agent 运行时。

它**不是**把 ZCode 的 Node/Electron 运行时打进 APK。仓库里的 Agent CLI 依赖 Node 24、PTY 和桌面进程模型，当前不支持在 Android 上独立运行；手机端只负责界面、输入和恢复连接。

## 功能与交互

- 扫码（CameraX + ML Kit）或粘贴 `https://zcode.z.ai/remote/v4?...` 链接打开远控。
- 最近 5 条成功链接保存在 App 私有存储，重启后可直接打开。
- OAuth 登录保持在同一个 WebView 内：`chat.z.ai` / `bigmodel.cn` 授权页及后续身份提供方跳转不会被丢到外部浏览器，回调后 token 写入同一份 localStorage。
- 转屏、分屏、深色模式、语言切换不会重建 Activity，也不会重新加载 WebView。
- 键盘弹出时只增加「键盘或导航栏中较高者」的底部间距，避免输入框被键盘遮挡或出现双倍空白。
- 支持网页里的附件选择（`<input type="file">`）和多选文件。
- 支持网页语音输入：申请 `RECORD_AUDIO`，并在 `WebChromeClient.onPermissionRequest` 中授权音频采集。
- WebView 渲染进程崩溃时销毁并重建页面，而不是让整个 App 被杀掉。
- 修复了原实现中 `view.url != remoteUrl` 导致 SPA 内部跳转后被反复 `loadUrl` 的问题。
- 单例 Activity、任务移除时停止保活服务、退出时清理前台通知。
- 关闭系统滚动条、按主题设置 WebView 背景色、开启离屏预光栅化，减少白闪和长列表掉帧。

生产包只允许 `https://zcode.z.ai/remote...`；Debug 包额外允许本机和局域网 `/remote` 地址，方便真机调试
`pnpm dev:web`。

## 构建

只需要 JDK 17+（本仓库验证使用 JDK 21）和 Android SDK：

- Android SDK Platform 36.1
- Build-Tools 36.1.0
- 建议使用 Gradle Wrapper 自带的 9.4.1

```bash
cd packages/android

export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 可安装的调试包（允许明文 HTTP / 局域网调试）
./gradlew :app:assembleDebug

# 正式包
./gradlew :app:assembleRelease
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

### Release 签名

`app/build.gradle.kts` 支持两种方式，二选一：

1. 在 `packages/android/keystore.properties` 中配置（已被 `.gitignore` 忽略）：

```properties
storeFile=/absolute/path/to/zcode-release.jks
storePassword=...
keyAlias=zcode
keyPassword=...
```

2. 使用环境变量：

```bash
export ZCODE_ANDROID_KEYSTORE=/absolute/path/to/zcode-release.jks
export ZCODE_ANDROID_KEYSTORE_PASSWORD=...
export ZCODE_ANDROID_KEY_ALIAS=zcode
export ZCODE_ANDROID_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

生成自签名密钥：

```bash
keytool -genkeypair -v \
  -keystore ~/zcode-android-keys/zcode-release.jks \
  -alias zcode -keyalg RSA -keysize 4096 -validity 10000
```

没有配置签名时 `assembleRelease` 仍会产出**未签名** APK，不能直接安装。请保留这个 keystore；
以后升级 APK 必须使用同一份密钥，否则需要先卸载旧版本。

## 安装

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

也可以在手机上直接打开 APK 安装（需要允许「安装未知来源应用」）。

## 使用

1. 在电脑上启动 ZCode 桌面版并打开要操作的工作区。
2. 在桌面端打开远控入口，生成二维码或复制远控链接。
3. 手机打开 ZCode App：
   - 扫码：点「扫码打开」，对准桌面二维码；
   - 或点输入框右侧「粘贴」后打开。
4. 首次使用需要在手机网页里登录与桌面相同的 Z.AI / BigModel 账号；授权完成后会自动回到远控页。
5. 打开后 App 会启动前台保活通知并保持屏幕常亮。远控会话仍然依赖桌面端在线。

## 限制

- **必须有桌面端 host**：手机不创建独立 Agent runtime、local host 或 SSH/WSL/Docker 会话，只 attach 到桌面窗口已经存在的 host。
- 桌面端休眠、退出或断网时，手机无法继续执行任务；恢复后依赖 Web 端 replayable snapshot 重新对齐。
- 长时间后台运行受 Android 前台服务策略和厂商省电策略影响；必要时在系统设置里把 ZCode 设为「不受限制」。
- 如果系统 WebView 版本过旧，远控页面可能无法加载；请在应用商店更新 Android System WebView / Chrome。
- 企业网络或 SSO 如果禁止嵌入式 WebView 登录，可能需要改用账号密码/验证码方式，或在桌面端完成登录后再扫码。

## 验证记录

本仓库的构建验证：

```bash
./gradlew :app:assembleRelease   # BUILD SUCCESSFUL
./gradlew :app:lintRelease       # BUILD SUCCESSFUL（仅依赖版本类 warning）
```

`apksigner verify` 显示 APK 使用 v2 签名方案，`aapt2 dump badging` 确认：

- `minSdkVersion=24`、`targetSdkVersion=36`
- `MainActivity`：`configChanges`、`windowSoftInputMode=adjustResize`、`launchMode=singleTop`
- 权限包含 `INTERNET`、`CAMERA`、`RECORD_AUDIO`、`FOREGROUND_SERVICE_DATA_SYNC`

在无 `/dev/kvm` 的 x86_64 软件模拟器（TCG，Android 11 / API 30）上使用 Debug 包完成：

- 冷启动、首页渲染、WebView 加载本地测试页；
- 软键盘弹出时 WebView 从 `360x520` 缩到 `360x257`，输入框保持在键盘上方；
- 横竖屏切换后页面 `marker` 和 `loads` 不变，确认未重载；横屏三键导航栏不再压住 Close；
- 文件选择器能打开并进入 Downloads；
- 麦克风权限弹窗出现，授权后 WebView 的 `getUserMedia` 到达音频源（模拟器用 `-no-audio` 启动，真实采集需真机）；
- 杀死 WebView 渲染进程后 App 未崩溃，页面自动重建并加载；
- 返回键回到首页，前台保活服务同步停止。

模拟器没有硬件加速，交互延迟不代表真机性能；真实网络、OAuth 回调、相机扫码和麦克风采集仍建议在真机上验收。
