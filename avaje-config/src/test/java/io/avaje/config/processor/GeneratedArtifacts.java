package io.avaje.config.processor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Integrity checks for annotation-processor output. Each check refuses vacuous success:
 * finding nothing to validate is itself a failure (zero-collection rejection), and every
 * failure carries the offending artifact and value for diagnosis.
 */
final class GeneratedArtifacts {

  private static final String SERVICES_PREFIX = "META-INF/services/";
  private static final Set<String> VALID_INCREMENTAL_TYPES =
    new HashSet<>(Arrays.asList("isolating", "aggregating", "dynamic"));

  private GeneratedArtifacts() {
  }

  /** Service interface -> registered implementations, as declared by the generated files. */
  static Map<String, List<String>> serviceDeclarations(Path outputDir) {
    Map<String, List<String>> declarations = new LinkedHashMap<>();
    Path servicesDir = outputDir.resolve("META-INF").resolve("services");
    if (!Files.isDirectory(servicesDir)) {
      throw new AssertionError("No generated META-INF/services directory under " + outputDir);
    }
    try (Stream<Path> files = Files.list(servicesDir)) {
      files.filter(Files::isRegularFile).sorted().forEach(file -> {
        List<String> lines = nonBlankLines(file);
        if (lines.isEmpty()) {
          throw new AssertionError("Empty generated service declaration file: " + file);
        }
        declarations.put(file.getFileName().toString(), lines);
      });
    } catch (IOException e) {
      throw new AssertionError("Cannot read generated service declarations under " + servicesDir, e);
    }
    if (declarations.isEmpty()) {
      throw new AssertionError("Zero service declaration files collected under " + servicesDir);
    }
    return declarations;
  }

  /**
   * Full three-direction validation of generated service files:
   * no duplicates, no stale registrations, and no missing @ServiceProvider registrations.
   */
  static void verifyServiceRegistrations(Path outputDir, Path sourceDir) {
    Map<String, List<String>> declarations = serviceDeclarations(outputDir);
    Set<String> allRegistrations = new LinkedHashSet<>();
    for (Map.Entry<String, List<String>> entry : declarations.entrySet()) {
      String serviceFile = SERVICES_PREFIX + entry.getKey();
      List<String> implementations = entry.getValue();
      Set<String> unique = new LinkedHashSet<>();
      for (String implementation : implementations) {
        if (!unique.add(implementation)) {
          throw new AssertionError("Duplicate service declaration for " + implementation
            + " in " + serviceFile);
        }
        Path classFile = outputDir.resolve(implementation.replace('.', '/') + ".class");
        if (!Files.isRegularFile(classFile)) {
          throw new AssertionError("Stale service declaration: " + serviceFile + " lists "
            + implementation + " but generated class is missing: " + classFile);
        }
        Path sourceFile = sourceDir.resolve(implementation.replace('.', '/') + ".java");
        if (!Files.isRegularFile(sourceFile)) {
          throw new AssertionError("Stale service declaration: " + serviceFile + " lists "
            + implementation + " but no annotated source exists: " + sourceFile);
        }
        allRegistrations.add(implementation);
      }
    }
    Set<String> annotatedSources = serviceProviderSources(sourceDir);
    if (annotatedSources.isEmpty()) {
      throw new AssertionError("Zero @ServiceProvider sources collected under " + sourceDir);
    }
    for (String annotated : annotatedSources) {
      if (!allRegistrations.contains(annotated)) {
        throw new AssertionError("Missing service declaration: " + annotated
          + " is annotated with @ServiceProvider but appears in no generated file under "
          + SERVICES_PREFIX);
      }
    }
  }

  /** FQCN of every source under the fixture that is annotated with {@code @ServiceProvider}. */
  static Set<String> serviceProviderSources(Path sourceDir) {
    Set<String> providers = new LinkedHashSet<>();
    try (Stream<Path> walk = Files.walk(sourceDir)) {
      walk.filter(path -> path.toString().endsWith(".java")).forEach(source -> {
        try {
          String text = Files.readString(source);
          if (text.contains("@ServiceProvider")) {
            providers.add(fqn(sourceDir, source, text));
          }
        } catch (IOException e) {
          throw new AssertionError("Cannot read fixture source " + source, e);
        }
      });
    } catch (IOException e) {
      throw new AssertionError("Cannot scan fixture sources under " + sourceDir, e);
    }
    return providers;
  }

  /**
   * Cross-checks {@code META-INF/services/javax.annotation.processing.Processor} against
   * {@code META-INF/gradle/incremental.annotation.processors} in every processor jar.
   * Registration and metadata may live in different jars on the processor path, so the
   * comparison is aggregated across all supplied jars: every registered processor must
   * be declared exactly once overall, with a valid incremental type.
   *
   * @return processor FQCN -> declared incremental type
   */
  static Map<String, String> verifyIncrementalMetadata(List<Path> processorJars) {
    if (processorJars.isEmpty()) {
      throw new AssertionError("Zero processor jars supplied for incremental metadata check");
    }
    List<String> allRegistered = new ArrayList<>();
    Map<String, String> declared = new LinkedHashMap<>();
    for (Path jar : processorJars) {
      List<String> registered = readJarLines(jar,
        "META-INF/services/javax.annotation.processing.Processor");
      allRegistered.addAll(registered);
      List<String> metadata = readJarLines(jar,
        "META-INF/gradle/incremental.annotation.processors");
      for (String line : metadata) {
        int comma = line.indexOf(',');
        if (comma < 0) {
          throw new AssertionError("Malformed incremental metadata line in " + jar + ": " + line);
        }
        String processor = line.substring(0, comma).trim();
        String type = line.substring(comma + 1).trim();
        if (!VALID_INCREMENTAL_TYPES.contains(type)) {
          throw new AssertionError("Incremental metadata for " + processor + " in " + jar
            + " declares unknown type '" + type + "'");
        }
        String previous = declared.putIfAbsent(processor, type);
        if (previous != null) {
          throw new AssertionError("Duplicate incremental metadata declaration for "
            + processor + " (seen in " + jar + " and an earlier processor jar)");
        }
      }
    }
    if (declared.isEmpty()) {
      throw new AssertionError("Zero registered annotation processors collected from "
        + processorJars);
    }
    if (allRegistered.isEmpty()) {
      throw new AssertionError("Zero registered annotation processors found in "
        + processorJars);
    }
    for (String processor : allRegistered) {
      if (!declared.containsKey(processor)) {
        throw new AssertionError("Incremental metadata mismatch: registered processor "
          + processor + " on the processor path has no "
          + "META-INF/gradle/incremental.annotation.processors declaration "
          + "(declared processors: " + declared.keySet() + ")");
      }
    }
    for (String processor : declared.keySet()) {
      if (!allRegistered.contains(processor)) {
        throw new AssertionError("Incremental metadata mismatch: " + processor
          + " is declared in incremental.annotation.processors but is not registered "
          + "in META-INF/services/javax.annotation.processing.Processor");
      }
    }
    return declared;
  }

  /** Rejects any generated file whose bytes embed an absolute fixture path. */
  static void assertNoAbsolutePathLeak(Path outputDir, Path... roots) {
    List<Path> generated;
    try (Stream<Path> walk = Files.walk(outputDir)) {
      generated = walk.filter(Files::isRegularFile).collect(Collectors.toList());
    } catch (IOException e) {
      throw new AssertionError("Cannot walk generated output " + outputDir, e);
    }
    if (generated.isEmpty()) {
      throw new AssertionError("Zero generated files collected under " + outputDir);
    }
    for (Path file : generated) {
      byte[] bytes;
      try {
        bytes = Files.readAllBytes(file);
      } catch (IOException e) {
        throw new AssertionError("Cannot read generated file " + file, e);
      }
      for (Path root : roots) {
        String absolute = root.toAbsolutePath().normalize().toString();
        if (new String(bytes, StandardCharsets.UTF_8).contains(absolute)) {
          throw new AssertionError("Generated file " + file + " embeds absolute path " + absolute);
        }
      }
    }
  }


  private static List<String> nonBlankLines(Path file) {
    List<String> lines = new ArrayList<>();
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
          lines.add(trimmed);
        }
      }
    } catch (IOException e) {
      throw new AssertionError("Cannot read generated file " + file, e);
    }
    return lines;
  }

  private static List<String> readJarLines(Path jarPath, String entryName) {
    List<String> lines = new ArrayList<>();
    try (ZipFile jar = new ZipFile(jarPath.toFile())) {
      ZipEntry entry = jar.getEntry(entryName);
      if (entry == null) {
        return lines;
      }
      try (InputStream in = jar.getInputStream(entry)) {
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        for (String line : content.split("\\R")) {
          String trimmed = line.trim();
          if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
            lines.add(trimmed);
          }
        }
      }
    } catch (IOException e) {
      throw new AssertionError("Cannot read " + entryName + " from " + jarPath, e);
    }
    return lines;
  }

  private static String fqn(Path sourceDir, Path source, String content) {
    String packageName = "";
    for (String line : content.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("package ")) {
        packageName = trimmed.substring("package ".length(), trimmed.indexOf(';')).trim();
        break;
      }
    }
    String simpleName = source.getFileName().toString().replace(".java", "");
    return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
  }
}
