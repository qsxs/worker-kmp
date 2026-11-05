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
