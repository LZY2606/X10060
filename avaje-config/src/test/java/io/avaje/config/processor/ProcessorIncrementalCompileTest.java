package io.avaje.config.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the avaje-spi annotation processor contract that avaje-config plugins rely on:
 * full compile, single-source incremental recompile, and deletion of an annotated class.
 */
class ProcessorIncrementalCompileTest {

  @TempDir Path tempDir;

  @Test
  void fullCompileGeneratesCompleteServiceDescriptor() {
    FixtureCompiler fixture = FixtureCompiler.create(tempDir, "ProviderAlpha", "ProviderBeta");

    fixture.compileClasspath().assertSuccess();

    fixture.verifyDescriptor();
    assertThat(fixture.descriptorLines())
        .containsExactlyInAnyOrder("org.fixture.ProviderAlpha", "org.fixture.ProviderBeta");
  }

  @Test
  void singleSourceChangeKeepsDescriptorWithoutDuplicates() throws IOException {
    FixtureCompiler fixture = FixtureCompiler.create(tempDir, "ProviderAlpha", "ProviderBeta");
    fixture.compileClasspath().assertSuccess();

    fixture.modifyProvider("ProviderBeta");
    fixture
        .compileClasspathFiles(List.of(fixture.providerSource("ProviderBeta")))
        .assertSuccess();

    fixture.verifyDescriptor();
    assertThat(fixture.descriptorLines())
        .as("incremental recompile must not duplicate service declarations")
        .containsExactlyInAnyOrder("org.fixture.ProviderAlpha", "org.fixture.ProviderBeta");
    byte[] regenerated =
        Files.readAllBytes(fixture.classes().resolve("org/fixture/ProviderBeta.class"));
    assertThat(new String(regenerated, java.nio.charset.StandardCharsets.ISO_8859_1))
        .as("the edited source must actually be recompiled")
        .contains("markerMethod");
  }

  @Test
  void deletedProviderLeavesStaleDescriptorThatVerificationRejects_thenCleanRebuildRecovers() {
    FixtureCompiler fixture = FixtureCompiler.create(tempDir, "ProviderAlpha", "ProviderBeta");
    fixture.compileClasspath().assertSuccess();

    fixture.deleteProvider("ProviderBeta");
    // incremental recompile of the remaining sources into the same output dir:
    // the processor merges the pre-existing descriptor, so the deleted provider survives
    fixture.compileClasspath().assertSuccess();

    assertThatThrownBy(fixture::verifyDescriptor)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("stale generated entries")
        .hasMessageContaining("org.fixture.ProviderBeta");

    // failure recovery: a clean rebuild drops the stale generated entry
    deleteRecursively(fixture.classes());
    fixture.compileClasspath().assertSuccess();
    fixture.verifyDescriptor();
    assertThat(fixture.descriptorLines()).containsExactly("org.fixture.ProviderAlpha");
  }

  @Test
  void zeroCollectedProvidersIsRejected() {
    FixtureCompiler fixture = FixtureCompiler.create(tempDir, "ProviderAlpha");
    // -proc:none simulates the processor silently not running: nothing is generated
    fixture.compileClasspath("-proc:none").assertSuccess();

    assertThatThrownBy(fixture::verifyDescriptor)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("zero providers collected");
  }

  @Test
  void duplicateServiceDeclarationIsRejected() throws IOException {
    FixtureCompiler fixture = FixtureCompiler.create(tempDir, "ProviderAlpha");
    fixture.compileClasspath().assertSuccess();

    Path descriptor = fixture.classes().resolve(FixtureCompiler.DESCRIPTOR_PATH);
    Files.write(
        descriptor,
        List.of("org.fixture.ProviderAlpha"),
        StandardOpenOption.APPEND);

    assertThatThrownBy(fixture::verifyDescriptor)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("duplicate service declarations");
  }

  @Test
  void processorIsDeclaredAggregatingInIncrementalMetadata() throws IOException {
    // the processor merges existing descriptors, so it must stay declared "aggregating";
    // a downgrade to "isolating" would let build tools skip the full recompile it needs
    var resources =
        getClass()
            .getClassLoader()
            .getResources("META-INF/gradle/incremental.annotation.processors");
    StringBuilder metadata = new StringBuilder();
    while (resources.hasMoreElements()) {
      try (var in = resources.nextElement().openStream()) {
        metadata.append(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
    }
    assertThat(metadata.toString())
        .contains("io.avaje.spi.internal.ServiceProcessor,aggregating");
  }

  private static void deleteRecursively(Path dir) {
    try (var stream = Files.walk(dir)) {
      stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
