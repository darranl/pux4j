// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.ssd1675a;

import dev.pux4j.ui.core.FourGrayFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The panel's RAM polarity for 4-gray, as in WaveShare's reference (BW RAM / RED RAM):
 * white 0/0, light grey 1/0, dark grey 0/1, black 1/1. A {@link FourGrayFrame} uses white = 1/1,
 * light = 1/0, dark = 0/1, black = 0/0.
 */
class Ssd1675aFourGrayPolarityTest {

    /** Pixels 0..3 of the byte are white, light, dark, black; pixels 4..7 are white. */
    private static final FourGrayFrame FRAME =
        new FourGrayFrame(new byte[] { (byte) 0b1100_1111 }, new byte[] { (byte) 0b1010_1111 });

    private static int[] panelBits(int pixel) {
        int bw  = (Ssd1675aDisplayDriver.panelBwRam(FRAME)[0]  >> (7 - pixel)) & 1;
        int red = (Ssd1675aDisplayDriver.panelRedRam(FRAME)[0] >> (7 - pixel)) & 1;
        return new int[] { bw, red };
    }

    @Test
    void whiteIsZeroZero() {
        assertEquals("0/0", panelBits(0)[0] + "/" + panelBits(0)[1]);
        assertEquals("0/0", panelBits(7)[0] + "/" + panelBits(7)[1]);
    }

    @Test
    void lightGreyIsOneZero() {
        assertEquals("1/0", panelBits(1)[0] + "/" + panelBits(1)[1]);
    }

    @Test
    void darkGreyIsZeroOne() {
        assertEquals("0/1", panelBits(2)[0] + "/" + panelBits(2)[1]);
    }

    @Test
    void blackIsOneOne() {
        assertEquals("1/1", panelBits(3)[0] + "/" + panelBits(3)[1]);
    }

    @Test
    void thePlanesAreNewArrays() {
        byte[] bw = { 0x12 };
        FourGrayFrame frame = new FourGrayFrame(bw, new byte[] { 0x34 });
        Ssd1675aDisplayDriver.panelBwRam(frame)[0] = 0;
        assertEquals(0x12, bw[0], "the caller's plane must not be modified");
    }
}
