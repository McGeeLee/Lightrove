# Lightrove · 拾光

基于 **EmbeddingGemma 2** 的原生 Android 图库搜索应用。Kotlin + Jetpack Compose + Material 3 Expressive，以 **English 为基准语言**，完整本地化为简体中文、繁体中文、日语、韩语和印地语，支持 Android 12（API 31）及以上。

## 可以做什么

- 用自然语言搜索照片、视频画面、照片和视频中的文字，以及视频声音。
- 用系统照片选择器选一张图片，以图搜图；选音频或视频文件，用前 10 秒声音查询图库。
- 拉丁、中文、日文、韩文、天城文五种文字体系的 OCR 精确检索与语义向量检索融合，保留订单号、票据编号等精确字符串。
- 视频每 5 秒取画面，音轨解码为 16 kHz 单声道，10 秒窗口、1 秒重叠。结果保留匹配来源与时间点，使用 Media3 从命中处播放。
- 图库全量／部分访问和 Photo Picker 持久授权；恢复前台时重新检查授权，删除或撤权的媒体会连同索引移除。
- Room 持久化、按媒体修改时间和大小等元数据失效；WorkManager 前台索引、进度通知停止、失败重试。单项成功结果事务提交，长视频分阶段保存，恢复时复用已完成向量。Android 16+ 支持实时进度通知，旧版保留普通进度条。
- 向量以带版本的 int8 格式存储，768 维由 3,072 字节降至 780 字节；旧 Float32 索引后台分批转换并回收数据库空闲页，无需重新运行 OCR 或模型。
- 动态配色、深浅主题、手机底部导航、平板侧栏、原生详情底部面板；紧凑搜索首页、可滚动筛选、长译文按钮换行及清晰的索引状态。首页、设置和引导使用简短标题与操作，移除重复说明，保留权限范围、模型大小、进度和错误。
- 原生系统语言入口：首次引导右上角与 Settings → App language 都直接打开 Android 系统设置，不显示应用自建的语言选择弹窗。返回后自动刷新界面与运行状态的语言，保留查询、筛选和当前引导步骤。
- 首次启动三步引导：了解搜索能力 → 选择图库访问范围 → 准备本地模型。所有授权、下载和索引均需明确操作；可随时“Set up later”，不要求先授权或等待模型下载。完成状态存入 DataStore，设置中的 Getting started 可重看；重看关闭后保留设置页位置。

## 运行

1. 用 Android Studio 打开项目，安装 **Android SDK 37.1** 和 Build Tools 37。
2. 使用 Gradle 配置的 Java 25（Android Studio bundled JBR 可用），同步依赖并运行 `app`。
3. 首次打开跟随引导授权图库或选择特定照片，也可以稍后设置；引导右上角的语言按钮直接打开系统语言设置，返回后继续当前步骤。
4. 在引导或 Settings 中下载模型或导入官方 `.litertlm` 文件，完成后在首页点击 Start indexing。下载模型不会自动开始索引；未安装模型时可先索引并搜索 OCR／文件名称。
5. Settings → App language 同样打开系统语言设置。Android 13+ 可仅更改拾光的应用语言或选择跟随系统；Android 12 打开设备语言设置，修改会影响设备语言，应用随设备变化。支持 English、简体中文、繁体中文、日语、韩语和印地语，以 English 为基准及未支持语言的回退；界面语言不限制 OCR 文字体系或搜索语言。

两个入口共用 `AppLanguageSettings`：Android 13+ 使用 `Settings.ACTION_APP_LOCALE_SETTINGS`，携带 `package:com.mcgeelee.photos` 直达本应用语言页；Android 12 使用 `Settings.ACTION_LOCALE_SETTINGS` 打开设备语言页。设备未提供专用页面时回退到可用的系统设置页面。返回应用时重新读取系统选择，并按当前语言渲染模型状态、索引进度、错误与匹配来源；语言变化引起的 Activity 配置重建保留查询和引导步骤。

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`；本地交付副本放在 `dist/`。Release 构建启用 R8、资源压缩和正式签名，缺少签名配置时会明确失败。公开仓库的普通 Debug 构建不需要发布密钥。

## 发布

[GitHub 仓库](https://github.com/McGeeLee/Lightrove) · [Release Action](https://github.com/McGeeLee/Lightrove/actions/workflows/release.yml) · [签名与发布说明](docs/RELEASING.md)

推送与 `versionName` 一致的 `v*` 标签后，GitHub Actions 自动运行单元测试与 lint，构建并验证正式签名的 APK / AAB，然后生成包含安装包和 SHA-256 校验文件的 **Release 草稿**。检查后在 GitHub 点击 Publish release。工作流也支持手动选择已有标签重跑，已正式发布的版本不会被覆盖。

签名私钥与密码通过 GitHub Actions Secrets 提供；仓库只提交公钥证书。后续版本复用同一签名身份，并递增 `versionCode`。

## 模型与隐私

运行时为 **LiteRT-LM 0.18.0**，使用 Google EmbeddingGemma 2 的官方文档所链接的 LiteRT community 转换包，CPU 执行文本、视觉和音频编码，输出相同的 768 维归一化空间。没有随机向量、关键词伪装向量或云端推理替代。

- [原始模型卡](https://huggingface.co/google/embeddinggemma-2)
- [Android 转换模型及说明](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm)
- 文件：`embeddinggemma-2-740m.litertlm`
- 大小：484,622,336 字节（约 485 MB / 462 MiB）
- SHA-256：`e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c`

模型仅在用户点击后下载，导入和下载均验证大小与 SHA-256，临时文件完成后原子替换。此版本只接受上述完整多模态模型，避免误用 text-only 或其他量化包生成不兼容索引。首次下载需要网络，之后推理和检索不需要联网。应用不上传图库内容；OCR 模型随 APK 提供。模型放在 `noBackupFilesDir`，索引与偏好设置已排除云备份和设备迁移。

## 技术栈

| 组件 | 版本／实现 |
| --- | --- |
| Android SDK | compile 37.1 / target 37 / min 31 |
| AGP / Gradle | 9.4.1 / 9.8.0 |
| Kotlin / KSP | 2.4.21 / 2.3.12，AGP built-in Kotlin |
| Compose BOM | 2026.09.00 |
| Material 3 | 1.5.0-beta01，使用 Expressive theme、motion 与 LoadingIndicator |
| Lifecycle / Activity / AppCompat | 2.11.0 / 1.13.0 / 1.8.0 |
| Room / WorkManager | 2.8.5 / 2.12.0 |
| Preferences DataStore | 1.2.1，首次引导状态 |
| LiteRT-LM | 0.18.0 |
| ML Kit OCR | Latin + Chinese + Japanese + Korean + Devanagari 16.0.1，全部 bundled |
| Coil / Media3 | 3.6.3 / 1.11.1 |

大部分依赖采用当前稳定版本；Material 3 Expressive 使用当前 beta，版本统一锁定在 `gradle/libs.versions.toml`。Gradle Wrapper 当前为 9.8.0。原生推理包含 arm64-v8a / x86_64，LiteRT arm64 动态库已检查 16 KB ELF 对齐。

## 代码结构

- `data/`：MediaStore、授权范围内的内容 URI、Room、OCR、视频取帧及向量工具。
- `ml/`：模型下载／校验、真实多模态推理、平台音轨解码和重采样。
- `indexing/`：可取消后台任务、逐媒体事务提交和部分失败重试。
- `search/`：精确文字 + 余弦相似度混合排序，每个媒体保留最优片段。
- `ui/`：纯 Compose 视图状态和界面；`PhotosViewModel` 负责协调生命周期与查询。
- `localization/`：原生应用语言与可重新本地化的运行状态；默认 `res/values` 全部使用 English，其他语种使用标准 Android 资源目录。

## 当前边界

- 视频按固定 5 秒间隔抽样，可能漏掉短于采样间隔的场景；当前不做镜头切分，也不建模动作的时间顺序。
- 声音检索直接使用音频嵌入，**不生成逐字转写字幕**。声音查询取选中文件前 10 秒；视频索引处理整个音轨。
- OCR 接入 ML Kit 的五种文字体系，图片默认执行五个识别器；**视频 OCR 默认关闭**，可在 Settings 的“Video text recognition／视频文字识别”开启，对抽样视频帧执行同样识别。设置持久保存，从下一次索引开始生效；关闭后的下一次索引会清理已有视频 OCR 文字和对应向量，保留画面／声音向量，再次开启可补建文字。未安装语义模型且关闭视频 OCR 时不会解码视频。识别结果做 Unicode 归一化与逐行去重。一个识别器失败时保留其余结果并标记可重试。新增识别器增大安装包，也会增加首次索引耗时。阿拉伯文等不在这五类中的文字尚无专用 OCR，不能等同于模型的多语言语义能力。OCR 文本保存，文本语义输入目前限制为前 2,000 个字符。
- 向量精确线性扫描，最多返回 100 条结果；0.25 的余弦候选阈值是初始工程值，未进行大规模真实图库质量评测。适合先验证个人图库；十万级以上片段应引入 ANN/分页向量存储。
- 首次索引的速度、内存和耗电依赖设备与媒体数量；工作要求非低电量、非低存储。只读取待处理媒体，未变化的图库快照不会整库重写。数据库进度最多每秒更新一次，通知最多每 3 秒更新一次，结束状态立即发布。视频每约 30 秒在阶段边界增量保存；用户取消时再保存当前结果，下一次继续复用成功的视觉／声音向量，OCR 会重新校验。系统终止进程时保留最近检查点。
- Android 16+ 使用 `ProgressStyle` 和简短百分比，只为用户手动启动、正在运行的索引请求实时活动；系统权限、设备厂商和用户设置决定是否提升显示。划走后本次任务不再请求提升，普通进度通知与停止操作仍可用。锁屏公开版本隐藏文件名。Android 16 的 WorkManager 仍受任务配额限制，Android 15+ 媒体处理前台服务也有后台时长限制；本应用依靠检查点恢复，不能保证无限后台运行。参见 [Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)、[长时间 Worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) 和 [前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)。
- int8 采用每向量缩放，保留完整 768 维、全部媒体时间戳与 OCR 文本；会引入小幅数值误差，接近的语义分数可能改变顺序。约 75% 的降幅仅指向量 BLOB，实际数据库还包含文本、索引和页开销。旧索引在非低存储状态下分批转换；数据库回收需要临时空间，不足时延后至以后启动。不会删除媒体或重建模型结果。扫描仍自动更新可见文件清单，新媒体的完整索引通过“开始整理／更新索引”触发。
- 当前验证以 Android 17 arm64 模拟器为主；真实手机上的热管理、长视频、各厂商编解码器以及 Android 12–16 权限矩阵仍需进一步验收。

## 验证

JVM 测试覆盖混合检索、模型版本隔离、异常向量、时间点去重、视频抽样、PCM/WAV、重采样、重叠窗口、OCR 部分重试与运行消息序列化。Android 测试覆盖 Room 失效／撤权事务、Compose 交互、真实中英日／韩／印地文 OCR、视频取帧和音轨解码，以及应用语言与系统同步、重建时查询保留和英文回退。首次引导测试覆盖显式权限/下载操作、跳过、重看退出、进度、英中大字体、语言切换与步骤恢复；DataStore 测试覆盖关闭重开、读取恢复和写入失败。

`GemmaEmbeddingSmokeTest` 使用真实模型验证文字、文档、图片、音频的向量维度、归一化和确定性，并检查六种语言查询能将对应的英文文档排在无关文档之前。模型未安装时该测试明确跳过。为保留已经安装的模型，使用增量安装与 instrumentation 命令执行；Gradle connected 测试可能在结束时卸载应用并清除模型：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 先在应用中下载或导入模型，再执行：
adb shell am instrument -w \
  -e class com.mcgeelee.photos.ml.GemmaEmbeddingSmokeTest \
  com.mcgeelee.photos.test/androidx.test.runner.AndroidJUnitRunner
```

测试图片与视频位于 `app/src/androidTest/assets`，只用于测试；测试会创建并在结束时删除自己拥有的 MediaStore 条目，应用不包含演示图库。

## 参考

参考 [DigUp](https://github.com/ARahim3/DigUp) 的本地多模态分段、精确文字补充、文件失效和匹配时间点思路，以 Kotlin/Android 原生 API 重新实现；未移植 Swift、llama.cpp 或其 UI。参考项目使用 MIT 许可，说明见 `docs/THIRD_PARTY_NOTICES.md`。

官方文档：[LiteRT-LM 嵌入](https://developers.google.com/edge/litert-lm/embedding_models)、[Android 集成](https://developers.google.com/edge/litert-lm/android)、[M3 发行说明](https://developer.android.com/jetpack/androidx/releases/compose-material3)、[Android 部分图库访问](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)、[应用语言](https://developer.android.com/guide/topics/resources/app-languages)、[ML Kit OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)、[Room](https://developer.android.com/training/data-storage/room)。
