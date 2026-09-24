package org.example;

import io.avaje.config.Configuration;
import io.avaje.config.ConfigurationSource;
import io.avaje.spi.ServiceProvider;

@ServiceProvider
public class ALoader implements ConfigurationSource {

  @Override
  public void load(Configuration configuration) {
    configuration.setProperty("fixture.a", "loaded");
  }
}
