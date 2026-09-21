package io.avaje.config.processor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Drives a forked {@code javac} against source fixtures written into temporary
 * directories, so annotation-processor behaviour is exercised exactly as in a real
 * build: a fresh compiler JVM per invocation (the processor keeps static state that
 * makes a second in-process compile invalid), no network, no sleeps.
 *
 * <p>A failing compiler invocation is never swallowed: every failure propagates with
 * the full command and diagnostic context.
 */
final class FixtureCompiler {

  static final String SPI_SERVICE_JAR_PREFIX = "avaje-spi-service-";
  static final String SPI_CORE_JAR_PREFIX = "avaje-spi-core-";

  private FixtureCompiler() {
  }

  /** A single javac run together with everything needed to diagnose it afterwards. */
  static final class Result {
    final boolean success;
    final String diagnostics;
    final List<String> command;
    final List<Path> sourceFiles;

    Result(boolean success, String diagnostics, List<String> command,
           List<Path> sourceFiles) {
      this.success = success;
      this.diagnostics = diagnostics;
      this.command = command;
      this.sourceFiles = sourceFiles;
    }
  }

  static String processorPath() {
    return classpathEntry(SPI_SERVICE_JAR_PREFIX) + File.pathSeparator
      + classpathEntry(SPI_CORE_JAR_PREFIX);
  }

  /** Locate a single classpath entry whose file name starts with the given prefix. */
  static Path classpathEntry(String fileNamePrefix) {
    return classpathEntryMatches(java.util.regex.Pattern.quote(fileNamePrefix));
  }

  /** Locate a single classpath entry whose file name matches the given regex. */
  static Path classpathEntryMatches(String fileNameRegex) {
    String classpath = System.getProperty("java.class.path", "");
    var pattern = java.util.regex.Pattern.compile(fileNameRegex);
    List<Path> matches = Arrays.stream(classpath.split(File.pathSeparator))
      .filter(entry -> !entry.isBlank())
      .map(Path::of)
      .filter(path -> pattern.matcher(path.getFileName().toString()).find())
      .collect(Collectors.toList());
    if (matches.size() != 1) {
      throw new AssertionError("Expected exactly one classpath entry matching "
        + fileNameRegex + " but found " + matches + " on classpath:\n" + classpath);
    }
    return matches.get(0);
  }

  /** Compile every {@code .java} file found under the source directory. */
  static Result compileTree(Path sourceDir, Path outputDir, String... extraOptions) {
    return run(sourceDir, listJavaSources(sourceDir), outputDir, extraOptions);
  }

  /** Compile but do not raise on failure (used by negative test cases). */
  static Result compileTreeExpectFailure(Path sourceDir, Path outputDir,
                                         String... extraOptions) {
    try {
      return compileTree(sourceDir, outputDir, extraOptions);
    } catch (AssertionError expected) {
      return new Result(false, expected.getMessage(), List.of(),
        listJavaSources(sourceDir));
    }
  }

  /** Compile an explicit set of source paths relative to the source directory. */
  static Result compileFiles(Path sourceDir, List<Path> sourceFiles, Path outputDir,
                             String... extraOptions) {
    List<Path> absolute = sourceFiles.stream()
      .map(sourceDir::resolve)
      .map(Path::normalize)
      .collect(Collectors.toList());
    return run(sourceDir, absolute, outputDir, extraOptions);
  }

  private static Result run(Path sourceDir, List<Path> sourceFiles, Path outputDir,
                            String... extraOptions) {
    List<String> command = new ArrayList<>();
    command.add(javacExecutable().toString());
    command.addAll(Arrays.asList(extraOptions));
    command.addAll(Arrays.asList("-d", outputDir.toString(),
      "-s", outputDir.toString()));
    boolean processorPathProvided = command.stream()
      .anyMatch("-proc:none"::equals)
      || command.stream().anyMatch("-processorpath"::equals);
    if (!processorPathProvided) {
      command.addAll(Arrays.asList("-processorpath", processorPath()));
    }
    boolean classPathProvided = command.contains("-cp")
      || command.contains("-classpath")
      || command.contains("--module-path");
    if (!classPathProvided) {
      command.addAll(Arrays.asList("-cp",
        classpathEntry(SPI_SERVICE_JAR_PREFIX).toString()));
    }
    sourceFiles.forEach(file -> command.add(file.toString()));

    Process process;
    try {
      process = new ProcessBuilder(command)
        .directory(sourceDir.toFile())
        .redirectErrorStream(true)
        .start();
    } catch (IOException e) {
      throw new AssertionError("Cannot start javac: " + command, e);
    }
    String output;
    try {
      byte[] bytes = process.getInputStream().readAllBytes();
      if (!process.waitFor(5, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new AssertionError("javac timed out: " + command);
      }
      output = new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError("Cannot read javac output: " + command, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted waiting for javac: " + command, e);
    }

    boolean success = process.exitValue() == 0;
    if (!success) {
      throw new AssertionError("javac failed (exit " + process.exitValue()
        + ") compiling fixture in " + sourceDir + System.lineSeparator()
        + "command: " + command + System.lineSeparator()
        + "output:" + System.lineSeparator() + output);
    }
    return new Result(true, output, command, sourceFiles);
  }

  private static Path javacExecutable() {
    Path javaHome = Path.of(System.getProperty("java.home"));
    String executable = System.getProperty("os.name", "").toLowerCase()
      .contains("win") ? "javac.exe" : "javac";
    Path javac = javaHome.resolve("bin").resolve(executable);
    if (!Files.isExecutable(javac)) {
      throw new AssertionError("JDK javac not found or not executable at " + javac
        + " (tests must run on a JDK, not a JRE)");
    }
    return javac;
  }

  static List<Path> listJavaSources(Path sourceDir) {
    try (Stream<Path> walk = Files.walk(sourceDir)) {
      return walk
        .filter(path -> path.toString().endsWith(".java"))
        .sorted()
        .collect(Collectors.toList());
    } catch (IOException e) {
      throw new AssertionError("Cannot list sources under " + sourceDir, e);
    }
  }
}
