# Smshelper

一个使用 **Kotlin + Jetpack Compose** 编写的 Android 短信助手。通过所选 SIM 卡向运营商发送短信指令，将回复整理成话费、流量、语音和账单仪表盘。

## 功能

- 保留漫画风卡片、按压动画、四种配色、首页仪表盘及原有页面导航。
- 内置移动、联通、电信、广电的 18 类、112 条快捷指令；参数指令可填写内容后发送。
- 多 SIM 选择、卡片详情，以及按接收 SIM 隔离的账户指标。
- 直接发送与系统短信兼容模式；多段短信发送结果统一汇总。
- 小信箱、全屏大信箱、未读计数、号码/内容/日期搜索、长按回复、`1`/`TD` 快捷回复和滚动定位。
- JSON 规则文件/剪贴板导入、规则分配、云端订阅、手动刷新、按间隔在应用启动时检查更新。
- TXT 短信备份导入/导出、四位数字 PIN、清空历史及完整重置。
- 点击、长按、确认和错误反馈统一通过系统触感接口执行，Debug 与 Release 共用实现。

内置指令是可编辑规则，并不保证所有地区、套餐或时间均可使用；办理类指令可能改变业务或产生费用，请核对运营商回复。同步向导提交的是查询请求，不把“已提交”当作已经收到最新数据。

## 使用

需要 Android 8.0（API 26）或更高版本，以及可用于收发短信的 SIM。允许电话状态和相关短信权限后选择 SIM；直接发送受手机系统的短信权限、短号码确认和运营商网络限制。兼容模式会打开系统短信应用，最终发送及卡片选择仍以系统应用为准。

点开“小信箱”，长按该按钮进入“大信箱”；长按搜索按钮打开高级搜索，长按短信回复。长按大信箱右上角设置图标进入原有“绝对禁区”菜单，设置 PIN 或管理历史。

触感反馈遵循系统“触摸反馈/振动”设置及设备能力，不强制越过系统开关。没有物理振动器的设备不会产生震动。

## 数据与升级

- 升级保留原有配置、订阅、主题、PIN 和短信记录。旧版 AES-CBC/历史明文记录会迁移为 Android Keystore AES-GCM 加密的完整短信快照；新快照成功写入后才移除旧存储字段。
- 旧版短信没有接收 SIM 信息，因此旧指标标记为未分卡历史数据。新短信按实际接收 SIM 更新；不能确认 SIM 时保留为未归属历史，不猜测归属。
- 新版 TXT 备份采用带版本的 JSON 文本，能够保存正文中的换行和 `[SMS]` / `[END]`。同时兼容旧版 TXT 导入。备份是明文，请妥善保存。
- 导入备份不会把其他设备的数字 SIM ID 直接套用到本机；导入记录作为未归属历史，避免覆盖正在使用的卡片指标。
- Keystore 密钥绑定安装设备，系统云备份/设备迁移不备份短信存储文件；跨设备迁移短信请使用应用内 TXT 导出/导入。无法解密的原文件不会被空数据覆盖，界面会提示存储异常。
- 规则订阅支持 HTTPS 和用户主动添加的 HTTP 地址。建议使用 HTTPS；HTTPS 订阅不会跟随降级为 HTTP 的重定向。应用不会向订阅服务器上传短信。
- 自定义规则保留 Java/Android 正则语法。文件、正文解析长度和匹配数量有上限，但这些限制不是正则运行时间保证；请只导入可信来源的规则。

## 构建与发布

使用 JDK 21、仓库自带的 Gradle Wrapper 和 Android SDK 36.1。首次构建需要下载依赖和对应 SDK，并接受 Android SDK 许可。

```bash
bash ./gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease
bash ./gradlew assembleDebug assembleRelease assembleDebugAndroidTest
```

生成四种 ABI（armeabi-v7a、arm64-v8a、x86、x86_64）及 universal APK。发布版保留现有的未开启压缩配置。版本名、版本码和文件命名规则、签名密钥配置见 [构建与发布说明](.github/BUILDING.md)。

PR 的 GitHub Actions 执行检查并上传调试签名的 Debug APK、未签名的 Release APK，不使用发布密钥、不发布版本。`v*` 标签或手动运行才进入签名任务；标签触发会创建 GitHub Release。**PR 合并由维护者手动执行。**

## 代码结构

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` | 页面入口、PIN 生命周期、完整重置 |
| `HomeScreen.kt` / `SettingsAndSecrets.kt` | 保留原有页面和交互 |
| `UIComponents.kt` / `Haptics.kt` | 公共卡片、按钮、密码键盘和触感 |
| `SimRepository.kt` / `SmsDispatcher.kt` / `SmsReceiver.kt` | SIM 信息、短信发送回执和异步接收 |
| `ConfigManager.kt` / `ConfigValidation.kt` | 配置持久化、运营商匹配、订阅更新和校验 |
| `SmsParser.kt` / `SmsRepository.kt` / `CryptoUtil.kt` | 解析、按卡存储、旧数据迁移和加密 |
| `TxtBackup.kt` / `Models.kt` / `ThemeManager.kt` | 备份格式、数据模型和原有配色 |

单元测试覆盖解析、数据迁移、账户隔离、配置校验、备份和分段发送回执；Compose 仪器测试验证真实交互经过统一触感入口。物理震感、运营商指令有效性和不同厂商系统的短信权限需要真机验证。

## 许可证

仓库包含 [GNU GPL v3](LICENSE) 许可证。
