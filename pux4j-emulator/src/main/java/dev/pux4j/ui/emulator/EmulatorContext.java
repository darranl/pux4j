// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.emulator;

/**
 * Package-private singleton that shares state between {@link EmulatedDisplayDriverFactory}
 * and {@link EmulatedTouchDriverFactory}. The display factory creates both the display
 * and touch driver and stores them here; the touch factory retrieves the touch driver.
 */
final class EmulatorContext {

    private static final EmulatorContext INSTANCE = new EmulatorContext();

    private volatile EmulatedEInkDisplay display;
    private volatile EmulatedTouchDriver touch;

    private EmulatorContext() {}

    static EmulatorContext instance() { return INSTANCE; }

    void setDisplay(EmulatedEInkDisplay display) { this.display = display; }
    void setTouch(EmulatedTouchDriver touch)     { this.touch = touch;    }

    EmulatedEInkDisplay display() { return display; }
    EmulatedTouchDriver touch()   { return touch;   }
}
