package io.avaje.config.it.support;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * Locates the jars the fixtures compile against directly from the test classpath.
 * <p>
 * Nothing here reaches the network or assumes a specific machine layout: every
 * jar is resolved from paths already used to run this test.
 */
final class ClasspathJars {

  private static final Map<String, String> ARTIFACTS = new LinkedHashMap<>();

  static {
    // logical name -> jar file name fragment, matched against real artifacts on disk
    ARTIFACTS.put("avaje-config", "avaje-config-5.2.jar");
    ARTIFACTS.put("avaje-applog", "avaje-applog-1.2.jar");
    ARTIFACTS.put("jspecify", "jspecify-1.0.1.jar");
    ARTIFACTS.put("avaje-spi-service", "avaje-spi-service-2.17.jar");
    ARTIFACTS.put("avaje-spi-core", "avaje-spi-core-2.17.jar");
  }

  private final Map<String, Path> jars = new LinkedHashMap<>();

  private ClasspathJars() {
  }

  static ClasspathJars resolve() {
    ClasspathJars resolved = new ClasspathJars();
    List<Path> entries = new ArrayList<>();
    entries.addAll(classpathFromSystemProperty());
    entries.addAll(classpathFromClassLoader());

    for (Map.Entry<String, String> artifact : ARTIFACTS.entrySet()) {
      Path jar = find(entries, artifact.getValue());
      if (jar == null) {
        throw new IllegalStateException("Required test dependency jar not found on classpath: "
          + artifact.getValue() + " (scanned " + entries.size() + " entries)");
      }
      resolved.jars.put(artifact.getKey(), jar);
    }
    return resolved;
  }

  private static List<Path> classpathFromSystemProperty() {
    List<Path> paths = new ArrayList<>();
    String cp = System.getProperty("java.class.path");
    if (cp != null && !cp.isEmpty()) {
      for (String entry : cp.split(File.pathSeparator)) {
        paths.add(Paths.get(entry));
      }
    }
    return paths;
  }

  private static List<Path> classpathFromClassLoader() {
    List<Path> paths = new ArrayList<>();
    ClassLoader cl = ClasspathJars.class.getClassLoader();
    if (cl instanceof URLClassLoader) {
      for (URL url : ((URLClassLoader) cl).getURLs()) {
        if ("file".equals(url.getProtocol())) {
          paths.add(Paths.get(url.getFile().replace("%20", " ")));
        }
      }
    }
    return paths;
  }

  private static Path find(List<Path> entries, String jarName) {
    for (Path entry : entries) {
      if (entry.getFileName() != null && entry.getFileName().toString().equals(jarName)
        && Files.isRegularFile(entry)) {
        return entry.toAbsolutePath().normalize();
      }
    }
    return null;
  }

  Path jar(String logicalName) {
    Path jar = jars.get(logicalName);
    if (jar == null) {
      throw new IllegalStateException("Unknown fixture jar: " + logicalName);
    }
    return jar;
  }

  /** Module path for compiling and running named-module consumers. */
  String compileModulePath() {
    return String.join(File.pathSeparator,
      jar("avaje-config").toString(),
      jar("avaje-applog").toString(),
      jar("jspecify").toString(),
      jar("avaje-spi-service").toString());
  }

  /** Processor path: api jar plus the processor implementation jar. */
  String processorPath() {
    return String.join(File.pathSeparator,
      jar("avaje-spi-service").toString(),
      jar("avaje-spi-core").toString());
  }

  /** Plain classpath used for unnamed-module (classpath) fixtures. */
  String compileClasspath() {
    return String.join(File.pathSeparator,
      jar("avaje-config").toString(),
      jar("avaje-applog").toString(),
      jar("jspecify").toString(),
      jar("avaje-spi-service").toString());
  }

  String autoModuleName(Path jar) {
    try (JarFile jf = new JarFile(jar.toFile())) {
      Attributes attrs = jf.getManifest() == null ? null : jf.getManifest().getMainAttributes();
      if (attrs != null) {
        String name = attrs.getValue("Automatic-Module-Name");
        if (name != null && !name.isBlank()) {
          return name;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String n = jar.getFileName().toString();
    int dash = n.indexOf('-');
    return n.substring(0, dash).replace('.', '-');
  }
}
