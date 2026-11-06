# worker-kmp 鸿蒙 core 制品

此独立入口使用 Gradle 8.11.1、JDK 17、CPF Kotlin `2.4.20-0.1.0-11` 和 HarmonyOS API 26 SDK，从 `cmp-worker-kmp/src/commonMain` 编译完整 core。两个 Native 目标共用 `cmp-worker-kmp/src/ohosMain`。

鸿蒙沿用已选的 CPF coroutines `1.11.0-0.1.0-07`、datetime `0.8.0-0.1.0-07` 和 Kermit `2.0.8-1.1.0`。Kermit 及 Kermit Core 的根模块和双 ABI 叶子从 CPF `maven-inner` 精确解析。鸿蒙 Native 和元数据依赖图将 Kermit 的 stdlib 请求对齐到当前 CPF Kotlin `.11`。外部消费验证也应采用此版本对齐。

在 Windows PowerShell 中，将以下变量改为本机已校验的工具与缓存路径。已有完整 Gradle 缓存时可为构建追加 `--offline`。

```powershell
$ForkRoot = 'D:\path\to\worker-kmp'
$Gradle811 = 'D:\path\to\gradle-8.11.1\bin\gradle.bat'
$CpfNativeHome = 'D:\path\to\kotlin-native-2.4.20-0.1.0-11'
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:DEVECO_SDK_HOME = 'C:\path\to\DevEcoStudio\sdk'
$env:GRADLE_USER_HOME = 'D:\path\to\gradle-user-home'
$env:KONAN_DATA_DIR = 'D:\path\to\konan'
$NativeProperty = "-Pkotlin.native.home=$CpfNativeHome"

& $Gradle811 -p "$ForkRoot\cpf-ohos-build" $NativeProperty compileCommonMainKotlinMetadata compileKotlinOhosX64 compileKotlinOhosArm64 --console=plain
& $Gradle811 -p "$ForkRoot\cpf-ohos-build" $NativeProperty publishAllPublicationsToCpfRepository --console=plain
```

本地 Maven 仓库默认位于 `cpf-ohos-build/build/repository`。请通过 `publishAllPublicationsToCpfRepository` 一次发布 `com.lihb.forks.worker:worker-kmp:4.0.8-cpf.3-ohos.1` 和同版 `worker-kmp-ohosx64`、`worker-kmp-ohosarm64`。

发布前会检查三个候选目录，并原子创建当前版本的仓库占位文件。占位文件在每次尝试后保留。失败时可通过 `-Pcpf.ohos.repository=<独立本地目录>` 在新的空仓库重试。独立消费工程应使用同一次完整发布生成的一个文件仓库，声明根坐标并通过 Gradle Module Metadata 选择对应 Native 叶子，再分别编译和链接。

当前批次验证范围为 core 制品及外部链接。持久化和系统调度能力均报告为 `false`，前台提权请求会抛出 `ForegroundNotSupportedException`。宿主调度与设备运行留待后续批次。
