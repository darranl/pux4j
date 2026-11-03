package dev.pux4j.ui.driver.hat2in9v2.ssd1675a;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.pux4j.ui.driver.hat2in9v2.ssd1675a.FourGrayLut.Variant;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class FourGrayLutTest {

    private static final byte[] VENDOR = Ssd1675aDisplayDriver.GRAY4_LUT;
    private static final int VSL = 2;
    private static final int VSH1 = 1;

    /** Frames driven per "lut/voltage code/frame-rate code", counting phase, state and group repeats. */
    private static Map<String, Integer> frames(byte[] lut) {
        Map<String, Integer> out = new TreeMap<>();
        for (int g = 0; g < FourGrayLut.GROUPS; g++) {
            int t = 60 + g * 7;
            int fr = g % 2 == 0 ? (lut[144 + g / 2] >> 4) & 0x0F : lut[144 + g / 2] & 0x0F;
            int[] tp = {lut[t] & 0xFF, lut[t + 1] & 0xFF, lut[t + 3] & 0xFF, lut[t + 4] & 0xFF};
            int srAb = (lut[t + 2] & 0xFF) + 1;
            int srCd = (lut[t + 5] & 0xFF) + 1;
            int rp = (lut[t + 6] & 0xFF) + 1;
            for (int row = 0; row < FourGrayLut.LUTS; row++) {
                int vs = lut[row * FourGrayLut.GROUPS + g] & 0xFF;
                for (int ph = 0; ph < 4; ph++) {
                    int code = (vs >> (6 - 2 * ph)) & 3;
                    int sr = ph < 2 ? srAb : srCd;
                    int n = tp[ph] * sr * rp;
                    if (n > 0) {
                        out.merge(row + "/" + code + "/" + fr, n, Integer::sum);
                    }
                }
            }
        }
        return out;
    }

    @Test
    void vendor_isReturnedUnchanged() {
        assertArrayEquals(VENDOR, FourGrayLut.build(VENDOR, Variant.VENDOR));
    }

    @Test
    void everyVariant_is159Bytes_andDoesNotAliasTheVendorArray() {
        for (Variant v : Variant.values()) {
            byte[] lut = FourGrayLut.build(VENDOR, v);
            assertEquals(FourGrayLut.LENGTH, lut.length);
            assertEquals(false, lut == VENDOR);
        }
    }

    @Test
    void everyVariant_drivesExactlyTheVendorFramesPerClassVoltageAndRate() {
        Map<String, Integer> expected = frames(VENDOR);
        for (Variant v : Variant.values()) {
            assertEquals(expected, frames(FourGrayLut.build(VENDOR, v)), v.name());
        }
    }

    @Test
    void everyVariant_netsZeroDcPerClassAtEachFrameRate() {
        for (Variant v : Variant.values()) {
            Map<String, Integer> f = frames(FourGrayLut.build(VENDOR, v));
            for (int row = 0; row < FourGrayLut.LUTS; row++) {
                for (int fr = 0; fr < 16; fr++) {
                    int up = f.getOrDefault(row + "/" + VSH1 + "/" + fr, 0);
                    int down = f.getOrDefault(row + "/" + VSL + "/" + fr, 0);
                    assertEquals(up, down, v + " lut " + row + " frame-rate code " + fr);
                }
            }
        }
    }

    @Test
    void flashFirst_putsTheFlashFirst_andMovesItsFrameRateWithIt() {
        byte[] lut = FourGrayLut.build(VENDOR, Variant.FLASH_FIRST);
        // Group 0 is the flash: every class VSH1 then VSL, VCOM VSL then VSH1.
        for (int row = 0; row < 4; row++) {
            assertEquals(0x60, lut[row * 12] & 0xFF);
        }
        assertEquals(0x90, lut[4 * 12] & 0xFF);
        assertArrayEquals(new byte[] {0x1E, 0x1E, 0, 0, 0, 0, 0x01}, Arrays.copyOfRange(lut, 60, 67));
        assertEquals(0x42, lut[144] & 0xFF);
        // Frame-rate bytes and everything after the groups are untouched.
        assertArrayEquals(Arrays.copyOfRange(VENDOR, 145, 159), Arrays.copyOfRange(lut, 145, 159));
    }

    @Test
    void splitFlash_runsFlashMirrorFlashSet() {
        byte[] lut = FourGrayLut.build(VENDOR, Variant.SPLIT_FLASH);
        byte[] flashTiming = {0x1E, 0x1E, 0, 0, 0, 0, 0x00};
        byte[] mirrorTiming = {0x00, 0x02, 0, 0x05, 0x14, 0, 0x00};
        assertArrayEquals(flashTiming, Arrays.copyOfRange(lut, 60, 67));
        assertArrayEquals(mirrorTiming, Arrays.copyOfRange(lut, 67, 74));
        assertArrayEquals(flashTiming, Arrays.copyOfRange(lut, 74, 81));
        assertArrayEquals(mirrorTiming, Arrays.copyOfRange(lut, 81, 88));
        // LUT3: flash, mirror (VSL), flash, set (VSH1).
        assertEquals(0x60, lut[3 * 12] & 0xFF);
        assertEquals(0x2A, lut[3 * 12 + 1] & 0xFF);
        assertEquals(0x60, lut[3 * 12 + 2] & 0xFF);
        assertEquals(0x15, lut[3 * 12 + 3] & 0xFF);
        // VCOM swings with the flash groups only.
        assertEquals(0x90, lut[4 * 12] & 0xFF);
        assertEquals(0x00, lut[4 * 12 + 1] & 0xFF);
        assertEquals(0x90, lut[4 * 12 + 2] & 0xFF);
        assertEquals(0x00, lut[4 * 12 + 3] & 0xFF);
        assertEquals(0x42, lut[144] & 0xFF);
        assertEquals(0x42, lut[145] & 0xFF);
        assertArrayEquals(Arrays.copyOfRange(VENDOR, 146, 159), Arrays.copyOfRange(lut, 146, 159));
    }

    @Test
    void parse_isCaseInsensitive_andRejectsUnknownNames() {
        assertEquals(Variant.SPLIT_FLASH, FourGrayLut.parse(" split_flash "));
        assertThrows(IllegalArgumentException.class, () -> FourGrayLut.parse("nope"));
    }
}
