// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.fx;

import dev.pux4j.ui.fx.DisabledInverter.Rect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DisabledInverterTest {

    @Test
    void clip_snapsOutwardToWholePixels() {
        assertEquals(new Rect(1, 2, 5, 7), DisabledInverter.clip(1.2, 2.9, 4.1, 6.01, 10, 10));
    }

    @Test
    void clip_clipsToTheFrame() {
        assertEquals(new Rect(0, 0, 10, 10), DisabledInverter.clip(-5, -5, 50, 50, 10, 10));
    }

    @Test
    void clip_returnsNullWhenOutsideOrEmptyOrNaN() {
        assertNull(DisabledInverter.clip(20, 0, 30, 5, 10, 10));
        assertNull(DisabledInverter.clip(3, 3, 3, 8, 10, 10));
        assertNull(DisabledInverter.clip(Double.NaN, 0, 5, 5, 10, 10));
    }

    @Test
    void invert_flipsColourNotAlphaAndOnlyInsideTheRect() {
        int w = 4;
        byte[] px = new byte[w * 3 * 4];
        for (int i = 0; i < px.length; i += 4) {
            px[i] = 0x10; px[i + 1] = 0x20; px[i + 2] = 0x30; px[i + 3] = (byte) 0xFF;
        }
        DisabledInverter.invert(px, w, List.of(new Rect(1, 1, 3, 2)));
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                boolean inside = y == 1 && (x == 1 || x == 2);
                assertEquals(inside ? (byte) 0xEF : 0x10, px[i], "B at " + x + "," + y);
                assertEquals(inside ? (byte) 0xDF : 0x20, px[i + 1], "G at " + x + "," + y);
                assertEquals(inside ? (byte) 0xCF : 0x30, px[i + 2], "R at " + x + "," + y);
                assertEquals((byte) 0xFF, px[i + 3], "A at " + x + "," + y);
            }
        }
    }

    @Test
    void invert_overlappingRectsAreInvertedOnce() {
        int w = 4;
        byte[] px = new byte[w * 2 * 4];
        java.util.Arrays.fill(px, (byte) 0x10);
        DisabledInverter.invert(px, w, List.of(new Rect(0, 0, 3, 2), new Rect(1, 0, 4, 2)));
        for (int i = 0; i < px.length; i += 4) {
            assertEquals((byte) 0xEF, px[i], "B at " + i);
        }
    }
}
