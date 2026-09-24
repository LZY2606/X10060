# Changelog

## Unreleased — 锁定处理器增量编译、JPMS 与发布元数据

### 实现选择

- **局部 fixture 而非独立 fixture 模块**：测试在 `avaje-config/src/test/java/io/avaje/config/processor/`
  下用 `FixtureCompiler` 在 JUnit `@TempDir` 里现场生成被 `@ServiceProvider` 注解的 fixture 源码，
  以 `java.home` 派生的外部 `javac`/`java` 进程编译运行。不污染仓库源码树、不依赖本机绝对路径，
  本地与 CI 走同一条代码路径。
- **外部 javac 进程而非 `javax.tools` API**：测试编译在 `--release 11` 模块模式下进行，
  `java.compiler` 模块不可读；外部进程与消费者运行测试的 `java` 子进程对称，且天然满足
  “子命令失败必须传递”——非零退出码连同完整诊断一起抛出。
- **校验器 `verifyDescriptor` 是判定核心**：对生成物 `META-INF/services/io.avaje.config.ConfigExtension`
  与 fixture 当前源码集合做双向比对，零收集、重复声明、陈旧条目、缺失条目各自给出带上下文的
  `AssertionError`。
- **统一入口 `scripts/verify.sh`**：`set -eu` 下依次执行 `mvn -q -DskipTests package` 与
  `mvn -q test`，任一子命令失败即非零退出；CI（`.github/workflows/build.yml`）改为调用同一脚本，
  本地与 CI 收敛到同一条离线可复现入口。测试本身不触网、无 sleep、无 fixture 名称特判。

### 原覆盖的空白

- 处理器生成的服务描述符此前没有任何全量/增量/删除三态验证；`avaje-aws-appconfig` 与
  `avaje-dynamic-logback` 的 `provides` 指令只靠人工与 `module-info.java` 同步。
- Gradle 增量元数据（`incremental.annotation.processors` 中 `aggregating` 声明）此前无校验，
  被降级为 `isolating` 也不会有人发现。
- 发布元数据（`project.build.outputTimestamp`、`<name>`、各模块 `module-info.java`、
  标准源码布局）分散在四个模块 pom 中，此前无整体锁定。
- 生成物可复现性（路径/时间戳不进入输出）此前无断言。

### 相邻语义的退化保护

- `ReproducibleBuildTest`：两个独立临时目录构建同一 fixture，全部生成物 SHA-256 必须一致，
  防止绝对路径或时间戳渗入生成结果。
- `PublishingMetadataTest`：反应堆每个模块必须保留 reproducible 构建时间戳、发布用 `<name>`、
  JPMS 描述符；任何 `.java` 游离在 `src/main/java`/`src/test/java` 之外即失败，
  防止 sources jar 静默缺文件。
- `processorIsDeclaredAggregatingInIncrementalMetadata`：处理器合并既有描述符的行为决定了
  它必须保持 `aggregating` 声明，元数据错配即失败。
- `ModulePathConsumerTest` 从边界两侧验证：消费者模块在 module path 上能编译并通过
  `ServiceLoader` 解析到 provider；缺失 `provides` 指令、访问未导出包两个方向都必须编译失败
  且诊断可定位。

### 最危险反例与对应回归用例

**反例：删除被注解类后，陈旧服务条目在增量编译中存活。** `avaje-spi` 的
`ServiceProcessor.write()` 会把输出目录中已存在的 `META-INF/services/*` 合并进本轮结果
（这是“只改一个源文件”场景下描述符不丢条目的机制）。代价是：删除一个 `@ServiceProvider`
类后只对剩余源码做增量重编，被删类的条目仍留在描述符里——编译成功、无警告，运行期
`ServiceLoader` 却在加载一个已不存在的类。Gradle 侧靠 `aggregating` 声明触发全量重编来兜底，
一旦元数据错配或直接 javac 增量编译，该陈旧生成物会静默进入产物。

**回归用例**：
`ProcessorIncrementalCompileTest#deletedProviderLeavesStaleDescriptorThatVerificationRejects_thenCleanRebuildRecovers`
先证明增量重编后校验器必须以 “stale generated entries” 失败（拒绝陈旧生成物），
再证明清空输出目录的干净重建能恢复正常（失败恢复路径）。配套的
`zeroCollectedProvidersIsRejected` 与 `duplicateServiceDeclarationIsRejected`
锁定零收集与重复声明两个对称失败方向。
