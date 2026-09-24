package io.avaje.config.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Locks the Maven publishing metadata spread across the reactor modules: reproducible-build
 * timestamps, JPMS descriptors, and a source layout that keeps the sources jar complete.
 */
class PublishingMetadataTest {

  private static final Pattern MODULE_PATTERN = Pattern.compile("<module>([^<]+)</module>");

  @Test
  void reactorModulesCarryPublishingAndReproducibleBuildMetadata() throws IOException {
    Path repoRoot = repoRoot();
    Path rootPom = repoRoot.resolve("pom.xml");
    assertThat(rootPom).exists();
    List<String> modules = modulesOf(rootPom);
    assertThat(modules).as("reactor must declare modules").isNotEmpty();

    List<String> problems = new ArrayList<>();
    for (String module : modules) {
      Path moduleDir = repoRoot.resolve(module);
      Path pom = moduleDir.resolve("pom.xml");
      if (!Files.exists(pom)) {
        problems.add(module + ": pom.xml missing");
        continue;
      }
      String pomXml = Files.readString(pom);
      if (!pomXml.contains("project.build.outputTimestamp")) {
        problems.add(module + ": missing project.build.outputTimestamp (reproducible jar metadata)");
      }
      if (!pomXml.contains("<name>")) {
        problems.add(module + ": missing <name> (published metadata)");
      }
      if (!Files.exists(moduleDir.resolve("src/main/java/module-info.java"))) {
        problems.add(module + ": missing src/main/java/module-info.java (JPMS descriptor)");
      }
      problems.addAll(straySources(moduleDir));
    }
    assertThat(problems)
        .as("publishing metadata problems across the reactor")
        .isEmpty();
  }

  /** Every .java file must live under src/main/java or src/test/java, otherwise the
   * sources jar silently drops it. */
  private static List<String> straySources(Path moduleDir) {
    try (var stream = Files.walk(moduleDir)) {
      return stream
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !path.startsWith(moduleDir.resolve("target")))
          .filter(path -> !path.startsWith(moduleDir.resolve("src/main/java")))
          .filter(path -> !path.startsWith(moduleDir.resolve("src/test/java")))
          .map(path -> moduleDir.getFileName() + ": stray source outside standard layout: "
              + moduleDir.relativize(path))
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> modulesOf(Path rootPom) throws IOException {
    String xml = Files.readString(rootPom);
    Matcher matcher = MODULE_PATTERN.matcher(xml);
    List<String> modules = new ArrayList<>();
    while (matcher.find()) {
      modules.add(matcher.group(1).trim());
    }
    return modules;
  }

  private static Path repoRoot() {
    // surefire runs with the module basedir as working directory
    return Path.of("").toAbsolutePath().getParent();
  }
}
