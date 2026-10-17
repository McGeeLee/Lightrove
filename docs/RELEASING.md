# Lightrove 签名与发布

仓库：[McGeeLee/Lightrove](https://github.com/McGeeLee/Lightrove)。工作流：[Release](https://github.com/McGeeLee/Lightrove/actions/workflows/release.yml)。

## 当前签名身份

首次配置已经创建专用 RSA 4096 / SHA256withRSA 发布密钥，PKCS12 格式，有效期 10,000 天。公开证书位于 [`signing/release-certificate.pem`](../signing/release-certificate.pem)，SHA-256：

```text
69bebcec726f3864e0be9619dd0cc4b94f8f4ce3ef647615269ffb1fb686c3c7
```

原始工作目录的私密文件位于 `.signing/`：

- `lightrove-release.p12`：发布私钥。
- `release.properties`：本地签名配置及随机生成的密码。

目录权限为 700，私密文件为 600，整个目录已被 Git 忽略。请把这两个文件一起保存到自己的加密备份中；GitHub Secrets 不能直接读回原始值。公钥证书可以公开，私钥和密码不能提交。

仓库配置了以下 Actions Secrets：

| Secret | 内容 |
| --- | --- |
| `LIGHTROVE_KEYSTORE_BASE64` | PKCS12 私钥文件的 Base64 |
| `LIGHTROVE_KEYSTORE_PASSWORD` | Keystore 密码 |
| `LIGHTROVE_KEY_ALIAS` | 签名 key alias |
| `LIGHTROVE_KEY_PASSWORD` | Key 密码 |

Base64 只是传输编码；私密性由 GitHub Secrets 的存储和访问控制提供。恢复现有 Secrets 时运行以下命令，脚本会比对远端公开证书并拒绝替换成另一签名身份，秘密通过 stdin 上传，不出现在命令参数和日志里：

```bash
python3 scripts/signing.py upload --repo McGeeLee/Lightrove --replace
```

`python3 scripts/signing.py create` 仅用于首次初始化，已有私钥、配置或公开证书时会拒绝覆盖。公开克隆应从私密备份恢复 `.signing/` 或使用 CI 环境变量；不要为现有应用重新生成密钥。

## 发一个版本

1. 修改 `app/build.gradle.kts` 中的 `versionName`，同时递增 `versionCode`。
2. 提交并推送到默认分支 `main`。
3. 创建并推送对应的版本标签。例如 `versionName = "0.2.1"`：

```bash
git tag -a v0.2.1 -m "Lightrove 0.2.1"
git push origin v0.2.1
```

4. 等待 Release Action 成功，再检查 GitHub Releases 中的草稿并点击 **Publish release**。

草稿包含 `lightrove-<version>.apk`、`lightrove-<version>.aab` 和 `SHA256SUMS`。APK 可直接安装；AAB 用于应用商店分发，不是可直接安装的文件。若接入 Play App Signing，应按 Play 的应用签名／上传密钥机制单独配置，不能假定 Play 分发的 APK 与 GitHub APK 具有相同证书。

工作流支持 `v0.3.0-beta.1` 这类预发布标签，也可以从 Actions 页面在 `main` 上手动指定已有标签。标签必须与 `versionName` 相同，且提交位于默认分支历史中。重跑可以更新尚未发布的草稿；已发布的 Release 不会覆盖，应发布新版本。

## 工作流验证

1. `validate`：检查标签格式、版本、提交以及默认分支归属。
2. `verify`：执行发布脚本测试、JVM 单元测试和 Android lint；此任务没有签名 Secrets。
3. `sign`：恢复临时私钥，以 Java 25、Android SDK 37.1、Build Tools 37 构建 APK / AAB；校验版本、APK 安装标记、两种产物的签名与仓库公钥证书，并生成校验和。
4. `publish`：再次核对远端 tag 与构建提交一致，仅此任务有 `contents: write`，用于创建或更新草稿。

Actions 固定到已核验的完整 commit SHA。签名任务关闭 Gradle 缓存和配置缓存，临时私钥在退出／清理步骤删除，Artifacts 仅包含校验后的发布文件。设备上的 OCR、模型推理和 UI 仪器测试需要模拟器及模型，未包含在本发布 CI 中。

## 本地构建

原始工作目录已有 `.signing/release.properties`，可直接构建：

```bash
./gradlew --no-configuration-cache -PrequireReleaseSigning=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease
```

输出为 `app/build/outputs/apk/release/app-release.apk` 和 `app/build/outputs/bundle/release/app-release.aab`。四个 `LIGHTROVE_KEYSTORE_PATH`、`LIGHTROVE_KEYSTORE_PASSWORD`、`LIGHTROVE_KEY_ALIAS`、`LIGHTROVE_KEY_PASSWORD` 环境变量可以整体覆盖本地配置；只提供一部分时不会混用本地密码，而是拒绝 Release 构建。Debug 构建仍使用 Android 的调试签名。

已安装 Debug 版时，Android 不允许用不同签名的 Release 直接覆盖。首次切换到正式签名版需要先卸载 Debug，卸载会删除应用的本地索引、模型和设置。后续正式版本使用同一密钥和递增版本号即可更新。

参考：[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。
