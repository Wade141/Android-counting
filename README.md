# Android counting（月度记账）

使用 Kotlin、Jetpack Compose 和 SQLite 开发的 Android 记账应用。

## 功能

- 手动记账、分类管理、历史查询与筛选。
- 月度预算、每日预算展示和专项预算。
- 通过 Android 通知访问权限识别微信、支付宝付款通知，支持待确认与重复检测。
- 从相册或系统分享入口导入付款截图，离线 OCR 后由用户核对并保存。
- JSON 备份恢复和 CSV 导出。
- 分类关键词规则及记账完成提醒。

通知记账需要用户主动授予系统通知访问权限。手机厂商的后台限制、通知内容和截图版式可能影响识别结果，请核对账目。

## 构建

需要 Android SDK（API 35）和兼容的 JDK，建议使用 Android Studio 自带的 JDK。最低支持 Android 8.0（API 26）。

1. 使用 Android Studio 打开项目。
2. 在本机配置 Android SDK；本地 local.properties 不应提交。
3. 等待 Gradle 同步后运行 app，或在 Windows 执行：

    .\gradlew.bat :app:assembleDebug

macOS / Linux：

    ./gradlew :app:assembleDebug

首次构建需要下载 Gradle 和依赖。本项目使用本地捆绑的中文 OCR 模型，无需配置云端 OCR API 密钥。Release 签名由使用者自行配置，仓库不提供签名密钥。

## 测试

    .\gradlew.bat :app:testDebugUnitTest

本公开版本保留通用测试和脱敏后的通知样本。涉及私人付款截图的 OCR 回放测试及内部调试文档未收录；测试通过不等于所有机型的真机验收完成。

## 公开内容与隐私

公开内容包括应用源码、资源、Gradle 构建文件、Gradle Wrapper 和可公开的测试。通知回归样本中的账户、商户、通知标识及相关交易数据已替换为合成示例。

本仓库从清理后的源码快照开始，不包含原开发仓库的历史记录。本机配置、API 密钥、访问令牌、签名文件、真实账本、付款截图、私人 OCR 样本、日志和备份不应提交。提交新文件前请检查内容；.gitignore 无法检查源码中硬编码的敏感信息。