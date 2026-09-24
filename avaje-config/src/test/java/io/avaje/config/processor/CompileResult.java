package io.avaje.config.processor;

import java.util.List;

/** Outcome of one javac invocation, keeping full diagnostic context for failures. */
final class CompileResult {

  final boolean success;
  final String diagnostics;
  final List<String> options;
  final List<String> files;

  CompileResult(boolean success, String diagnostics, List<String> options, List<String> files) {
    this.success = success;
    this.diagnostics = diagnostics;
    this.options = options;
    this.files = files;
  }

  /** Fail the test with full compiler context when the compilation unexpectedly failed. */
  CompileResult assertSuccess() {
    if (!success) {
      throw new AssertionError("javac failed but was expected to succeed\n" + describe());
    }
    return this;
  }

  /** Fail the test when the compilation unexpectedly succeeded. */
  CompileResult assertFailure() {
    if (success) {
      throw new AssertionError("javac succeeded but was expected to fail\n" + describe());
    }
    return this;
  }

  String describe() {
    return "options=" + options + "\nfiles=" + files + "\ndiagnostics:\n" + diagnostics;
  }
}
