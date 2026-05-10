package dev.pux4j.ui.core;

public interface DisplayDriverFactory {
    String name();
    EInkDisplayDriver create(DriverConfig config);
}
