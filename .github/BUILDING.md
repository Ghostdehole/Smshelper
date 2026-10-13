# 构建与发布

## 本地验证

使用 JDK 21 和 Android SDK 36.1。Gradle Wrapper 固定为 9.3.1，项目的 Gradle Daemon 同样使用 Java 21；应用的 Java 源码/字节码兼容级别仍为 11。

```bash
bash ./gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest
```

Windows 使用 `gradlew.bat` 加相同任务名。构建产物位于 `app/build/outputs/apk/`。Release 默认不在 Gradle 中签名，GitHub Actions 在校验通过后使用已有发布密钥签名。

项目通过 `android.onlyEnableUnitTestForTheTestedBuildType=false` 显式启用 Debug 与 Release 两个变体的 JVM 单元测试，避免 AGP 9 默认只为设备测试对应的 Debug 变体生成单测任务。

`assembleDebugAndroidTest` 只编译设备测试，不代表测试已经运行。连接模拟器或测试设备后，可执行 `bash ./gradlew connectedDebugAndroidTest` 运行 Compose 交互回归；触觉测试通过替身统计点击、长按、开关和 PIN 的事件次数及动作结果。

Debug 和 Release 使用相同业务代码与触觉反馈逻辑。上述自动化测试无法验证真机的震动硬件、系统触感设置、短信权限、SIM 卡以及运营商回复，这些仍需要真机检查。

## 版本

默认版本为 `2.0.1`。版本码按 `major × 1,000,000 + minor × 1,000 + patch` 计算，因此默认版本码为 `2000001`。minor、patch 各为 0–999，最终版本码必须为 1–2100000000。

```bash
bash ./gradlew assembleRelease -PappVersionName=2.1.0
```

也可以通过 `-PappVersionCode=2001001` 显式覆盖版本码。每次向现有用户分发更新，都应确保版本码高于此前分发的版本。

版本名支持 `2.1.0-beta.1`、`2.1.0+build.1` 等后缀；后缀不参与自动版本码计算，不能依靠后缀区分递增更新。分发这些版本时，需要自行安排版本码以及后续正式版的版本号，避免重复或倒退。

`BuildConfig.VERSION_NAME` 和 `BuildConfig.VERSION_CODE` 来自本次实际构建配置，界面无需另外维护版本字符串。

## GitHub Actions

- Pull Request 与推送到 `main`：分别对 Debug、Release 运行单元测试、Lint 和 APK 构建；Debug 额外编译 Compose 设备测试。上传检查报告、调试签名的 Debug APK 和明确标注未签名的 Release APK，不使用发布签名凭证，也不发布版本。CI 不会把设备测试仅编译成功标记为已经执行成功。
- 推送 `v*` 标签：检查通过后按标签内版本构建，例如 `v2.0.1` 对应内部版本 `2.0.1` / `2000001`，签名并创建 GitHub Release。
- 手动运行：输入版本名，可选输入版本码，检查通过后上传签名 APK 产物，不自动创建 GitHub Release。

发布保留 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 与 `universal` 五种 APK。签名前检查五种产物齐全且内部版本与文件名一致，签名后逐个运行 `apksigner verify`。

PR 的 `Smshelper-Debug-signed-APKs` 使用调试密钥，不能直接覆盖已用正式发布密钥安装的应用；请在模拟器或备用测试设备上检查。`Smshelper-Release-unsigned-APKs` 供检查发布构建，未签名前不能直接安装。要在原设备保留数据并覆盖更新，须使用原发布密钥签署的 Release APK。

沿用以下四个仓库 Secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。必须继续使用原发布签名，才能给现有安装正常覆盖更新。私钥只写入临时目录，并在签名步骤退出时删除；密码通过环境变量提供给 `apksigner`。

## 独立 Preview 测试版

Preview 是从 Release 派生的独立构建类型：`isDebuggable=false`，包名为 `com.ghost.smshelper.preview`，应用标签为 **SmsHelper 测试版**。正式版的包名、默认版本和发布签名流程保持原样。Preview 与正式版的数据、权限和 Android Keystore 相互独立，可以同时安装；测试版不会直接迁移或改写正式版数据。

本地构建并检查：

```bash
bash ./gradlew testPreviewUnitTest lintPreview assemblePreview assembleDebugAndroidTest -PappVersionName=2.1.0 -PpreviewBuildNumber=1
```

APK 位于 `app/build/outputs/apk/preview/`，本地使用本机 Android 调试密钥签名。`-PpreviewBuildNumber=1` 在版本名后添加 `-preview.1`，不会改变正式构建的版本名；版本码仍遵循现有 `appVersionCode` 配置。Preview 的 JVM 测试会运行相同测试源码，设备测试仍使用 Debug 变体编译或执行，不把“编译通过”当作真机运行通过。

独立工作流 **Build Preview APKs** 在推送 `preview/**`、Pull Request 或手动运行时执行 Preview 单元测试、Lint、APK 构建和 Debug 设备测试编译。CI 显式传入 `appVersionName=2.1.0`，使用工作流 `run_number` 作为测试版版本码和预览编号，生成 `2.1.0-preview.<run_number>`。它只上传以下两个可直接安装的 APK：

- `Smshelper-2.1.0-preview.<run_number>-arm64-v8a.apk`：小米 13 等 ARM64 设备。
- `Smshelper-2.1.0-preview.<run_number>-universal.apk`：包含四种架构，文件较大。

下载 **Smshelper-Preview-installable-APKs-运行编号** 产物并解压即可安装；`build-info.json` 标明提交、内部版本和签名方式，`SHA256SUMS.txt` 提供 APK 校验和。报告单独上传，产物保留 14 天。

这个工作流不会创建 GitHub Release、推送标签、合并分支或访问原正式签名 Secrets。原 **Build Release APKs** 工作流也禁止在 `preview/**` 分支通过手动运行进入正式签名任务；`main` 的手动发布及 `v*` 标签发布保持原有行为。

### 测试签名与连续升级

未配置专用测试密钥时，CI 使用当前临时 runner 的 Android 调试密钥；这是可安装的非调试 Preview APK，不需要用户先配置任何 Secrets。不同 CI 运行的密钥可能不同，不能保证覆盖升级。遇到签名不一致时，先导出需要保留的测试版数据，只卸载 **SmsHelper 测试版**，再安装新包；正式版无需卸载。不要把本地 Debug APK 或原工作流中的 Debug APK 当作这个独立测试版，它们仍沿用正式包名。

需要在多次 CI 运行之间保留测试版数据，可生成一把只供 Preview 使用的密钥，并配置以下四项仓库 Secrets：

| Secret | 内容 |
| --- | --- |
| `PREVIEW_KEYSTORE_BASE64` | 专用测试 keystore 文件的 Base64 编码 |
| `PREVIEW_KEYSTORE_PASSWORD` | keystore 密码 |
| `PREVIEW_KEY_ALIAS` | 密钥别名 |
| `PREVIEW_KEY_PASSWORD` | 密钥密码 |

这四项必须全部配置。专用测试密钥只用于 `preview/**` 分支的推送或手动运行；所有 Pull Request 构建均保留临时签名，不注入签名 Secrets。私钥仅写入 runner 临时文件，签名步骤退出时删除，不提交、不上传、不放入 Actions 缓存。请保存好这把专用测试密钥，且不要复用正式发布密钥。首次从临时测试签名切换到专用签名时，同样需要备份并重新安装一次测试版。

删除测试分支不等同于删除已安装应用或 Actions 产物；若决定不合并，可以关闭测试 PR、删除分支并卸载测试版，正式版不受影响。
