package io.avaje.config.it;

import io.avaje.config.it.support.FixtureCompiler;
import io.avaje.config.it.support.FixtureCompiler.Options;
import io.avaje.config.it.support.FixtureWorkspace;
import io.avaje.config.it.support.GeneratedOutputs;
import io.avaje.config.it.support.IncrementalDriver;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Incremental and failure-recovery contract.
 * <p>
 * The processor is declared to Gradle as an <em>aggregating</em> processor, so
 * its {@code META-INF/services/...} declaration is derived from every
 * annotated source in the source set. Two properties are locked here:
 * <ul>
 *   <li><b>Safe path:</b> the {@link IncrementalDriver} (clear derived output,
 *   then rebuild the whole source set) always converges to exactly the live
 *   providers, whether one file changed or an annotated class was deleted.</li>
 *   <li><b>Danger path:</b> recompiling into <em>stale</em> derived output
 *   without clearing it demonstrably keeps dead providers and classes - the
 *   regression guard that makes the safe path mandatory.</li>
 * </ul>
 */
class IncrementalBuildRecoveryTest {

  private static final String SERVICE = GeneratedOutputs.SERVICE_FILE;
  private static final List<String> BOTH =
    List.of("org.example.ALoader", "org.example.BLoader");

  private final FixtureCompiler javac = new FixtureCompiler();
  private final IncrementalDriver driver = new IncrementalDriver(javac);

  // ------------------------------------------------------------------ safe path

  @Test
  void changeOneSourceFile_safeRebuildKeepsExactlyTheLiveProviders() {
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("unnamed")) {
      driver.rebuild(ws, ws.sourceTree(false), Options.classpathMode())
        .assertSuccess("initial full build");
      GeneratedOutputs.assertServiceExactly(ws.output(), BOTH);

      String modified = ws.read("org/example/ALoader.java")
        .replace("loaded", "loaded-incrementally");
      ws.write("org/example/ALoader.java", modified);

      driver.rebuild(ws, ws.sourceTree(false), Options.classpathMode())
        .assertSuccess("safe incremental rebuild after editing one file");

      GeneratedOutputs.assertServiceExactly(ws.output(), BOTH);
      GeneratedOutputs.assertNoDuplicateServiceEntries(ws.output());
      GeneratedOutputs.assertClassFiles(ws.output(),
        List.of("org.example.ALoader", "org.example.BLoader"));
    }
  }

  @Test
  void deleteAnnotatedClass_safeRebuildDropsProviderAndClass() {
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("unnamed")) {
      driver.rebuild(ws, ws.sourceTree(false), Options.classpathMode())
        .assertSuccess("initial full build");

      ws.delete("org/example/BLoader.java");

      driver.rebuild(ws, ws.sourceTree(false), Options.classpathMode())
        .assertSuccess("safe rebuild after deleting annotated class BLoader");

      GeneratedOutputs.assertServiceExactly(ws.output(), List.of("org.example.ALoader"));
      GeneratedOutputs.assertNoStaleServiceProviders(ws.output(), List.of("org.example.BLoader"));
      GeneratedOutputs.assertNoClassFor(ws.output(), List.of("org.example.BLoader"));
    }
  }

  @Test
  void deleteAnnotatedClass_namedModule_safeRebuildAfterProvidesUpdate() {
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("mod")) {
      driver.rebuildAll(ws, Options.moduleMode())
        .assertSuccess("initial full named-module build");

      ws.delete("org/example/BLoader.java");

      FixtureCompiler.Result staleProvides =
        driver.rebuildAll(ws, Options.moduleMode());
      staleProvides.assertFailed(
        "rebuild after deleting BLoader but leaving it in the provides clause", "provides");

      ws.write("module-info.java",
        "module example.fixture {" + System.lineSeparator()
          + "  requires io.avaje.config;" + System.lineSeparator()
          + "  requires static io.avaje.spi;" + System.lineSeparator()
          + "  provides io.avaje.config.ConfigExtension with org.example.ALoader;"
          + System.lineSeparator()
          + "}" + System.lineSeparator());

      driver.rebuildAll(ws, Options.moduleMode())
        .assertSuccess("named-module rebuild after removing BLoader from provides");

      GeneratedOutputs.assertServiceExactly(ws.output(), List.of("org.example.ALoader"));
      GeneratedOutputs.assertNoClassFor(ws.output(), List.of("org.example.BLoader"));
    }
  }

  // ---------------------------------------------------------------- danger path

  /**
   * Regression guard for the most dangerous aggregating-processor foot-gun:
   * a provider deleted from the source set must not survive in the generated
   * service declaration. This compiles into stale output WITHOUT clearing it,
   * reproduces the stale provider, and proves our verification rejects it.
   */
  @Test
  void deletedProviderInStaleOutput_isDetectedAsRegression() {
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("unnamed")) {
      driver.rebuild(ws, ws.sourceTree(false), Options.classpathMode())
        .assertSuccess("initial full build");

      ws.delete("org/example/BLoader.java");
      // Deliberately do NOT clear: emulate a naive incremental driver.
      FixtureCompiler.Result naive =
        javac.compile(ws, ws.sourceTree(false), Options.classpathMode());
      naive.assertSuccess("naive rebuild reusing stale output directory");

      boolean staleDetected;
      try {
        GeneratedOutputs.assertNoStaleServiceProviders(ws.output(),
          List.of("org.example.BLoader"));
        staleDetected = false;
      } catch (AssertionError expected) {
        staleDetected = true;
      }
      if (!staleDetected) {
        throw new AssertionError("Expected the stale generated provider org.example.BLoader "
          + "to be detectable; the aggregating-processor regression guard no longer fires.");
      }
    }
  }

  /**
   * Stale class files are equally dangerous: javac never removes
   * {@code BLoader.class} just because its source was deleted, so a clean
   * derived-output step is mandatory.
   */
  @Test
  void staleClassFile_isDetectedAsRegression() {
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("unnamed")) {
      driver.rebuild(ws, ws.sourceTree(false), Options.classpathMode())
        .assertSuccess("initial full build");

      ws.delete("org/example/ALoader.java");
      javac.compile(ws, ws.sourceTree(false), Options.classpathMode())
        .assertSuccess("naive rebuild reusing stale output directory");

      boolean staleDetected;
      try {
        GeneratedOutputs.assertNoClassFor(ws.output(), List.of("org.example.ALoader"));
        staleDetected = false;
      } catch (AssertionError expected) {
        staleDetected = true;
      }
      if (!staleDetected) {
        throw new AssertionError("Expected stale org/example/ALoader.class to be detectable; "
          + "the stale-class regression guard no longer fires.");
      }
    }
  }

  /**
   * A duplicate service line produced by repeated processing over stale output
   * must be rejected, and the safe driver must converge back to one entry each.
   */
  @Test
  void duplicatedServiceEntries_areRejectedAndRecoverable() {
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("unnamed")) {
      ws.writeOutput(SERVICE,
        "org.example.ALoader" + System.lineSeparator()
          + "org.example.ALoader" + System.lineSeparator()
          + "org.example.BLoader" + System.lineSeparator());
      javac.compile(ws, ws.sourceTree(false), Options.classpathMode())
        .assertSuccess("compile over a duplicated generated service file");

      boolean duplicateDetected;
      try {
        GeneratedOutputs.assertNoDuplicateServiceEntries(ws.output());
        duplicateDetected = false;
      } catch (AssertionError expected) {
        duplicateDetected = true;
      }
      if (!duplicateDetected) {
        throw new AssertionError("Expected duplicate provider entries to be detectable; "
          + "the duplicate-service regression guard no longer fires.");
      }

      driver.rebuild(ws, ws.sourceTree(false), Options.classpathMode())
        .assertSuccess("safe rebuild after clearing duplicated output");
      GeneratedOutputs.assertServiceExactly(ws.output(), BOTH);
    }
  }

  // ------------------------------------------------------------- failure recovery

  @Test
  void missingProvidesClause_failsWithDiagnosticContext() {
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("mod")) {
      ws.write("org/example/CLoader.java",
        "package org.example;" + System.lineSeparator()
          + "import io.avaje.config.Configuration;" + System.lineSeparator()
          + "import io.avaje.config.ConfigurationSource;" + System.lineSeparator()
          + "import io.avaje.spi.ServiceProvider;" + System.lineSeparator()
          + "@ServiceProvider" + System.lineSeparator()
          + "public class CLoader implements ConfigurationSource {" + System.lineSeparator()
          + "  @Override public void load(Configuration configuration) {" + System.lineSeparator()
          + "    configuration.setProperty(\"fixture.c\", \"loaded\");" + System.lineSeparator()
          + "  }" + System.lineSeparator()
          + "}" + System.lineSeparator());

      FixtureCompiler.Result result =
        javac.compile(ws, ws.allSourceFiles(), Options.moduleMode());
      result.assertFailed("adding CLoader without a provides clause",
        "provides io.avaje.config.ConfigExtension with");
    }
  }
}
