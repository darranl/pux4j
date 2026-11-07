// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.launcher;

import dev.pux4j.ui.app.DriverApp;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherTest {

    /** Fake driver app recording what it was given. */
    static class Fake implements DriverApp {
        final String name;
        final int result;
        final boolean fail;
        String[] received;

        Fake(String name, int result, boolean fail) {
            this.name = name;
            this.result = result;
            this.fail = fail;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "fake " + name; }
        @Override public int run(String[] args) throws Exception {
            received = args;
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return result;
        }
    }

    /** Two classes with the same simple name, to force the fully qualified fallback. */
    static final class OuterA { static final class Twin extends Fake { Twin(String n) { super(n, 0, false); } } }
    static final class OuterB { static final class Twin extends Fake { Twin(String n) { super(n, 0, false); } } }
    static final class Other extends Fake { Other(String n) { super(n, 0, false); } }
    static final class Another extends Fake { Another(String n) { super(n, 0, false); } }

    private static AppEntry driver(DriverApp d) {
        return new AppEntry(AppEntry.Kind.DRIVER, d.name(), d.description(), d);
    }

    private static AppEntry fx(String name) {
        return new AppEntry(AppEntry.Kind.JAVAFX, name, "", new Object());
    }

    private static AppSelection select(List<AppEntry> apps, String... args) {
        return AppSelection.select(AppCatalogue.resolve(apps), args, null);
    }

    // --- selection ---

    @Test
    void singleAppNeedsNoName() {
        AppSelection s = select(List.of(fx("demo")));
        assertEquals("demo", s.entry().name());
        assertEquals(0, s.exitCode());
    }

    @Test
    void severalAppsNeedAName() {
        List<AppEntry> apps = List.of(fx("demo"), driver(new Fake("smoke", 0, false)));
        AppSelection none = select(apps);
        assertEquals(2, none.exitCode());
        assertTrue(none.error().contains("smoke"));
        assertEquals("smoke", select(apps, "smoke").entry().name());
    }

    @Test
    void unknownAndEmpty() {
        AppSelection unknown = select(List.of(fx("demo"), fx("dice")), "nope");
        assertEquals(2, unknown.exitCode());
        assertTrue(unknown.error().contains("demo") && unknown.error().contains("dice"));
        assertEquals(2, select(List.of()).exitCode());
    }

    @Test
    void propertyNameStillWorksAndPositionalWins() {
        List<AppEntry> apps = List.of(fx("demo"), fx("dice"));
        assertEquals("dice", AppSelection.select(apps, new String[0], "dice").entry().name());
        assertEquals("demo", AppSelection.select(apps, new String[] {"demo"}, "dice").entry().name());
    }

    @Test
    void appNameIsStrippedAndArgumentsPassedUnchanged() {
        List<AppEntry> apps = List.of(fx("demo"), driver(new Fake("validation", 0, false)));
        AppSelection s = select(apps, "validation", "--start-step", "3", "--notes", "a b  c");
        assertArrayEquals(new String[] {"--start-step", "3", "--notes", "a b  c"}, s.appArgs());
    }

    // --- collisions ---

    @Test
    void noCollisionKeepsNames() {
        List<AppEntry> r = AppCatalogue.resolve(List.of(fx("demo"), driver(new Other("smoke"))));
        assertEquals(List.of("demo", "smoke"), r.stream().map(AppEntry::name).toList());
        assertFalse(r.get(0).renamed() || r.get(1).renamed());
    }

    @Test
    void collisionResolvedBySimpleClassName() {
        List<AppEntry> r = AppCatalogue.resolve(List.of(driver(new Other("x")), driver(new Another("x"))));
        assertEquals(List.of("Other", "Another"), r.stream().map(AppEntry::name).toList());
        assertTrue(r.get(0).renamed());
        assertEquals("x", r.get(0).originalName());
    }

    @Test
    void collidingSimpleNamesFallBackToQualifiedNames() {
        List<AppEntry> r = AppCatalogue.resolve(List.of(
                driver(new OuterA.Twin("x")), driver(new OuterB.Twin("x"))));
        assertEquals(OuterA.Twin.class.getName(), r.get(0).name());
        assertEquals(OuterB.Twin.class.getName(), r.get(1).name());
    }

    @Test
    void threeWayMixRenamesOnlyTheCollidingGroup() {
        List<AppEntry> r = AppCatalogue.resolve(List.of(
                driver(new Other("demo")), driver(new Another("demo")), driver(new Fake("smoke", 0, false))));
        assertEquals(List.of("Other", "Another", "smoke"), r.stream().map(AppEntry::name).toList());
        assertFalse(r.get(2).renamed());
    }

    @Test
    void fallbackIsPerGroup() {
        List<AppEntry> r = AppCatalogue.resolve(List.of(
                driver(new OuterA.Twin("a")), driver(new OuterB.Twin("a")),
                driver(new Other("b")), driver(new Another("b"))));
        assertEquals(OuterA.Twin.class.getName(), r.get(0).name());
        assertEquals("Other", r.get(2).name());
    }

    @Test
    void selectingByOriginalCollidingNameIsRejected() {
        List<AppEntry> apps = List.of(driver(new Other("x")), driver(new Another("x")));
        AppSelection byOriginal = select(apps, "x");
        assertEquals(2, byOriginal.exitCode());
        assertTrue(byOriginal.error().contains("Other") && byOriginal.error().contains("Another"));
        assertEquals("Another", select(apps, "Another").entry().name());
    }

    // --- list ---

    @Test
    void listFormatting() {
        List<AppEntry> apps = AppCatalogue.resolve(List.of(
                fx("demo"), driver(new Fake("smoke", 0, false)),
                driver(new Other("x")), driver(new Another("x"))));
        String text = AppList.format(apps);
        String[] lines = text.split("\n");
        assertEquals(4, lines.length);
        assertTrue(lines[0].startsWith("  demo   ") && lines[0].endsWith("JavaFX app"), lines[0]);
        assertTrue(lines[1].contains("driver test") && lines[1].endsWith("fake smoke"), lines[1]);
        assertTrue(lines[2].contains("Other") && lines[2].contains("(name 'x' clashes)"), lines[2]);
        assertEquals("Display drivers: ssd1675a; touch drivers: icnt86x\n",
                AppList.footer(List.of("ssd1675a"), List.of("icnt86x")));
        assertEquals("Display drivers: none; touch drivers: none\n", AppList.footer(List.of(), List.of()));
    }

    // --- run: exit codes, seams ---

    private record Result(int code, String out, String err) { }

    private static Result run(List<AppEntry> apps, AppRunnerFx fx, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = EInkLauncher.run(args, apps, "FOOTER\n", null,
                new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8), fx::run);
        return new Result(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private interface AppRunnerFx {
        int run(Object content) throws Exception;
    }

    @Test
    void driverAppExitCodesAndNoJavaFx() {
        List<String> fxCalls = new ArrayList<>();
        AppRunnerFx fx = c -> { fxCalls.add("called"); return 0; };
        Fake ok = new Fake("ok", 0, false);
        Fake three = new Fake("three", 3, false);
        Fake bad = new Fake("bad", 0, true);
        List<AppEntry> apps = List.of(driver(ok), driver(three), driver(bad), fx("demo"));

        assertEquals(0, run(apps, fx, "ok", "--notes", "a b").code());
        assertArrayEquals(new String[] {"--notes", "a b"}, ok.received);
        assertEquals(3, run(apps, fx, "three").code());
        assertEquals(1, run(apps, fx, "bad").code());
        assertTrue(fxCalls.isEmpty(), "driver apps must not touch the JavaFX runner");
    }

    @Test
    void javaFxAppIsDispatchedAndExtraArgsDropped() {
        List<Object> started = new ArrayList<>();
        AppEntry demo = fx("demo");
        Result r = run(List.of(demo, driver(new Fake("smoke", 0, false))), c -> { started.add(c); return 1; },
                "demo", "--ignored");
        assertEquals(1, r.code());
        assertEquals(List.of(demo.source()), started);
    }

    @Test
    void usageErrorsExitTwoAndListApps() {
        List<AppEntry> apps = List.of(fx("demo"), driver(new Fake("smoke", 0, false)));
        Result unknown = run(apps, c -> 0, "nope");
        assertEquals(2, unknown.code());
        assertTrue(unknown.err().contains("smoke") && unknown.err().contains("driver test"));
        assertEquals(2, run(apps, c -> 0).code());
        assertEquals(2, run(List.of(), c -> 0).code());
    }

    @Test
    void listPrintsAppsThenFooterAndExitsZero() {
        Result r = run(List.of(fx("demo")), c -> 0, "--list");
        assertEquals(0, r.code());
        assertTrue(r.out().endsWith("FOOTER\n"));
        assertTrue(r.out().contains("demo"));
    }
}
