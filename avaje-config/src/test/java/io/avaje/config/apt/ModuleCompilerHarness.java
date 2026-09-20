package io.avaje.config.apt;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Drives module-path compilation of a small two-module fixture (provider + consumer) using the
 * in-process JDK compiler and a {@code javap} read-back for {@code module-info.class}.
 * No build tool, network or absolute fixture path is involved - every input lives under the
 * JUnit temp directory and the processor jars come from the test classpath.
 */
final class ModuleCompilerHarness {

  private final Path work;
  private final Path providerSrc;
  private final Path providerOut;
  private final Path consumerSrc;
  private final Path consumerOut;
  private final List<String> notes = new ArrayList<>();

  private ModuleCompilerHarness(Path work) {
    this.work = work;
    this.providerSrc = work.resolve("provider-src");
    this.providerOut = work.resolve("provider-mod");
    this.consumerSrc = work.resolve("consumer-src");
    this.consumerOut = work.resolve("consumer-mod");
    dirs(providerSrc, providerOut, consumerSrc, consumerOut);
  }

  static ModuleCompilerHarness create(Path work) {
    return new ModuleCompilerHarness(work);
  }

  void moduleSource(String moduleInfo) {
    write(providerSrc.resolve("module-info.java"), moduleInfo);
  }

  Path source(String relativePath, String content) {
    Path file = providerSrc.resolve(relativePath);
    write(file, content);
    return file;
  }

  void consumerModuleSource(String moduleInfo) {
    write(consumerSrc.resolve("module-info.java"), moduleInfo);
  }

  void consumerSource(String relativePath, String content) {
    write(consumerSrc.resolve(relativePath), content);
  }

  CompilerHarness.Result compileProviderModule() {
    List<Path> sources = javaSources(providerSrc);
    return runJavac(sources, providerOut, List.of(
      "--module-path", CompilerHarness.spiAnnotationJar().toString(),
      "--processor-module-path",
        CompilerHarness.spiAnnotationJar() + java.io.File.pathSeparator + CompilerHarness.spiProcessorJar()
    ));
  }

  CompilerHarness.Result compileConsumerModule() {
    return runJavac(javaSources(consumerSrc), consumerOut, List.of(
      "--module-path", providerOut.toString()
    ));
  }

  List<String> runConsumerMain() {
    Path javaExe = Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable("java"));
    String modulePath = providerOut + java.io.File.pathSeparator + consumerOut;
    ProcessBuilder pb = new ProcessBuilder(
      javaExe.toString(),
      "--module-path", modulePath,
      "-m", "com.example.consumer/com.example.consumer.Main");
    pb.directory(work.toFile());
    try {
      Process process = pb.start();
      String stdout = drain(process.getInputStream());
      String stderr = drain(process.getErrorStream());
      int exit = process.waitFor();
      if (exit != 0) {
        throw new AssertionError("consumer Main failed exit=" + exit + System.lineSeparator() + stderr);
      }
      return stdout.lines().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  List<String> serviceLines(String serviceFqn) {
    Path file = providerOut.resolve("META-INF").resolve("services").resolve(serviceFqn);
    if (!Files.exists(file)) {
      return List.of();
    }
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
        .map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#"))
        .sorted().collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  boolean moduleInfoDeclaresProvides() {
    return javapModule().contains("provides") && javapModule().contains("com.example.spi.Feature");
  }

  boolean moduleInfoRequiresStaticSpi() {
    return javapModule().lines().anyMatch(l -> l.contains("requires static") && l.contains("io.avaje.spi"));
  }

  boolean moduleInfoRequiresSpiStrongly() {
    return javapModule().lines().anyMatch(l -> {
      String t = l.trim();
      return t.startsWith("requires") && !t.startsWith("requires static")
        && !t.startsWith("requires transitive") && t.contains("io.avaje.spi");
    });
  }

  private String javapModule() {
    Path javap = Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable("javap"));
    ProcessBuilder pb = new ProcessBuilder(javap.toString(), "-p",
      providerOut.resolve("module-info.class").toString());
    try {
      Process process = pb.start();
      String out = drain(process.getInputStream());
      process.waitFor();
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private CompilerHarness.Result runJavac(List<Path> sources, Path out, List<String> extraOptions) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("System java compiler unavailable - tests require a JDK, not a JRE");
    }
    List<String> options = new ArrayList<>(Arrays.asList(
      "-proc:full",
      "-encoding", "UTF-8",
      "--release", "11",
      "-d", out.toString()));
    options.addAll(extraOptions);
    Path log = work.resolve("javac-" + (out == providerOut ? "provider" : "consumer") + ".txt");
    boolean ok;
    String diagnostics;
    try (StandardJavaFileManager fm =
           compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
      fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
      Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);
      JavaCompiler.CompilationTask task =
        compiler.getTask(Files.newBufferedWriter(log, StandardCharsets.UTF_8),
          fm, null, options, null, units);
      ok = task.call();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    try {
      diagnostics = Files.readString(log, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!diagnostics.isBlank()) {
      notes.add(diagnostics.strip());
    }
    return new CompilerHarness.Result(ok, diagnostics, String.join(System.lineSeparator(), notes));
  }

  private List<Path> javaSources(Path root) {
    try (Stream<Path> stream = Files.walk(root)) {
      return stream.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String drain(java.io.InputStream in) {
    try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
      return reader.lines().collect(Collectors.joining(System.lineSeparator()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String executable(String name) {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? name + ".exe" : name;
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
}
