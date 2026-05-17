// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.ssd1675a;

import java.util.Map;

/**
 * Maps SSD1675A command bytes to their symbolic names for trace output.
 * Used by the ByteMan hardware-trace rule file (hardware-trace.btm) to annotate
 * SPI command bytes in the trace log — avoiding a giant conditional expression
 * inside the BTM file. Kept package-private; not part of the public API.
 */
final class CmdNames {

    private static final Map<Byte, String> NAMES = Map.ofEntries(
        Map.entry((byte) 0x01, "DRIVER_OUTPUT"),
        Map.entry((byte) 0x03, "GATE_VOLTAGE"),
        Map.entry((byte) 0x04, "SOURCE_VOLTAGE"),
        Map.entry((byte) 0x10, "DEEP_SLEEP"),
        Map.entry((byte) 0x11, "DATA_ENTRY_MODE"),
        Map.entry((byte) 0x12, "SW_RESET"),
        Map.entry((byte) 0x20, "ACTIVATE"),
        Map.entry((byte) 0x21, "DISP_UPDATE_1"),
        Map.entry((byte) 0x22, "DISP_UPDATE_2"),
        Map.entry((byte) 0x24, "WRITE_BW_RAM"),
        Map.entry((byte) 0x26, "WRITE_RED_RAM"),
        Map.entry((byte) 0x2C, "VCOM"),
        Map.entry((byte) 0x32, "WRITE_LUT"),
        Map.entry((byte) 0x37, "WRITE_DISP_OPT"),
        Map.entry((byte) 0x3C, "BORDER_WAVEFORM"),
        Map.entry((byte) 0x3F, "EOPT"),
        Map.entry((byte) 0x44, "SET_X_WINDOW"),
        Map.entry((byte) 0x45, "SET_Y_WINDOW"),
        Map.entry((byte) 0x4E, "SET_X_CURSOR"),
        Map.entry((byte) 0x4F, "SET_Y_CURSOR"),
        Map.entry((byte) 0x74, "ANALOG_BLOCK"),
        Map.entry((byte) 0x7E, "DIGITAL_BLOCK")
    );

    private CmdNames() {}

    /** Returns the symbolic name for {@code cmd}, or "0xNN" if unknown. */
    static String name(byte cmd) {
        return NAMES.getOrDefault(cmd, String.format("0x%02X", cmd & 0xFF));
    }
}
