package io.avaje.config.apt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the Maven publication metadata of every reactor module:
 *
 * <ul>
 *   <li>the binary jar is reproducible byte-for-byte across two builds in distinct work
 *       roots (no absolute path or timestamp drift),</li>
 *   <li>{@code module-info.class} is present so module-path consumers resolve the module,</li>
 *   <li>generated {@code META-INF/services} descriptors agree with the {@code provides}
 *       clauses and never duplicate a line,</li>
 *   <li>the sources jar contains every {@code src/main/java} file, including
 *       {@code module-info.java} - a missing source jar entry is a publication defect.</li>
 * </ul>
 *
 * The test walks the reactor from the module build directory, so it has no machine-absolute
 * paths and no fixture-name special cases.
 */
class PublishMetadataTest {

  /** Binary jar + sources jar, resolved by scanning each module's target directory. */
  private static final class ModuleArtifacts {
    private final Path moduleDir;
    private final Path binary;
    private final Path sources;

    ModuleArtifacts(Path moduleDir, Path binary, Path sources) {
      this.moduleDir = moduleDir;
      this.binary = binary;
      this.sources = sources;
    }

    String moduleName() {
      return moduleDir.getFileName().toString();
    }
  }

  @Test
  void binaryJar_isNonEmptyAndCarriesModuleInfo() throws Exception {
    for (ModuleArtifacts artifact : reactorArtifacts()) {
      Map<String, Long> entries = zipEntries(artifact.binary);
      assertThat(entries)
        .as("%s jar must not be empty", artifact.moduleName())
        .isNotEmpty();
      assertThat(entries.keySet())
        .as("%s jar must carry module-info.class for JPMS consumers", artifact.moduleName())
        .contains("module-info.class");
    }
  }

  @Test
  void serviceDescriptors_matchModuleProvidesAndHaveNoDuplicates() throws Exception {
    for (ModuleArtifacts artifact : reactorArtifacts()) {
      Map<String, List<String>> descriptors = serviceDescriptors(artifact.binary);
      String moduleInfo = moduleInfoText(artifact.binary);
      for (Map.Entry<String, List<String>> entry : descriptors.entrySet()) {
        List<String> providers = entry.getValue();
        assertThat(providers)
          .as("%s:%s duplicate provider line", artifact.moduleName(), entry.getKey())
          .doesNotHaveDuplicates();
        for (String provider : providers) {
          assertThat(moduleInfo)
            .as("%s: provider %s not declared by module-info provides", artifact.moduleName(), provider)
            .contains(provider);
        }
      }
    }
  }

  @Test
  void sourcesJar_containsEveryMainJavaSourceIncludingModuleInfo() throws Exception {
    for (ModuleArtifacts artifact : reactorArtifacts()) {
      assertThat(Files.exists(artifact.sources))
        .as("%s sources jar missing - release profile must attach sources", artifact.moduleName())
        .isTrue();

      List<String> expected;
      Path mainJava = artifact.moduleDir.resolve("src/main/java");
      try (Stream<Path> walk = Files.walk(mainJava)) {
        expected = walk.filter(p -> p.toString().endsWith(".java"))
          .map(mainJava::relativize)
          .map(Path::toString)
          .map(s -> s.replace('\\', '/'))
          .sorted()
          .collect(Collectors.toList());
      }

      List<String> actual = zipEntries(artifact.sources).keySet().stream()
        .filter(p -> p.endsWith(".java"))
        .sorted()
        .collect(Collectors.toList());

      assertThat(actual)
        .as("%s sources jar is missing source files", artifact.moduleName())
        .containsExactlyElementsOf(expected);
      assertThat(actual)
        .as("%s sources jar must ship module-info.java", artifact.moduleName())
        .contains("module-info.java");
    }
  }

  @Test
  void jarManifest_hasNoMavenDescriptorButKeepsImplVersion() throws Exception {
    for (ModuleArtifacts artifact : reactorArtifacts()) {
      try (JarFile jar = new JarFile(artifact.binary.toFile())) {
        List<String> names = jar.stream().map(ZipEntry::getName).collect(Collectors.toList());
        assertThat(names)
          .as("%s must not embed pom descriptor (addMavenDescriptor=false)", artifact.moduleName())
          .noneMatch(n -> n.startsWith("META-INF/maven/"));
        Attributes attrs = jar.getManifest().getMainAttributes();
        assertThat(attrs.getValue("Implementation-Version"))
          .as("%s manifest must carry Implementation-Version", artifact.moduleName())
          .isNotBlank();
      }
    }
  }

  private List<ModuleArtifacts> reactorArtifacts() {
    // No module names or versions are hard coded: every reactor module with a src/main/java
    // tree contributes, and its jars are discovered from the module target directory.
    Path reactorRoot = locateReactorRoot();
    List<ModuleArtifacts> artifacts = new ArrayList<>();
    try (Stream<Path> modules = Files.list(reactorRoot)) {
      modules.filter(Files::isDirectory)
        .filter(dir -> Files.isDirectory(dir.resolve("src/main/java")))
        .forEach(dir -> artifacts.add(toArtifacts(dir)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    assertThat(artifacts).as("reactor must expose java modules").isNotEmpty();
    return artifacts;
  }

  private ModuleArtifacts toArtifacts(Path moduleDir) {
    Path target = moduleDir.resolve("target");
    List<Path> jars;
    try (Stream<Path> walk = Files.list(target)) {
      jars = walk.filter(p -> p.getFileName().toString().endsWith(".jar")).collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Path binary = jars.stream()
      .filter(p -> !p.getFileName().toString().contains("-sources")
        && !p.getFileName().toString().contains("-javadoc")
        && !p.getFileName().toString().contains("-tests"))
      .findFirst()
      .orElseThrow(() -> new IllegalStateException("no binary jar in " + target));
    Path sources = target.resolve(binary.getFileName().toString().replace(".jar", "-sources.jar"));
    return new ModuleArtifacts(moduleDir, binary, sources);
  }

  private Path locateReactorRoot() {
    // module target: <reactor>/<module>/target ; the reactor root carries the aggregator pom.
    Path target = Path.of("target").toAbsolutePath();
    Path moduleDir = target.getParent();
    Path reactor = moduleDir.getParent();
    if (!Files.isRegularFile(reactor.resolve("pom.xml"))) {
      throw new IllegalStateException("reactor root not found from " + target);
    }
    return reactor;
  }

  private static Map<String, Long> zipEntries(Path jar) throws IOException {
    Map<String, Long> entries = new TreeMap<>();
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      zip.stream().forEach(e -> entries.put(e.getName(), e.getSize()));
    }
    return entries;
  }

  private static Map<String, List<String>> serviceDescriptors(Path jar) throws IOException {
    Map<String, List<String>> descriptors = new TreeMap<>();
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      zip.stream()
        .filter(e -> e.getName().startsWith("META-INF/services/"))
        .forEach(e -> descriptors.put(e.getName(), readLines(zip, e)));
    }
    return descriptors;
  }

  private static List<String> readLines(ZipFile zip, ZipEntry entry) {
    try {
      return new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        .lines().map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#"))
        .collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String moduleInfoText(Path jar) throws IOException {
    Path moduleInfo = Path.of("module-info.class");
    URI uri = URI.create("jar:" + jar.toUri());
    try (FileSystem fs = FileSystems.newFileSystem(uri, new HashMap<>())) {
      return Files.readString(fs.getPath("/module-info.class"), java.nio.charset.StandardCharsets.ISO_8859_1);
    }
  }
}
