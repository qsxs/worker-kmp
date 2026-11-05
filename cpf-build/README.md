# CPF core / Android 独立构建入口

此目录是 worker-kmp 本地 fork 的独立 Gradle 工程，以 `-p cpf-build` 选择。
根项目挂接 core 的 JVM 与 common 源集，`:android` 挂接完整 Android 模块；
不包含上游根工程的 composite build、样例、Store5 或 KSP，也不向应用增加依赖。
`android-consumer` 是另一个独立工程，不被此入口 `include`。

## 源码基线与保留规则

- 上游 release 基线：`5f763dd`。
- 本入口建立时的 fork 基线：`b4276cb8c7fa482170a182bdeffe2ea038db2ca4`，
  上游版本为 `4.0.8` 开发状态，包含 `96cec57` 的通知 hook。
- 当前 fork 暂无个人远端；保留现有 Git 历史，以便追踪上游和后续私有提交。
- `cmp-worker-kmp/src` 是实际源码，整个本地 fork 都不是可随意删除的缓存。
  `cpf-build/build` 才是本入口生成构建产物的位置。

此入口不复制、生成或修补上游源码。以下目录直接作为源集输入：

| 源集 | Kotlin 源码目录（相对于 fork 根目录） |
| --- | --- |
| commonMain | `cmp-worker-kmp/src/commonMain/kotlin` |
| jvmMain | `cmp-worker-kmp/src/jvmMain/kotlin` |
| commonTest | `cmp-worker-kmp/src/commonTest/kotlin` |
| jvmTest | `cmp-worker-kmp/src/jvmTest/kotlin` |
| androidMain（`:android`） | `cmp-worker-android/src/androidMain/kotlin` |

各源集同级的 `resources` 目录也已挂接；目录不存在时没有对应资源输入。
测试源集仅用于主控明确授权的定向测试，不代表已运行测试。
Android producer 包含该目录全部 10 个 Kotlin 文件，保留 `WorkerKmpComposeActivity`、
`WorkerKmpStarterApplication` 与 `AndroidForegroundBridge.notificationCustomizer`。
原空 `cmp-worker-android/src/androidMain/AndroidManifest.xml` 经 AGP 公共 Variant API
直接加入 Manifest 输入，不删减 API、不复制源码，也不接入上游 host/device 测试。

## 工具链与依赖

使用 fork 根目录现有 wrapper（Gradle `9.7.1`），不新增或修改 wrapper。
主控通过当前进程的 `JAVA_HOME` 选择 JDK 21，工程也指定 JDK 21 工具链；
生产 JVM 字节码目标保持上游的 `11`，不修改应用的 JVM `17` 设置。

版本直接读取 `../gradle/libs.versions.toml`：Kotlin `2.4.20`、
coroutines `1.11.0`、datetime `0.8.0`、Kermit `2.2.0`。
Android producer 也沿用 catalog 的 AGP `9.4.0`、CMP `1.12.0`、
WorkManager `2.11.2`、Koin `4.2.2`、Activity Compose `1.13.0`、compileSdk `37`、minSdk `24`。
生产依赖及其 `api` 可见性与上游 core 一致；测试依赖沿用 Kotlin Test 和
coroutines-test。Android 使用 `api(project(":"))` 依赖同组 core；WorkManager、
Compose Runtime、Activity Compose、Koin Android 的公开签名所需依赖以 `api` 暴露。
不复制依赖或版本目录，不配置本地 jar。

core 只应用 Kotlin Multiplatform 与 Gradle 自带的 `maven-publish`；Android 另应用
Android KMP library、Compose Multiplatform、Compose compiler 插件，不使用上游发布插件。
主控使用上游已有 Spotless `8.10.2` 格式化时，第一次因 configuration cache
序列化失败而未完成；第二次增加 `--no-configuration-cache` 后成功，但该版本不支持
`spotlessFiles` 参数，因此同时触及了 440 个已跟踪文件的行尾，其 canonical Git diff 为 0。
主控随后仅将这 440 个路径精确还原至 HEAD，保留已格式化的四个 A2 新文件，
还原后已跟踪文件处于 clean 状态。

后续已从实际 Spotless `8.10.2` jar 核实，限定文件应使用
`spotlessIdeHook`，值为逗号分隔的绝对文件路径；formatter 命令单独增加
`--no-configuration-cache`，避免上述序列化问题。

## 任务发现与后续验证

从 fork 根目录、已配置当前进程 JDK 21 的 PowerShell 执行任务发现：

```powershell
& .\gradlew.bat -p cpf-build tasks --all --console=plain
```

以下只是待主控以真实任务列表核实的预期名称，不是执行记录：

- `compileKotlinJvm`、`compileCommonMainKotlinMetadata`：JVM 与 common 编译入口。
- `jvmTest`：连接 commonTest 与 jvmTest，只有明确授权时才运行指定测试。
- `generatePomFileForKotlinMultiplatformPublication`、
  `generatePomFileForJvmPublication`：检查私有 POM。
- `publishKotlinMultiplatformPublicationToCpfRepository`、
  `publishJvmPublicationToCpfRepository`：写入本入口的文件仓库。

任务发现仅确认配置和任务可见性，不等于编译、测试、发布或运行验证通过。
主控还需核对真实产出的 POM、Gradle Module Metadata、common/JVM 制品及源码包；
若单 JVM 目标下的 common 编译任务被跳过，也必须如实记录实际输出。
本批未运行上述命令，不声称任何验证成功。

## 私有坐标与仓库边界

坐标由本目录 `gradle.properties` 的 `cpf.group`、`cpf.version` 提供，默认值为：

- core KMP 根坐标：`com.lihb.forks.worker:worker-kmp:4.0.8-cpf.2`。
- core JVM 坐标：`com.lihb.forks.worker:worker-kmp-jvm:4.0.8-cpf.2`。
- Android KMP 根坐标：`com.lihb.forks.worker:worker-android:4.0.8-cpf.2`。
- Android 目标叶坐标：`com.lihb.forks.worker:worker-android-android:4.0.8-cpf.2`。
- 文件仓库：`cpf-build/build/repository`，发布仓库名称为 `Cpf`。

Android 坐标按 KMP 的 `kotlinMultiplatform` / `android` publication 分别设置，
根 publication 通过 GMM 转向真实 Android AAR；叶名称、POM 与 GMM 必须由主控核实。
四个 publication 使用同一个 `cpf.version` 成组发布，已有 `4.0.8-cpf.1` 目录保留，
禁止用相同版本覆盖 A2 产物。Android 对 core 的发布依赖必须保持私有组，
不能出现 `io.github.mobilebytelabs:worker-kmp` 或重复的官方 core。

不配置 `mavenLocal()`，Maven Local 发布任务已禁用；不发布到上游
`io.github.mobilebytelabs` 命名空间，不配置任何远端发布仓库。
发布 POM 保留 Apache-2.0 许可证、上游作者与 SCM 信息；这些字段用于说明来源，
并不表示上游维护或发布此私有 fork。

本入口配置 common/JVM/Android 产物，平台重定向与元数据以实际生成内容为准，
配置本身不能证明 Android、iOS、OHOS、JS 或 Wasm 产物已经发布。
此环境无 Mac/ARM 验证条件，不能声明这些平台编译或运行通过；iOS / OHOS 等
producer 属于后续批次，不加入占位实现。

## A2 已取得的验证结果

以下为入口写入后主控取得的真实门禁结果，更新前述创建时的待验证状态。

- 工具链为 wrapper `9.7.1` / JDK `21`；独立 `tasks --all` 退出码 `0`，耗时约 `19s`。
- `compileKotlinJvm` 实际执行并通过。
- `compileKotlinMetadata` 在 single-JVM profile 下为 `SKIPPED`，只生成项目结构 metadata / GMM；这不代表 common 声明或 Native 编译通过。
- `jvmTest` 仅运行 `WorkDataTest`（31）、`ConstraintsTest`（24）、`RetryConfigTest`（11），共 66 项测试，零失败。
- `publishKotlinMultiplatformPublicationToCpfRepository` 与 `publishJvmPublicationToCpfRepository` 退出码均为 `0`，发布耗时约 `1s`。
- 实际 KMP 根坐标为 `com.lihb.forks.worker:worker-kmp:4.0.8-cpf.1`。
- 实际 JVM 坐标为 `com.lihb.forks.worker:worker-kmp-jvm:4.0.8-cpf.1`，仓库为 `cpf-build/build/repository`。
- 已核对 2 份 POM、2 份 `.module`；共 8 个 jar/POM/module 文件已记录 SHA。
- JVM 生产制品中存在 `WorkManager`、`CoroutineWorker`、`WorkData`、`WorkInfo`，字节码 major 为 `55`（Java 11）。
- 根 App 的 `aiVerifyTier1` 退出码为 `0`，耗时约 `28s`（2 executed、28 up-to-date）；App 尚未消费此 fork。
- 未完成 Android / iOS / OHOS 生产或设备验收，不据此声明全平台或所有语义修复通过。
- 本轮完成范围为 A2 JVM bootstrap 与结构 metadata；Native 声明编译留待后续目标档位。

## A3a Android producer 与消费验证（待主控执行）

本批只新增 producer 和消费探针，下面是预期验收顺序，未预填任何通过结果。
先使用 fork wrapper `9.7.1` / JDK `21` 发现 `:android` 实际编译与 AAR 任务名，
再执行真实 Android 编译、AAR 生成和整组私有发布。发布可使用：

```powershell
& .\gradlew.bat -p cpf-build tasks --all --console=plain
& .\gradlew.bat -p cpf-build publishAllPublicationsToCpfRepository :android:publishAllPublicationsToCpfRepository --console=plain
```

第二条必须在确认任务存在后执行；它不能代替确认 Kotlin 编译实际执行、AAR 含完整类，
也不能把 core metadata 的 `SKIPPED` 写成声明编译通过。发布后先解开真实 AAR 的
`META-INF/com/android/build/gradle/aar-metadata.properties`，记录 `minAgpVersion` /
`minCompileSdk` 的实际值（若字段未声明也需如实记录）。不伪造或手改 metadata。

消费探针固定 AGP `9.1.0`、Kotlin `2.4.20`、compileSdk `37`、minSdk `24`、
targetSdk `36`、JVM `17`；沿用宿主的 AGP 内置 Kotlin 配置，应用 ID 为
`com.lihb.forks.worker.probe`。它仅声明 `worker-android` 根 GAV，版本从
`../gradle.properties` 读取，也可由 `-Pcpf.version=...` 覆盖；不得额外声明 core。
私有组只从 `../build/repository` 解析，Google / Maven Central 仅解析基础依赖，
不使用 Maven Local、project/composite dependency 或直接 AAR。

从主 App 根目录使用其现有 wrapper `9.5`，令 `$consumerDir` 指向
此 fork 的 `cpf-build/android-consumer` 绝对路径后执行：

```powershell
& .\gradlew.bat -p $consumerDir checkDebugAarMetadata compileDebugKotlin assembleDebug --console=plain
& .\gradlew.bat -p $consumerDir dependencies --configuration debugCompileClasspath --console=plain
& .\gradlew.bat -p $consumerDir dependencies --configuration debugRuntimeClasspath --console=plain
```

探针只引用 core、Android WorkManager 入口、通知定制接口和两个 launcher 的完整类型。
它不构造 WorkManager、不调用初始化或调度；两个 launcher 子类不注册进 Manifest。
只允许编译和 assemble，不安装、启动或执行后台任务。

主控还需检查四份 POM/GMM 的私有 core 依赖、叶坐标与完整依赖图，确认 AAR 中含
两个 launcher 和通知 customizer，再执行根 App Tier 1。AGP `9.4.0` producer 不意味着
升级 App；若 AGP `9.1.0` metadata 检查失败，保留实际错误后再讨论 producer 兼容调整。
本批不声称 Android 设备、调度、前台通知或 Native 运行通过。

## A3a 主控取得的真实验证结果

以下记录本批门禁实测结果，更新前述待执行状态；验收范围仅为 Android producer / consumer ABI。

- producer 使用 wrapper `9.7.1` / JDK `21` / AGP `9.4.0`；`:android:compileAndroidMain` 与 `:android:bundleAar` 退出码 `0`，约 `21s`。
- AAR 为 `66112` bytes、`37` 个 class；两个 launcher 与 `notificationCustomizer` 均存在，字节码 major 为 `55`（Java 11）。
- 实际 AAR metadata 为 `minCompileSdk=37`、`minAndroidGradlePluginVersion=1.0.0`，未伪造或修改 metadata。
- 实际 Manifest 仅声明 minSdk `24`，未新增自动初始化或 launcher 入口。
- 私有 core 根、JVM、`worker-android` 根与 `worker-android-android` 叶坐标以 `4.0.8-cpf.2` 成组发布，退出码 `0`，约 `2s`。
- 已核验本组 `16` 个 jar / aar / POM / module 文件的 SHA；旧 `cpf.1` 的 `8` 个文件 hash 保持不变。
- consumer 使用主仓 wrapper `9.5` / AGP `9.1.0` / Kotlin `2.4.20` / JVM `17`，compileSdk `37`、minSdk `24`、targetSdk `36`。
- consumer 仅声明私有 Android 根 GAV；`checkDebugAarMetadata`、`compileDebugKotlin`、`assembleDebug` 退出码 `0`，约 `33s`。
- 生成 debug APK 为 `8309922` bytes；依赖图中的 core / JVM 均为私有组同版本 `.2`，无官方 `io.github.mobilebytelabs` 重复制品。
- 未安装或启动 APK，未初始化 WorkManager，也未执行调度。
- 根 App 新执行的 `aiVerifyTier1` 退出码 `0`，约 `4s`；App 的实际 SDK 依赖与业务源码未改动。
- 主控仅对指定 `8` 个路径执行实际支持的 `spotlessIdeHook`，退出码 `0`，约 `53s`，无白名单外改动。
- 未完成 iOS / OHOS 运行验收；本批通过不代表全平台或全部调度、前台通知语义已修复。
