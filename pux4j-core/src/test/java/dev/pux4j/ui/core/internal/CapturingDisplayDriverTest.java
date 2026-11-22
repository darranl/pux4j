// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.DriverConfig;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.RefreshMode;
import dev.pux4j.ui.core.ScreenshotCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CapturingDisplayDriverTest {

    private static final int W = 16, H = 24;

    /** Records calls; stands in for a real driver. */
    private static final class Recorder implements EInkDisplayDriver {
        final List<String> calls = new ArrayList<>();
        final EInkDisplayDriver png;
        Recorder(Path pngDir) {
            png = DisplayDriverFactory.select("png").create(null, DriverConfig.builder()
                .property("width", W).property("height", H)
                .property("orientation", "LANDSCAPE").property("outputDir", pngDir.toString()).build());
        }
        public int getWidth() { return W; }
        public int getHeight() { return H; }
        public Orientation getOrientation() { return Orientation.LANDSCAPE; }
        public DisplayCapabilities getCapabilities() { return png.getCapabilities(); }
        public void initialize() { calls.add("initialize"); }
        public void reset() { calls.add("reset"); }
        public void sleep() { calls.add("sleep"); }
        public void wake() { calls.add("wake"); }
        public CompletableFuture<Void> writeFrame(FrameData f) {
            calls.add("writeFrame");
            return png.writeFrame(f);
        }
        public CompletableFuture<Void> writeRegion(int x, int y, int w, int h, FrameData f) {
            calls.add("writeRegion");
            return png.writeRegion(x, y, w, h, f);
        }
    }

    private static MonochromeFrame frame(int fill, int w, int h) {
        byte[] d = new byte[((w + 7) / 8) * h];
        java.util.Arrays.fill(d, (byte) fill);
        return new MonochromeFrame(d, RefreshMode.FULL);
    }

    private static List<Path> pngs(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".png")).sorted().toList();
        }
    }

    @Test
    void forwardsEveryCallAndWritesTheSamePngsAsThePngDriver(@TempDir Path tmp) throws Exception {
        Path oracleDir = tmp.resolve("oracle"), shots = tmp.resolve("shots");
        Recorder rec = new Recorder(oracleDir);
        try (var cap = new CapturingDisplayDriver(rec, shots, "app")) {
            cap.initialize();
            cap.writeFrame(frame(0xFF, W, H)).get();   // white
            cap.writeRegion(8, 8, 8, 8, frame(0x00, 8, 8)).get();  // black square
            cap.sleep();
        }
        assertEquals(List.of("initialize", "writeFrame", "writeRegion", "sleep"), rec.calls);
        List<Path> got = pngs(shots);
        assertEquals(List.of("app-0001.png", "app-0002.png"),
            got.stream().map(p -> p.getFileName().toString()).toList());
        ((AutoCloseable) rec.png).close();
        List<Path> expected = pngs(oracleDir);
        assertEquals(2, expected.size());
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(Files.readAllBytes(expected.get(i)), Files.readAllBytes(got.get(i)));
        }
    }

    @Test
    void identicalFrameIsNotWrittenAgain(@TempDir Path tmp) throws Exception {
        Recorder rec = new Recorder(tmp.resolve("oracle"));
        try (var cap = new CapturingDisplayDriver(rec, tmp.resolve("shots"), "screen")) {
            cap.writeFrame(frame(0xFF, W, H)).get();
            cap.writeFrame(frame(0xFF, W, H)).get();
            cap.writeRegion(0, 0, 8, 8, frame(0xFF, 8, 8)).get(); // same pixels as before
            cap.writeFrame(frame(0x00, W, H)).get();
        }
        assertEquals(2, pngs(tmp.resolve("shots")).size());
        assertEquals(4, rec.calls.size()); // every write still reached the real driver
    }

    @Test
    void wrapIsOffWithoutTheProperty() {
        System.clearProperty(ScreenshotCapture.DIR_PROPERTY);
        EInkDisplayDriver d = new Recorder(Path.of("target/unused"));
        assertSame(d, ScreenshotCapture.wrap(d));
    }

    @Test
    void wrapWrapsWhenThePropertyIsSet(@TempDir Path tmp) throws Exception {
        System.setProperty(ScreenshotCapture.DIR_PROPERTY, tmp.resolve("s").toString());
        try {
            EInkDisplayDriver d = new Recorder(tmp.resolve("o"));
            EInkDisplayDriver w = ScreenshotCapture.wrap(d);
            assertEquals(CapturingDisplayDriver.class, w.getClass());
            ((AutoCloseable) w).close();
        } finally {
            System.clearProperty(ScreenshotCapture.DIR_PROPERTY);
        }
    }
}
