# CPF core 独立构建入口

此目录是 worker-kmp 本地 fork 的独立 Gradle 工程，以 `-p cpf-build` 选择。
它只挂接 core 的 JVM 与 common 源集，不包含上游根工程的 composite build、
样例、UI、Store5、KSP 或其他模块，也不向应用增加依赖。

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

各源集同级的 `resources` 目录也已挂接；目录不存在时没有对应资源输入。
测试源集仅用于主控明确授权的定向测试，不代表已运行测试。

## 工具链与依赖

使用 fork 根目录现有 wrapper（Gradle `9.7.1`），不新增或修改 wrapper。
主控通过当前进程的 `JAVA_HOME` 选择 JDK 21，工程也指定 JDK 21 工具链；
生产 JVM 字节码目标保持上游的 `11`，不修改应用的 JVM `17` 设置。

版本直接读取 `../gradle/libs.versions.toml`：Kotlin `2.4.20`、
coroutines `1.11.0`、datetime `0.8.0`、Kermit `2.2.0`。
生产依赖及其 `api` 可见性与上游 core 一致；测试依赖沿用 Kotlin Test 和
coroutines-test。不复制依赖或版本目录，不配置本地 jar，也不加载其他模块的插件。

本入口只应用 Kotlin Multiplatform 与 Gradle 自带的 `maven-publish`。
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

- KMP 根坐标：`com.lihb.forks.worker:worker-kmp:4.0.8-cpf.1`。
- JVM 坐标：`com.lihb.forks.worker:worker-kmp-jvm:4.0.8-cpf.1`。
- 文件仓库：`cpf-build/build/repository`，发布仓库名称为 `Cpf`。

不配置 `mavenLocal()`，Maven Local 发布任务已禁用；不发布到上游
`io.github.mobilebytelabs` 命名空间，不配置任何远端发布仓库。
发布 POM 保留 Apache-2.0 许可证、上游作者与 SCM 信息；这些字段用于说明来源，
并不表示上游维护或发布此私有 fork。

本入口只配置 common/JVM 产物。根 KMP 坐标的实际平台重定向与元数据以生成内容为准，
不得据此声称 Android、iOS、OHOS、JS 或 Wasm 产物已经发布。
此环境无 Mac/ARM 验证条件，不能声明这些平台编译或运行通过；后续成组 producer
属于独立批次，不在此入口扩展平台或加入占位实现。

## 本轮验证结果

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
