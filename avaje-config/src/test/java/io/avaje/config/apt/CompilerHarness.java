package io.avaje.config.apt;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Minimal in-process javac fixture driver for the avaje {@code ServiceProcessor}.
 *
 * <p>The fixture deliberately compiles loose source trees with the system JDK compiler so that
 * full, single-file and delete scenarios all run identically on a developer laptop and in CI,
 * without Gradle, network access or any machine-specific path. Processor jars are resolved from
 * the test classpath (avaje-spi-service / avaje-spi-core).
 */
final class CompilerHarness {

  private final Path work;
  private final Path sourceRoot;
  private final Path classes;
  private final List<String> notes = new ArrayList<>();

  private CompilerHarness(Path work) {
    this.work = work;
    this.sourceRoot = work.resolve("src");
    this.classes = work.resolve("classes");
    dirs(sourceRoot, classes);
  }

  static CompilerHarness create(Path work) {
    return new CompilerHarness(work);
  }

  Path sourceRoot() {
    return sourceRoot;
  }

  Path classes() {
    return classes;
  }

  /** Write a source file relative to the source root, parent packages created automatically. */
  Path source(String relativePath, String content) {
    Path file = sourceRoot.resolve(relativePath);
    write(file, content);
    return file;
  }

  /** Delete a previously written source (the "annotated class removed" scenario). */
  void deleteSource(String relativePath) {
    delete(sourceRoot.resolve(relativePath));
  }

  /** Modify a source in place, bumping its content (the "only one file changed" scenario). */
  void modifySource(String relativePath, String content) {
    write(sourceRoot.resolve(relativePath), content);
  }

  /** Compile every {@code .java} file found under the source root (full build). */
  Result compileAll() {
    return compile(javaSources());
  }

  /**
   * Compile only the given source-relative files. The previously compiled output directory is
   * both the destination and on the classpath, so untouched classes remain visible exactly like
   * an incremental build.
   */
  Result compileOnly(String... relativeSourcePaths) {
    List<Path> files = Arrays.stream(relativeSourcePaths).map(sourceRoot::resolve).collect(Collectors.toList());
    return compile(files);
  }

  private Result compile(List<Path> files) {
    return compile(files, false);
  }

  /**
   * Incremental compilation: only the given files are compiled with {@code -implicit:none} so the
   * classes left over in the output directory (which is itself on the classpath) are reused
   * instead of being regenerated through implicit source compilation. This mirrors how build
   * tools recompile a subset against a persistent output directory.
   */
  Result recompileIncrementally(String... relativeSourcePaths) {
    List<Path> files = Arrays.stream(relativeSourcePaths).map(sourceRoot::resolve).collect(Collectors.toList());
    return compile(files, true);
  }

  private Result compile(List<Path> files, boolean incremental) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("System java compiler unavailable - tests require a JDK, not a JRE");
    }
    Path processorOut = work.resolve("processor-diagnostics.txt");
    List<String> options = new ArrayList<>(Arrays.asList(
      "-proc:full",
      "-encoding", "UTF-8",
      "-classpath", spiAnnotationJar().toString() + java.io.File.pathSeparator + classes.toString(),
      "-processorpath", spiAnnotationJar() + java.io.File.pathSeparator + spiProcessorJar(),
      "-d", classes.toString(),
      // let the processor resolve types referenced but not part of the current compilation unit
      // (e.g. the service interface when only one implementation is recompiled incrementally)
      "-sourcepath", sourceRoot.toString()
    ));
    if (incremental) {
      options.add("-implicit:none");
    }
    String diagnostics;
    boolean ok;
    try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
      fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
      Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(files);
      JavaCompiler.CompilationTask task =
        compiler.getTask(Files.newBufferedWriter(processorOut, StandardCharsets.UTF_8),
          fm, null, options, null, units);
      ok = task.call();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    diagnostics = readString(processorOut);
    if (!diagnostics.isBlank()) {
      notes.add(diagnostics.strip());
    }
    return new Result(ok, diagnostics, notes());
  }

  /** Read the service descriptor lines (one provider FQN per line, sorted, comments dropped). */
  List<String> serviceLines(String serviceFqn) {
    Path file = classes.resolve("META-INF").resolve("services").resolve(serviceFqn);
    if (!Files.exists(file)) {
      return List.of();
    }
    return readLines(file).stream()
      .map(String::trim)
      .filter(s -> !s.isEmpty() && !s.startsWith("#"))
      .sorted()
      .collect(Collectors.toList());
  }

  boolean serviceFileExists(String serviceFqn) {
    return Files.exists(classes.resolve("META-INF").resolve("services").resolve(serviceFqn));
  }

  boolean classExists(String fqn) {
    return Files.exists(classes.resolve(fqn.replace('.', '/') + ".class"));
  }

  List<String> allServiceFiles() {
    Path dir = classes.resolve("META-INF").resolve("services");
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> stream = Files.walk(dir)) {
      return stream.filter(Files::isRegularFile)
        .map(p -> classes.relativize(p).toString().replace('\\', '/'))
        .sorted()
        .collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** SHA-256 of every regular file under classes, keyed by classes-relative path. */
  Map<String, String> contentHashes() {
    Map<String, String> hashes = new TreeMap<>();
    if (!Files.isDirectory(classes)) {
      return hashes;
    }
    try (Stream<Path> stream = Files.walk(classes)) {
      stream.filter(Files::isRegularFile).forEach(p -> {
        String relative = classes.relativize(p).toString().replace('\\', '/');
        hashes.put(relative, sha256(p));
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return hashes;
  }

  String diagnosticsText() {
    return notes();
  }

  private String notes() {
    return String.join(System.lineSeparator(), notes);
  }

  private List<Path> javaSources() {
    try (Stream<Path> stream = Files.walk(sourceRoot)) {
      return stream.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Path spiAnnotationJar() {
    return jarFromClasspath("avaje-spi-service");
  }

  static Path spiProcessorJar() {
    return jarFromClasspath("avaje-spi-core");
  }

  private static Path jarFromClasspath(String artifact) {
    String classpath = System.getProperty("java.class.path");
    for (String entry : classpath.split(java.io.File.pathSeparator, -1)) {
      String normalized = entry.replace('\\', '/');
      if (normalized.contains("/" + artifact + "/") && normalized.endsWith(".jar")) {
        Path path = Path.of(entry);
        if (Files.isRegularFile(path)) {
          return path.toAbsolutePath().normalize();
        }
      }
    }
    throw new IllegalStateException(artifact + " jar not found on test classpath: " + classpath);
  }

  private static void dirs(Path... paths) {
    for (Path path : paths) {
      try {
        Files.createDirectories(path);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static void write(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void delete(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String readString(Path file) {
    try {
      return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String sha256(Path file) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  static final class Result {
    final boolean success;
    final String diagnostics;
    final String allDiagnostics;

    Result(boolean success, String diagnostics, String allDiagnostics) {
      this.success = success;
      this.diagnostics = diagnostics;
      this.allDiagnostics = allDiagnostics;
    }

    Result assertSuccess() {
      if (!success) {
        throw new AssertionError("javac compilation failed" + System.lineSeparator() + allDiagnostics);
      }
      return this;
    }

    Result assertFailure() {
      if (success) {
        throw new AssertionError("javac compilation unexpectedly succeeded" + System.lineSeparator() + allDiagnostics);
      }
      return this;
    }

    Result assertDiagnosticContains(String fragment) {
      if (!allDiagnostics.contains(fragment)) {
        throw new AssertionError(
          "expected javac diagnostic containing [" + fragment + "] but got:" + System.lineSeparator() + allDiagnostics);
      }
      return this;
    }
  }
}
