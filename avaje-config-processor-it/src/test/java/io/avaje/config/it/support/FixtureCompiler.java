package io.avaje.config.it.support;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Runs javac in-process against an isolated {@link FixtureWorkspace}.
 * <p>
 * Diagnostics are always captured and attached to failures so a regression is
 * debuggable without rerunning anything by hand.
 */
final class FixtureCompiler {

  private final ClasspathJars jars = ClasspathJars.resolve();
  private final JavaCompiler compiler;

  FixtureCompiler() {
    this.compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("System JavaCompiler unavailable - tests must run on a JDK");
    }
  }

  ClasspathJars jars() {
    return jars;
  }

  static final class Result {
    final boolean success;
    final String diagnostics;
    final List<String> errorLines;

    private Result(boolean success, String diagnostics, List<String> errorLines) {
      this.success = success;
      this.diagnostics = diagnostics;
      this.errorLines = errorLines;
    }

    void assertSuccess(String scenario) {
      if (!success) {
        throw new AssertionError("javac failed while " + scenario + System.lineSeparator() + diagnostics);
      }
    }

    void assertFailed(String scenario, String messageFragment) {
      if (success) {
        throw new AssertionError("javac unexpectedly succeeded while " + scenario
          + " (expected failure mentioning: " + messageFragment + ")");
      }
      if (!diagnostics.contains(messageFragment)) {
        throw new AssertionError("javac failed while " + scenario + " but the diagnostic did not contain ["
          + messageFragment + "]:" + System.lineSeparator() + diagnostics);
      }
    }
  }

  static final class Options {
    boolean moduleMode;
    boolean processorEnabled = true;
    Path explicitProcessorPath;
    boolean useProcessorModulePath;

    static Options moduleMode() {
      Options o = new Options();
      o.moduleMode = true;
      o.useProcessorModulePath = true;
      return o;
    }

    static Options classpathMode() {
      return new Options();
    }

    Options withoutProcessor() {
      this.processorEnabled = false;
      return this;
    }

    Options processorPath(Path path) {
      this.explicitProcessorPath = path;
      return this;
    }
  }

  Result compile(FixtureWorkspace ws, List<Path> sourceFiles, Options options) {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    StringWriter compilerOut = new StringWriter();

    try (StandardJavaFileManager fileManager =
           compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {

      fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(ws.output().toFile()));

      Iterable<? extends JavaFileObject> units =
        fileManager.getJavaFileObjectsFromPaths(sourceFiles);

      List<String> rawOptions = new ArrayList<>();
      rawOptions.add("-Xlint:none");

      if (options.moduleMode) {
        rawOptions.add("--module-path");
        rawOptions.add(jars.compileModulePath());
        if (options.processorEnabled) {
          rawOptions.add("--processor-module-path");
          rawOptions.add(options.explicitProcessorPath != null
            ? options.explicitProcessorPath.toString()
            : jars.processorPath());
        }
      } else {
        rawOptions.add("-classpath");
        rawOptions.add(jars.compileClasspath());
        if (options.processorEnabled) {
          rawOptions.add("--processor-path");
          rawOptions.add(options.explicitProcessorPath != null
            ? options.explicitProcessorPath.toString()
            : jars.processorPath());
        }
      }
      if (!options.processorEnabled) {
        rawOptions.add("-proc:none");
      }

      JavaCompiler.CompilationTask task = compiler.getTask(
        compilerOut, fileManager, diagnostics, rawOptions, null, units);

      boolean ok = task.call();
      String text = render(diagnostics) + compilerOut;
      List<String> errors = diagnostics.getDiagnostics().stream()
        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
        .map(Diagnostic::getMessage)
        .collect(Collectors.toList());
      return new Result(ok, text, errors);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String render(DiagnosticCollector<JavaFileObject> diagnostics) {
    StringBuilder sb = new StringBuilder();
    for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
      sb.append(d.getKind()).append(": ").append(d.getMessage(Locale.ROOT));
      if (d.getSource() != null) {
        sb.append(" @ ").append(d.getSource().getName())
          .append(':').append(d.getLineNumber());
      }
      sb.append(System.lineSeparator());
    }
    return sb.toString();
  }
}
