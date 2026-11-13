# CPF 三平台统一版本组合

唯一发布版本位于 [`../cpf-build/gradle.properties`](../cpf-build/gradle.properties) 的 `cpf.version`，group 固定为 `com.lihb.forks.worker`。Android、可选 iOS 和 OHOS 的独立构建入口及消费探针均默认读取此值；平台消费探针仍可用参数核对历史版本。

`compose.py` 使用 Python 3.9+ 标准库，将已完成构建的三组 Maven 原件组合为全新隔离仓库。它不调用 Gradle、不上传、不提交 Git、不修改输入仓库。只接受不存在的输出目录；失败现场保留，不可在同一目录重试。目录中出现 `cpf-release-receipt.json` 且 `verify` 通过后，才是可送消费编译验收的完整候选。

## 首次组合

在 fork 根目录执行；输入可以是同一个包含三个旧版本的本地 Maven 克隆，也可以是三个独立仓库。

```powershell
$OriginalRepository = 'D:\path\to\cpf-maven'
$Version = python cpf-release/compose.py version
$CandidateRepository = "cpf-release/build/candidate-$Version"
python cpf-release/compose.py compose `
  --android-repository $OriginalRepository --android-version 4.0.8-cpf.3-android.9 `
  --ios-repository $OriginalRepository --ios-version 4.0.8-cpf.3-ios.1 `
  --ohos-repository $OriginalRepository --ohos-version 4.0.8-cpf.3-ohos.3 `
  --output $CandidateRepository
python cpf-release/compose.py verify $CandidateRepository
```

输出版本默认读取唯一 `cpf.version`，仅临时预审另一个版本时才使用 `--version`。未来三组 producer 均按同一 `cpf.version` 构建时，三个输入版本参数可以都传 `$Version`；输出仍必须使用全新目录。输入原件在此之前已存在的同版本目录不会被写入。

## 制品选择与元数据

| 输出模块 | 原件来源 |
| --- | --- |
| `worker-kmp` | iOS 根 commonMain 二进制 + OHOS 根 ohosMain 二进制 + 合并的 KPSM/GMM/sources/tooling metadata |
| `worker-kmp-jvm`、`worker-kmp-iosarm64`、`worker-kmp-iossimulatorarm64` | iOS 组原件，二进制逐字节保持 |
| `worker-kmp-ohosx64`、`worker-kmp-ohosarm64` | OHOS 组原件，KLIB 逐字节保持 |
| `worker-android`、`worker-android-android` | Android 组原件，根归档与 AAR 逐字节保持 |
| `worker-ios`、`worker-ios-iosarm64`、`worker-ios-iossimulatorarm64` | iOS 组原件，根归档与 KLIB 逐字节保持 |

Android 旧 core 根 JAR 仅含 KPSM，没有 commonMain 二进制，不能充当组合根。Android 组的旧 JVM 只用于 common 源码兼容性比较，实际 JVM 采用 iOS 组；首次原件的差异仅为新增 `ProcessOneTimeWorkManager.kt`。源码比较只归一化 CRLF，不改写输出源文件字节。

组合根保留唯一的 iOS commonMain，合入 OHOS ohosMain 片段，KPSM 合并 JVM/iOS/OHOS 六个变体，保留 `iosMain -> commonMain` 的 hostSpecific/cinterop 信息及 `ohosMain -> commonMain` 的 cinterop 信息。OHOS 片段保持非 hostSpecific（缺省值为 false），二进制仍在根 JAR。SQLite/Okio 只属于 `ohosMain.moduleDependency` 和 OHOS Native 叶的运行依赖。根 sources.jar 合入 iOS commonMain/iosMain、JVM jvmMain 和 OHOS ohosMain，完整保留四个源码集。

根 `metadataApiElements` 只保留公共 API 依赖：官方 Kotlin `2.4.20`、coroutines `1.11.0`、datetime `0.8.0`、Kermit `2.2.0`，不追加 SQLite/Okio 私有实现依赖。OHOS 对它们的引用均为 internal；KPSM 的 ohosMain 依赖和两个 OHOS Native 叶的完整运行依赖保持，不向 consumer 直接补 SQLite/Okio，也不要求 iOS 配置 CPF 仓库。Android/iOS/JVM 叶依赖保持原样，公共根不覆盖成 CPF 版本。

OHOS 消费探针仅在 Native/metadata 解析图中映射 OHOS 基础制品：stdlib `2.4.21-0.1.0-13`、coroutines `1.11.0-0.1.0-07`、datetime `0.8.0-0.1.0-07`，Kermit/kermit-core 使用匹配既有 OHOS 原件的 `2.0.8-1.1.0`。该映射覆盖基础根模块及两种 OHOS 叶名；不影响 JVM 构建工具，不改变发布的公共根版本。这些基础依赖需要 OHOS 平台片段，直接选择官方 common 版本会导致 OHOS 元数据编译缺少符号。

内部 GAV、POM、GMM component/available-at、文件 url 与含版本的逻辑 name 统一重写。`files.name` 可以与物理文件名不同，校验始终依据 `files.url`。所有输出 payload 生成 MD5/SHA-1/SHA-256/SHA-512，11 个模块各有只含候选版本的 `maven-metadata.xml`。旧签名不能复用，工具拒绝带 `.asc` 的输入。

## 回执与验收边界

`cpf-release-receipt.json` 记录脚本及版本配置哈希、三平台输入版本、所有输入原件的 Maven 相对路径和哈希、所有输出文件的哈希及来源/变换、common 源码比较、依赖版本策略、合并 KPSM 和验证计数。公开回执不含本机绝对路径。旧 `publication.json` 只作为原件记录，不带入新版本；以组合回执取代它。

工具预检所有输入模块、必须文件、已有校验和、GMM URL、内部同版关系、KLIB 目标、ZIP CRC 和 common 源码兼容性；任何缺失或不匹配直接拒绝。组合后逐字节复核未变文件与两个二进制片段，重读整个输出回执。`METADATA_VERIFIED` 仅表示标准库静态验收；混合工具链元数据仍必须通过真实消费编译，Native 链接和设备运行另行验收。

集成修复首轮候选移除根 GMM 的 OHOS 私有实现项，并补上述消费解析映射。必须重新执行 iOS 元数据转换和 OHOS `compileOhosMainKotlinMetadata`，不能通过跳过该任务或只编译 Native 叶替代真实门禁。此处描述的是最小修复策略，实际可用性以主控的消费编译结果为准。

消费探针支持：Android `-PworkerAndroidRepository=<路径或URI>`（默认 `../build/repository`）、iOS `-PworkerIosRepository=<URI>`、OHOS `-PworkerOhosRepository=<路径或HTTPS>`。消费版本默认读取唯一 `cpf.version`；也可分别传 `cpf.version` / `workerIosVersion` / `workerOhosVersion`。Android 仓库参数指向此候选目录即可检验实际根 GAV 与 JVM/AAR 选择，不应直接引用文件绕过元数据。

Windows 长路径由工具内部使用 `\\?\` 前缀处理。`verify` 为只读操作，验证所有 url、索引、四类 checksum 与回执清单，不访问网络。
