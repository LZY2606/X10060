package io.avaje.config.it;

import io.avaje.config.it.support.FixtureCompiler;
import io.avaje.config.it.support.FixtureCompiler.Options;
import io.avaje.config.it.support.FixtureWorkspace;
import io.avaje.config.it.support.GeneratedOutputs;
import io.avaje.config.it.support.ModulePathRunner;
import io.avaje.config.it.support.ModulePathRunner.ExecResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer-side boundary on the module path.
 * <p>
 * Locks that a named module compiled with the processor exposes both providers
 * to {@link java.util.ServiceLoader} from a separate consumer module, and that
 * the classpath (unnamed module) entry point behaves symmetrically.
 */
class JpmsModulePathConsumerTest {

  private final FixtureCompiler javac = new FixtureCompiler();
  private final ModulePathRunner runner = new ModulePathRunner(javac);

  @Test
  void namedModuleProviders_areServiceLoadedOnModulePath() {
    try (FixtureWorkspace provider = FixtureWorkspace.copyOf("mod");
         FixtureWorkspace consumer = FixtureWorkspace.copyOf("consumer")) {

      javac.compile(provider, provider.allSourceFiles(), Options.moduleMode())
        .assertSuccess("building named-module provider");
      GeneratedOutputs.assertServiceExactly(provider.output(),
        List.of("org.example.ALoader", "org.example.BLoader"));

      runner.compileConsumer(consumer, provider.output());
      ExecResult result = runner.run(consumer, "example.consumer",
        "org.example.consumer.Main", provider.output());
      result.assertSuccess("running module-path consumer");
      assertThat(result.output).contains("providers=2");
    }
  }

  @Test
  void deletingProvider_reflectsImmediatelyForModulePathConsumer() {
    try (FixtureWorkspace provider = FixtureWorkspace.copyOf("mod");
         FixtureWorkspace consumer = FixtureWorkspace.copyOf("consumer")) {

      javac.compile(provider, provider.allSourceFiles(), Options.moduleMode())
        .assertSuccess("initial named-module provider build");

      provider.delete("org/example/BLoader.java");
      provider.write("module-info.java",
        "module example.fixture {" + System.lineSeparator()
          + "  requires io.avaje.config;" + System.lineSeparator()
          + "  requires static io.avaje.spi;" + System.lineSeparator()
          + "  provides io.avaje.config.ConfigExtension with org.example.ALoader;"
          + System.lineSeparator()
          + "}" + System.lineSeparator());

      // Safe recovery: clear derived output, rebuild whole source set.
      new io.avaje.config.it.support.IncrementalDriver(javac)
        .rebuildAll(provider, Options.moduleMode())
        .assertSuccess("provider rebuild after deleting BLoader");

      GeneratedOutputs.assertServiceExactly(provider.output(), List.of("org.example.ALoader"));
      GeneratedOutputs.assertNoClassFor(provider.output(), List.of("org.example.BLoader"));

      // The consumer's Main expects exactly 2 providers, so running it against the
      // reduced provider module must now fail - proving the stale provider is gone.
      runner.compileConsumer(consumer, provider.output());
      ExecResult result = runner.run(consumer, "example.consumer",
        "org.example.consumer.Main", provider.output());
      if (result.exitCode == 0 && result.output.contains("providers=2")) {
        throw new AssertionError("Consumer still observed the deleted BLoader provider on the "
          + "module path. Output: " + result.output);
      }
      assertThat(result.output).contains("expected 2");
    }
  }
}
