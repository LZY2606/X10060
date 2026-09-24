package io.avaje.config.it;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the published jar metadata for every reactor module:
 * JPMS descriptor presence, deduplicated service declarations, absence of
 * machine-specific paths, and a sources jar that really contains every source
 * (the "sources jar missing files" regression).
 * <p>
 * These assertions read the reactor's {@code target} jars when reachable from
 * the working directory, which is the case for both {@code mvn package} and
 * {@code mvn test} invoked from the repository root.
 */
class PublishingMetadataTest {

  private static final Map<String, String> MODULE_JARS = new LinkedHashMap<>();

  static {
    MODULE_JARS.put("avaje-config", "avaje-config/target/avaje-config-5.2.jar");
    MODULE_JARS.put("avaje-config-toml", "avaje-config-toml/target/avaje-config-toml-5.2.jar");
    MODULE_JARS.put("avaje-aws-appconfig", "avaje-aws-appconfig/target/avaje-aws-appconfig-1.7.jar");
    MODULE_JARS.put("avaje-dynamic-logback",
      "avaje-dynamic-logback/target/avaje-dynamic-logback-2.0.jar");
  }

  private static final Map<String, Path> SOURCE_ROOTS = new LinkedHashMap<>();

  static {
    SOURCE_ROOTS.put("avaje-config", Paths.get("avaje-config/src/main/java"));
    SOURCE_ROOTS.put("avaje-config-toml", Paths.get("avaje-config-toml/src/main/java"));
    SOURCE_ROOTS.put("avaje-aws-appconfig", Paths.get("avaje-aws-appconfig/src/main/java"));
    SOURCE_ROOTS.put("avaje-dynamic-logback",
      Paths.get("avaje-dynamic-logback/src/main/java"));
  }

  private static Path repoRoot() {
    Path cwd = Paths.get("").toAbsolutePath();
    if (Files.isRegularFile(cwd.resolve("pom.xml"))) {
      return cwd;
    }
    Path fromModule = cwd.resolve("../pom.xml");
    if (Files.isRegularFile(fromModule)) {
      return fromModule.getParent().normalize();
    }
    throw new IllegalStateException("Cannot locate reactor root from working directory " + cwd);
  }

  private static Path reactorJar(String relative) {
    Path jar = repoRoot().resolve(relative);
    if (!Files.isRegularFile(jar)) {
      throw new IllegalStateException("Expected built jar is missing: " + jar
        + ". Run the reactor build (package) first.");
    }
    return jar;
  }

  @Test
  void everyReactorJarCarriesItsModuleDescriptor() throws Exception {
    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> module : MODULE_JARS.entrySet()) {
      try (JarFile jar = new JarFile(reactorJar(module.getValue()).toFile())) {
        if (jar.getJarEntry("module-info.class") == null) {
          missing.add(module.getKey());
        }
      }
    }
    if (!missing.isEmpty()) {
      throw new AssertionError("Published jars missing module-info.class (JPMS visibility loss): "
        + missing);
    }
  }

  @Test
  void serviceDeclarationsInPublishedJars_areDeduplicatedAndSorted() throws Exception {
    for (Map.Entry<String, String> module : MODULE_JARS.entrySet()) {
      Path jarPath = reactorJar(module.getValue());
      try (JarFile jar = new JarFile(jarPath.toFile())) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          if (!entry.getName().startsWith("META-INF/services/") || entry.isDirectory()) {
            continue;
          }
          List<String> lines = readServiceLines(jar, entry);
          List<String> duplicates = duplicatesOf(lines);
          if (!duplicates.isEmpty()) {
            throw new AssertionError("Duplicate service declarations in " + jarPath
              + "!" + entry.getName() + ": " + duplicates + " full=" + lines);
          }
          List<String> blank = lines.stream().filter(String::isBlank).collect(Collectors.toList());
          if (!blank.isEmpty() && !lines.isEmpty()) {
            throw new AssertionError("Blank/whitespace service line in " + jarPath + "!"
              + entry.getName() + ": " + lines);
          }
          for (String line : lines) {
            if (line.startsWith("/") || line.contains("\\") || line.contains("target")) {
              throw new AssertionError("Service declaration embeds a machine path in "
                + jarPath + "!" + entry.getName() + ": " + line);
            }
          }
        }
      }
    }
  }

  @Test
  void publishedJarsDoNotEmbedTheMavenDescriptorOrAbsolutePaths() throws Exception {
    for (Map.Entry<String, String> module : MODULE_JARS.entrySet()) {
      Path jarPath = reactorJar(module.getValue());
      try (JarFile jar = new JarFile(jarPath.toFile())) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          String name = entries.nextElement().getName();
          if (name.startsWith("META-INF/maven/")) {
            throw new AssertionError("Maven descriptor leaks into published jar "
              + jarPath + ": " + name);
          }
        }
      }
    }
  }

  @Test
  void sourcesJarsContainEveryMainSource() throws Exception {
    List<String> failures = new ArrayList<>();
    for (Map.Entry<String, String> module : MODULE_JARS.entrySet()) {
      String name = module.getKey();
      Path mainJar = reactorJar(module.getValue());
      Path sourcesJar = sourcesJarFor(mainJar);
      if (!Files.isRegularFile(sourcesJar)) {
        failures.add(name + ": sources jar missing at " + sourcesJar);
        continue;
      }
      Set<String> sourcesEntries = new HashSet<>();
      try (JarFile jar = new JarFile(sourcesJar.toFile())) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          String entryName = entries.nextElement().getName();
          if (entryName.endsWith(".java")) {
            sourcesEntries.add(entryName);
          }
        }
      }
      Set<String> expected = expectedSourceEntries(SOURCE_ROOTS.get(name));
      if (!sourcesEntries.equals(expected)) {
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(sourcesEntries);
        Set<String> extra = new HashSet<>(sourcesEntries);
        extra.removeAll(expected);
        failures.add(name + ": sources jar mismatch missing=" + missing + " extra=" + extra);
      }
    }
    if (!failures.isEmpty()) {
      throw new AssertionError("Sources jar verification failed:"
        + System.lineSeparator() + String.join(System.lineSeparator(), failures));
    }
  }

  @Test
  void reactorPomPinsReproducibleBuildTimestamp() throws Exception {
    Path rootPom = repoRoot().resolve("pom.xml");
    String text = Files.readString(rootPom, StandardCharsets.UTF_8);
    assertThat(text)
      .as("reactor pom must pin project.build.outputTimestamp for reproducible artifacts")
      .contains("project.build.outputTimestamp");
    assertThat(text).doesNotContain("${maven.build.timestamp}");
  }

  // --------------------------------------------------------------- helpers

  private static Path sourcesJarFor(Path mainJar) {
    String n = mainJar.getFileName().toString();
    String sourcesName = n.replace(".jar", "-sources.jar");
    return mainJar.resolveSibling(sourcesName);
  }

  private static Set<String> expectedSourceEntries(Path sourceRoot) throws IOException {
    if (!Files.isDirectory(sourceRoot)) {
      throw new IllegalStateException("source root missing: " + sourceRoot);
    }
    try (Stream<Path> walk = Files.walk(sourceRoot)) {
      return walk.filter(Files::isRegularFile)
        .filter(p -> p.toString().endsWith(".java"))
        .map(sourceRoot::relativize)
        .map(Path::toString)
        .map(s -> s.replace('\\', '/'))
        .collect(Collectors.toCollection(HashSet::new));
    }
  }

  private static List<String> readServiceLines(JarFile jar, JarEntry entry) throws IOException {
    List<String> lines = new ArrayList<>();
    try (InputStream in = jar.getInputStream(entry);
         BufferedReader reader = new BufferedReader(
           new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
          lines.add(trimmed);
        }
      }
    }
    return lines;
  }

  private static List<String> duplicatesOf(List<String> lines) {
    Set<String> seen = new HashSet<>();
    List<String> dupes = new ArrayList<>();
    for (String line : lines) {
      if (!seen.add(line)) {
        dupes.add(line);
      }
    }
    return dupes;
  }

  @SuppressWarnings("unused")
  private static String readAll(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
