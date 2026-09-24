package io.avaje.config.it.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Compiles and launches a named-module consumer on the module path as a forked
 * JVM, returning combined output. A non-zero exit raises with full diagnostics.
 */
final class ModulePathRunner {

  static final class ExecResult {
    final int exitCode;
    final String output;

    private ExecResult(int exitCode, String output) {
      this.exitCode = exitCode;
      this.output = output;
    }

    void assertSuccess(String scenario) {
      if (exitCode != 0) {
        throw new AssertionError("Forked JVM failed while " + scenario + " (exit " + exitCode + "):"
          + System.lineSeparator() + output);
      }
    }
  }

  private final FixtureCompiler compiler;

  ModulePathRunner(FixtureCompiler compiler) {
    this.compiler = compiler;
  }

  Path compileConsumer(FixtureWorkspace consumer, Path providerOutput) {
    List<String> option = new ArrayList<>();
    option.add("--module-path");
    option.add(compiler.jars().compileModulePath() + java.io.File.pathSeparator + providerOutput);
    return javacRaw(consumer, option, consumer.allSourceFiles());
  }

  ExecResult run(FixtureWorkspace consumer, String moduleName, String mainClass,
                 Path... extraModulePaths) {
    Path javaHome = Path.of(System.getProperty("java.home"));
    Path javaExe = javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");

    Path appOut = consumer.output();
    StringBuilder modulePath = new StringBuilder(compiler.jars().compileModulePath())
      .append(java.io.File.pathSeparator).append(appOut);
    for (Path extra : extraModulePaths) {
      modulePath.append(java.io.File.pathSeparator).append(extra);
    }

    List<String> command = new ArrayList<>(List.of(
      javaExe.toString(),
      "--module-path", modulePath.toString(),
      "--module", moduleName + "/" + mainClass));

    ProcessBuilder pb = new ProcessBuilder(command);
    return start(pb, "running " + moduleName);
  }

  private Path javacRaw(FixtureWorkspace ws, List<String> extraOptions, List<Path> sources) {
    // simple raw javac so the consumer can reference an already-built provider output
    Path javac = Path.of(System.getProperty("java.home")).resolve("bin")
      .resolve(isWindows() ? "javac.exe" : "javac");
    List<String> command = new ArrayList<>(List.of(javac.toString(), "-d", ws.output().toString()));
    command.addAll(extraOptions);
    command.addAll(sources.stream().map(Path::toString).collect(Collectors.toList()));

    ProcessBuilder pb = new ProcessBuilder(command);
    ExecResult result = start(pb, "compiling consumer " + ws.fixtureName());
    result.assertSuccess("compiling consumer " + ws.fixtureName());
    return ws.output();
  }

  private ExecResult start(ProcessBuilder pb, String scenario) {
    pb.redirectErrorStream(true);
    try {
      Process process = pb.start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      boolean finished = process.waitFor(60, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new AssertionError("Forked JVM timed out while " + scenario + ":"
          + System.lineSeparator() + describe(pb) + System.lineSeparator() + output);
      }
      return new ExecResult(process.exitValue(), output);
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("Failed executing forked JVM while " + scenario
        + ":" + System.lineSeparator() + describe(pb), e);
    }
  }

  private static String describe(ProcessBuilder pb) {
    return String.join(" ", pb.command());
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }
}
