package io.avaje.config.processor;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Harness that generates a throwaway annotation-processor fixture in a temp directory and
 * compiles it with the system javac. Everything is derived from the current test classpath,
 * so the same code path runs locally and on CI with no network, sleeps, or host specifics.
 */
final class FixtureCompiler {

  static final String CONTRACT = "io.avaje.config.ConfigExtension";
  static final String DESCRIPTOR_PATH = "META-INF/services/" + CONTRACT;
  static final String PACKAGE_NAME = "org.fixture";
  static final String PROCESSOR = "io.avaje.spi.internal.ServiceProcessor";
  static final String MODULE_NAME = "org.fixture.providers";

  private static final List<String> MODULE_PATH_MARKERS =
      Arrays.asList("avaje-config", "avaje-spi", "avaje-applog", "jspecify", "snakeyaml");

  private final Path root;
  private final Path src;
  private final Path classes;
  private final List<String> providers = new ArrayList<>();

  private FixtureCompiler(Path root, List<String> providerNames) {
    this.root = root;
    this.src = root.resolve("src");
    this.classes = root.resolve("classes");
    this.providers.addAll(providerNames);
  }

  /** Create a fixture with one {@code @ServiceProvider} class per given simple name. */
  static FixtureCompiler create(Path parent, String... providerNames) {
    try {
      Path root = Files.createTempDirectory(parent, "fixture");
      FixtureCompiler fixture = new FixtureCompiler(root, Arrays.asList(providerNames));
      for (String name : providerNames) {
        fixture.writeProvider(name);
      }
      return fixture;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  Path root() {
    return root;
  }

  Path classes() {
    return classes;
  }

  List<String> providers() {
    return providers;
  }

  Path providerSource(String simpleName) {
    return src.resolve(PACKAGE_NAME.replace('.', '/')).resolve(simpleName + ".java");
  }

  void writeProvider(String simpleName) {
    write(providerSource(simpleName), providerSourceContent(simpleName, false));
  }

  /** Rewrite the provider with an extra marker method, simulating a single-source edit. */
  void modifyProvider(String simpleName) {
    write(providerSource(simpleName), providerSourceContent(simpleName, true));
  }

  void deleteProvider(String simpleName) {
    try {
      Files.delete(providerSource(simpleName));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    providers.remove(simpleName);
  }

  /** Generate module-info.java with a provides directive matching the current providers. */
  void writeModuleInfo(boolean withProvides) {
    StringBuilder sb = new StringBuilder();
    sb.append("import ").append(CONTRACT).append(";\n");
    sb.append("module ").append(MODULE_NAME).append(" {\n");
    sb.append("  requires io.avaje.config;\n");
    sb.append("  requires static io.avaje.spi;\n");
    if (withProvides) {
      sb.append("  provides ").append(CONTRACT).append(" with ");
      sb.append(
          providers.stream()
              .map(name -> PACKAGE_NAME + "." + name)
              .collect(Collectors.joining(", ")));
      sb.append(";\n");
    }
    sb.append("}\n");
    write(src.resolve("module-info.java"), sb.toString());
  }

  private static String providerSourceContent(String simpleName, boolean modified) {
    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(PACKAGE_NAME).append(";\n");
    sb.append("import ").append(CONTRACT).append(";\n");
    sb.append("import io.avaje.spi.ServiceProvider;\n");
    sb.append("@ServiceProvider\n");
    sb.append("public class ").append(simpleName).append(" implements ConfigExtension {\n");
    sb.append("  public ").append(simpleName).append("() {}\n");
    if (modified) {
      sb.append("  public String markerMethod() { return \"modified\"; }\n");
    }
    sb.append("}\n");
    return sb.toString();
  }

  private static void write(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, content.getBytes(UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  List<Path> allSources() {
    try (var stream = Files.walk(src)) {
      return stream
          .filter(path -> path.toString().endsWith(".java"))
          .sorted()
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Compile every current source in classpath mode (no module-info). */
  CompileResult compileClasspath(String... extraOptions) {
    return compileClasspathFiles(allSources(), extraOptions);
  }

  /** Compile only the given sources in classpath mode into the shared output dir. */
  CompileResult compileClasspathFiles(List<Path> files, String... extraOptions) {
    List<String> options = new ArrayList<>();
    options.addAll(
        Arrays.asList(
            "-classpath", String.join(java.io.File.pathSeparator, testClasspath()),
            "-processorpath", String.join(java.io.File.pathSeparator, processorPath()),
            "-processor", PROCESSOR,
            "-encoding", "UTF-8",
            "-d", classes.toString()));
    options.addAll(Arrays.asList(extraOptions));
    return javac(options, files);
  }

  /** Compile the fixture as an explicit JPMS module on the module path. */
  CompileResult compileModule() {
    List<String> options = new ArrayList<>();
    options.addAll(
        Arrays.asList(
            "--module-path", String.join(java.io.File.pathSeparator, modulePath()),
            "-processorpath", String.join(java.io.File.pathSeparator, processorPath()),
            "-processor", PROCESSOR,
            "-encoding", "UTF-8",
            "-d", classes.toString()));
    return javac(options, allSources());
  }

  /**
   * Verify the generated service descriptor against the providers currently in the fixture.
   * Rejects zero collection, duplicate declarations, stale entries (generated for a provider
   * whose source is gone) and missing entries. Every failure keeps full context.
   */
  void verifyDescriptor() {
    Path descriptor = classes.resolve(DESCRIPTOR_PATH);
    List<String> expected =
        providers.stream().map(name -> PACKAGE_NAME + "." + name).sorted().collect(Collectors.toList());
    if (!Files.exists(descriptor)) {
      throw new AssertionError(
          "zero providers collected: " + DESCRIPTOR_PATH + " was not generated under " + classes
              + " for providers " + expected);
    }
    List<String> actual;
    try {
      actual =
          Files.readAllLines(descriptor, UTF_8).stream()
              .map(String::trim)
              .filter(line -> !line.isEmpty())
              .collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (actual.isEmpty()) {
      throw new AssertionError(
          "zero providers collected: " + descriptor + " is empty, expected " + expected);
    }
    List<String> duplicates =
        actual.stream()
            .filter(line -> actual.indexOf(line) != actual.lastIndexOf(line))
            .distinct()
            .collect(Collectors.toList());
    if (!duplicates.isEmpty()) {
      throw new AssertionError(
          "duplicate service declarations in " + descriptor + ": " + duplicates
              + " full content " + actual);
    }
    List<String> stale =
        actual.stream().filter(line -> !expected.contains(line)).collect(Collectors.toList());
    if (!stale.isEmpty()) {
      throw new AssertionError(
          "stale generated entries in " + descriptor + ": " + stale
              + " have no matching source, expected " + expected + " actual " + actual);
    }
    List<String> missing =
        expected.stream().filter(line -> !actual.contains(line)).collect(Collectors.toList());
    if (!missing.isEmpty()) {
      throw new AssertionError(
          "missing generated entries in " + descriptor + ": " + missing
              + " expected " + expected + " actual " + actual);
    }
  }

  List<String> descriptorLines() {
    Path descriptor = classes.resolve(DESCRIPTOR_PATH);
    try {
      return Files.readAllLines(descriptor, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + descriptor, e);
    }
  }

  /** SHA-256 of every file under the output dir, keyed by relative path. */
  Map<String, String> hashOutputs() {
    try (var stream = Files.walk(classes)) {
      return stream
          .filter(Files::isRegularFile)
          .sorted()
          .collect(
              Collectors.toMap(
                  path -> classes.relativize(path).toString(),
                  FixtureCompiler::sha256,
                  (a, b) -> a,
                  TreeMap::new));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String sha256(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(Files.readAllBytes(file));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException | IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static CompileResult javac(List<String> options, List<Path> files) {
    List<String> command = new ArrayList<>();
    command.add(javacBinary());
    command.addAll(options);
    files.forEach(file -> command.add(file.toString()));
    List<String> fileNames = files.stream().map(Path::toString).collect(Collectors.toList());
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output;
      try (var in = process.getInputStream()) {
        output = new String(in.readAllBytes(), UTF_8);
      }
      if (!process.waitFor(120, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new AssertionError(
            "javac did not exit within 120s\ncommand: " + command + "\noutput:\n" + output);
      }
      return new CompileResult(process.exitValue() == 0, output, List.copyOf(options), fileNames);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to launch javac: " + command, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running javac: " + command, e);
    }
  }

  private static String javacBinary() {
    String executable = isWindows() ? "javac.exe" : "javac";
    return Path.of(System.getProperty("java.home"), "bin", executable).toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /** Full test runtime classpath, expanding a surefire booter jar if present. */
  static List<String> testClasspath() {
    List<String> entries =
        new ArrayList<>(Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
    if (entries.size() == 1 && entries.get(0).endsWith(".jar")) {
      List<String> expanded = expandManifestClassPath(entries.get(0));
      if (!expanded.isEmpty()) {
        return expanded;
      }
    }
    return entries;
  }

  private static List<String> expandManifestClassPath(String booterJar) {
    try (JarFile jar = new JarFile(booterJar)) {
      String classPath = jar.getManifest().getMainAttributes().getValue("Class-Path");
      if (classPath == null) {
        return List.of();
      }
      Path base = Path.of(booterJar).toAbsolutePath().getParent();
      List<String> expanded = new ArrayList<>();
      for (String entry : classPath.split(" ")) {
        String cleaned = entry.trim();
        if (cleaned.startsWith("file:")) {
          cleaned = Path.of(java.net.URI.create(cleaned)).toString();
        }
        expanded.add(cleaned);
      }
      return expanded;
    } catch (Exception e) {
      return List.of();
    }
  }

  /** Classpath entries that carry the modules the fixture needs, as a JPMS module path. */
  static List<String> modulePath() {
    return testClasspath().stream()
        .filter(entry -> !entry.contains("test-classes"))
        // slf4j bindings are runtime-only test deps and would drag unresolved requires in
        .filter(entry -> !entry.contains("slf4j"))
        .filter(entry -> MODULE_PATH_MARKERS.stream().anyMatch(entry::contains))
        .collect(Collectors.toList());
  }

  /** The jars that carry the annotation processor itself. */
  static List<String> processorPath() {
    return testClasspath().stream()
        .filter(entry -> entry.contains("avaje-spi"))
        .collect(Collectors.toList());
  }

  /** Compile an arbitrary source tree (used for the consumer module) against a module path. */
  static CompileResult compileWithModulePath(List<String> modulePath, Path outDir, List<Path> files) {
    List<String> options =
        new ArrayList<>(
            Arrays.asList(
                "--module-path", String.join(java.io.File.pathSeparator, modulePath),
                "-encoding", "UTF-8",
                "-d", outDir.toString()));
    return javac(options, files);
  }

  /** Deterministic ordering helper for hash comparisons. */
  static Map<String, String> sortedByKey(Map<String, String> map) {
    return map.entrySet().stream()
        .sorted(Comparator.comparing(Map.Entry::getKey))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }
}
