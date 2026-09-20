package io.avaje.config.apt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Independent guard that answers one question: does the published classes directory agree with
 * the source set the build driver believes it compiled? It detects the three incremental-build
 * failure modes for a service-file annotation processor:
 *
 * <ul>
 *   <li><b>stale provider class</b> - a provider {@code .class} whose implementation source
 *       has been deleted (the interface is allowed via the {@code expectedClasses} set),</li>
 *   <li><b>stale descriptor entry</b> - a provider FQN still listed in
 *       {@code META-INF/services/...} after it should have disappeared,</li>
 *   <li><b>duplicate descriptor entry</b> - the same provider listed twice in one file.</li>
 * </ul>
 */
final class StaleOutputGuard {

  private StaleOutputGuard() {
  }

  /** Delete all generated files so the next build starts from an empty output directory. */
  static void clean(Path classesDir) {
    if (!Files.exists(classesDir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(classesDir)) {
      List<Path> paths = walk.sorted((a, b) -> b.compareTo(a)).collect(Collectors.toList());
      for (Path path : paths) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Compare the classes directory against the expected live providers. A provider class present
   * on disk but absent from {@code expectedProviders} is stale; the descriptor must list
   * exactly the expected providers once each.
   */
  static Drift inspect(Path classesDir, List<String> expectedProviders, String serviceFqn) {
    boolean staleClass = staleProviderClass(classesDir, new HashSet<>(expectedProviders));
    List<String> descriptor = readDescriptor(classesDir, serviceFqn);
    boolean staleEntry = !descriptor.equals(sorted(expectedProviders));
    boolean duplicate = hasDuplicateLines(descriptor);
    return new Drift(staleClass, staleEntry, duplicate);
  }

  static Drift inspectForDuplicates(Path classesDir, String serviceFqn) {
    return new Drift(false, false, hasDuplicateLines(readDescriptor(classesDir, serviceFqn)));
  }

  /** Append the provider a second time to simulate a processor that failed to de-duplicate. */
  static void duplicateLine(Path classesDir, String serviceFqn, String provider) {
    Path file = descriptor(classesDir, serviceFqn);
    try {
      List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
      lines.add(provider);
      Files.write(file, lines, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void assertRejectsDrift(Drift drift) {
    if (drift.clean()) {
      throw new AssertionError("guard unexpectedly accepted an output directory with drift");
    }
  }

  private static boolean staleProviderClass(Path classesDir, Set<String> expectedProviders) {
    Path packageRoot = classesDir.resolve("com").resolve("example");
    if (!Files.isDirectory(packageRoot)) {
      return false;
    }
    try (Stream<Path> walk = Files.walk(packageRoot)) {
      return walk.filter(p -> p.toString().endsWith("Feature.class"))
        .map(classesDir::relativize)
        .map(Path::toString)
        .map(s -> s.substring(0, s.length() - ".class".length()).replace('/', '.'))
        .anyMatch(fqn -> !fqn.equals("com.example.spi.Feature") && !expectedProviders.contains(fqn));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean hasDuplicateLines(List<String> lines) {
    return new HashSet<>(lines).size() != lines.size();
  }

  private static List<String> readDescriptor(Path classesDir, String serviceFqn) {
    Path file = descriptor(classesDir, serviceFqn);
    if (!Files.exists(file)) {
      return List.of();
    }
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
        .map(String::trim)
        .filter(s -> !s.isEmpty() && !s.startsWith("#"))
        .sorted()
        .collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path descriptor(Path classesDir, String serviceFqn) {
    return classesDir.resolve("META-INF").resolve("services").resolve(serviceFqn);
  }

  private static List<String> sorted(List<String> in) {
    return in.stream().sorted().collect(Collectors.toList());
  }

  static final class Drift {
    private final boolean staleProviderClass;
    private final boolean staleDescriptorEntry;
    private final boolean duplicateDescriptorEntry;

    Drift(boolean staleProviderClass, boolean staleDescriptorEntry, boolean duplicateDescriptorEntry) {
      this.staleProviderClass = staleProviderClass;
      this.staleDescriptorEntry = staleDescriptorEntry;
      this.duplicateDescriptorEntry = duplicateDescriptorEntry;
    }

    boolean hasStaleProviderClass() {
      return staleProviderClass;
    }

    boolean hasStaleDescriptorEntry() {
      return staleDescriptorEntry;
    }

    boolean hasDuplicateDescriptorEntry() {
      return duplicateDescriptorEntry;
    }

    boolean clean() {
      return !staleProviderClass && !staleDescriptorEntry && !duplicateDescriptorEntry;
    }
  }
}
