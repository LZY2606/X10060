package io.avaje.config.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression fixture for the avaje-spi annotation processor used by avaje-config.
 *
 * <p>The same in-process javac fixture is driven from both sides of every boundary
 * (valid compile succeeds / invalid compile is rejected) and through failure recovery
 * (delete an annotated class, then verify stale output is refused and a clean rebuild
 * is accepted). Nothing here hits the network, sleeps, or relies on machine paths or
 * special fixture names.
 */
class ProcessorFixtureTest {

  private static final String SPI_INTERFACE = join(
    "package fixture.api;",
    "",
    "import io.avaje.spi.Service;",
    "",
    "@Service",
    "public interface ExtensionPoint {",
    "  String describe();",
    "}",
    "");

  private static final String ALPHA = join(
    "package fixture.impl;",
    "",
    "import fixture.api.ExtensionPoint;",
    "import io.avaje.spi.ServiceProvider;",
    "",
    "@ServiceProvider",
    "public class AlphaExtension implements ExtensionPoint {",
    "  public String describe() {",
    "    return \"alpha\";",
    "  }",
    "}",
    "");

  private static final String BETA = join(
    "package fixture.impl;",
    "",
    "import fixture.api.ExtensionPoint;",
    "import io.avaje.spi.ServiceProvider;",
    "",
    "@ServiceProvider",
    "public class BetaExtension implements ExtensionPoint {",
    "  public String describe() {",
    "    return \"beta\";",
    "  }",
    "}",
    "");

  private static final String MODULE_INFO = join(
    "module fixture.ext {",
    "  requires static io.avaje.spi;",
    "  exports fixture.api;",
    "  uses fixture.api.ExtensionPoint;",
    "  provides fixture.api.ExtensionPoint",
    "    with fixture.impl.AlphaExtension, fixture.impl.BetaExtension;",
    "}",
    "");

  private static final String MODULE_INFO_WITHOUT_PROVIDES = join(
    "module fixture.ext {",
    "  requires static io.avaje.spi;",
    "  exports fixture.api;",
    "  uses fixture.api.ExtensionPoint;",
    "}",
    "");

  private static String join(String... lines) {
    return String.join(System.lineSeparator(), lines);
  }

  @TempDir
  Path workDir;

  @Test
  void fullCompile_generatesServiceDeclarations() throws IOException {
    Path sourceDir = writePlainFixture(workDir.resolve("full"), true, true);
    Path outputDir = sourceDir.resolveSibling("out");
    Files.createDirectories(outputDir);

    FixtureCompiler.Result result = FixtureCompiler.compileTree(sourceDir, outputDir);

    assertThat(result.success).isTrue();
    Map<String, List<String>> declarations =
      GeneratedArtifacts.serviceDeclarations(outputDir);
    assertThat(declarations)
      .containsEntry("fixture.api.ExtensionPoint",
        List.of("fixture.impl.AlphaExtension", "fixture.impl.BetaExtension"));
    GeneratedArtifacts.verifyServiceRegistrations(outputDir, sourceDir);
  }

  @Test
  void incrementalCompile_singleSourceChange_preservesAggregatedDeclarations() throws IOException {
    Path sourceDir = writePlainFixture(workDir.resolve("inc"), true, true);
    Path outputDir = sourceDir.resolveSibling("out");
    Files.createDirectories(outputDir);
    FixtureCompiler.compileTree(sourceDir, outputDir);

    Files.writeString(sourceDir.resolve("fixture/impl/BetaExtension.java"),
      BETA.replace("return \"beta\";", "return \"beta-changed\";"));
    FixtureCompiler.Result incremental = FixtureCompiler.compileFiles(sourceDir,
      List.of(Path.of("fixture/impl/BetaExtension.java")), outputDir,
      "-cp", outputDir + ":" + FixtureCompiler.classpathEntry(
        FixtureCompiler.SPI_SERVICE_JAR_PREFIX));

    assertThat(incremental.success).isTrue();
    Map<String, List<String>> declarations =
      GeneratedArtifacts.serviceDeclarations(outputDir);
    assertThat(declarations.get("fixture.api.ExtensionPoint")).containsExactly(
      "fixture.impl.AlphaExtension", "fixture.impl.BetaExtension");
    GeneratedArtifacts.verifyServiceRegistrations(outputDir, sourceDir);
  }

  /**
   * The dangerous counterexample for an aggregating processor: javac does not delete the
   * class or generated registration of a removed source during a non-clean rebuild, so the
   * META-INF/services file keeps pointing at a class that no longer exists. The verifier
   * must refuse this state; recovery via a clean rebuild must then pass.
   */
  @Test
  void deleteAnnotatedClass_staleRegistration_rejectedThenCleanRebuildAccepted() throws IOException {
    Path sourceDir = writePlainFixture(workDir.resolve("stale"), true, true);
    Path outputDir = sourceDir.resolveSibling("out");
    Files.createDirectories(outputDir);
    FixtureCompiler.compileTree(sourceDir, outputDir);

    Files.delete(sourceDir.resolve("fixture/impl/BetaExtension.java"));
    FixtureCompiler.compileTree(sourceDir, outputDir,
      "-cp", outputDir + ":" + FixtureCompiler.classpathEntry(
        FixtureCompiler.SPI_SERVICE_JAR_PREFIX));

    Path servicesFile = outputDir
      .resolve("META-INF/services/fixture.api.ExtensionPoint");
    assertThat(Files.readString(servicesFile))
      .as("plain javac increment leaves the stale registration in place")
      .contains("fixture.impl.BetaExtension");

    assertThatThrownBy(() ->
      GeneratedArtifacts.verifyServiceRegistrations(
        outputDir, sourceDir))
      .isInstanceOf(AssertionError.class)
      .hasMessageContaining("Stale service declaration")
      .hasMessageContaining("fixture.impl.BetaExtension");

    Path cleanOutput = workDir.resolve("stale-clean-out");
    Files.createDirectories(cleanOutput);
    FixtureCompiler.compileTree(sourceDir, cleanOutput);
    GeneratedArtifacts.verifyServiceRegistrations(cleanOutput, sourceDir);
    assertThat(Files.readString(
      cleanOutput.resolve("META-INF/services/fixture.api.ExtensionPoint")))
      .contains("fixture.impl.AlphaExtension")
      .doesNotContain("fixture.impl.BetaExtension");
    assertThat(cleanOutput.resolve("fixture/impl/BetaExtension.class")).doesNotExist();
  }

  @Test
  void duplicateServiceDeclarations_areRejected() throws IOException {
    Path sourceDir = writePlainFixture(workDir.resolve("dup"), true, true);
    Path outputDir = sourceDir.resolveSibling("out");
    Files.createDirectories(outputDir);
    FixtureCompiler.compileTree(sourceDir, outputDir);
    Path servicesFile = outputDir
      .resolve("META-INF/services/fixture.api.ExtensionPoint");
    Files.writeString(servicesFile,
      Files.readString(servicesFile) + System.lineSeparator()
        + "fixture.impl.AlphaExtension" + System.lineSeparator());

    assertThatThrownBy(() ->
      GeneratedArtifacts.verifyServiceRegistrations(outputDir, sourceDir))
      .isInstanceOf(AssertionError.class)
      .hasMessageContaining("Duplicate service declaration")
      .hasMessageContaining("fixture.impl.AlphaExtension");
  }

  @Test
  void missingServiceProviderRegistration_isRejected() throws IOException {
    Path sourceDir = writePlainFixture(workDir.resolve("missing"), true, true);
    Path outputDir = sourceDir.resolveSibling("out");
    Files.createDirectories(outputDir);
    FixtureCompiler.compileTree(sourceDir, outputDir);
    Path servicesFile = outputDir
      .resolve("META-INF/services/fixture.api.ExtensionPoint");
    Files.writeString(servicesFile, "fixture.impl.AlphaExtension\n");

    assertThatThrownBy(() ->
      GeneratedArtifacts.verifyServiceRegistrations(outputDir, sourceDir))
      .isInstanceOf(AssertionError.class)
      .hasMessageContaining("Missing service declaration")
      .hasMessageContaining("fixture.impl.BetaExtension");
  }

  @Test
  void emptyOutputDirectory_isRejected() throws IOException {
    Path emptyOut = Files.createDirectories(workDir.resolve("empty-out"));
    Path emptySrc = Files.createDirectories(workDir.resolve("empty-src"));

    assertThatThrownBy(() ->
      GeneratedArtifacts.verifyServiceRegistrations(emptyOut, emptySrc))
      .isInstanceOf(AssertionError.class)
      .hasMessageContaining("META-INF/services");
  }

  @Test
  void incrementalProcessorMetadata_declaresAggregatingProcessor() {
    List<Path> processorJars = List.of(
      FixtureCompiler.classpathEntry(FixtureCompiler.SPI_SERVICE_JAR_PREFIX),
      FixtureCompiler.classpathEntry(FixtureCompiler.SPI_CORE_JAR_PREFIX));

    Map<String, String> metadata =
      GeneratedArtifacts.verifyIncrementalMetadata(processorJars);

    assertThat(metadata).containsEntry(
      "io.avaje.spi.internal.ServiceProcessor", "aggregating");
  }

  @Test
  void incrementalProcessorMetadata_mismatch_isRejected() throws IOException {
    Path original = FixtureCompiler.classpathEntry(
      FixtureCompiler.SPI_SERVICE_JAR_PREFIX);
    Path tampered = workDir.resolve("tampered-spi-service.jar");
    copyJarReplacingEntry(original, tampered,
      "META-INF/gradle/incremental.annotation.processors",
      "io.avaje.spi.internal.SomeOtherProcessor,aggregating\n");

    assertThatThrownBy(() ->
      GeneratedArtifacts.verifyIncrementalMetadata(List.of(tampered)))
      .isInstanceOf(AssertionError.class)
      .hasMessageContaining("Incremental metadata mismatch")
      .hasMessageContaining("io.avaje.spi.internal.ServiceProcessor");
  }

  @Test
  void moduleInfoWithoutProvidesDirective_isRejectedByProcessor() throws IOException {
    Path sourceDir = writeModularFixture(
      workDir.resolve("jpms-bad"), MODULE_INFO_WITHOUT_PROVIDES);
    Path outputDir = sourceDir.resolveSibling("out");
    Files.createDirectories(outputDir);

    FixtureCompiler.Result result = FixtureCompiler.compileTreeExpectFailure(
      sourceDir, outputDir,
      "--module-path", FixtureCompiler.classpathEntry(
        FixtureCompiler.SPI_SERVICE_JAR_PREFIX).toString());

    assertThat(result.success).isFalse();
    assertThat(result.diagnostics)
      .contains("Missing `provides fixture.api.ExtensionPoint");
    assertThat(outputDir.resolve("module-info.class"))
      .as("the rejected module must never finish compilation")
      .doesNotExist();
    Path serviceFile = outputDir
      .resolve("META-INF/services/fixture.api.ExtensionPoint");
    if (java.nio.file.Files.exists(serviceFile)) {
      assertThatThrownBy(() ->
        GeneratedArtifacts.verifyServiceRegistrations(outputDir, sourceDir))
        .as("a rejected module-info must not leave a coherent service registry")
        .isInstanceOf(AssertionError.class);
    }
  }

  @Test
  void modularFixtureWithProvidesDirective_compilesAndRegistersServices() throws IOException {
    Path sourceDir = writeModularFixture(workDir.resolve("jpms-good"), MODULE_INFO);
    Path outputDir = sourceDir.resolveSibling("out");
    Files.createDirectories(outputDir);

    FixtureCompiler.Result result = FixtureCompiler.compileTree(sourceDir, outputDir,
      "--module-path", FixtureCompiler.classpathEntry(
        FixtureCompiler.SPI_SERVICE_JAR_PREFIX).toString());

    assertThat(result.success).isTrue();
    assertThat(outputDir.resolve("module-info.class")).exists();
    GeneratedArtifacts.verifyServiceRegistrations(outputDir, sourceDir);
  }

  @Test
  void consumerOnModulePath_compilesAgainstAvajeConfig() throws IOException {
    Path configModule = locateConfigModule();
    Path applog = FixtureCompiler.classpathEntryMatches("avaje-applog-\\d.*\\.jar$");
    Path jspecify = FixtureCompiler.classpathEntryMatches("jspecify-\\d.*\\.jar$");
    String modulePath = configModule + ":" + applog + ":" + jspecify;

    Path sourceDir = writeConsumerFixture(workDir.resolve("consumer"), true);
    Path outputDir = sourceDir.resolveSibling("out");
    Files.createDirectories(outputDir);

    FixtureCompiler.Result result = FixtureCompiler.compileTree(sourceDir, outputDir,
      "-proc:none", "--module-path", modulePath);

    assertThat(result.success).isTrue();
    assertThat(outputDir.resolve("consumer/app/Main.class")).exists();
  }

  @Test
  void consumerOnModulePath_withoutRequiresDirective_isRejected() throws IOException {
    Path configModule = locateConfigModule();
    Path applog = FixtureCompiler.classpathEntryMatches("avaje-applog-\\d.*\\.jar$");
    Path jspecify = FixtureCompiler.classpathEntryMatches("jspecify-\\d.*\\.jar$");
    String modulePath = configModule + ":" + applog + ":" + jspecify;

    Path sourceDir = writeConsumerFixture(workDir.resolve("consumer-bad"), false);
    Path outputDir = sourceDir.resolveSibling("out");
    Files.createDirectories(outputDir);

    FixtureCompiler.Result result = FixtureCompiler.compileTreeExpectFailure(
      sourceDir, outputDir, "-proc:none", "--module-path", modulePath);

    assertThat(result.success).isFalse();
    assertThat(result.diagnostics).contains("io.avaje.config");
  }

  @Test
  void twoIndependentBuilds_produceIdenticalHashes() throws IOException {
    Path buildA = workDir.resolve("build-a");
    Path buildB = workDir.resolve("build-b");
    Path sourcesA = writeModularFixture(buildA, MODULE_INFO);
    Path sourcesB = writeModularFixture(buildB, MODULE_INFO);
    Path outA = buildA.resolve("out");
    Path outB = buildB.resolve("out");
    Files.createDirectories(outA);
    Files.createDirectories(outB);
    String spiModulePath = FixtureCompiler.classpathEntry(
      FixtureCompiler.SPI_SERVICE_JAR_PREFIX).toString();

    FixtureCompiler.compileTree(sourcesA, outA, "--module-path", spiModulePath);
    FixtureCompiler.compileTree(sourcesB, outB, "--module-path", spiModulePath);

    Map<String, String> hashesA = hashTree(outA);
    Map<String, String> hashesB = hashTree(outB);
    assertThat(hashesA).isNotEmpty();
    assertThat(hashesA).isEqualTo(hashesB);
    GeneratedArtifacts.assertNoAbsolutePathLeak(outA, workDir);
    GeneratedArtifacts.assertNoAbsolutePathLeak(outB, workDir);
  }

  private static Path locateConfigModule() {
    try {
      Path codeLocation = Path.of(
        io.avaje.config.Config.class.getProtectionDomain()
          .getCodeSource().getLocation().toURI());
      if (!Files.isRegularFile(codeLocation.resolve("module-info.class"))) {
        throw new AssertionError("avaje-config module-info.class missing at " + codeLocation);
      }
      return codeLocation;
    } catch (Exception e) {
      throw new AssertionError(
        "Cannot locate compiled io.avaje.config module directory", e);
    }
  }

  private static Path writeConsumerFixture(Path root, boolean withRequires) throws IOException {
    Path sourceDir = Files.createDirectories(root.resolve("src"));
    String moduleInfo = withRequires
      ? "module consumer.app {\n  requires io.avaje.config;\n}\n"
      : "module consumer.app {\n}\n";
    Files.writeString(sourceDir.resolve("module-info.java"), moduleInfo);
    Path packageDir = Files.createDirectories(sourceDir.resolve("consumer/app"));
    Files.writeString(packageDir.resolve("Main.java"), join(
      "package consumer.app;",
      "",
      "import io.avaje.config.Config;",
      "",
      "public class Main {",
      "  public String value() {",
      "    return Config.get(\"consumer.fixture.key\", \"default\");",
      "  }",
      "}",
      ""));
    return sourceDir;
  }

  private static Path writePlainFixture(Path root, boolean alpha, boolean beta) throws IOException {
    Path sourceDir = Files.createDirectories(root.resolve("src"));
    Files.writeString(
      Files.createDirectories(sourceDir.resolve("fixture/api"))
        .resolve("ExtensionPoint.java"),
      SPI_INTERFACE);
    if (alpha) {
      Files.writeString(
        Files.createDirectories(sourceDir.resolve("fixture/impl"))
          .resolve("AlphaExtension.java"),
        ALPHA);
    }
    if (beta) {
      Files.writeString(
        Files.createDirectories(sourceDir.resolve("fixture/impl"))
          .resolve("BetaExtension.java"),
        BETA);
    }
    return sourceDir;
  }

  private static Path writeModularFixture(Path root, String moduleInfoSource) throws IOException {
    Path sourceDir = writePlainFixture(root, true, true);
    Files.writeString(sourceDir.resolve("module-info.java"), moduleInfoSource);
    return sourceDir;
  }

  private static void copyJarReplacingEntry(Path sourceJar, Path targetJar,
                                            String entryName, String replacement)
    throws IOException {
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(sourceJar));
         ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(targetJar))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        byte[] bytes = in.readAllBytes();
        if (entry.getName().equals(entryName)) {
          bytes = replacement.getBytes(StandardCharsets.UTF_8);
        }
        ZipEntry copy = new ZipEntry(entry.getName());
        copy.setTime(entry.getTime());
        if (entry.getMethod() == ZipEntry.STORED) {
          copy.setMethod(ZipEntry.STORED);
          copy.setSize(bytes.length);
          copy.setCompressedSize(bytes.length);
          CRC32 crc = new CRC32();
          crc.update(bytes);
          copy.setCrc(crc.getValue());
        }
        out.putNextEntry(copy);
        out.write(bytes);
        out.closeEntry();
      }
    }
  }

  private static Map<String, String> hashTree(Path root) throws IOException {
    Map<String, String> hashes = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
        String digest;
        try {
          MessageDigest md = MessageDigest.getInstance("SHA-256");
          digest = toHex(md.digest(Files.readAllBytes(file)));
        } catch (Exception e) {
          throw new AssertionError("Cannot hash " + file, e);
        }
        hashes.put(root.relativize(file).toString(), digest);
      }
    }
    return hashes;
  }

  private static String toHex(byte[] bytes) {
    StringBuilder hex = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16));
      hex.append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }
}
