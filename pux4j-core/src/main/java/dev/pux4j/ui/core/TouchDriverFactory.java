package dev.pux4j.ui.core;

public interface TouchDriverFactory {
    String name();
    TouchDriver create(DriverConfig config);
}
