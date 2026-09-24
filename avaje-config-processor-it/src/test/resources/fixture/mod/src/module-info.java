module example.fixture {

  requires io.avaje.config;
  requires static io.avaje.spi;

  provides io.avaje.config.ConfigExtension with org.example.ALoader, org.example.BLoader;
}
