// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

final class ValidationMessages {

    private static final String BUNDLE_NAME = "dev.pux4j.ui.validation.messages";
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle(BUNDLE_NAME, Locale.ROOT);

    private ValidationMessages() {}

    static String text(String key) {
        return BUNDLE.getString(key);
    }

    static String format(String key, Object... args) {
        return MessageFormat.format(text(key), args);
    }
}