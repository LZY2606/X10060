package io.avaje.config.it;

import io.avaje.config.it.support.ClasspathJars;
import io.avaje.config.it.support.FixtureCompiler;
import io.avaje.config.it.support.FixtureCompiler.Options;
import io.avaje.config.it.support.FixtureWorkspace;
import io.avaje.config.it.support.GeneratedOutputs;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the processor's registration and incremental metadata that lives in
 * the published jars, because a mismatch there silently changes how build tools
 * invoke (or fail to invoke) the processor.
 */
class ProcessorRegistrationMetadataTest {

  private static final String GRADLE_INCREMENTAL =
    "META-INF/gradle/incremental.annotation.processors";
  private static final String PROCESSOR_SERVICE =
    "META-INF/services/javax.annotation.processing.Processor";
  private static final String PROCESSOR_CLASS =
    "io/avaje/spi/internal/ServiceProcessor.class";

  private final ClasspathJars jars = ClasspathJars.resolve();
  private final FixtureCompiler javac = new FixtureCompiler();

  @Test
  void gradleIncrementalDescriptorDeclaresTheProcessorAsAggregating() throws Exception {
    Path apiJar = jars.jar("avaje-spi-service");
    String descriptor = readJarEntry(apiJar, GRADLE_INCREMENTAL);
    assertThat(descriptor).as("Gradle incremental descriptor in %s", apiJar).isNotNull();

    List<String> nonBlank = nonCommentLines(descriptor);
    boolean matched = nonBlank.stream().anyMatch(line -> {
      String[] parts = line.split(",");
      return parts.length == 2
        && parts[0].trim().equals("io.avaje.spi.internal.ServiceProcessor")
        && parts[1].trim().equals("aggregating");
    });
    if (!matched) {
      throw new AssertionError("Incremental metadata mismatch: expected exactly "
        + "'io.avaje.spi.internal.ServiceProcessor,aggregating' but found: " + nonBlank
        + System.lineSeparator() + "Raw descriptor: " + descriptor);
    }
  }

  @Test
  void processorClassIsContainedInTheRegisteredImplementationJar() throws Exception {
    Path coreJar = jars.jar("avaje-spi-core");
    try (JarFile jar = new JarFile(coreJar.toFile())) {
      JarEntry entry = jar.getJarEntry(PROCESSOR_CLASS);
      if (entry == null) {
        throw new AssertionError("Processor declared by " + GRADLE_INCREMENTAL
          + " is missing from the implementation jar " + coreJar);
      }
    }
  }

  @Test
  void apiJarAloneCannotInstantiateTheProcessor_registrationIsConsistent() throws Exception {
    // The api jar ships @Service/@ServiceProvider and (transitively) advertises the
    // Processor service; the implementation actually lives in avaje-spi-core.
    // Using ONLY the api jar on the processor path must fail loudly rather than
    // silently skip annotation processing.
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("unnamed")) {
      Path apiOnly = jars.jar("avaje-spi-service");
      FixtureCompiler.Result result = javac.compile(ws, ws.sourceTree(false),
        Options.classpathMode().processorPath(apiOnly));
      assertThat(result.success)
        .as("processor path containing only the api jar must not silently succeed; "
          + "diagnostics=%s", result.diagnostics)
        .isFalse();
      assertThat(result.diagnostics).containsIgnoringCase("processor");
    }
  }

  @Test
  void noAnnotatedSources_doesNotEmitServiceDeclaration() {
    try (FixtureWorkspace ws = FixtureWorkspace.copyOf("unnamed")) {
      ws.delete("org/example/ALoader.java");
      ws.delete("org/example/BLoader.java");
      ws.write("org/example/Plain.java",
        "package org.example;" + System.lineSeparator()
          + "public class Plain {" + System.lineSeparator()
          + "  public String hello() { return \"hi\"; }" + System.lineSeparator()
          + "}" + System.lineSeparator());

      javac.compile(ws, ws.sourceTree(false), Options.classpathMode())
        .assertSuccess("compiling sources with no @ServiceProvider");

      GeneratedOutputs.assertNoServiceFile(ws.output());
    }
  }

  private static String readJarEntry(Path jarPath, String entryName) throws Exception {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      JarEntry entry = jar.getJarEntry(entryName);
      if (entry == null) {
        return null;
      }
      try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          sb.append(line).append('\n');
        }
        return sb.toString();
      }
    }
  }

  private static List<String> nonCommentLines(String content) {
    List<String> lines = new ArrayList<>();
    for (String raw : content.split("\\R")) {
      String line = raw.trim();
      if (!line.isEmpty() && !line.startsWith("#")) {
        lines.add(line);
      }
    }
    return lines;
  }

  @SuppressWarnings("unused")
  private static List<String> listEntries(Path jarPath, String prefix) throws Exception {
    List<String> names = new ArrayList<>();
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        String name = entries.nextElement().getName();
        if (name.startsWith(prefix)) {
          names.add(name);
        }
      }
    }
    return names;
  }
}
