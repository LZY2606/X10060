package io.avaje.config.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two independent temp-dir builds of the same fixture must produce byte-identical
 * generated artifacts: no absolute paths or timestamps may leak into the output.
 */
class ReproducibleBuildTest {

  @TempDir Path tempDirA;
  @TempDir Path tempDirB;

  @Test
  void twoIndependentBuildsProduceIdenticalHashes() {
    FixtureCompiler first = FixtureCompiler.create(tempDirA, "ProviderAlpha", "ProviderBeta");
    FixtureCompiler second = FixtureCompiler.create(tempDirB, "ProviderAlpha", "ProviderBeta");

    first.compileClasspath().assertSuccess();
    second.compileClasspath().assertSuccess();

    Map<String, String> hashesA = first.hashOutputs();
    Map<String, String> hashesB = second.hashOutputs();

    assertThat(hashesA).as("first build must generate artifacts").isNotEmpty();
    assertThat(hashesB).as("second build must generate artifacts").isNotEmpty();
    assertThat(hashesA)
        .as(
            "generated artifacts must be byte-identical across independent temp dirs:\nA=%s\nB=%s",
            hashesA, hashesB)
        .isEqualTo(hashesB);
    assertThat(first.descriptorLines()).isEqualTo(second.descriptorLines());
  }
}
