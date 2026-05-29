// SPDX-License-Identifier: Apache-2.0
module dev.pux4j.ui.validation {
    requires dev.pux4j.ui.core;
    requires dev.pux4j.ui.transform;
    requires com.pi4j;
    requires jakarta.json;
    requires org.slf4j;

    uses dev.pux4j.ui.core.DisplayDriverFactory;
    uses dev.pux4j.ui.core.TouchDriverFactory;
}
