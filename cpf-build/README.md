# CPF core / Android / 可选 iOS 独立构建入口

此目录是 worker-kmp 本地 fork 的独立 Gradle 工程，以 `-p cpf-build` 选择。
默认根项目挂接 core 的 JVM 与 common 源集，`:android` 挂接完整 Android 模块；
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
| androidHostTest（`:android`） | `cmp-worker-android/src/androidHostTest/kotlin` |

各源集同级的 `resources` 目录也已挂接；目录不存在时没有对应资源输入。
测试源集仅用于主控明确授权的定向测试，不代表已运行测试。
Android producer 包含该目录全部 10 个 Kotlin 文件，保留 `WorkerKmpComposeActivity`、
`WorkerKmpStarterApplication` 与 `AndroidForegroundBridge.notificationCustomizer`。
原空 `cmp-worker-android/src/androidMain/AndroidManifest.xml` 经 AGP 公共 Variant API
直接加入 Manifest 输入，不删减 API、不复制源码。上游 Android host 测试接线见下文 C0，
不接入上游 device 测试。

## 工具链与依赖

使用 fork 根目录现有 wrapper（Gradle `9.7.1`），不新增或修改 wrapper。
主控通过当前进程的 `JAVA_HOME` 选择 JDK 21，工程也指定 JDK 21 工具链；
生产 JVM 字节码目标保持上游的 `11`，不修改应用的 JVM `17` 设置。

版本直接读取 `../gradle/libs.versions.toml`：Kotlin `2.4.20`、
coroutines `1.11.0`、datetime `0.8.0`、Kermit `2.2.0`。
Android producer 也沿用 catalog 的 AGP `9.4.0`、CMP `1.12.0`、
WorkManager `2.11.2`、Koin `4.2.2`、Activity Compose `1.13.0`、compileSdk `37`、minSdk `24`。
生产依赖及其 `api` 可见性与上游 core 一致；core 测试依赖沿用 Kotlin Test 和
coroutines-test，Android host 测试依赖沿用原 Android 模块的 catalog alias。
Android 使用 `api(project(":"))` 依赖同组 core；WorkManager、
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

- core KMP 根坐标：`com.lihb.forks.worker:worker-kmp:4.0.8-cpf.3-android.8`。
- core JVM 坐标：`com.lihb.forks.worker:worker-kmp-jvm:4.0.8-cpf.3-android.8`。
- Android KMP 根坐标：`com.lihb.forks.worker:worker-android:4.0.8-cpf.3-android.8`。
- Android 目标叶坐标：`com.lihb.forks.worker:worker-android-android:4.0.8-cpf.3-android.8`。
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
此环境无 Mac/ARM 验证条件，不能声明这些平台编译或运行通过；iOS producer 的
可选配置见下文，OHOS 等平台仍属于后续批次，不加入占位实现。

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

## A3a Android producer 与消费验证

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

- producer 使用 wrapper `9.7.1` / JDK `21` / AGP `9.4.0`；`:android:compileAndroidMain` 与 `:android:bundleAndroidMainAar` 退出码 `0`，约 `21s`。
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

## A3b 可选 iOS producer（Windows 配置已验，Native 待 Mac）

仅传入 `-Pcpf.enableIos=true` 才包含 `:ios`，并为 core 增加 `iosArm64` 与
`iosSimulatorArm64`；未传参数的目标、源码输入和默认 `4.0.8-cpf.2` 版本不变。
此参数不引入上游根工程、样例或发布插件，也不修改应用、SDK 或生产 API。
保留 catalog 的 Kotlin `2.4.20`、CMP `1.12.0`、Koin `4.2.2`，不降级工具链。

| 工程 / 源集 | 上游物理目录（相对于 fork 根目录） |
| --- | --- |
| core / iosMain | `cmp-worker-kmp/src/iosMain/kotlin`（全部 4 文件） |
| core / iosTest | `cmp-worker-kmp/src/iosTest/kotlin` |
| :ios / commonMain | `cmp-worker-ios/src/commonMain/kotlin` 与 `cmp-worker-ios/src/iosMain/kotlin`（全部 10 个现有生产文件） |
| :ios / commonTest | `cmp-worker-ios/src/commonTest/kotlin` 与 `cmp-worker-ios/src/iosTest/kotlin` |

所有表列目录的同级 `resources` 也作为输入；不存在的目录不生成占位文件。
core 使用 `common → ios → 两个 iOS 目标` 与 `common → JVM` 的显式层级，
保留原 `expect` 对应的真实 iOS `actual`，不添加空实现。
`:ios` 只有两个 iOS 目标，采用 `common → 两个 iOS 目标`，将原 commonMain /
iosMain 物理目录合并为模块的 commonMain 输入，避免目标集合相同的冗余中间源集。
这里的 commonMain 只服务 iOS，不会把 UIKit / Foundation 源码接入 JVM 或 Android。
测试目录仅保留源码接线，不代表本批获准或已经执行测试。

`:ios` 通过 `api(project(":"))` 依赖同组 core，保留全部持久化、调度、配置和启动器源码。
coroutines 保持上游 implementation；Compose Runtime / UI 与 Koin Core 用 `api`
暴露启动器公开签名所需类型。两个 target 均保留上游 `WorkerKmpIos` static framework，
未增加 framework export、宿主项目、Info.plist 或 Xcode 签名配置。

### Windows 配置边界

主控可从 fork 根目录使用当前进程 JDK 21 发现配置后的任务：

```powershell
& .\gradlew.bat -p cpf-build '-Pcpf.enableIos=true' tasks --all --console=plain
```

预期出现 core / `:ios` 的 `compileKotlinIosArm64`、`compileKotlinIosSimulatorArm64`，
以及 `:ios:linkDebugFrameworkIosArm64` / `:ios:linkDebugFrameworkIosSimulatorArm64`。
这些名称仍须由真实任务列表确认；配置成功或任务可见不代表 Native 编译成功。
Windows 不执行 iOS Native 编译、framework 链接、宿主构建、模拟器或设备运行。

### Preview 坐标与发布完整性

启用 iOS 时，全工程固定使用新版本 `4.0.8-cpf.3-ios-preview.1`，覆盖默认
`cpf.version` 的读取；Android 也跟随 root 使用该 preview 版本，因此依赖组内一致。
默认模式仍读取原 `cpf.version=4.0.8-cpf.2`，不写入或覆盖已验收的 `.2` 制品。
新增坐标与现有 core / JVM / Android 坐标使用同一私有组：

- core Native 叶：`worker-kmp-iosarm64`、`worker-kmp-iossimulatorarm64`。
- iOS KMP 根：`worker-ios`。
- iOS Native 叶：`worker-ios-iosarm64`、`worker-ios-iossimulatorarm64`。

文件仓库仍为 `cpf-build/build/repository`，不配置远端或 Maven Local。
opt-in 后所有工程的仓库发布任务均依赖 `verifyCpfIosPublicationHost`，非 macOS
显式失败，避免 Windows 将缺少 Native 叶的根 metadata 发布成可消费版本。
core 根发布先发布 JVM 与两个 Native 叶；`:ios` 根发布先发布自身两个 Native 叶。
两个根发布还检查 Native 叶的 `.klib` / POM / GMM 确实存在且非空；Android / iOS 的所有发布均先完成
同版本 core 根发布，防止 adapter 指向未发布的 preview core。

以下为 macOS 后续验收入口示例，须先完成任务发现，并由主控决定执行；不是本批结果：

```bash
./gradlew -p cpf-build -Pcpf.enableIos=true compileKotlinIosArm64 compileKotlinIosSimulatorArm64 :ios:compileKotlinIosArm64 :ios:compileKotlinIosSimulatorArm64 --console=plain
./gradlew -p cpf-build -Pcpf.enableIos=true :ios:linkDebugFrameworkIosArm64 :ios:linkDebugFrameworkIosSimulatorArm64 --console=plain
./gradlew -p cpf-build -Pcpf.enableIos=true publishAllPublicationsToCpfRepository :android:publishAllPublicationsToCpfRepository :ios:publishAllPublicationsToCpfRepository --console=plain
```

macOS 仍须具备上游平台 API 所需 Apple SDK / Xcode，核验两个 Native 叶、全部 POM /
GMM 私有依赖和 framework 的实际内容；发布任务不能代替 Native 编译和宿主验证。
本批 implementer 未运行构建、任务发现、发布、Native 或 Host 门禁；具体结果由主控记录。

### A3b 实际门禁（2026-11-05）

- Windows 使用加引号的 `-Pcpf.enableIos=true` 完成 opt-in 任务发现（exit 0，11 秒）；首次未加引号被 PowerShell 拆参，已修正文档命令。
- 默认 Android producer 编译及完整 `:android:bundleAndroidMainAar` 通过（1 秒，20 tasks 中 18 UP-TO-DATE）；AGP 9.1 消费编译及组装通过（2 秒，36 tasks 全部 UP-TO-DATE）。本次主要复用缓存。
- 非 Mac 发布检查按预期明确拒绝（该负向检查 exit 1，匹配专属错误）；三模块发布 dry-run exit 0，仅证明任务依赖图可构造。未执行 Native 编译或发布。
- 已验收 `.2` 的 16 个制品 SHA-256 全部保持。iOS Native 编译、framework 链接、Swift Host 和运行验收仍等待 Mac/Xcode。

## C0 Android host 测试接线

`:android` 通过 `withHostTestBuilder` 启用 host 测试，并设置
`isIncludeAndroidResources = true`，供 Robolectric 使用 Android 资源。
`androidHostTest` 直接读取上游 `cmp-worker-android/src/androidHostTest/kotlin`
和同级 `resources`，当前包含全部 11 个测试文件；不存在的资源目录不生成占位文件。
接线不复制、删除、修补或筛选原测试，也不改变原测试内已有的忽略标记。

测试依赖与原 `cmp-worker-android/build.gradle.kts` 一致：Kotlin Test / JUnit、
Koin Core / Test `4.2.2`、Robolectric `4.17`、WorkManager Testing `2.11.2`、
AndroidX Test Core `1.7.0` 和 coroutines-test `1.11.0`，全部复用现有 catalog alias。
不因本地缓存是否命中而新增或调整版本。生产源码、API、依赖可见性、发布配置和版本不变；
默认仍是 JVM / Android，iOS 仍须显式 opt-in。

主控从 fork 根目录使用 wrapper `9.7.1` / JDK `21` 重新发现任务：

```powershell
& .\gradlew.bat -p cpf-build tasks --all --console=plain
```

任务发现已核实 `:android:compileAndroidHostTest` 与 `:android:testAndroidHostTest` 存在。
以下为本批验证顺序，真实结果见节末：

1. 编译完整原 Android host 测试源集，保留全部测试文件参与编译。
2. 使用实际 host 测试任务，以 `--tests "io.github.mobilebytelabs.worker.android.WorkConvertersTest"`
   运行定向 baseline，分别记录通过、失败和跳过数；baseline 不等于全部 host 测试通过。
3. 再执行 Android producer 编译和根 App Tier 1，记录实际任务、退出码和必要错误。
4. 核对已有 `.1` / `.2` 私有制品的 SHA-256 保持，本批不执行发布或覆盖已有坐标。

若完整测试编译暴露原 fixture 与当前 API 不一致，保留真实错误交主控决定；
本批未修改测试或生产源码；主控已完成下述门禁，未发布新制品。
Host / Robolectric 结果不能作为真实设备后台调度、进程生命周期、
前台服务或系统通知行为的验收，相关系统级验证仍须另行执行。

### C0 实际门禁（2026-11-05）

任务发现 exit 0（7 秒）；`:android:compileAndroidMain :android:compileAndroidHostTest :android:testAndroidHostTest --tests "io.github.mobilebytelabs.worker.android.WorkConvertersTest"` exit 0（48 秒，24 tasks 中 17 executed、7 UP-TO-DATE）。全部 11 个原 host 测试文件参加编译；定向类报告 9 项，8 项运行通过、1 项沿用上游 @Ignore 的 contentUriTrigger 用例，零失败/错误，不表示整个 host 测试集已运行。
根 App `aiVerifyTier1` exit 0（7 秒，30 tasks 中 2 executed、28 UP-TO-DATE）。`.1` 的 8 个及 `.2` 的 16 个已验收制品 SHA-256 全部保持，未执行发布。Native、真实设备后台/前台行为均不在本次验收范围。

## C1 Android 原生 UUID 身份

单次和周期请求 builder 均将 KMP request UUID 写入 Android WorkRequest 的原生 ID。
执行中的 WorkerContext 与 AndroidForegroundBridge 注册键来自原生 Worker.id；
输入内的 `kmp_worker_id` 和 `kmp_id:` 标签只保留为兼容元数据，不再决定身份。
因此周期 A 经 UPDATE B、再 UPDATE C 后，输入和标签可以变为 B/C，执行、查询与取消仍使用 A。
按 ID 查询和取消直接委托 WorkManager 的原生公开 API，不用标签反查或维护映射表。
所有原生 WorkInfo 都按原生 ID 转换；没有 KMP 标签和输入的旧 BackupWorker 记录也保留可见。

四个限定 host 测试文件补充官方 WorkManagerTestInitHelper 的内存 WorkDatabase 与原 TestDriver 用例：
新单次/周期请求的 request/native Worker/context UUID 一致，周期连续两次 UPDATE 的真实执行与取消，
单次和周期 KEEP 不插入 B，以及无 KMP 元数据的旧原生任务按 tag、unique name、ID 查询和取消。
夹具通过原生 WorkerFactory 观察真实 Worker 和执行上下文，只记录执行结果，不替代数据库或调度器。
原集成用例的“只要非 FAILED 即接受”改为确切 SUCCEEDED 和输出断言；
原 factory 默认值用例允许静态实例被前序测试污染且未断言实例，改为真实执行期间安装及 bridge 清理断言。
原 tag 解析辅助用例保留为兼容元数据测试；新增 native ID 与标签冲突、缺少标签时的转换断言。
上游 contentUriTrigger 的既有忽略标记保持。

上游公开 enqueue 方法仍返回传入 request.id；KEEP 未插入新请求、UPDATE 保留旧 ID 时，
此返回值不能证明该 UUID 对应新增记录。这一既有返回契约缺口本批仅记录；
后续 facade Operation 不暴露假插入 ID，也不通过入队前后查询猜测竞争胜者。
本批不增加持久映射、清库、重排任务或额外初始化守卫，不修改 App、SDK、第三方依赖或已验收制品；仅预留新的 fork 私有发布版本。

限定四类 host 测试、完整生产与 host 编译及根 App Tier 1 已完成，真实结果见下文；
未执行发布、设备或系统后台/前台验收。

C1 同时将默认私有版本预留为 `4.0.8-cpf.3-android.1`，防止变更后的生产源码通过默认发布命令覆盖已验收 `.2`。C1 源码提交时尚未发布新制品，隔离消费探针默认读取同一私有版本属性；实际发布和消费结果见后续 C1p 节。App 尚未消费该 fork，现有 SDK 与依赖保持。iOS opt-in 的 preview 版本逻辑保持。

### C1 实际门禁（2026-11-05）

`:android:compileAndroidMain :android:compileAndroidHostTest :android:testAndroidHostTest` 加四个完整类过滤（AndroidWorkManagerTest、AndroidWorkManagerIntegrationTest、WorkConvertersTest、KmpAndroidWorkerTest）exit 0，耗时 16 秒，24 tasks 中 9 executed、15 UP-TO-DATE。报告合计 22 项，21 项实际运行通过，1 项沿用上游 contentUriTrigger 忽略，零失败/错误；全部 11 个原 Host 源文件仍参与编译。
真实 WorkDatabase 测试覆盖一次性/周期执行身份、连续 A→B→C UPDATE 的输入/标签/generation 与旧 ID 保持、KEEP 不插入新请求、旧无 KMP 元数据原生任务的查询取消，以及桥注册和清理。Host 结果不证明设备进程恢复或前台服务。
根 App `aiVerifyTier1` exit 0（5 秒，30 tasks 中 2 executed、28 UP-TO-DATE）；`.1/.2` 共 24 个已验收制品摘要全部保持。C1 源码提交只预留新版本，随后发布结果见 C1p 节；应用依赖尚未切换。

## C1p 新私有版本发布与实际消费（2026-11-05）

已将 C1 提交 `01a4776` 对应的 `4.0.8-cpf.3-android.1` 成组发布到本入口文件仓库，未写外部仓库。core 根/JVM、Android 根/叶四坐标均同版，16 个 jar/AAR/POM/GMM 制品已记录摘要；`.1/.2` 共 24 个旧制品摘要保持。发布 exit 0、3 秒，44 tasks 中 24 executed、20 UP-TO-DATE。
独立 consumer 从私有 Android 根 GAV 解析，同版私有 core 闭包完整，无上游 worker 重复制品；实际 `checkDebugAarMetadata compileDebugKotlin assembleDebug` exit 0、11 秒，36 tasks 中 5 executed、31 UP-TO-DATE。编译与运行时依赖图均 exit 0。consumer 使用主仓 wrapper 9.5 / AGP 9.1 / Kotlin 2.4.20 / JVM 17，未通过 project、composite、mavenLocal 或直接 AAR 绕过。
AAR 保留 launcher 与 notificationCustomizer 及本批三类生产实现，class major 55（JVM 11）；Android 叶 sources.jar 的三份源码与 C1 源文件字节一致。GMM 叶 component 正常回指根所有者，已核实根叶版本、关联文件及依赖闭包；未将叶 component 强行要求为物理叶名。
校验工具初始误判了 GMM 所有者和 Android 源码制品位置，已分别改按真实根叶关联、叶 sources.jar 核对；没有修改库代码或制品。验证结果不包含 APK 安装运行、系统后台/前台服务或 Native/iOS 验收。App 尚未切换到 fork，SDK 和现有功能表现仍保持。

## C2 Android Data、进度与输出

Android Data 转换统一由原生 `Data.Builder.putAll(...).build()` 校验支持类型与 10KB
序列化限制；转回 WorkData 保留原生装箱数组，不推测空数组元素类型、不转回原始数组。
顶层 null 和数组内 null 沿用 WorkManager 2.11.2 的内存及持久化语义，测试以原生
字节序列化往返为对照。通用转换不删除任何字段，只有 Worker 执行输入剥离 class/id 元数据。

单次与周期请求在调用原生入队前拒绝 `kmp_worker_class`、`kmp_worker_id` 输入键和
`kmp_id:` 用户标签前缀；其他 `kmp_*` 业务字段与标签仍允许使用。
业务输入和身份元数据合并后只构建一次最终 Data；本批所测未知类型及常规可序列化输入的最终大小超限，均在提交前失败并无新数据库记录，极端原生编码边界见下文。

进度使用 `kmp_worker_progress_percent` 存放百分比，只禁止进度业务载荷占用该键，
完整传递其余字段并等待原生 ProgressUpdater 完成。WorkInfo 只剥离此百分比键，缺失时返回 0，
保留旧备份 INDEX/TOTAL/MESSAGE/LAST_RESULT_*、业务 `progress` 和 class/id 字段。
Success 与非空 Failure 输出使用统一转换，完整保留 Failure 业务载荷（包括用户 `error` 字段）；
只有 Failure 输出为空时保留既有 message → `error` 兼容行为，未增加 core 或 facade API。

C2 源码提交时预留私有版本 `4.0.8-cpf.3-android.2`，避免覆盖 C1p 已发布的 `.3-android.1`。
完整生产/host 编译、五类定向 host 测试、旧制品摘要与 App Tier 1 已完成，实际结果见下文；
新版本发布与实际 consumer 验收结果见后续 C2p 节。
App、SDK、依赖与 iOS preview 逻辑未改动，未据此声明设备或系统调度行为通过。

C2 的 10KB 验证按本批实际测试的常规可序列化输入记录。源码复核另观察到 WorkManager 2.11.2 的原生序列化捕获 IOException 后可能返回空字节；例如单个字符串超出 modified-UTF 长度上限属于原生边界，本批未运行该极端场景，也不承诺它必然在入队前抛错。该行为沿用既有 SDK，不新增项目编码层或额外生产守卫，未将其列为产品需求或本批通过项。

### C2 实际门禁

完整生产与 Host 源集编译通过；五个完整类过滤（WorkConvertersTest、AndroidWorkManagerTest、KmpAndroidWorkerTest、AndroidWorkManagerIntegrationTest、AndroidWorkerContextTest）的门禁 exit 0，16 秒，24 tasks 中 4 executed、20 UP-TO-DATE。报告合计 40 项，39 项运行通过、1 项沿用上游 contentUriTrigger 忽略，零失败/错误。类型/序列化矩阵、真实 Worker 调用与注入 ProgressUpdater 的等待契约、Success/Failure 输出、入队前冲突与大小限制均有定向断言；未将此夹具作为 SDK WorkProgressUpdater 实际落库验收，C1 连续 UPDATE/KEEP/旧任务用例保持。
首轮仅测试编译遇到数组/Class 泛型推断和内部 Room 类型依赖问题；只修测试类型表达及公开查询，未增加依赖或弱化断言，四个生产文件摘要修前修后保持。根 App `aiVerifyTier1` exit 0（6 秒，30 tasks 中 2 executed、28 UP-TO-DATE）。已接受的 `.1/.2` 与 C1p 共 40 个制品摘要保持；C2 源码提交时尚未发布，后续发布见 C2p，App 尚未切换。

## C2p 新私有版本发布与消费

已将 C2 提交 `d264cbf` 对应的 `4.0.8-cpf.3-android.2` 成组发布到本地文件仓库，发布 exit 0（2 秒，44 tasks 中 24 executed、20 UP-TO-DATE）。四个私有坐标同版，16 个新制品摘要已登记；此前已验收的 40 个制品摘要保持，未覆盖旧版本或写外部仓库。
独立 AGP 9.1 / Gradle 9.5 / Kotlin 2.4.20 / JVM 17 消费工程仅声明 Android 根 GAV，实际元数据检查、Kotlin 编译与 APK 组装 exit 0（10 秒，36 tasks 中 5 executed、31 UP-TO-DATE）。compile/runtime 两份依赖图均验证同版私有 core 闭包，没有上游 worker 重复依赖或 project/composite/mavenLocal/直接 AAR 绕过。
AAR 中完整 launcher/customizer、Context 和相关实现均保留，class major 55；Android 叶 sources.jar 与 C2 的四份生产源码字节一致，GMM 根叶关系及 POM 依赖通过检查。验证仍不包含 APK 安装、SDK 进度落库、系统后台/前台服务、Native 或 iOS；App 尚未切换私有 fork，原有 SDK 与业务行为保持。

## C2b 执行次数与 Android 原生管理能力

core 的公开 `WorkerContext` 新增默认 `runAttemptCount: Int` getter，默认返回 `0`，
已有未覆写该属性的实现可继续从源码编译。Android 的 `AndroidWorkerContext` 覆写它，
直接读取当前原生 `KmpAndroidWorker.runAttemptCount`，不从输入数据、标签或重试配置推测次数。
默认值只提供源码兼容性，不承诺旧二进制兼容，也不代表 iOS 或其他平台已经取得真实执行次数。
本批未给 `CoroutineWorker` 新增便捷属性，也未扩大 common `WorkManager` 接口。

具体 `AndroidWorkManager` 增加两个 Android 方法：

- `suspend cancelUniqueWork(String)` 委托原生同名 API，并等待 `Operation.result` 完成，
  按 unique name 取消未完成任务链，不通过 tag 模拟。
- `getWorkInfoByIdFlow(Uuid): Flow<WorkInfo?>` 持续映射原生 ID Flow，
  保留不存在记录的 `null`，不轮询、不将单次查询包装为持续状态。

以上公开 getter 与两个具体 Android 方法属于 ABI 增量；已对本批编译类用 `javap` 核实，
core 为 `public default int getRunAttemptCount()`，Android 覆写 getter，并具有原生 unique 取消的 suspend ABI 与 ID Flow 方法；不由此推断所有已编译消费者二进制兼容。
原入队方法的等待行为、C1 的原生 UUID 身份、C2 的 Data/进度/Failure 载荷处理均保留。

三个现有 Host 测试文件复用原夹具并保留原有断言：旧 fake context 不增加 override 而断言默认 `0`；
官方 `TestListenableWorkerBuilder.setRunAttemptCount(0/3)` 核对原生 Worker 与传给工厂的实际
Android context；同一持续 ID Flow collector 通过 rendezvous channel 依次确认
`null → ENQUEUED → CANCELLED`，固定 ID、约束与延迟阻止提前执行，原生取消触发最后一次更新，
真实调度器上的有界 timeout 负责失败收敛，无 sleep 或反复 `first()` 重订阅。
unique name 用例构建 KEEP 与两次 APPEND 的真实任务链，检查忽略的 KEEP 未插入、
整链取消、另一条故意携带同名 tag 的 unique 任务保持 ENQUEUED，以及不存在 name 不改变现有状态。

C2b 源码提交时预留私有版本 `4.0.8-cpf.3-android.3`，同步上述四个默认坐标；发布结果见 C2bp：
此前已验收的 56 个制品必须保持摘要不变。App 尚未切换到 fork，SDK、依赖与 iOS preview 逻辑未改。
完整 core JVM 生产及测试源码编译、Android 生产与 Host 编译、五个定向 Host 类、ABI 检查、
旧制品摘要及 App Tier 1 已通过，实际结果见下文。
Native 编译、Mac/Apple 宿主和系统后台/前台行为均未验收，不据此声明通过。

### C2b 实际门禁

core JVM 生产与原测试源码、完整 Android/Host 编译和五类定向测试 exit 0（20 秒，25 tasks 中 11 executed、14 UP-TO-DATE）。报告合计 44 项，43 项运行通过、1 项沿用上游 contentUriTrigger 忽略，零失败/错误；core 测试本批只编译源码，不将其写作完整执行。
实际 attempt 0/3、旧 context 默认值、同一持续 ID Flow、目标任务链按唯一名称取消而同名 tag 的无关任务存活均通过；C1/C2 回归保持。App `aiVerifyTier1` exit 0（6 秒，30 tasks 中 2 executed、28 UP-TO-DATE），56 个旧制品摘要保持。C2b 源码提交时新 `.3-android.3` 尚未发布，后续结果见 C2bp；Native、其他平台真实 attempt 和系统调度仍未验收。

## C2bp 公开增量的实际制品消费

`4.0.8-cpf.3-android.3` 四坐标同版本地发布 exit 0（2 秒，44 tasks 中 25 executed、19 UP-TO-DATE），16 个新制品及既有 56 个制品摘要通过核对。Android 完整类和四份叶源码保持，实际 JVM core JAR 的 javap 确认 `public default int getRunAttemptCount()`，其 JVM sources.jar 与本批 core 源码字节一致。
独立消费探针保留全部旧 API 引用，并增加 getter、按 ID Flow、suspend unique 取消三个仅类型检查引用；没有构造管理器或执行方法。AGP 9.1 / Gradle 9.5 的真实根 GAV 消费元数据、Kotlin 编译和组装 exit 0（9 秒，36 tasks 中 7 executed、29 UP-TO-DATE），compile/runtime 闭包同版且无上游 worker 重复。
这项验收证明新公开 Kotlin API 可由外部制品编译使用及 JVM 默认 getter 形态，不扩大为所有旧二进制兼容、Native/iOS、设备或系统调度保证。App SDK/依赖和运行路径保持，未安装消费包或推送远端。

## C3a Android 前台提升完成链

`AndroidForegroundBridge.promote(ForegroundWorker, ForegroundInfo)` 返回
`CompletableFuture<Void>`，与原生 `setForegroundAsync` 的完成、失败和取消保持联动。
未注册活动 Worker、通知构建或 customizer 同步失败均直接抛出；原生异步失败保留原始原因。
适配只使用 Java 8 / API 24 已有 API，双向取消均调用 `cancel(false)`，不创建额外协程作用域。
既有 `ListenableFuture.await`、通知 channel、ID、service type 和定制能力保持。

core JVM 反射派发等待现有 coroutines-core `CompletionStage.await`；仅
`Class.forName` 查找 bridge 本身的 `ClassNotFoundException` 表示 bridge 不存在。
已找到 bridge 后，方法查找、链接、返回类型、同步执行或异步完成失败均向上传播，
不会因 Android 提升失败进入 AWT 桌面路径；反射调用异常解包为实际目标异常。

新增 JVM 反射合同测试的同 FQCN fake 仅位于 `jvmTest`，不属于生产源集。
Android Host 合同测试使用真实 `TestListenableWorkerBuilder` / `KmpAndroidWorker`
和可控 `ForegroundUpdater`，覆盖 SDK 24 / 30 / 31 的 pending、完成、错误、双向取消，
同步 customizer 失败、无活动 Worker，以及成功、异常和取消后的实际 `finally` 注销。
两条路径分开验证，不修改运行时系统属性；Host 结果不能证明真实系统前台服务行为。

默认四坐标预留为 `4.0.8-cpf.3-android.4`，后续须成组发布和消费配套 core / Android。
`promote` 的返回方法描述符发生 ABI 变化，不保证与旧 Android bridge 二进制混用兼容。
C3a 编译、定向测试和生产 core JAR 的测试隔离检查已完成，结果见下文；源码提交时尚未发布，后续发布见 C3ap。
App、SDK guard、Provider / Factory、依赖及 iOS preview 版本逻辑保持。

### C3a 实际门禁

Core JVM 反射桥 7 项、Android 六类报告 70 项合计 77 项，其中 76 项运行通过、1 项沿用上游 contentUriTrigger 忽略，零失败/错误。Android bridge 26 项涵盖 SDK 24/30/31 的受控 ForegroundUpdater；这些是 Worker/桥接等待与清理合同，不是系统前台服务启动或通知落库验收。生产和完整测试源集编译通过，定向门禁 exit 0（1 分 31 秒，26 tasks 中 6 executed、20 UP-TO-DATE）。
首轮 JVM 仅异步异常顶层对象身份断言失败；coroutines 1.11 栈恢复会创建同类型副本并保留原 cause。只修两处异步测试，仍要求相同类型/消息和原实例自身或单层 cause，保留同步身份与取消/清理断言，生产编译在重跑中均 UP-TO-DATE，没有关闭恢复或修改生产来迎合测试。
生产 core JVM Jar 扫描确认反射 fake 和新测试类均不存在；编译产物 javap 确认 promote 返回 CompletableFuture<Void>。72 个旧制品摘要保持；App `aiVerifyTier1` exit 0（4 秒，32 tasks 中 3 executed、29 UP-TO-DATE，已含自有模块编译）。C3a 源码提交时新 `.3-android.4` 尚未发布，后续见 C3ap；设备服务、Native/iOS 和 C3b 启动前 provider 仍未验收。

## C3ap Future 返回 ABI 的实际消费

`.3-android.4` 配套 core/JVM/Android 根叶四坐标已本地发布，exit 0（2 秒，44 tasks 中 25 executed、19 UP-TO-DATE）。16 个新制品同版，旧 72 个摘要保持；core 生产 JAR 和 JVM sources.jar 均不含测试 fake 或新测试类，C3a 三份生产源码字节与相应制品一致。实际 AAR classes.jar 的 javap 确认 CompletableFuture<Void> 返回描述符。
独立 AGP9.1 / Gradle9.5 consumer 新增仅函数引用的 CF<Void> 返回类型检查，保留所有旧引用；从新根 GAV 编译/组装 exit 0（10 秒，36 tasks 中 7 executed、29 UP-TO-DATE），两份依赖图同版闭包通过。未调用 promote、安装消费 APK 或初始化调度。新 core 与 Android 必须配套，不承诺和旧 void bridge 二进制混用兼容；设备前台服务、Native/iOS 与 C3b provider 仍未验收。

## C3b1 启动前前台通知描述

common `ForegroundWorker` 新增 `public open suspend getForegroundInfo(): ForegroundInfo`。
默认实现明确抛出 `ForegroundNotSupportedException`；既有子类仍可仅覆写 `doWork`。
provider 只返回初始描述，不执行业务或主动提升前台；AndroidX 负责后续提升与等待。

`KmpAndroidWorker` 在构造时捕获工厂，provider 与 `doWork` 通过普通线程安全 lazy
共享同一个 context 和业务 worker。`KmpWorkerFactory` 直接注入自身工厂；原 public
`(Context, WorkerParameters)` 构造器保留，并在构造时只读取一次旧全局工厂入口。
初始化失败沿用标准 lazy 重试语义，不缓存失败或额外维护状态机。

启动前 provider 和运行时 `promote` 共用通知转换，保留 channel、通知 ID、图标、进度、
service type 和 customizer 行为。provider 不注册 bridge；仅 `doWork` 的 try/finally
持有注册，注销按 ID 与当前 worker 配对释放。这是资源所有权约束，不引入 generation
表或全局运行 guard，也不声明当前 WorkManager 会并行执行同一 work 的多代 worker。

新增 common provider 合同和真实 `TestListenableWorkerBuilder` / native worker Host 测试，
涵盖共享实例与 context、工厂替换、普通任务、provider 失败，以及 SDK 24 / 30 / 31 的通知
映射与定制。编译、定向测试、ABI、旧制品摘要和 App Tier 1 已完成，真实结果见下文。
SDK ≥ 31 的 expedited guard 继续保留，低 SDK expedited 调度顺序属于 C3b2，尚未验收；
Host 测试不能证明设备前台服务行为，Native/iOS 同样未验收。

C3b1 源码提交时预留四坐标 `4.0.8-cpf.3-android.5`，实际发布见 C3b1p；新增 open suspend 方法属于公开 ABI
增量，须成组发布配套 core / Android，并以实际制品消费核对，不承诺所有旧二进制兼容。
App SDK、依赖和 iOS preview 版本逻辑保持。

### C3b1 实际门禁

Core 四类 16 项、Android 八类 102 项报告合计 118 项，117 项运行通过、1 项沿用上游 contentUriTrigger 忽略，零失败/错误；新 native provider 类 30 项含 SDK24/30/31 的工厂捕获、同 delegate/context、失败无注册和通知映射合同。生产与全部测试源码编译通过，门禁 exit 0（41 秒，26 tasks 中 12 executed、14 UP-TO-DATE）。
实际 javap 确认 core 非抽象 suspend getForegroundInfo、Android override、原 public(Context,WorkerParameters) 构造器和新增注入构造方法描述符；Kotlin internal 注入入口及公开默认实现只按本批源码/ABI记录，不泛称旧二进制兼容。88 个旧制品摘要保持，生产 core JAR 没有测试类或反射 fake。App Tier1 exit 0（4 秒，32 tasks 中 3 executed、29 UP-TO-DATE）。
C3b1 源码提交时 `.3-android.5` 尚未发布，后续见 C3b1p；SDK guard 未变，低版本原生 expedited 排队、设备前台服务和 Native/iOS 均未据此通过。资源所有权配对测试不宣称真实 SDK 代际竞争复现。

## C3b1p Provider 的外部制品消费

`.3-android.5` 四坐标同版本地发布 exit 0（3 秒，44 tasks 中 25 executed、19 UP-TO-DATE）；16 新制品及旧 88 个摘要通过。实际 core JAR javap 确认 provider 非抽象默认实现，AAR 确认原 public 双参构造器与原生 getForegroundInfo override；变更 core/common 与 Android 工厂/桥/worker 源码字节匹配，core Jar/sources 不含测试 fake 或 provider 测试。
独立 AGP9.1 / Gradle9.5 consumer 对公开 suspend provider 的仅编译引用与旧引用均通过，从新根 GAV 编译/组装 exit 0（7 秒，36 tasks 中 7 executed、29 UP-TO-DATE），compile/runtime 同版闭包通过。未调用 provider、构造调度或安装包；SDK guard 保留，实际低 SDK expedited 与系统服务须待后续 C3b2/设备验收，Native/iOS 未因此通过。

## C3b2 所有支持 SDK 的 Android 原生 expedited 委托

`AndroidWorkManager` 对所有支持的 Android SDK 调用原生 `setExpedited`，原样传递两种
`OutOfQuotaPolicy`；移除 API 31 guard 和低版本普通任务降级。common 请求 KDoc 同步说明
低于 API 31 时由 WorkManager 获取初始 foreground descriptor 并等待提升，再启动业务。
原生配额 fallback、约束校验、provider、delegate 和通知转换保持既有实现。

新增 `AndroidExpeditedWorkTest` 使用官方 `WorkManagerTestInitHelper` 内存数据库、真实
`WorkManager`、`TestDriver` 和 `WorkerWrapper`；只使用原生 expedited 支持的网络约束，
不加入 charging 或 initialDelay。API 24/30 的实测覆盖 provider → updater → 完成 → 业务
顺序、单个工厂/delegate/context、provider 与 updater 失败、updater 自取消、真实取消等待
和业务阶段、业务失败后的 bridge 清理；API 31 覆盖实际标志/两种策略和普通 worker 无 provider
的执行，另保留非 expedited 普通任务回归。控制通过 Deferred、Channel 和有界 timeout，
没有 sleep 轮询，也不手动调用 provider 或 doWork 充当原生调度验收。

此夹具明确依赖固定 WorkManager `2.11.2`：工厂只在测试内反射修改 WorkerWrapper 创建的
原始 `WorkerParameters.mForegroundUpdater`，包装真实 updater，并以受控 Future 延后完成
或注入故障/取消；反射读取 WorkDatabase/DAO/WorkSpec 只核对真实 expedited 与配额字段，
未引入 Room、MockK、新依赖或生产 hook。字段或源码契约变化应视为夹具阻塞并重新核实，
不能扩大生产接口。Host 结果不证明设备前台服务、通知实际投递、Native 或 iOS 行为。

C3b2 源码提交时预留四坐标 `4.0.8-cpf.3-android.6`；编译、定向回归、旧制品摘要与 App Tier 1 已通过，
结果见下文，后续发布见 C3b2p。设备服务或 Native/iOS 未验收，既有 `.3-android.5` 及更早制品不覆盖。

### C3b2 实际门禁

Core16 与 Android119 合计135项报告，134项运行通过、1项沿用上游contentUriTrigger忽略，零失败/错误。新增真实原生 expedited 类17项覆盖API24/30预启动顺序与API31差异、真实标志/两种policy、成功前门控、失败/取消和资源清理；updater自身取消按原生语义进入失败且业务不启动，显式用户取消等待/业务则分别检查原生取消及桥注销，不混写取消语义。
生产与完整测试源码编译通过，门禁 exit 0（48秒，26tasks中12executed、14UP-TO-DATE）。App Tier1 exit 0（5秒，32tasks中3executed、29UP-TO-DATE）。104个已接受制品摘要保持；统计时重复登记同一份`.5`报告导致数量断言，已去重索引并重新核对全部摘要，未改制品/源码或重跑已通过测试。C3b2源码提交时`.6`尚未发布，后续见C3b2p；这些是Robolectric内真实WorkManager流程与固定SDK私有字段夹具，不替代设备ForegroundService或Native/iOS验收。

## C3b2p 低 SDK expedited 变更制品消费

`.3-android.6` 四坐标同版本地发布 exit 0（2秒，44tasks中25executed、19UP-TO-DATE），16新制品、104个唯一旧制品摘要保持；生产core Jar/sources无测试fake，变更Android manager与core请求KDoc的发布源码字节匹配。
独立AGP9.1/Gradle9.5 consumer从新根GAV元数据检查、Kotlin编译、APK组装exit0（11秒，36tasks中5executed、31UP-TO-DATE），compile/runtime闭包同版，无project/composite/mavenLocal/directAAR绕过。沿用既有编译探针，无新增运行入口。低SDK行为依据C3b2真实WM的17项新用例，消费APK未安装运行；App未切换，实际设备前台服务、Native/iOS仍待验。

## D0b 默认失败结果的空输出

`KmpAndroidWorker` 将输出为空且 message 为空的默认 `WorkResult.Failure()` 映射为
原生 `Data.EMPTY`，不再产生值为空字符串的 `error` 字段。非空输出继续原样转换，
业务 `error` 字段不会被 message 覆盖；仅输出为空而 message 非空时保留旧 `error` 映射。

新增用例复用真实 `TestListenableWorkerBuilder` / `KmpAndroidWorker`，断言默认失败的
原生输出等于 `Data.EMPTY` 且键值表为空；非空 payload 与旧 message 用例及断言保持。
本批不新增公开 API 或 ABI，core 结果模型与 App SDK、依赖、运行路径保持。

D0b 源码提交时预留四坐标 `4.0.8-cpf.3-android.7`，后续发布见 D0bp。编译、定向回归、旧制品摘要
与 App Tier 1 已完成，真实记录见下文；不据此宣称设备或 Native/iOS 验收。

### D0b 实际门禁

Core16项有效缓存报告、Android120项报告（含新增默认Failure为空）共136项，零失败/错误，1项沿用上游contentUriTrigger忽略。Android相关回归本次实际执行，Core runtime为UP-TO-DATE，生产与完整测试源码编译通过；门禁exit0（44秒，26tasks中10executed、16UP-TO-DATE）。payload优先/用户error保留与非空legacy message既有断言不变，未引入哨兵metadata或Facade重复映射。
120个唯一旧制品摘要保持，App Tier1 exit0（5秒，32tasks中3executed、29UP-TO-DATE）。D0b源码提交时新`.3-android.7`尚未发布，后续见D0bp；App SDK/业务未切换，Native/iOS及设备服务未据此通过。

## D0bp 默认空失败制品消费

`.3-android.7`四坐标同版本地发布exit0（3秒，44tasks中24executed、20UP-TO-DATE），16新制品与旧120个唯一摘要保持，变更Worker源码字节匹配，core生产Jar/sources测试隔离通过。
独立AGP9.1/Gradle9.5 consumer从根GAV元数据检查、Kotlin编译、组装exit0（8秒，36tasks中4executed、32UP-TO-DATE），compile/runtime同版闭包通过；probe未改，行为依据D0b真实Worker结果测试，不宣称consumerAPK运行、设备或Native服务已验。App SDK与实际备份路径尚未切换，未推送。

## D1pid 周期任务的显式 UUID

`PeriodicWorkRequestBuilder.setId(Uuid)` 指定后续构建使用的 UUID，直到再次设置。
默认每次构建生成新的 UUID。Android Host 回归以显式 UUID 入队首个周期任务，
并检查连续两次 `UPDATE` 后的执行仍使用首个原生 UUID。

Core 和 Android Host 三个测试类共 23 项通过。实际编译的 JVM JAR 公开了 `setId(kotlin.uuid.Uuid)`，
原有 136 个制品的摘要均匹配。四个私有坐标预留版本 `4.0.8-cpf.3-android.8`。
上游根工程的 API dump 留待向上游提交时同步，本批门禁使用独立 `cpf-build`。
