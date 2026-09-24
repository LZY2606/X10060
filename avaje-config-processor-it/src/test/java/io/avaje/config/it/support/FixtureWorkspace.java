package io.avaje.config.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An isolated, on-disk copy of one fixture tree living under
 * {@code src/test/resources/fixture}.
 * <p>
 * Each workspace gets its own temporary root so parallel or repeated builds can
 * never observe each other's generated output.
 */
final class FixtureWorkspace implements AutoCloseable {

  private final String fixtureName;
  private final Path root;
  private final Path sources;
  private final Path output;

  private FixtureWorkspace(String fixtureName, Path root) {
    this.fixtureName = fixtureName;
    this.root = root;
    this.sources = root.resolve("src");
    this.output = root.resolve("out");
  }

  static FixtureWorkspace copyOf(String fixtureName) {
    try {
      Path root = Files.createTempDirectory("avaje-config-it-" + fixtureName + "-");
      FixtureWorkspace ws = new FixtureWorkspace(fixtureName, root);
      ws.copyResourceTree(fixtureName);
      Files.createDirectories(ws.output);
      return ws;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create workspace for fixture " + fixtureName, e);
    }
  }

  private void copyResourceTree(String name) throws IOException {
    Path base = resourceRoot();
    Path fixtureRoot = base.resolve(name);
    if (!Files.isDirectory(fixtureRoot)) {
      throw new IllegalStateException("Fixture not found on test classpath: fixture/" + name
        + " (resolved at " + fixtureRoot + ")");
    }
    try (Stream<Path> walk = Files.walk(fixtureRoot)) {
      walk.filter(Files::isRegularFile).forEach(source -> {
        Path relative = fixtureRoot.relativize(source);
        Path target = root.resolve(relative);
        try {
          Files.createDirectories(target.getParent());
          Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
          throw new UncheckedIOException("Failed to copy fixture file " + relative, e);
        }
      });
    }
  }

  private static Path resourceRoot() {
    Path local = Paths.get("src/test/resources/fixture");
    if (Files.isDirectory(local)) {
      return local.toAbsolutePath().normalize();
    }
    try {
      Path marker = urlToPath(FixtureWorkspace.class.getResource("/fixture/mod/src/module-info.java"));
      Path modDir = marker.getParent().getParent();
      Path fixtureDir = modDir.getParent();
      if (fixtureDir == null || !fixtureDir.getFileName().toString().equals("fixture")) {
        throw new IllegalStateException("Unexpected fixture resource layout at " + marker);
      }
      return fixtureDir;
    } catch (Exception e) {
      throw new IllegalStateException("Unable to locate fixture resources", e);
    }
  }

  private static Path urlToPath(java.net.URL url) throws Exception {
    if (url == null) {
      throw new IllegalStateException("fixture resource missing");
    }
    return Paths.get(url.toURI());
  }

  Path root() {
    return root;
  }

  Path sources() {
    return sources;
  }

  Path output() {
    return output;
  }

  String fixtureName() {
    return fixtureName;
  }

  Path sourceFile(String relativePath) {
    return sources.resolve(relativePath);
  }

  String read(String relativePath) {
    try {
      return Files.readString(sources.resolve(relativePath), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void write(String relativePath, String content) {
    Path target = sources.resolve(relativePath);
    try {
      Files.createDirectories(target.getParent());
      Files.writeString(target, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void delete(String relativePath) {
    try {
      Files.delete(sources.resolve(relativePath));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  List<Path> allSourceFiles() {
    return sourceTree(true);
  }

  List<Path> sourceTree(boolean includeModuleInfo) {
    try (Stream<Path> walk = Files.walk(sources)) {
      return walk
        .filter(Files::isRegularFile)
        .filter(p -> p.toString().endsWith(".java"))
        .filter(p -> includeModuleInfo || !p.getFileName().toString().equals("module-info.java"))
        .sorted()
        .collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  List<Path> generatedFiles() {
    try (Stream<Path> walk = Files.walk(output)) {
      return walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  byte[] readOutput(String relativePath) {
    try {
      return Files.readAllBytes(output.resolve(relativePath));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  boolean outputExists(String relativePath) {
    return Files.exists(output.resolve(relativePath));
  }

  void writeOutput(String relativePath, String content) {
    Path target = output.resolve(relativePath);
    try {
      Files.createDirectories(target.getParent());
      Files.writeString(target, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  String readResource(String resource) {
    try (InputStream in = getClass().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing test resource: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    try (Stream<Path> walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
