# 验证记录

日期：2026-10-17（Asia/Singapore）。当前版本：**0.3.0**（versionCode 3）。设备：Android 17 / API 37 arm64-v8a 模拟器，1080 × 2400。

## 后台增量索引、存储压缩与视频 OCR 开关（本次修改）

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:testDebugUnitTest`、`:app:lintDebug` 均通过。JVM **43 项通过，0 失败、0 跳过**；lint **0 错误、24 警告**。
- API 37 arm64 模拟器整批设备测试 **47 项通过，111.279 秒，0 跳过**：Room 增量计划／来源校验／检查点 11 项、旧库迁移 1 项、视频 OCR 偏好 4 项、物理存储回收 1 项、通知 6 项、真实 EmbeddingGemma 2 推理及量化回归 2 项、Compose 设置及六语种大字体 22 项。
- 增量索引只处理新增、来源变化、失败／中断或模型／相关设置失效的媒体；未变化且完成的媒体不会再次进入处理队列。删除／撤权后清理对应索引；扫描失败不清库；未变化快照不重复写入。模型版本变化会使相关向量失效，纯存储压缩无需重新推理。
- 视频 OCR 默认关闭，持久保存，下次索引生效；切换只使视频进入待处理队列。关闭时清理已有视频文字，保留视觉／音频向量。开启时补建文字并复用已有视觉／音频向量。图片 OCR 保持开启。
- 存储测试使用 640 个 768 维旧 Float32 向量，先转换 64 条并关闭重开数据库，再完成剩余迁移及物理回收。DB + WAL 从 **2,715,648 字节降至 741,376 字节，减少约 72.7%**；单个向量 BLOB 从 **3,072 降至 780 字节，减少约 74.6%**。记录、OCR 和测试排序保留。真实模型测试覆盖文本、图片、音频向量及六语种检索；量化仍可能影响接近分数的排序。
- Android 实时进度通知测试覆盖进度边界、状态资格、锁屏隐私、打开／停止及划走后不再请求提升。是否实际提升由系统、权限和设备实现决定。当前显示完成数／百分比／处理阶段，**尚未提供预计剩余时间**。
- 本轮运行设备为 API 37；Android 12–15 通知回退、真机后台耗电、长视频和系统配额耗尽未在本轮实测。压缩比例来自固定测试数据，不能直接推算任意用户图库的总空间降幅。
- 本轮本地产物：`app/build/outputs/apk/debug/app-debug.apk`。0.3.0 正式签名 APK / AAB 由版本标签触发 Release 工作流构建、验签并生成校验和，见 [0.3.0 发布页](https://github.com/McGeeLee/Lightrove/releases/tag/v0.3.0)。`dist/` 未更新，下方本地发布产物记录为此前版本。
- 日志：[构建](verification-logs/indexing-storage-build.txt)、[47 项设备测试](verification-logs/indexing-storage-device.txt)、[测试与存储汇总](verification-logs/indexing-storage-summary.txt)。下方旧记录保留作为历史结果，不代表本轮测试数量。

## Release 签名与工作流

- GitHub 首次端到端运行 [39022280469](https://github.com/McGeeLee/Lightrove/actions/runs/39022280469) 已成功：标签校验、云端测试与 lint、签名构建及验签、Release 草稿生成四个任务均通过。`v0.2.0` 草稿含 APK、AAB 和 SHA256SUMS，尚未正式发布。
- 本地 Debug JVM 单元测试 34 项通过；签名 Release APK 与 AAB 构建成功。
- APK 经 apksigner 验证，AAB 经 jarsigner 严格验证，两者证书 SHA-256 均与仓库公钥一致：`69bebcec726f3864e0be9619dd0cc4b94f8f4ce3ef647615269ffb1fb686c3c7`。
- 签名参数不完整时，Debug 任务图仍可配置；Release 签名检查和强制签名构建均按预期失败，没有退回未签名产物。
- 发布脚本 17 项测试通过，包括标签与版本不匹配、tag 不属于默认分支、多签名者、错误证书以及 Build Tools 37 的新版验签输出。Actionlint 和 Shell 语法检查通过。
- 签名构建、验签及失败保护日志：`verification-logs/release-signing-build.txt`、`release-signature.txt`、`release-signing-guards.txt`。发布方法与密钥备份见 [RELEASING.md](RELEASING.md)。

## Lightrove 品牌更新

- 英文及日语、韩语、印地语的应用名称统一为 **Lightrove**；简体和繁体中文保留 **拾光**。
- Debug、Release 和仪器测试 APK 均重新构建；交付文件使用 `lightrove-0.2.0-*`，SHA-256 已更新。
- 已检查 Debug / Release 内嵌的六种语言应用标签；Debug 无 testOnly，Release 无 testOnly / debuggable 标记。
- 在独立 API 37 模拟器增量安装后，现有 `AppLanguageTest` **5 项通过（29.275 秒）**，包括英文默认与未支持语言回退、系统语言列表、语言切换及跟随系统。
- 已实测英文首次引导、首页、设置和 Android 应用语言页显示 Lightrove；切换到简体中文后仍显示拾光，并保留引导步骤。下列 7 张当前界面截图已全部刷新。
- 本次品牌更新的日志为 `verification-logs/lightrove-build.txt`、`lightrove-package.txt` 和 `lightrove-localization.txt`；下文其他回归结果来自更名前的 0.2.0 功能验证。

## 0.2.0 构建与静态检查

| 项目 | 当前结果 |
| --- | --- |
| `:app:assembleDebug` | 已通过 |
| `:app:testDebugUnitTest` | 34 项通过，0 失败 |
| `:app:lintDebug` | 0 错误，19 警告 |
| 六语种资源完整性 | English、简体中文、繁体中文、日语、韩语和印地语，每份 187 条可翻译资源完整，格式占位符一致 |
| `:app:assembleRelease` / `:app:bundleRelease` | 已通过：R8 与资源压缩，正式签名 APK / AAB |

## 0.2.0 设备测试

此前已在实际运行的 AVD 上通过 **20 项后台／数据／语言仪器测试**，包括：

- ML Kit 五种文字体系 OCR；使用实际中英、日文、韩文、印地文测试图片及视频帧，不依赖模拟识别结果。
- 已安装真实 EmbeddingGemma 2 模型的多模态推理和六种语言检索，实际检查对应文档排序。
- `OnboardingPreferencesTest` 共 3 项：默认未完成、完成后另一实例可观察且关闭重开存储仍保持完成、读取失败恢复，以及写入失败不报告完成。
- 原生应用语言测试 5 项，包括系统应用语言同步、跟随系统、资源回退和配置重建后的查询／运行文案保留。此轮结果早于最新 Android 12 旧语言覆盖迁移代码，不能作为该迁移的设备验证。

原宿主 QEMU 后续在 HangDetector／GPU 路径崩溃，最终回归已改用独立的 **headless 模拟器 `emulator-5560`，软件 GPU**。

| 最终回归 | 当前结果 |
| --- | --- |
| UI、首次引导、系统语言入口及语言回归，共 34 项 | 34 项已通过，分批验证，0 跳过；执行详情见下文 |

合并去重后，已通过的设备测试共 **49 项**：此前 20 项与最终回归 34 项中有 5 项重复。这是分批验证结果，并非一次运行 49 项。

最终 34 项批次有 33 项通过；英文双倍字体用例中的文本测量断言把字符推进边界的亚像素取整误判为裁切。修正测试对 Compose 语义段落重建及 Android 居中文字像素取整的处理后，仅重跑受影响用例和测量断言测试，**2 项通过（7.389 秒）**。真实 2 像素／8 dp 高度裁切和省略负例仍会被检测到，生产界面没有为通过测试放宽裁剪。

## 0.2.0 界面精简

- 首页、设置和引导移除重复副标题、功能长说明、技术页脚和已无引用的 32 个文案资源，六语种同步清理。
- 保留图库访问范围、约 485 MB 模型大小、OCR 无需大模型、下载进度、错误及全部操作入口。
- 模型缺失／就绪时不再显示重复介绍；下载和错误状态保留实际运行信息。

## 0.2.0 手动验证

- 首次启动显示 English 三步引导，依次展示功能、图库访问和模型准备。
- 未授权图库、未安装模型时，可以点击 Finish 进入主页；没有强制授权或等待模型的要求。
- 完成后 force-stop 并冷启动应用，不再自动显示引导。设置中的 Getting started 可重新打开；按系统返回后恢复原设置页。
- 引导与设置页两个入口均实际打开 Android 系统的本应用 App language 页面。在引导第 2 步选择 `zh-CN`，返回后仍处于第 2 步且显示中文；随后选择 `en-US`，返回后仍保留步骤并显示英文。设置页显示 English (United States)，不会误标跟随系统。

本轮截图：

- [引导欢迎页（English）](screenshots/onboarding-welcome-en.png)
- [引导图库访问页（English）](screenshots/onboarding-access-en.png)
- [引导图库访问页（简体中文）](screenshots/onboarding-access-zh-CN.png)
- [引导模型准备页（English）](screenshots/onboarding-model-en.png)
- [Android 系统应用语言页](screenshots/system-app-language.png)
- [设置页与当前地区语言](screenshots/settings-en.png)
- [精简首页](screenshots/home-en.png)

## 尚未完成的兼容性验证

Android 12 / API 31–32 的旧 AppCompat 应用语言覆盖迁移，以及打开设备系统语言设置的回退逻辑已经编写；对应 Intent 构造和 fallback 分支测试已在 API 37 通过。**尚未在 API 31/32 设备上实测**，不能将 API 37 结果视为 Android 12 冷启动、迁移及设备语言切换验证。

## 构建产物与日志

交付构建使用独立输出目录，避免 Android Studio 的临时 testOnly 安装包覆盖结果。`dist/lightrove-0.2.0-debug.apk` 已实际增量安装，Manifest 无 testOnly 标记；`dist/lightrove-0.2.0.apk` 与 `.aab` 为正式签名产物，旧未签名交付包已移除。校验值见 `dist/SHA256SUMS`。

`verification-logs/` 保存最终构建、单元测试汇总、lint、34 项批次原始记录和修正后的定向回归结果；原始记录保留当时的测量断言失败，未抹除历史。

## 历史核心验证（此前版本）

以下保留原始核心功能验收记录，作为历史背景；测试数量、索引版本及 Release 结果不代表本次 0.2.0 最终回归状态。

- `:app:assembleDebug`：可安装 Debug APK。
- `:app:assembleRelease`：R8 与资源压缩，未签名 Release APK。
- `:app:testDebugUnitTest`：21 项通过，0 失败。包含项目原有 1 项模板测试。
- `:app:lintDebug`：0 错误。剩余警告主要为 Gradle 新版本提示、模板未用颜色、URI KTX 建议和空间分配 API 建议。
- AndroidJUnitRunner 仪器测试共 16 项通过，0 失败、0 跳过：完整核心测试 14 项（48.563 秒，包含原有 1 项包名测试），最终播放器回归测试 2 项（12.144 秒）。
- 真实模型已安装且 SHA-256 一致，EmbeddingGemma 2 测试实际执行了文本、文档、图像、音频推理，验证了 768 维、有限数值、单位范数与重复查询确定性。
- 中英文票据图片 OCR、0/5/10 秒视频帧 OCR，以及实际 AAC 音轨到 16 kHz PCM 的 10 秒／1 秒重叠窗口均通过。
- Room 删除／撤权级联清理、文件变化失效与事务提交通过。
- Compose 授权、模型下载／导入回调、查询输入、筛选、命中时间点和 OCR 详情测试通过。
- 生产播放器的首次命中跳转与播放后重新定位均通过实际帧 PTS 验证；测试要求 5 秒位置显示 5 秒帧。
- 实际点击“开始整理”后 Android 17 前台服务启动，两个测试文件均完成 v2 索引：1 个图像、3 个视频画面、4 个文字和 2 个音频片段，向量均为 768 维。输入 `MARINA` 将测试票据排在首位；输入 `BLUE` 将视频 0:05 片段排在首位，并显示该时刻的蓝色画面缩略图。
- 最终界面截图：`screenshots/first-launch.png`、`screenshots/search.png`、`screenshots/video-hit.png`，截图中的媒体为自制测试素材；最终播放器详情已肉眼确认 0:05 蓝色画面。

测试期间发现并修复：AAC 首包负 PTS 被误判为 EOS；查询附件编码与前台权限刷新竞争；部分模态失败导致其他成功内容丢失；视频命中缩略图使用起始帧的问题，以及 API 37 模拟器 Goldfish H.264 解码器按解码顺序输出 B 帧导致的跳转偏差。后者通过实际输入／输出 PTS 追踪定位；系统软件 AVC 解码器对照测试成功输出第 5 秒帧。索引版本包含处理流程版本，AAC 修复后旧索引会在下一次整理时重建。

## 范围

这是功能与集成验证，不是检索质量基准或全机型认证。真机耗电、内存压力、十万级片段、长视频、HDR／特殊编解码器、Android 12–16 部分权限组合仍需扩展验证。视频 5 秒抽帧及语义输入上限见 README。
