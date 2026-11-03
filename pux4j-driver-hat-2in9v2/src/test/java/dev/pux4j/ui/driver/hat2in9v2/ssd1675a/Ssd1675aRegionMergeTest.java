// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.ssd1675a;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Ssd1675aRegionMergeTest {

    private static byte[] whiteFrame() {
        byte[] f = new byte[Ssd1675aDisplayDriver.FRAME_BYTES];
        Arrays.fill(f, (byte) 0xFF);
        return f;
    }

    @Test
    void regionLandsAtItsByteColumnAndRow() {
        byte[] frame = whiteFrame();
        // 16 wide (2 bytes) x 2 high, at x=8 (byte column 1), y=3
        byte[] region = { 0x01, 0x02, 0x03, 0x04 };
        Ssd1675aDisplayDriver.mergeRegion(frame, 8, 3, 16, 2, region);
        int rowBytes = Ssd1675aDisplayDriver.BYTES_PER_ROW;
        assertEquals(0x01, frame[3 * rowBytes + 1]);
        assertEquals(0x02, frame[3 * rowBytes + 2]);
        assertEquals(0x03, frame[4 * rowBytes + 1]);
        assertEquals(0x04, frame[4 * rowBytes + 2]);
    }

    @Test
    void everythingOutsideTheRegionIsUntouched() {
        byte[] frame = whiteFrame();
        Ssd1675aDisplayDriver.mergeRegion(frame, 8, 3, 16, 2, new byte[] { 0, 0, 0, 0 });
        int changed = 0;
        for (byte b : frame) {
            if (b != (byte) 0xFF) {
                changed++;
            }
        }
        assertEquals(4, changed);
    }

    @Test
    void aRegionPartlyInTheLastByteColumnAndRowFits() {
        byte[] frame = whiteFrame();
        int rowBytes = Ssd1675aDisplayDriver.BYTES_PER_ROW;
        // 8 wide at the last byte column (x=120), last row (y=295)
        Ssd1675aDisplayDriver.mergeRegion(frame, 120, Ssd1675aDisplayDriver.HEIGHT - 1, 8, 1, new byte[] { 0x5A });
        assertEquals(0x5A, frame[frame.length - 1]);
        assertEquals((byte) 0xFF, frame[frame.length - 2]);
        assertEquals(rowBytes, 16);
    }

    @Test
    void mergingTwiceKeepsBothRegions() {
        byte[] frame = whiteFrame();
        Ssd1675aDisplayDriver.mergeRegion(frame, 0, 0, 8, 1, new byte[] { 0x11 });
        Ssd1675aDisplayDriver.mergeRegion(frame, 8, 0, 8, 1, new byte[] { 0x22 });
        assertEquals(0x11, frame[0]);
        assertEquals(0x22, frame[1]);
    }
}
