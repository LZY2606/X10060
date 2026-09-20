package io.avaje.config.apt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the observable contract of the avaje {@code ServiceProcessor} across the three build
 * shapes that matter for incremental annotation processing:
 *
 * <ol>
 *   <li><b>full build</b> - every {@code @ServiceProvider} lands in the service descriptor,</li>
 *   <li><b>single source changed</b> - recompiling one file against a persistent output directory
 *       keeps the descriptor complete and free of duplicate lines,</li>
 *   <li><b>annotated class deleted</b> - a fresh full build drops the removed provider while an
 *       incremental run against a stale output directory is explicitly rejected.</li>
 * </ol>
 *
 * <p>The most dangerous regression for a service-file writer is silently shipping a descriptor that
 * still names a deleted provider (or names a live provider twice). Both surface late as
 * {@code ServiceConfigurationError} at consumer startup, so they are hard failures here.
 */
class IncrementalServiceProcessorTest {

  private static final String SERVICE = "com.example.spi.Feature";
  private static final String ALPHA = "com.example.spi.AlphaFeature";
  private static final String BETA = "com.example.spi.BetaFeature";

  @TempDir
  Path work;

  private CompilerHarness fullBuild() {
    CompilerHarness harness = CompilerHarness.create(work);
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
    harness.compileAll().assertSuccess();
    return harness;
  }

  @Test
  void fullBuild_generatesCompleteDescriptor() {
    CompilerHarness harness = fullBuild();

    assertThat(harness.serviceLines(SERVICE)).containsExactly(ALPHA, BETA);
    assertThat(harness.classExists(ALPHA)).isTrue();
    assertThat(harness.classExists(BETA)).isTrue();
    assertThat(harness.allServiceFiles()).containsExactly("META-INF/services/" + SERVICE);
  }

  @Test
  void singleSourceChanged_recompilingOneFileKeepsDescriptorCompleteAndUnique() {
    CompilerHarness harness = fullBuild();

    harness.modifySource("com/example/spi/AlphaFeature.java",
      "package com.example.spi;\n"
      + "import io.avaje.spi.ServiceProvider;\n"
      + "@ServiceProvider\n"
      + "public final class AlphaFeature implements Feature {\n"
      + "  public String name() { return \"alpha-v2\"; }\n"
      + "}\n");

    harness.recompileIncrementally("com/example/spi/AlphaFeature.java").assertSuccess();

    List<String> lines = harness.serviceLines(SERVICE);
    assertThat(lines).containsExactly(ALPHA, BETA);
    assertThat(lines).doesNotHaveDuplicates();
    assertThat(harness.allServiceFiles()).hasSize(1);
  }

  @Test
  void annotatedClassDeleted_freshFullBuildRemovesProvider() {
    CompilerHarness harness = fullBuild();

    harness.deleteSource("com/example/spi/BetaFeature.java");
    harness.compileAll().assertSuccess();

    assertThat(harness.classExists(BETA)).isFalse();
    assertThat(harness.serviceLines(SERVICE)).containsExactly(ALPHA);
  }

  @Test
  void annotatedClassDeleted_staleIncrementalOutputIsRejected() {
    CompilerHarness harness = fullBuild();
    harness.deleteSource("com/example/spi/BetaFeature.java");

    // Simulate a broken incremental driver: it recompiles only the changed/remaining file but does
    // NOT prune stale outputs. Beta.class is left on disk and the processor merges the old
    // descriptor content when rewriting, so the published output resurrects the deleted provider.
    harness.recompileIncrementally("com/example/spi/AlphaFeature.java").assertSuccess();

    StaleOutputGuard.Drift drift =
      StaleOutputGuard.inspect(harness.classes(), List.of(ALPHA), SERVICE);

    assertThat(drift.hasStaleProviderClass())
      .as("deleted BetaFeature.class must not survive an incremental build")
      .isTrue();
    assertThat(drift.hasStaleDescriptorEntry())
      .as("descriptor must not keep listing the deleted BetaFeature")
      .isTrue();
    StaleOutputGuard.assertRejectsDrift(drift);

    // Failure recovery: a clean full build from the remaining sources makes the output consistent again.
    StaleOutputGuard.clean(harness.classes());
    harness.compileAll().assertSuccess();
    StaleOutputGuard.Drift recovered =
      StaleOutputGuard.inspect(harness.classes(), List.of(ALPHA), SERVICE);
    assertThat(recovered.clean()).isTrue();
    assertThat(harness.serviceLines(SERVICE)).containsExactly(ALPHA);
  }

  @Test
  void duplicateProviderLine_isRejected() {
    CompilerHarness harness = fullBuild();
    StaleOutputGuard.duplicateLine(harness.classes(), SERVICE, ALPHA);

    StaleOutputGuard.Drift drift = StaleOutputGuard.inspectForDuplicates(harness.classes(), SERVICE);
    assertThat(drift.hasDuplicateDescriptorEntry()).isTrue();
    StaleOutputGuard.assertRejectsDrift(drift);
  }

  @Test
  void cleanOutputOfFullBuild_hasNoDrift() {
    CompilerHarness harness = fullBuild();
    StaleOutputGuard.Drift drift =
      StaleOutputGuard.inspect(harness.classes(), List.of(ALPHA, BETA), SERVICE);
    assertThat(drift.clean()).isTrue();
  }
}
