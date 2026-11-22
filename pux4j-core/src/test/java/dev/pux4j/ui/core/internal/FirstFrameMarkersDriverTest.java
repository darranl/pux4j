// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core.internal;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.pux4j.ui.core.DisplayCapabilities;
import dev.pux4j.ui.core.EInkDisplayDriver;
import dev.pux4j.ui.core.FirstFrameMarkers;
import dev.pux4j.ui.core.FrameData;
import dev.pux4j.ui.core.MonochromeFrame;
import dev.pux4j.ui.core.Orientation;
import dev.pux4j.ui.core.RefreshMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstFrameMarkersDriverTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(FirstFrameMarkersDriver.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private static final class Stub implements EInkDisplayDriver {
        final List<String> calls = new ArrayList<>();
        CompletableFuture<Void> next = CompletableFuture.completedFuture(null);
        public int getWidth() { return 8; }
        public int getHeight() { return 8; }
        public Orientation getOrientation() { return Orientation.LANDSCAPE; }
        public DisplayCapabilities getCapabilities() { return null; }
        public void initialize() { calls.add("initialize"); }
        public void reset() {}
        public void sleep() {}
        public void wake() {}
        public CompletableFuture<Void> writeFrame(FrameData f) { calls.add("writeFrame"); return next; }
        public CompletableFuture<Void> writeRegion(int x, int y, int w, int h, FrameData f) {
            calls.add("writeRegion");
            return next;
        }
    }

    private static MonochromeFrame frame() {
        return new MonochromeFrame(new byte[8], RefreshMode.FULL);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void logsHandedThenWrittenOnceForTheFirstFrameOnly() throws Exception {
        Stub stub = new Stub();
        EInkDisplayDriver d = FirstFrameMarkers.wrap(stub);
        d.initialize();
        d.writeFrame(frame()).get();
        d.writeFrame(frame()).get();
        d.writeRegion(0, 0, 8, 8, frame()).get();
        assertEquals(List.of("initialize", "writeFrame", "writeFrame", "writeRegion"), stub.calls);
        List<String> m = messages();
        assertEquals(2, m.size());
        assertTrue(m.get(0).matches("first-frame-handed epochMs=\\d+"), m.get(0));
        assertTrue(m.get(1).matches("first-frame-written epochMs=\\d+ writeMs=\\d+"), m.get(1));
    }

    @Test
    void writtenIsLoggedOnlyWhenTheWriteCompletes() throws Exception {
        Stub stub = new Stub();
        stub.next = new CompletableFuture<>();
        EInkDisplayDriver d = FirstFrameMarkers.wrap(stub);
        var f = d.writeRegion(0, 0, 8, 8, frame());
        assertEquals(1, messages().size());
        assertTrue(messages().get(0).startsWith("first-frame-handed"));
        stub.next.complete(null);
        f.get();
        assertEquals(2, messages().size());
        assertTrue(messages().get(1).startsWith("first-frame-written"));
    }

    @Test
    void failedFirstWriteIsWarnedNotMarkedWritten() {
        Stub stub = new Stub();
        stub.next = CompletableFuture.failedFuture(new IllegalStateException("boom"));
        EInkDisplayDriver d = FirstFrameMarkers.wrap(stub);
        d.writeFrame(frame());
        assertTrue(messages().stream().noneMatch(s -> s.startsWith("first-frame-written")));
        assertTrue(messages().stream().anyMatch(s -> s.startsWith("first-frame write failed")));
    }
}
