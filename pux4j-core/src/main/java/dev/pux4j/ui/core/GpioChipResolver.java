// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the Linux GPIO character-device chip number for the Pi's 40-pin header
 * controller, by label rather than a fixed index.
 *
 * <p>Pi4J's FFM GPIO provider builds its device path as {@code "/dev/gpiochip" + bus},
 * defaulting to bus 0 unless a driver explicitly overrides it. That default is correct on
 * older/non-Pi5 boards (Raspberry Pi Zero 2 W, Pi 4B: the header controller is chip 0,
 * label {@code pinctrl-bcm2835}/{@code pinctrl-bcm2711}), but wrong on Pi 5-family boards
 * (Raspberry Pi 500+: the header controller enumerates as a higher-numbered chip alongside
 * several unrelated internal controllers — chip 15 on one observed system — label
 * {@code pinctrl-rp1}). Chip numbering on Pi 5-family boards is not guaranteed stable across
 * kernel/firmware updates, so this resolves it at runtime by scanning every
 * {@code /dev/gpiochipN} and matching the {@code pinctrl-} label prefix common to the header
 * controller across every Raspberry Pi generation, via {@code GPIO_GET_CHIPINFO_IOCTL}
 * (Linux GPIO character-device uAPI, {@code <linux/gpio.h>}).
 *
 * <p>Callers may bypass detection entirely with the {@code gpioChip} {@link DriverConfig}
 * property.
 */
public final class GpioChipResolver {

    private static final Logger log = LoggerFactory.getLogger(GpioChipResolver.class);

    private static final String DEV_DIR = "/dev";
    private static final Pattern CHIP_NAME = Pattern.compile("gpiochip(\\d+)");
    private static final String HEADER_LABEL_PREFIX = "pinctrl-";

    // struct gpiochip_info { char name[32]; char label[32]; __u32 lines; }; (linux/gpio.h)
    private static final int NAME_LEN = 32;
    private static final int LABEL_LEN = 32;
    private static final MemoryLayout CHIPINFO_LAYOUT = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(NAME_LEN, ValueLayout.JAVA_BYTE).withName("name"),
        MemoryLayout.sequenceLayout(LABEL_LEN, ValueLayout.JAVA_BYTE).withName("label"),
        ValueLayout.JAVA_INT.withName("lines"));
    private static final long LABEL_OFFSET =
        CHIPINFO_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("label"));

    // GPIO_GET_CHIPINFO_IOCTL = _IOR(0xB4, 0x01, struct gpiochip_info): dir=READ(2),
    // size=68 (sizeof(struct gpiochip_info)), type=0xB4, nr=0x01.
    // (2<<30) | (68<<16) | (0xB4<<8) | 0x01
    private static final long GPIO_GET_CHIPINFO_IOCTL = 0x8044B401L;

    private static final int O_RDWR = 0x0002;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle OPEN = LINKER.downcallHandle(
        LINKER.defaultLookup().find("open").orElseThrow(() -> new UnsatisfiedLinkError("open")),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle IOCTL = LINKER.downcallHandle(
        LINKER.defaultLookup().find("ioctl").orElseThrow(() -> new UnsatisfiedLinkError("ioctl")),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
    private static final MethodHandle CLOSE = LINKER.downcallHandle(
        LINKER.defaultLookup().find("close").orElseThrow(() -> new UnsatisfiedLinkError("close")),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    private static volatile Integer cachedHeaderChip;

    private GpioChipResolver() {
    }

    /**
     * Returns the chip number of the 40-pin header GPIO controller (the {@code N} in
     * {@code /dev/gpiochipN}) to use for {@code DigitalOutput}/{@code DigitalInput}
     * {@code .bus(...)} configuration.
     *
     * <p>If {@code config} carries a non-negative {@code gpioChip} property, that value is
     * returned directly and detection is skipped. Otherwise every {@code /dev/gpiochip*}
     * device is scanned and matched by label; the result is cached after the first scan.
     *
     * @throws IllegalStateException if zero or more than one header-controller chip is found
     */
    public static int resolveHeaderChip(DriverConfig config) {
        int override = config.property("gpioChip", -1);
        if (override >= 0) {
            log.info("Using configured gpioChip={} (auto-detection skipped)", override);
            return override;
        }
        return resolveHeaderChip();
    }

    private static synchronized int resolveHeaderChip() {
        if (cachedHeaderChip != null) {
            return cachedHeaderChip;
        }
        List<Integer> candidates = new ArrayList<>();
        List<Integer> matches = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(DEV_DIR), "gpiochip*")) {
            for (Path entry : stream) {
                Matcher m = CHIP_NAME.matcher(entry.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                int chip = Integer.parseInt(m.group(1));
                candidates.add(chip);
                String label = readLabel(entry);
                log.debug("{}: label='{}'", entry, label);
                if (label.startsWith(HEADER_LABEL_PREFIX)) {
                    matches.add(chip);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + DEV_DIR + "/gpiochip*", e);
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                "Expected exactly one GPIO chip labelled '" + HEADER_LABEL_PREFIX
                    + "*' among " + candidates + ", found matches " + matches
                    + ". Set the 'gpioChip' driver config property to override.");
        }
        int resolved = matches.get(0);
        log.info("Resolved 40-pin header GPIO chip: gpiochip{}", resolved);
        cachedHeaderChip = resolved;
        return resolved;
    }

    private static String readLabel(Path devicePath) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = arena.allocateFrom(devicePath.toString());
            int fd = (int) OPEN.invokeExact(path, O_RDWR);
            if (fd < 0) {
                throw new IllegalStateException("open(" + devicePath + ") failed");
            }
            try {
                MemorySegment info = arena.allocate(CHIPINFO_LAYOUT);
                int rc = (int) IOCTL.invokeExact(fd, GPIO_GET_CHIPINFO_IOCTL, info);
                if (rc < 0) {
                    throw new IllegalStateException("GPIO_GET_CHIPINFO_IOCTL(" + devicePath + ") failed");
                }
                return info.getString(LABEL_OFFSET);
            } finally {
                int ignored = (int) CLOSE.invokeExact(fd);
            }
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Failed reading GPIO chip info for " + devicePath, t);
        }
    }
}
