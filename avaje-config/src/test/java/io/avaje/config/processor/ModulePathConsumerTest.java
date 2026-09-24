package io.avaje.config.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the JPMS side of the processor contract: a provider module generated with a
 * {@code provides} directive is consumable from the module path, and module boundary
 * violations (missing provides, non-exported packages) fail with diagnostics.
 */
class ModulePathConsumerTest {

  @TempDir Path tempDir;

  @Test
  void consumerCompilesAndResolvesProviderOnModulePath() throws Exception {
    FixtureCompiler fixture = FixtureCompiler.create(tempDir, "ProviderAlpha");
    fixture.writeModuleInfo(true);
    fixture.compileModule().assertSuccess();
    fixture.verifyDescriptor();

    Path consumerOut = compileConsumer(fixture.classes(), consumerMainSource(false)).assertSuccessOut();

    List<String> modulePath = new ArrayList<>(FixtureCompiler.modulePath());
    modulePath.add(fixture.classes().toString());
    modulePath.add(consumerOut.toString());

    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--module-path",
                String.join(java.io.File.pathSeparator, modulePath),
                "--module",
                "org.fixture.consumer/org.fixture.consumer.ConsumerMain")
            .redirectErrorStream(true)
            .start();
    String output;
    try (var in = process.getInputStream()) {
      output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("consumer JVM did not exit within 60s\noutput:\n" + output);
    }
    assertThat(process.exitValue())
        .as("consumer run on the module path must succeed\noutput:\n%s", output)
        .isEqualTo(0);
    assertThat(output).contains("org.fixture.ProviderAlpha");
  }

  @Test
  void missingProvidesDirectiveFailsCompilation() {
    FixtureCompiler fixture = FixtureCompiler.create(tempDir, "ProviderAlpha");
    fixture.writeModuleInfo(false);

    CompileResult result = fixture.compileModule();

    result.assertFailure();
    assertThat(result.diagnostics)
        .as("processor must reject a module-info missing the provides directive\n%s", result.describe())
        .contains("Missing")
        .contains("provides")
        .contains("io.avaje.config.ConfigExtension");
  }

  @Test
  void consumerCannotCompileAgainstNonExportedPackage() {
    FixtureCompiler fixture = FixtureCompiler.create(tempDir, "ProviderAlpha");
    fixture.writeModuleInfo(true);
    fixture.compileModule().assertSuccess();

    ConsumerCompile consumer = compileConsumer(fixture.classes(), consumerMainSource(true));

    consumer.assertFailure();
    assertThat(consumer.result.diagnostics)
        .as("javac must reject access to a package the provider module does not export\n%s",
            consumer.result.describe())
        .contains("org.fixture");
  }

  /** Compile the consumer module against the fixture module on the module path. */
  private ConsumerCompile compileConsumer(Path fixtureClasses, String mainSource) {
    try {
      Path consumerSrcDir = Files.createDirectories(tempDir.resolve("consumer-src/org/fixture/consumer"));
      Path mainFile = consumerSrcDir.resolve("ConsumerMain.java");
      Path moduleFile = tempDir.resolve("consumer-src/module-info.java");
      Files.write(mainFile, mainSource.getBytes(StandardCharsets.UTF_8));
      Files.write(moduleFile, consumerModuleInfo().getBytes(StandardCharsets.UTF_8));
      Path consumerOut = Files.createDirectories(tempDir.resolve("consumer-classes"));

      List<String> modulePath = new ArrayList<>(FixtureCompiler.modulePath());
      modulePath.add(fixtureClasses.toString());
      CompileResult result =
          FixtureCompiler.compileWithModulePath(
              modulePath, consumerOut, List.of(moduleFile, mainFile));
      return new ConsumerCompile(result, consumerOut);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final class ConsumerCompile {
    final CompileResult result;
    final Path outDir;

    ConsumerCompile(CompileResult result, Path outDir) {
      this.result = result;
      this.outDir = outDir;
    }

    Path assertSuccessOut() {
      result.assertSuccess();
      return outDir;
    }

    void assertFailure() {
      result.assertFailure();
    }
  }

  private static String consumerModuleInfo() {
    return "module org.fixture.consumer {\n"
        + "  requires io.avaje.config;\n"
        + "  uses io.avaje.config.ConfigExtension;\n"
        + "}\n";
  }

  private static String consumerMainSource(boolean touchProviderClass) {
    StringBuilder sb = new StringBuilder();
    sb.append("package org.fixture.consumer;\n");
    sb.append("import io.avaje.config.ConfigExtension;\n");
    sb.append("import java.util.ServiceLoader;\n");
    if (touchProviderClass) {
      sb.append("import org.fixture.ProviderAlpha;\n");
    }
    sb.append("public final class ConsumerMain {\n");
    sb.append("  public static void main(String[] args) {\n");
    if (touchProviderClass) {
      sb.append("    System.out.println(ProviderAlpha.class.getName());\n");
    }
    sb.append("    java.util.List<String> found = ServiceLoader.load(ConfigExtension.class).stream()\n");
    sb.append("        .map(p -> p.type().getName())\n");
    sb.append("        .collect(java.util.stream.Collectors.toList());\n");
    sb.append("    System.out.println(\"providers=\" + found);\n");
    sb.append("    if (found.isEmpty()) {\n");
    sb.append("      throw new IllegalStateException(\"zero ConfigExtension providers resolved\");\n");
    sb.append("    }\n");
    sb.append("  }\n");
    sb.append("}\n");
    return sb.toString();
  }
}
