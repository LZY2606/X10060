package io.avaje.config.apt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the JPMS contract around {@code @ServiceProvider}:
 *
 * <ul>
 *   <li>a provider module compiles on the module path and emits both a {@code provides} clause in
 *       {@code module-info.class} and the matching {@code META-INF/services} descriptor,</li>
 *   <li>the processor rejects a non-static {@code requires io.avaje.spi} (the annotation jar must
 *       not leak onto the consumers runtime module path),</li>
 *   <li>a consumer module compiled on the module path with {@code uses ...} sees both providers via
 *       {@link java.util.ServiceLoader}.</li>
 * </ul>
 */
class JpmsModulePathTest {

  private static final String SERVICE = "com.example.spi.Feature";

  @TempDir
  Path work;

  private ModuleCompilerHarness providerProject() {
    ModuleCompilerHarness harness = ModuleCompilerHarness.create(work);
    harness.moduleSource(
      "module com.example.feature {\n"
      + "  exports com.example.spi;\n"
      + "  requires static io.avaje.spi;\n"
      + "  provides com.example.spi.Feature\n"
      + "    with com.example.spi.AlphaFeature, com.example.spi.BetaFeature;\n"
      + "}\n");
    harness.source("com/example/spi/Feature.java",
      "package com.example.spi;\n"
      + "public interface Feature { String name(); }\n");
    harness.source("com/example/spi/AlphaFeature.java",
      "package com.example.spi;\n"
      + "import io.avaje.spi.ServiceProvider;\n"
      + "@ServiceProvider\n"
      + "public final class AlphaFeature implements Feature {\n"
      + "  public String name() { return \"alpha\"; }\n"
      + "}\n");
    harness.source("com/example/spi/BetaFeature.java",
      "package com.example.spi;\n"
      + "import io.avaje.spi.ServiceProvider;\n"
      + "@ServiceProvider\n"
      + "public final class BetaFeature implements Feature {\n"
      + "  public String name() { return \"beta\"; }\n"
      + "}\n");
    return harness;
  }

  @Test
  void providerModule_compilesAndEmitsDescriptorOnModulePath() {
    ModuleCompilerHarness harness = providerProject();
    harness.compileProviderModule().assertSuccess();

    assertThat(harness.serviceLines(SERVICE))
      .containsExactly("com.example.spi.AlphaFeature", "com.example.spi.BetaFeature");
    assertThat(harness.moduleInfoDeclaresProvides()).isTrue();
    assertThat(harness.moduleInfoRequiresStaticSpi()).isTrue();
    // the annotation jar must not be copied into or required strongly by the published module
    assertThat(harness.moduleInfoRequiresSpiStrongly()).isFalse();
  }

  @Test
  void providerModule_nonStaticRequiresSpi_isRejected() {
    ModuleCompilerHarness harness = ModuleCompilerHarness.create(work);
    harness.moduleSource(
      "module com.example.feature {\n"
      + "  exports com.example.spi;\n"
      + "  requires io.avaje.spi;\n"
      + "  provides com.example.spi.Feature with com.example.spi.AlphaFeature;\n"
      + "}\n");
    harness.source("com/example/spi/Feature.java",
      "package com.example.spi;\npublic interface Feature { String name(); }\n");
    harness.source("com/example/spi/AlphaFeature.java",
      "package com.example.spi;\nimport io.avaje.spi.ServiceProvider;\n"
      + "@ServiceProvider\npublic final class AlphaFeature implements Feature {\n"
      + "  public String name() { return \"alpha\"; }\n}\n");

    harness.compileProviderModule().assertFailure()
      .assertDiagnosticContains("requires static io.avaje.spi");
  }

  @Test
  void consumerOnModulePath_loadsBothProviders() {
    ModuleCompilerHarness harness = providerProject();
    harness.compileProviderModule().assertSuccess();

    harness.consumerModuleSource(
      "module com.example.consumer {\n"
      + "  requires com.example.feature;\n"
      + "  uses com.example.spi.Feature;\n"
      + "}\n");
    harness.consumerSource("com/example/consumer/Main.java",
      "package com.example.consumer;\n"
      + "import com.example.spi.Feature;\n"
      + "import java.util.List;\n"
      + "import java.util.ServiceLoader;\n"
      + "import java.util.stream.Collectors;\n"
      + "public final class Main {\n"
      + "  public static void main(String[] args) {\n"
      + "    List<String> names = ServiceLoader.load(Feature.class).stream()\n"
      + "      .map(p -> p.get().getClass().getName()).sorted().collect(Collectors.toList());\n"
      + "    if (names.size() != 2) throw new AssertionError(\"expected 2 providers: \" + names);\n"
      + "    System.out.println(String.join(\",\", names));\n"
      + "  }\n"
      + "}\n");

    harness.compileConsumerModule().assertSuccess();
    List<String> loaded = harness.runConsumerMain();
    assertThat(loaded)
      .containsExactly("com.example.spi.AlphaFeature", "com.example.spi.BetaFeature");
  }
}
