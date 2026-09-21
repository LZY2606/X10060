# CHANGELOG — 锁定处理器增量编译、JPMS 与发布元数据

日期：2026-09-22
范围：`avaje-config` 模块（运行库本身零改动），新增测试与构建配置。

## 背景

`avaje-config` 的主模块通过 `io.avaje:avaje-spi-service`（处理器实现在
`avaje-spi-core`）的 `ServiceProcessor` 在编译期生成
`META-INF/services/...`，并由处理器校验 `module-info.java` 的
`uses`/`provides` 指令。此前仓库对这条链路没有任何自动化覆盖：增量编译后生成
物是否陈旧、JPMS 指令与处理器是否互相约束、发布 jar 是否模块化且可复现，全部
只能靠人工观察。

## 新增内容

### 1. 进程内临时目录 + fork javac 的处理器夹具

- `avaje-config/src/test/java/io/avaje/config/processor/FixtureCompiler.java`
  - 从 `java.class.path` 精确定位 `avaje-spi-service` / `avaje-spi-core`
    jar（正则/前缀匹配，不写死版本与本机路径），每次编译 fork 一个全新的
    `javac` 进程（使用 `java.home/bin/javac`，JDK 17 上运行、`--release 11`
    源码兼容）。
  - 选择 fork 而不是 in-process `JavaCompiler` 是有依据的：
    `ServiceProcessor`/`APContext` 持有 static 状态，同一 JVM 内第二次编译会抛
    `IllegalStateException`（真实构建里每个编译本身也是独立进程）。
  - 子命令失败一律抛出带完整命令行与全部诊断输出的 `AssertionError`，不吞错、
    不做随机 sleep、不访问网络。

- `avaje-config/src/test/java/io/avaje/config/processor/GeneratedArtifacts.java`
  生成物完整性校验器，每个检查都拒绝“零收集”（找不到要校验的东西直接失败）：
  - `META-INF/services/*`：空文件/空集合拒绝；重复行拒绝；声明的实现类没有
    对应 `.class` 或源文件 → 陈旧注册拒绝；带 `@ServiceProvider` 的源没有出现在
    任何声明中 → 漏注册拒绝。
  - 增量元数据：跨 processor path 上所有 jar 聚合比对
    `META-INF/services/javax.annotation.processing.Processor` 与
    `META-INF/gradle/incremental.annotation.processors`，要求每个注册处理器都有
    且仅有一条合法类型（isolating/aggregating/dynamic）声明，反向也要一致。
  - 路径泄漏：扫描全部产物字节，禁止包含临时目录绝对路径。

- `avaje-config/src/test/java/io/avaje/config/processor/ProcessorFixtureTest.java`
  13 个用例，均可单独定位（`-Dtest=ProcessorFixtureTest#方法名`）：
  - 全量编译生成服务声明；
  - 只改一个源文件做增量编译，验证 aggregating 处理器与旧
    `META-INF/services` 合并、其余实现不丢；
  - 删除被注解类后非 clean 重编译 → 陈旧注册被校验器拒绝；clean 重建后通过
    且无陈旧 class；
  - 重复声明、漏注册、空输出目录三个负向入口；
  - 增量元数据声明为 `aggregating`；篡改元数据（处理器名不匹配）被拒绝；
  - `module-info` 缺 `provides` 时处理器使编译失败（负向），指令齐全时编译成功
    （正向）；
  - module path 上的消费者模块 `requires io.avaje.config` 可编译（正向），缺少
    `requires` 时包不可见（负向）；
  - 两个独立临时目录构建按相对路径做 SHA-256 映射，完全一致且无路径泄漏。

### 2. 发布元数据测试

- `avaje-config/src/test/java/io/avaje/config/processor/ReleaseMetadataTest.java`
  - 主 jar 必须包含 `module-info.class` 与 `MANIFEST.MF`，不得包含
    `META-INF/maven/`（父 POM `addMavenDescriptor=false`）；
  - 全部 zip entry 时间戳必须等于 `pom.xml` 中
    `project.build.outputTimestamp`（按运行 JVM 时区解释，ZIP 的本地时区语义
    与 maven-jar-plugin 一致，CI 换时区仍成立）；
  - sources jar 必须包含 `src/main/java` 下每一个 `.java`（含
    `module-info.java`），缺一个即失败；
  - 找不到构件时直接失败并提示先执行 package，不允许静默通过。

### 3. 构建配置（`avaje-config/pom.xml`）

- 新增 `maven-source-plugin` 3.4.0 `jar-no-fork` 绑定到 package
  （execution id `attach-sources-offline`，刻意避开父 POM `central` profile 的
  `attach-sources` 同名 execution 合并问题）：sources jar 不再只在发布 profile
  下存在，离线本地构建与 CI 校验同一条产物。
- 生产 `module-info` 不做任何放宽：夹具 fork 出独立 `javac` 子进程，测试源码
  本身只使用普通 JDK API，无需 `--add-reads`。

## 统一入口（本地与 CI 收敛）

```bash
mvn -q -DskipTests package   # 产物先行：主 jar + sources jar
mvn -q test                  # 全套测试（含本变更的 16 个用例）
```

全部依赖与插件均已在本地仓库验证，可加 `-o` 完全离线复现
（已实测 `mvn -o clean` → `mvn -o -q -DskipTests package` →
`mvn -o -q test`）。单独定位新用例：

```bash
mvn -q -pl avaje-config test -Dtest=ProcessorFixtureTest
mvn -q -pl avaje-config test -Dtest=ProcessorFixtureTest#deleteAnnotatedClass_staleRegistration_rejectedThenCleanRebuildAccepted
mvn -q -pl avaje-config test -Dtest=ReleaseMetadataTest
```

## 最危险反例与其回归用例

**aggregating 注解处理器 + 非 clean 增量编译 → 陈旧服务声明（stale
ServiceLoader 注册）。**

现象：删除一个带 `@ServiceProvider` 的源文件后直接重编译（不清理输出目录），
javac 不会删除其旧 `.class`；aggregating 处理器又把输出目录里已有的
`META-INF/services/<SPI>` 读回来合并，于是服务文件仍指向一个源码与（clean
后）类都不存在的实现。运行期 `ServiceLoader` 行为静默退化（类找不到/加载失败
被吞或延迟爆炸），构建却是绿色。同形态的危害还包括：重复声明（同实现注册
两次）、增量元数据与处理器注册不一致（Gradle 静默把处理器降级为非增量或在
隔离编译时漏聚合）。

对应回归用例：
- `ProcessorFixtureTest#deleteAnnotatedClass_staleRegistration_rejectedThenCleanRebuildAccepted`
  — 先复现陈旧文件确实存在，再断言 `GeneratedArtifacts` 拒绝该状态、clean
  重建恢复；
- `#duplicateServiceDeclarations_areRejected`、
  `#missingServiceProviderRegistration_isRejected` — 重复/遗漏两个对称方向；
- `#incrementalProcessorMetadata_mismatch_isRejected` — 篡改
  `incremental.annotation.processors` 后与
  `javax.annotation.processing.Processor` 注册不一致即失败；
- `#twoIndependentBuilds_produceIdenticalHashes` — 双临时目录哈希一致，路径/
  时间戳不进产物。

## 原覆盖空白

- 处理器生成物此前零测试：全量、增量、删除三类场景均未覆盖。
- `module-info` 的 `provides/uses/exports` 约束此前只靠上游处理器隐式保证，
  没有消费者侧的 module path 编译验证。
- 发布侧没有任何自动检查：sources jar 只在 `central` profile 产生，
  `outputTimestamp` 可复现性、`module-info.class` 是否进 jar、
  `META-INF/maven` 是否泄漏均无守门。

## 相邻语义的退化保护

- **模块边界**：运行模块描述符保持 `exports io.avaje.config` +
  `requires static` 形态，未新增任何 `--add-reads`/`--add-exports`；消费者
  module path 编译用例会在意外 export/requires 变化时失败。
- **处理器行为**：聚合器类型被锁为 `aggregating`；升级
  `avaje-spi-service`/`avaje-spi-core` 时若元数据、注册名、生成格式变化，
  元数据校验与服务文件内容断言会立即失败。
- **发布形态**：sources jar 缺失/缺文件、jar 时间戳漂移、误开
  `addMavenDescriptor` 都会被 `ReleaseMetadataTest` 捕获。
- **构建隔离性**：新用例彼此独立（各自 `@TempDir`、各自 fork javac），不依赖
  执行顺序，不共享处理器 static 状态，不在源码里硬编码任何机器路径或夹具类名
  特判（校验器按注解扫描而非固定名称）。

## 未改动与已知限制

- 未改动运行时任何生产类，未改动其他三个模块（`avaje-config-toml`、
  `avaje-aws-appconfig`、`avaje-dynamic-logback`）。
- 处理器本身属于上游 `avaje-spi`；本仓库只能锁定它的*外部契约*（生成文件、
  元数据、JPMS 校验），不复制其实现。
- 时间戳校验依赖先 `package` 后 `test` 的统一入口顺序；单独直接
  `mvn test`（从无产物状态）时发布元数据用例会显式失败并给出提示，这是
  “零收集即失败”的有意行为。
