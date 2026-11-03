package dev.pux4j.ui.driver.hat2in9v2.ssd1675a;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the 4-gray waveform LUT (159 bytes: 153 waveform bytes and the six voltage bytes) in
 * one of several group orders.
 *
 * <p>The vendor waveform has three groups. Group 0 and group 2 are mirror images (opposite
 * polarity, equal length, equal frame rate), so each refresh nets zero DC drive per pixel class.
 * Group 1 is a flash that drives every pixel up and down regardless of class. The vendor order
 * (0, 1, 2) therefore applies group 0 to the new image while the previous image is still on the
 * panel, which is visible as the old and new images overlaid before the flash clears them.
 *
 * <p>The variants only permute whole groups. Each group carries its five VS rows (the four
 * pixel classes and VCOM), its 7 timing bytes and its frame-rate nibble together, so the drive
 * per pixel class, and its DC balance, is unchanged.
 *
 * <p>Layout (SSD1680 datasheet, waveform setting): bytes 0..59 are the VS rows (one row of 12
 * bytes per LUT, one byte per group, two bits per phase A..D); bytes 60..143 are 12 groups of
 * TP[A], TP[B], SR[AB], TP[C], TP[D], SR[CD], RP; bytes 144..149 are the frame-rate nibbles
 * (high nibble is the even group); bytes 150..152 are the gate-on selections.
 */
final class FourGrayLut {

    /** Order in which the vendor groups are played. */
    enum Variant {
        /** The vendor order: mirror, flash, set. */
        VENDOR,
        /** Flash first, then mirror, then set. */
        FLASH_FIRST,
        /** Flash, mirror, flash, set. The flash is split into two runs of one. */
        SPLIT_FLASH
    }

    static final int LENGTH = 159;
    static final int WAVEFORM_LENGTH = 153;
    static final int GROUPS = 12;
    static final int LUTS = 5;
    private static final int TIMING_OFFSET = 60;
    private static final int TIMING_BYTES = 7;
    private static final int FR_OFFSET = 144;
    private static final int XON_OFFSET = 150;

    private FourGrayLut() {
    }

    /** One waveform group: its VS byte per LUT row, timing bytes and frame-rate code. */
    private record Group(byte[] vs, byte[] timing, int fr) {
        Group {
            vs = vs.clone();
            timing = timing.clone();
        }

        Group withRepeat(int rp) {
            byte[] t = timing.clone();
            t[TIMING_BYTES - 1] = (byte) rp;
            return new Group(vs, t, fr);
        }
    }

    static Variant parse(String name) {
        try {
            return Variant.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown 4-gray LUT variant '" + name + "'; expected VENDOR, FLASH_FIRST or SPLIT_FLASH", e);
        }
    }

    /** Returns a new 159-byte LUT for {@code variant}, derived from the vendor LUT. */
    static byte[] build(byte[] vendor, Variant variant) {
        if (vendor.length != LENGTH) {
            throw new IllegalArgumentException("LUT must be " + LENGTH + " bytes, was " + vendor.length);
        }
        for (int i = XON_OFFSET; i < WAVEFORM_LENGTH; i++) {
            if (vendor[i] != 0) {
                throw new IllegalArgumentException("Gate-on bytes must be zero; they are not permuted");
            }
        }
        for (int g = 3; g < GROUPS; g++) {
            if (!isEmpty(group(vendor, g))) {
                throw new IllegalArgumentException("Vendor groups 3..11 must be unused; found data in group " + g);
            }
        }
        if (variant == Variant.VENDOR) {
            return vendor.clone();
        }
        Group mirror = group(vendor, 0);
        Group flash = group(vendor, 1);
        Group set = group(vendor, 2);

        List<Group> order = new ArrayList<>();
        switch (variant) {
            case FLASH_FIRST -> order.addAll(List.of(flash, mirror, set));
            case SPLIT_FLASH -> {
                // The vendor flash runs twice (RP=1); two runs of one keep the same drive.
                Group once = flash.withRepeat(0);
                order.addAll(List.of(once, mirror, once, set));
            }
            default -> throw new IllegalStateException();
        }

        byte[] out = vendor.clone();
        // Clear every group, then lay the chosen groups down in order.
        for (int row = 0; row < LUTS; row++) {
            for (int g = 0; g < GROUPS; g++) {
                out[row * GROUPS + g] = 0;
            }
        }
        for (int i = TIMING_OFFSET; i < FR_OFFSET; i++) {
            out[i] = 0;
        }
        for (int g = 0; g < order.size(); g++) {
            Group grp = order.get(g);
            for (int row = 0; row < LUTS; row++) {
                out[row * GROUPS + g] = grp.vs()[row];
            }
            System.arraycopy(grp.timing(), 0, out, TIMING_OFFSET + g * TIMING_BYTES, TIMING_BYTES);
            int idx = FR_OFFSET + g / 2;
            out[idx] = (byte) ((g % 2 == 0)
                    ? (out[idx] & 0x0F) | (grp.fr() << 4)
                    : (out[idx] & 0xF0) | grp.fr());
        }
        return out;
    }

    private static boolean isEmpty(Group g) {
        for (byte b : g.vs()) {
            if (b != 0) {
                return false;
            }
        }
        for (byte b : g.timing()) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static Group group(byte[] lut, int g) {
        byte[] vs = new byte[LUTS];
        for (int row = 0; row < LUTS; row++) {
            vs[row] = lut[row * GROUPS + g];
        }
        byte[] timing = new byte[TIMING_BYTES];
        System.arraycopy(lut, TIMING_OFFSET + g * TIMING_BYTES, timing, 0, TIMING_BYTES);
        int fr = (g % 2 == 0) ? (lut[FR_OFFSET + g / 2] >> 4) & 0x0F : lut[FR_OFFSET + g / 2] & 0x0F;
        return new Group(vs, timing, fr);
    }
}
