# worker-kmp 鸿蒙后端

发布 group/version 统一从 `../cpf-build/gradle.properties` 读取，不再维护 OHOS 专属版本。完整三平台 11 模块候选的组合与回执见 [`../cpf-release`](../cpf-release/README.md)。

此独立入口使用 Gradle 8.11.1、JDK 17 或 21、CPF Kotlin `2.4.21-0.1.0-13` 和 HarmonyOS API 26 SDK。两个 Native 目标共用 `cmp-worker-kmp/src/ohosMain`，Android/iOS 的构建入口仍在 `cpf-build`。

鸿蒙沿用 CPF coroutines `1.11.0-0.1.0-07`、datetime `0.8.0-0.1.0-07` 和 Kermit `2.0.8-1.1.0`。Kermit 从 CPF `maven-inner` 解析；Okio `3.17.0-0.1.0-07` 和 GitHub Pages Maven 上的 SQLite `2.7.1-chunjizhang-cpf11-p2r1` 是 OHOS 实现依赖。Native 和元数据解析的 stdlib 对齐到 CPF Kotlin `.13`。

在 Windows PowerShell 中，将以下变量改为本机已校验的工具与缓存路径。已有完整 Gradle 缓存时可为构建追加 `--offline`。

```powershell
$ForkRoot = 'D:\path\to\worker-kmp'
$Gradle811 = 'D:\path\to\gradle-8.11.1\bin\gradle.bat'
$CpfNativeHome = 'D:\path\to\kotlin-native-2.4.21-0.1.0-13'
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:DEVECO_SDK_HOME = 'C:\path\to\DevEcoStudio\sdk'
$env:GRADLE_USER_HOME = 'D:\path\to\gradle-user-home'
$env:KONAN_DATA_DIR = 'D:\path\to\konan'
$NativeProperty = "-Pkotlin.native.home=$CpfNativeHome"

& $Gradle811 -p "$ForkRoot\cpf-ohos-build" $NativeProperty compileCommonMainKotlinMetadata compileKotlinOhosX64 compileKotlinOhosArm64 --console=plain
& $Gradle811 -p "$ForkRoot\cpf-ohos-build" $NativeProperty publishAllPublicationsToCpfRepository --console=plain
$Repo = "$ForkRoot\cpf-ohos-build\build\repository"
& $Gradle811 -p "$ForkRoot\cpf-ohos-build\consumer" $NativeProperty "-PworkerOhosRepository=$Repo" verifyWorkerVariants linkDebugSharedOhosX64 linkDebugSharedOhosArm64 --console=plain
```

本地 Maven 仓库默认位于 `cpf-ohos-build/build/repository`。请通过 `publishAllPublicationsToCpfRepository` 一次产出 `com.lihb.forks.worker:worker-kmp:<cpf.version>` 和同版 `worker-kmp-ohosx64`、`worker-kmp-ohosarm64`。独立消费工程也接受 HTTPS Maven 根；正式分发沿用 GitHub Pages 匿名 Maven。

发布前会检查三个候选目录，并原子创建当前版本的仓库占位文件。占位文件在每次尝试后保留。失败时可通过 `-Pcpf.ohos.repository=<独立本地目录>` 在新的空仓库重试。独立消费工程应使用同一次完整发布生成的一个文件仓库，声明根坐标并通过 Gradle Module Metadata 选择对应 Native 叶子，再分别编译和链接。

### 宿主接线

- 传入应用作用域、Worker 注册表和 `OhosWorkManagerConfiguration` 构建 `OhosWorkManager`。两参数构造仍用于进程内一次性任务。
- 宿主提供 `OhosWorkScheduler`：`ensure` 要确认同一份登记确实存在，`cancel` 要确认同一份登记已移除。遇到同 workId 的其他登记要报错。宿主为该库保留 workId 范围，可配置 `firstSystemWorkId`。系统桥接可用后，在宿主作用域调用 `recoverPendingWork()`。
- 应用和 WorkScheduler 扩展传入同一应用私有文件目录。扩展从 WorkScheduler 回调还原 workId、应用与 Ability 名、请求 UUID 和登记代次，调用 `OhosPeriodicExecution.run`。保存 `onReserved` 交接的票据供停止回调调用 `cancel`，在执行退出后结束本次系统窗口。应用提供 Ability 声明、调度桥接和业务 Worker；库不引用应用类型。
- 新工作库位于私有目录的 `worker-kmp-ohos/work.sqlite`，不迁移其他后端的工作库。一次性任务只在调用进程执行，进程退出后的遗留记录会结算；周期任务可由系统唤起。中断的执行不会被重放，周期重试也要等下一次符合条件的系统窗口。
- 支持唯一任务 KEEP / REPLACE；周期 UPDATE 支持原地更新输入、Worker 和标签等不影响登记的字段。周期至少两小时，只实现无网络约束和 CONNECTED。前台提升、加急、其他约束、周期 flex 和原地更改系统调度条件均未接入。
