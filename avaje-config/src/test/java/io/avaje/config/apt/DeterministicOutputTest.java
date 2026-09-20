package io.avaje.config.apt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generated artifacts must be a pure function of the source set: building the same sources in
 * two unrelated temp directories must yield byte-identical files, and rebuilding after a
 * clean in the same directory must reproduce the same hashes. Absolute work-dir paths,
 * timestamps or map-iteration ordering leaking into the generated descriptor/class output would
 * break reproducible releases, so they fail this test.
 */
class DeterministicOutputTest {

  private static final String SERVICE = "com.example.spi.Feature";

  @TempDir
  Path work;

  private void seed(CompilerHarness harness) {
    harness.source("com/example/spi/Feature.java",
      "package com.example.spi;\npublic interface Feature { String id(); }\n");
    for (String name : new String[] {"AlphaFeature", "BetaFeature", "GammaFeature"}) {
      harness.source("com/example/spi/" + name + ".java",
        "package com.example.spi;\nimport io.avaje.spi.ServiceProvider;\n"
        + "@ServiceProvider\npublic final class " + name + " implements Feature {\n"
        + "  public String id() { return \"" + name + "\"; }\n}\n");
    }
  }

  @Test
  void twoIndependentTempBuilds_produceIdenticalHashes() {
    Path first = work.resolve("first");
    Path second = work.resolve("second");

    CompilerHarness a = CompilerHarness.create(first);
    seed(a);
    a.compileAll().assertSuccess();

    CompilerHarness b = CompilerHarness.create(second);
    seed(b);
    b.compileAll().assertSuccess();

    Map<String, String> hashesA = a.contentHashes();
    Map<String, String> hashesB = b.contentHashes();

    assertThat(hashesA.keySet()).isEqualTo(hashesB.keySet());
    assertThat(hashesA).isEqualTo(hashesB);
  }

  @Test
  void cleanRebuild_inSameDirectory_producesIdenticalHashes() {
    Path dir = work.resolve("rebuild");
    CompilerHarness harness = CompilerHarness.create(dir);
    seed(harness);
    harness.compileAll().assertSuccess();
    Map<String, String> before = harness.contentHashes();

    StaleOutputGuard.clean(harness.classes());
    harness.compileAll().assertSuccess();
    Map<String, String> after = harness.contentHashes();

    assertThat(after).isEqualTo(before);
  }

  @Test
  void serviceDescriptor_orderIsStableAcrossBuilds() {
    Path first = work.resolve("o1");
    Path second = work.resolve("o2");
    CompilerHarness a = CompilerHarness.create(first);
    seed(a);
    a.compileAll().assertSuccess();
    CompilerHarness b = CompilerHarness.create(second);
    seed(b);
    b.compileAll().assertSuccess();

    assertThat(a.serviceLines(SERVICE)).isEqualTo(b.serviceLines(SERVICE));
    assertThat(a.serviceLines(SERVICE)).isSorted();
  }
}
