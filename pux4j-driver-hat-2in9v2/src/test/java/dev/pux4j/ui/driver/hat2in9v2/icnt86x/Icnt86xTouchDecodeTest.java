// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.driver.hat2in9v2.icnt86x;

import dev.pux4j.ui.core.TouchPoint;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays record sequences captured from a real 2.9" panel (single finger). Event ids as inferred
 * from the captures: 1 = new contact, 2 = moving, 3 = stationary, 4 = lift (pressure byte 0).
 */
class Icnt86xTouchDecodeTest {

    private static final long MS = 1_000_000L;

    private static List<TouchPoint> decode(String hex) {
        byte[] bytes = HexFormat.ofDelimiter(" ").parseHex(hex);
        return Icnt86xTouchDriver.decode(bytes, bytes.length / 7);
    }

    @Test
    void newContactMovingAndStationaryAreAllDown() {
        assertEquals(List.of(new TouchPoint(0, 28, 60, true)), decode("00 1c 00 3c 00 0e 01"));
        assertEquals(List.of(new TouchPoint(0, 27, 58, true)), decode("00 1b 00 3a 00 11 02"));
        assertEquals(List.of(new TouchPoint(0, 28, 60, true)), decode("00 1c 00 3c 00 11 03"));
    }

    @Test
    void liftIsNotAContact() {
        assertTrue(decode("00 19 00 41 00 00 04").isEmpty());
    }

    @Test
    void coordinatesUseBothBytes() {
        // x = 0x0113 = 275, y = 0x0043 = 67
        assertEquals(List.of(new TouchPoint(0, 275, 67, true)), decode("00 13 01 43 00 11 02"));
    }

    @Test
    void secondRecordKeepsItsIndexWhenTheFirstIsLifted() {
        List<TouchPoint> points = decode("00 19 00 41 00 00 04 00 10 00 20 00 11 02");
        assertEquals(List.of(new TouchPoint(1, 16, 32, true)), points);
    }

    /** A capture that began 1, 3, 3 and never produced a "down" under the old decoding. */
    @Test
    void aStationaryTapIsHeldThroughTheGapsAndEndsOnTheLift() {
        TouchContactTracker t = new TouchContactTracker(TouchContactTracker.DEFAULT_HOLD_TIMEOUT_MS);
        String press = "00 1c 00 3c 00 0e 01";
        String still = "00 1c 00 3c 00 11 03";
        String lift  = "00 1c 00 3c 00 00 04";
        assertEquals(1, t.onReport(decode(press), 0).size());
        assertEquals(1, t.onNoReport(100 * MS).size());
        assertEquals(1, t.onReport(decode(still), 148 * MS).size());
        assertEquals(1, t.onNoReport(200 * MS).size());
        assertEquals(1, t.onReport(decode(still), 283 * MS).size());
        assertTrue(t.onReport(decode(lift), 300 * MS).isEmpty());
        assertTrue(t.onNoReport(310 * MS).isEmpty());
    }

    /** About half of the captured touches ended without a lift report. */
    @Test
    void aTouchWithoutALiftReportEndsAfterTheTimeout() {
        TouchContactTracker t = new TouchContactTracker(TouchContactTracker.DEFAULT_HOLD_TIMEOUT_MS);
        t.onReport(decode("00 1b 00 3a 00 11 02"), 0);
        t.onReport(decode("00 1b 00 3a 00 11 03"), 186 * MS);
        assertEquals(1, t.onNoReport(600 * MS).size());
        assertTrue(t.onNoReport(687 * MS).isEmpty());
    }
}
