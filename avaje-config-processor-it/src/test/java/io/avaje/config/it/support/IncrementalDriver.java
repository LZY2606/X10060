package io.avaje.config.it.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The safe incremental build driver used by the fixture.
 * <p>
 * The SPI processor is an <strong>aggregating</strong> processor: its service
 * declaration is a function of every annotated source in the module, and the
 * processor itself merges with whatever already sits in the output directory
 * rather than replacing it. javac also never deletes stale class files. The
 * only correct way to run such a processor incrementally is therefore:
 * <ol>
 *   <li>delete previously generated/derived output for this source set, and</li>
 *   <li>recompile the <em>whole</em> source set with the processor enabled.</li>
 * </ol>
 * This mirrors Gradle's contract for aggregating processors (full rebuild of
 * the source set) and Maven's {@code clean} semantics.
 */
final class IncrementalDriver {

  private final FixtureCompiler compiler;

  IncrementalDriver(FixtureCompiler compiler) {
    this.compiler = compiler;
  }

  /**
   * Clears all derived output and recompiles the given (whole) source set.
   */
  FixtureCompiler.Result rebuild(FixtureWorkspace ws, List<Path> sourceFiles,
                                FixtureCompiler.Options options) {
    clearOutput(ws.output());
    return compiler.compile(ws, sourceFiles, options);
  }

  FixtureCompiler.Result rebuildAll(FixtureWorkspace ws, FixtureCompiler.Options options) {
    return rebuild(ws, options.moduleMode ? ws.allSourceFiles()
      : ws.sourceTree(false), options);
  }

  static void clearOutput(Path output) {
    if (!Files.isDirectory(output)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(output)) {
      walk.sorted(Comparator.reverseOrder())
        .filter(p -> !p.equals(output))
        .forEach(p -> {
          try {
            Files.delete(p);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

}
