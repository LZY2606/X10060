package io.avaje.config.it.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Assertions over everything javac emitted into a workspace output directory.
 * <p>
 * Every check produces a message naming the offending file and showing the
 * actual content; nothing silently passes.
 */
final class GeneratedOutputs {

  static final String SERVICE_FILE = "META-INF/services/io.avaje.config.ConfigExtension";

  private GeneratedOutputs() {
  }

  static List<String> relativeFiles(Path output) {
    try (Stream<Path> walk = Files.walk(output)) {
      return walk.filter(Files::isRegularFile)
        .map(output::relativize)
        .map(Path::toString)
        .map(s -> s.replace('\\', '/'))
        .sorted()
        .collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<String> serviceLines(Path output) {
    Path file = output.resolve(SERVICE_FILE);
    if (!Files.isRegularFile(file)) {
      throw new AssertionError("Expected generated " + SERVICE_FILE + " but it is missing. Output tree: "
        + relativeFiles(output));
    }
    try {
      String content = Files.readString(file, StandardCharsets.UTF_8);
      List<String> lines = new ArrayList<>();
      for (String raw : content.split("\\R", -1)) {
        String line = raw.trim();
        if (!line.isEmpty()) {
          lines.add(line);
        }
      }
      return lines;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void assertServiceExactly(Path output, List<String> expected) {
    List<String> actual = serviceLines(output);
    List<String> sortedExpected = new ArrayList<>(expected);
    sortedExpected.sort(Comparator.naturalOrder());
    if (!actual.equals(sortedExpected)) {
      throw new AssertionError(SERVICE_FILE + " mismatch."
        + System.lineSeparator() + "expected: " + sortedExpected
        + System.lineSeparator() + "actual:   " + actual
        + System.lineSeparator() + "raw file bytes: "
        + bytesDescription(output.resolve(SERVICE_FILE)));
    }
  }

  static void assertNoServiceFile(Path output) {
    if (Files.exists(output.resolve(SERVICE_FILE))) {
      throw new AssertionError("Did not expect " + SERVICE_FILE + " but it exists with content: "
        + bytesDescription(output.resolve(SERVICE_FILE)));
    }
  }

  /** Rejects duplicate provider lines (a symptom of repeated processor registration). */
  static void assertNoDuplicateServiceEntries(Path output) {
    List<String> lines = serviceLines(output);
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String line : lines) {
      counts.merge(line, 1, Integer::sum);
    }
    List<String> dupes = counts.entrySet().stream()
      .filter(e -> e.getValue() > 1)
      .map(Map.Entry::getKey)
      .collect(Collectors.toList());
    if (!dupes.isEmpty()) {
      throw new AssertionError(SERVICE_FILE + " contains duplicate provider entries: " + dupes
        + System.lineSeparator() + lines);
    }
  }

  static void assertClassFiles(Path output, List<String> expectedClasses) {
    List<String> missing = expectedClasses.stream()
      .map(c -> c.replace('.', '/') + ".class")
      .filter(rel -> !Files.isRegularFile(output.resolve(rel)))
      .collect(Collectors.toList());
    if (!missing.isEmpty()) {
      throw new AssertionError("Missing expected generated class files: " + missing
        + System.lineSeparator() + "output tree: " + relativeFiles(output));
    }
  }

  static void assertNoClassFor(Path output, List<String> removedClasses) {
    List<String> stale = removedClasses.stream()
      .map(c -> c.replace('.', '/') + ".class")
      .filter(rel -> Files.exists(output.resolve(rel)))
      .collect(Collectors.toList());
    if (!stale.isEmpty()) {
      throw new AssertionError("Stale generated class files survive after their annotated source was deleted: "
        + stale + System.lineSeparator() + "output tree: " + relativeFiles(output));
    }
  }

  static void assertNoStaleServiceProviders(Path output, List<String> removedProviders) {
    List<String> lines = serviceLines(output);
    List<String> stale = new ArrayList<>(lines);
    stale.retainAll(removedProviders);
    if (!stale.isEmpty()) {
      throw new AssertionError(SERVICE_FILE
        + " still references providers whose @ServiceProvider source was deleted: " + stale
        + System.lineSeparator() + lines);
    }
  }

  /**
   * Generated service declarations must never embed the build machine location.
   */
  static void assertNoAbsolutePaths(Path output) {
    List<String> offending = new ArrayList<>();
    for (String rel : relativeFiles(output)) {
      Path file = output.resolve(rel);
      if (rel.endsWith(".class")) {
        continue;
      }
      try {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.contains(output.toString()) || text.contains("/Users/") || text.contains("/tmp/")
          || text.contains("\\Users\\") || text.contains("\\Temp\\")) {
          offending.add(rel);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    if (!offending.isEmpty()) {
      throw new AssertionError("Generated text resources leak absolute build paths: " + offending);
    }
  }

  /**
   * Content hash over every emitted file's bytes keyed by relative path.
   * Two independent builds must produce the same map: no path, timestamp or
   * ordering drift.
   */
  static Map<String, String> contentFingerprint(Path output) {
    Map<String, String> fingerprint = new TreeMap<>();
    for (String rel : relativeFiles(output)) {
      try {
        byte[] bytes = Files.readAllBytes(output.resolve(rel));
        fingerprint.put(rel, sha256(bytes));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return fingerprint;
  }

  static void assertFingerprintsEqual(Map<String, String> first, Map<String, String> second) {
    if (!first.equals(second)) {
      Map<String, String[]> diff = new TreeMap<>();
      for (Map.Entry<String, String> e : first.entrySet()) {
        diff.put(e.getKey(), new String[]{e.getValue(), second.get(e.getKey())});
      }
      for (Map.Entry<String, String> e : second.entrySet()) {
        diff.putIfAbsent(e.getKey(), new String[]{null, e.getValue()});
      }
      StringBuilder sb = new StringBuilder("Generated content is not reproducible across independent workspaces:");
      diff.forEach((k, v) -> sb.append(System.lineSeparator()).append(k)
        .append(" first=").append(v[0]).append(" second=").append(v[1]));
      throw new AssertionError(sb.toString());
    }
  }

  static void assertNonEmpty(Path output) {
    if (relativeFiles(output).isEmpty()) {
      throw new AssertionError("Zero generated outputs collected from " + output
        + " - the annotation processor did not run or emitted nothing");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(bytes);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String bytesDescription(Path file) {
    try {
      byte[] bytes = Files.readAllBytes(file);
      return bytes.length + " bytes: " + new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
