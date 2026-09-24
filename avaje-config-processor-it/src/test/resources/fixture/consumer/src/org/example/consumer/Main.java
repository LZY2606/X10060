package org.example.consumer;

import io.avaje.config.ConfigExtension;

import java.util.ServiceLoader;

public final class Main {

  public static void main(String[] args) {
    var providers = ServiceLoader.load(ConfigExtension.class);
    long count = providers.stream().count();
    if (count != 2) {
      throw new IllegalStateException("expected 2 ConfigExtension providers but found " + count);
    }
    System.out.println("providers=" + count);
  }
}
