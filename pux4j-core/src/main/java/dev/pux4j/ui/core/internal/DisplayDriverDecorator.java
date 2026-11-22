// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import dev.pux4j.ui.core.EInkDisplayDriver;

/**
 * Implemented by the display driver decorators in this module so that code can look through a chain
 * of them, for example to see whether a driver is already wrapped, or what the real driver is.
 */
public interface DisplayDriverDecorator extends EInkDisplayDriver {

    /** The driver this decorator forwards to. */
    EInkDisplayDriver wrapped();

    /** True if {@code driver}, or any driver it wraps through decorators, is an instance of {@code type}. */
    static boolean chainContains(EInkDisplayDriver driver, Class<?> type) {
        EInkDisplayDriver d = driver;
        while (d != null) {
            if (type.isInstance(d)) {
                return true;
            }
            d = d instanceof DisplayDriverDecorator decorator ? decorator.wrapped() : null;
        }
        return false;
    }
}
