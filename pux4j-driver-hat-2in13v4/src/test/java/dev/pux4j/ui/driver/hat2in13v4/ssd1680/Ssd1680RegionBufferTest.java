// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in13v4.ssd1680;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Ssd1680RegionBufferTest {

    private static byte[] frame() {
        byte[] f = new byte[Ssd1680DisplayDriver.FRAME_BYTES];
        for (int i = 0; i < f.length; i++) {
            f[i] = (byte) i;
        }
        return f;
    }

    @Test
    void mergePlacesPackedRowsAtByteColumnAndLeavesOtherBytesAlone() {
        byte[] f = frame();
        byte[] before = f.clone();
        // x=16 -> byte column 2; width 16 -> 2 bytes per row; rows 3..4
        Ssd1680DisplayDriver.mergeRegion(f, 16, 3, 16, 2, new byte[] {1, 2, 3, 4});
        assertEquals(1, f[3 * 16 + 2]);
        assertEquals(2, f[3 * 16 + 3]);
        assertEquals(3, f[4 * 16 + 2]);
        assertEquals(4, f[4 * 16 + 3]);
        int changed = 0;
        for (int i = 0; i < f.length; i++) {
            if (f[i] != before[i]) {
                changed++;
            }
        }
        assertEquals(4, changed);
    }

    @Test
    void widthNotMultipleOfEightCoversWholeBytes() {
        byte[] f = new byte[Ssd1680DisplayDriver.FRAME_BYTES];
        // width 10 -> 2 bytes per row, 3 rows
        Ssd1680DisplayDriver.mergeRegion(f, 56, 0, 10, 3, new byte[] {1, 2, 3, 4, 5, 6});
        assertArrayEquals(new byte[] {1, 2}, new byte[] {f[7], f[8]});
        assertArrayEquals(new byte[] {5, 6}, new byte[] {f[2 * 16 + 7], f[2 * 16 + 8]});
    }
}
