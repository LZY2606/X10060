package io.avaje.config.processor;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the release side of the annotation-processing story: the packaged module jar must
 * remain modular and reproducible (normalised timestamps, no embedded build descriptor),
 * and the attached sources jar must contain every main source. Missing artifacts and
 * empty collections are failures, never vacuous passes.
 */
class ReleaseMetadataTest {

 @Test
 void mainJar_isModularAndReproducible() throws IOException {
   Path jar = uniqueArtifact("main", "*.jar",
     "*-sources.jar", "*-javadoc.jar", "*-tests.jar");
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry moduleInfo = zip.getEntry("module-info.class");
      assertThat(moduleInfo).as("main jar must keep module-info.class visible on module path")
        .isNotNull();
      assertThat(zip.getEntry("META-INF/MANIFEST.MF")).isNotNull();
      assertThat(zip.getEntry("META-INF/maven/"))
        .as("addMavenDescriptor must stay disabled so absolute GAV metadata is not embedded")
        .isNull();

      List<ZipEntry> entries = new ArrayList<>();
      zip.stream().forEach(entries::add);
      assertThat(entries).isNotEmpty();
      Instant expected = expectedOutputTimestamp();
      List<String> wrongTimestamp = new ArrayList<>();
      for (ZipEntry entry : entries) {
        Instant actual = Instant.ofEpochMilli(entry.getTime());
        if (!actual.equals(expected)) {
          wrongTimestamp.add(entry.getName() + " -> " + actual);
        }
      }
      assertThat(wrongTimestamp)
        .as("all jar entries must use the project.build.outputTimestamp for reproducibility")
        .isEmpty();
    }
  }

  @Test
  void sourcesJar_containsEveryMainSource() throws IOException {
    Path sourcesJar = uniqueArtifact("sources", "*-sources.jar");
    Path projectDir = targetDir().getParent();
    Path mainSourceRoot = projectDir.resolve("src/main/java");

    List<Path> sources;
    try (Stream<Path> walk = Files.walk(mainSourceRoot)) {
      sources = walk
        .filter(Files::isRegularFile)
        .filter(path -> path.toString().endsWith(".java"))
        .collect(java.util.stream.Collectors.toList());
    }
    if (sources.isEmpty()) {
      throw new AssertionError("Zero main sources collected under " + mainSourceRoot);
    }

    try (ZipFile zip = new ZipFile(sourcesJar.toFile())) {
      List<String> missing = new ArrayList<>();
      for (Path source : sources) {
        String entryName = mainSourceRoot.relativize(source).toString()
          .replace(File.separatorChar, '/');
        if (zip.getEntry(entryName) == null) {
          missing.add(entryName);
        }
      }
      assertThat(missing)
        .as("sources jar must not silently omit sources (avoids stale/incomplete releases)")
        .isEmpty();
      assertThat(zip.getEntry("module-info.java")).isNotNull();
    }
  }

  @Test
  void missingArtifact_isReportedInsteadOfSilentlyPassing() {
    Path target = targetDir();
    assertThatThrownBy(() -> collectArtifacts(target, "no-such-pattern-*.jar"))
      .isInstanceOf(AssertionError.class)
      .hasMessageContaining("no-such-pattern-*.jar");
  }

  private static Path uniqueArtifact(String kind, String glob, String... excludes)
    throws IOException {
    List<Path> artifacts = collectArtifacts(targetDir(), glob);
    for (String exclude : excludes) {
      artifacts.removeIf(path -> matchesGlob(path.getFileName().toString(), exclude));
    }
    if (artifacts.size() != 1) {
      throw new AssertionError("Expected exactly one " + kind + " artifact matching " + glob
        + " in " + targetDir() + " but found " + artifacts);
    }
    return artifacts.get(0);
  }

  private static List<Path> collectArtifacts(Path dir, String glob) throws IOException {
    List<Path> matches;
    try (Stream<Path> stream = Files.list(dir)) {
      matches = stream.filter(Files::isRegularFile)
        .filter(path -> matchesGlob(path.getFileName().toString(), glob))
        .sorted()
        .collect(java.util.stream.Collectors.toList());
    }
    if (matches.isEmpty()) {
      throw new AssertionError("No artifact matching " + glob + " found in " + dir
        + " (run 'mvn package' before 'mvn test' so release artifacts exist)");
    }
    return new ArrayList<>(matches);
  }

  private static boolean matchesGlob(String fileName, String glob) {
    String regex = Pattern.quote(glob).replace("*", "\\E[^/]*\\Q");
    Matcher matcher = Pattern.compile(regex).matcher(fileName);
    return matcher.matches();
  }

  private static Path targetDir() {
    try {
      Path codeLocation = Path.of(
        io.avaje.config.Config.class.getProtectionDomain()
          .getCodeSource().getLocation().toURI());
      Path target = codeLocation.getParent();
      Path projectDir = target == null ? null : target.getParent();
      if (target == null || projectDir == null
        || !"target".equals(target.getFileName().toString())
        || !codeLocation.getFileName().toString().equals("classes")) {
        throw new AssertionError("Unexpected compiled classes location: " + codeLocation);
      }
      return target;
    } catch (Exception e) {
      throw new AssertionError("Cannot locate avaje-config target directory", e);
    }
  }

  private static Instant expectedOutputTimestamp() {
    Path pom = targetDir().getParent().resolve("pom.xml");
    try {
      String content = Files.readString(pom);
      Matcher matcher = Pattern
        .compile("<project\\.build\\.outputTimestamp>\\s*([^<]+?)\\s*</project\\.build\\.outputTimestamp>")
        .matcher(content);
      if (!matcher.find()) {
        throw new AssertionError("project.build.outputTimestamp not declared in " + pom);
      }
      String value = matcher.group(1);
      if (value.endsWith("Z")) {
        return java.time.LocalDateTime.parse(value.substring(0, value.length() - 1),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
          .atZone(java.time.ZoneId.systemDefault()).toInstant();
      }
      return Instant.from(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
        .withZone(java.time.ZoneId.systemDefault())
        .parse(value));
    } catch (IOException e) {
      throw new AssertionError("Cannot read " + pom, e);
    }
  }
}
