package io.avaje.config.it;

import io.avaje.config.it.support.FixtureCompiler;
import io.avaje.config.it.support.FixtureCompiler.Options;
import io.avaje.config.it.support.FixtureWorkspace;
import io.avaje.config.it.support.GeneratedOutputs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full (clean) compilation contract from both entry sides:
 * a named module and the unnamed classpath mode.
 */
class FullCompilationGenerationTest {

  private final FixtureCompiler javac = new FixtureCompiler();

  private static final List<String> EXPECTED_PROVIDERS =
    List.of("org.example.ALoader", "org.example.BLoader");
  private static final List<String> EXPECTED_CLASSES =
    List.of("org.example.ALoader", "org.example.BLoader", "module-info");

  @Test
  void namedModule_fullBuild_generatesServiceDeclarationAndClasses() {
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("mod")) {
      FixtureCompiler.Result result =
        javac.compile(ws, ws.allSourceFiles(), Options.moduleMode());

      result.assertSuccess("full named-module compilation");
      GeneratedOutputs.assertNonEmpty(ws.output());
      GeneratedOutputs.assertClassFiles(ws.output(), EXPECTED_CLASSES);
      GeneratedOutputs.assertServiceExactly(ws.output(), EXPECTED_PROVIDERS);
      GeneratedOutputs.assertNoDuplicateServiceEntries(ws.output());
      GeneratedOutputs.assertNoAbsolutePaths(ws.output());
    }
  }

  @Test
  void classpathMode_fullBuild_generatesSameServiceDeclaration() {
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("unnamed")) {
      FixtureCompiler.Result result =
        javac.compile(ws, ws.allSourceFiles(), Options.classpathMode());

      result.assertSuccess("full unnamed-module compilation");
      GeneratedOutputs.assertClassFiles(ws.output(),
        List.of("org.example.ALoader", "org.example.BLoader"));
      GeneratedOutputs.assertServiceExactly(ws.output(), EXPECTED_PROVIDERS);
      GeneratedOutputs.assertNoDuplicateServiceEntries(ws.output());
      GeneratedOutputs.assertNoAbsolutePaths(ws.output());
    }
  }

  @Test
  void twoIndependentWorkspaces_produceIdenticalContent() {
    Map<String, String> first;
    Map<String, String> second;
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("mod")) {
      javac.compile(ws, ws.allSourceFiles(), Options.moduleMode())
        .assertSuccess("first full build for fingerprinting");
      first = GeneratedOutputs.contentFingerprint(ws.output());
    }
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("mod")) {
      javac.compile(ws, ws.allSourceFiles(), Options.moduleMode())
        .assertSuccess("second full build for fingerprinting");
      second = GeneratedOutputs.contentFingerprint(ws.output());
    }
    assertThat(first).isNotEmpty();
    GeneratedOutputs.assertFingerprintsEqual(first, second);
  }

  @Test
  void processorDisabled_generatesNoServiceDeclaration() {
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("unnamed")) {
      FixtureCompiler.Result result =
        javac.compile(ws, ws.allSourceFiles(), Options.classpathMode().withoutProcessor());

      result.assertSuccess("compilation with annotation processing disabled");
      GeneratedOutputs.assertNoServiceFile(ws.output());
    }
  }
}
