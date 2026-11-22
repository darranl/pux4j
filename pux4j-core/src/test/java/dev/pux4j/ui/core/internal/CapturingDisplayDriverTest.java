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
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapturingDisplayDriverTest {

    private static final int W = 16, H = 24;

    /** Records calls; stands in for a real driver. */
    private static class Recorder implements EInkDisplayDriver {
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

    @Test
    void rejectedRegionDoesNotContaminateLaterScreenshots(@TempDir Path tmp) throws Exception {
        Recorder rec = new Recorder(tmp.resolve("oracle"));
        try (var cap = new CapturingDisplayDriver(rec, tmp.resolve("shots"), "s")) {
            cap.writeFrame(frame(0xFF, W, H)).get();
            // Extends below the framebuffer: the real driver rejects it.
            assertThrows(IllegalArgumentException.class,
                () -> cap.writeRegion(8, 20, 8, 8, frame(0x00, 8, 8)));
            cap.writeRegion(0, 0, 8, 8, frame(0x00, 8, 8)).get();
        }
        ((AutoCloseable) rec.png).close();
        List<Path> got = pngs(tmp.resolve("shots")), expected = pngs(tmp.resolve("oracle"));
        assertEquals(2, got.size());
        assertEquals(2, expected.size());
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(Files.readAllBytes(expected.get(i)), Files.readAllBytes(got.get(i)));
        }
    }

    @Test
    void resetKeepsTheCapturedPixels(@TempDir Path tmp) throws Exception {
        Recorder rec = new Recorder(tmp.resolve("oracle"));
        try (var cap = new CapturingDisplayDriver(rec, tmp.resolve("shots"), "s")) {
            cap.writeFrame(frame(0x00, W, H)).get();           // all black
            cap.reset();                                        // real drivers keep showing it
            cap.writeRegion(0, 0, 8, 8, frame(0xFF, 8, 8)).get(); // white square on the black frame
        }
        ((AutoCloseable) rec.png).close();
        List<Path> got = pngs(tmp.resolve("shots"));
        assertEquals(2, got.size());
        // The white square must sit on the black frame, so the two screenshots differ and the
        // second is not just a white screen.
        byte[] second = Files.readAllBytes(got.get(1));
        byte[] first = Files.readAllBytes(got.get(0));
        org.junit.jupiter.api.Assertions.assertFalse(java.util.Arrays.equals(first, second));
    }

    /** A stand-in that rejects everything, as a HAT driver rejects a misaligned region. */
    private static final class Rejecting extends Recorder {
        Rejecting(Path dir) { super(dir); }
        @Override public CompletableFuture<Void> writeRegion(int x, int y, int w, int h, FrameData f) {
            throw new IllegalArgumentException("rejected");
        }
    }

    @Test
    void synchronouslyRejectedWriteIsNotCaptured(@TempDir Path tmp) throws Exception {
        Rejecting rec = new Rejecting(tmp.resolve("oracle"));
        try (var cap = new CapturingDisplayDriver(rec, tmp.resolve("shots"), "s")) {
            cap.writeFrame(frame(0xFF, W, H)).get();
            assertThrows(IllegalArgumentException.class,
                () -> cap.writeRegion(4, 0, 8, 8, frame(0x00, 8, 8)));   // in bounds, driver rejects
            cap.writeFrame(frame(0xFF, W, H)).get();                      // identical to the first
        }
        // Only the first frame: the rejected region left nothing behind, so the third write is a duplicate.
        assertEquals(1, pngs(tmp.resolve("shots")).size());
    }

    @Test
    void resetClearsTheCaptureOnlyForThePngDriver(@TempDir Path tmp) throws Exception {
        // Directly wrapped PNG driver: its reset clears to white, so the capture must too.
        Path oracle = tmp.resolve("oracle");
        EInkDisplayDriver png = DisplayDriverFactory.select("png").create(null, DriverConfig.builder()
            .property("width", W).property("height", H)
            .property("orientation", "LANDSCAPE").property("outputDir", oracle.toString()).build());
        try (var cap = new CapturingDisplayDriver(png, tmp.resolve("shots"), "s")) {
            cap.writeFrame(frame(0x00, W, H)).get();                       // black
            cap.reset();                                                    // PNG driver: white
            cap.writeRegion(0, 0, 8, 8, frame(0xFF, 8, 8)).get();          // white square
        }
        List<Path> got = pngs(tmp.resolve("shots")), expected = pngs(oracle);
        assertEquals(2, got.size());
        assertEquals(2, expected.size());
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(Files.readAllBytes(expected.get(i)), Files.readAllBytes(got.get(i)));
        }
    }

    @Test
    void wrapFallsBackToTheOriginalDriverWhenSetupFails(@TempDir Path tmp) {
        System.setProperty(ScreenshotCapture.DIR_PROPERTY, "bad\0dir");   // NUL: InvalidPathException
        try {
            EInkDisplayDriver d = new Recorder(tmp.resolve("o"));
            assertSame(d, ScreenshotCapture.wrap(d));
        } finally {
            System.clearProperty(ScreenshotCapture.DIR_PROPERTY);
        }
    }

    @Test
    void wrappingAnAlreadyCapturingDriverReturnsItUnchanged(@TempDir Path tmp) throws Exception {
        System.setProperty(ScreenshotCapture.DIR_PROPERTY, tmp.resolve("s").toString());
        try {
            EInkDisplayDriver once = ScreenshotCapture.wrap(new Recorder(tmp.resolve("o")));
            assertSame(once, ScreenshotCapture.wrap(once));
            ((AutoCloseable) once).close();
        } finally {
            System.clearProperty(ScreenshotCapture.DIR_PROPERTY);
        }
    }

    @Test
    void captureOfSeveralDriversInOneDirectoryNeverOverwrites(@TempDir Path tmp) throws Exception {
        Path shots = tmp.resolve("shots");
        try (var a = new CapturingDisplayDriver(new Recorder(tmp.resolve("oa")), shots, "same");
             var b = new CapturingDisplayDriver(new Recorder(tmp.resolve("ob")), shots, "same")) {
            a.writeFrame(frame(0xFF, W, H)).get();
            b.writeFrame(frame(0x00, W, H)).get();
            a.writeFrame(frame(0x00, W, H)).get();
        }
        assertEquals(List.of("same-0001.png", "same-0002.png", "same-0003.png"),
            pngs(shots).stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void closeRemovesTheShutdownHookAndIsRepeatable(@TempDir Path tmp) throws Exception {
        var cap = new CapturingDisplayDriver(new Recorder(tmp.resolve("o")), tmp.resolve("shots"), "h");
        cap.close();
        cap.close();   // a second close must not throw
    }

    @Test
    void resetStillClearsThroughAnotherDecorator(@TempDir Path tmp) throws Exception {
        Path oracle = tmp.resolve("oracle");
        EInkDisplayDriver png = DisplayDriverFactory.select("png").create(null, DriverConfig.builder()
            .property("width", W).property("height", H)
            .property("orientation", "LANDSCAPE").property("outputDir", oracle.toString()).build());
        // The markers decorator sits between the capture and the PNG driver, as in the bridge.
        try (var cap = new CapturingDisplayDriver(
                dev.pux4j.ui.core.FirstFrameMarkers.wrap(png), tmp.resolve("shots"), "s")) {
            cap.writeFrame(frame(0x00, W, H)).get();
            cap.reset();
            cap.writeRegion(0, 0, 8, 8, frame(0xFF, 8, 8)).get();
        }
        List<Path> got = pngs(tmp.resolve("shots")), expected = pngs(oracle);
        assertEquals(2, got.size());
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(Files.readAllBytes(expected.get(i)), Files.readAllBytes(got.get(i)));
        }
    }

    @Test
    void prewrappedChainsAreNotWrappedAgainInEitherOrder(@TempDir Path tmp) throws Exception {
        System.setProperty(ScreenshotCapture.DIR_PROPERTY, tmp.resolve("s").toString());
        try {
            EInkDisplayDriver base = new Recorder(tmp.resolve("o"));
            // capture outside markers, then the bridge's own wrapping order applied on top
            EInkDisplayDriver chain = ScreenshotCapture.wrap(dev.pux4j.ui.core.FirstFrameMarkers.wrap(base));
            assertSame(chain, ScreenshotCapture.wrap(dev.pux4j.ui.core.FirstFrameMarkers.wrap(chain)));
            // markers outside capture
            EInkDisplayDriver other = dev.pux4j.ui.core.FirstFrameMarkers.wrap(ScreenshotCapture.wrap(base));
            assertSame(other, ScreenshotCapture.wrap(dev.pux4j.ui.core.FirstFrameMarkers.wrap(other)));
            ((AutoCloseable) chain).close();
            ((AutoCloseable) other).close();
        } finally {
            System.clearProperty(ScreenshotCapture.DIR_PROPERTY);
        }
    }
}
