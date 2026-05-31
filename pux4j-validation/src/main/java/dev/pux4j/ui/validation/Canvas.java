// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import dev.pux4j.ui.core.Orientation;
import java.util.ArrayList;
import java.util.Arrays;

// Pure-Java pixel buffer for rendering to an eInk display without AWT.
// Colour state (black/white) is set before drawing calls, matching the
// AWT Graphics2D.setColor() pattern used throughout this class.
final class Canvas {

    static final int SCALE_SMALL = 1;
    static final int SCALE_NORMAL = 2;
    static final int FONT_H = 8;
    static final int FONT_W = 5;

    // Adafruit GFX 5×8 classic bitmap font — 96 printable ASCII characters
    // (0x20 space through 0x7E tilde), 5 bytes per character.
    // Each byte is a column: bit 0 = top pixel, bit 7 = bottom pixel.
    // Source: Adafruit-GFX-Library/glcdfont.c
    // Copyright (c) 2012 Adafruit Industries. All rights reserved.
    // SPDX-License-Identifier: BSD-2-Clause
    // https://github.com/adafruit/Adafruit-GFX-Library/blob/master/glcdfont.c
    private static final byte[] FONT = {
        0x00, 0x00, 0x00, 0x00, 0x00, // ' '
        0x3E, 0x5B, 0x4F, 0x5B, 0x3E, // '!'
        0x3E, 0x6B, 0x4F, 0x6B, 0x3E, // '"'
        0x36, 0x7F, 0x36, 0x7F, 0x36, // '#'
        0x24, 0x2A, 0x7F, 0x2A, 0x12, // '$'
        0x23, 0x13, 0x08, 0x64, 0x62, // '%'
        0x36, 0x49, 0x55, 0x22, 0x50, // '&'
        0x00, 0x05, 0x03, 0x00, 0x00, // '''
        0x00, 0x1C, 0x22, 0x41, 0x00, // '('
        0x00, 0x41, 0x22, 0x1C, 0x00, // ')'
        0x14, 0x08, 0x3E, 0x08, 0x14, // '*'
        0x08, 0x08, 0x3E, 0x08, 0x08, // '+'
        0x00, 0x50, 0x30, 0x00, 0x00, // ','
        0x08, 0x08, 0x08, 0x08, 0x08, // '-'
        0x00, 0x60, 0x60, 0x00, 0x00, // '.'
        0x20, 0x10, 0x08, 0x04, 0x02, // '/'
        0x3E, 0x51, 0x49, 0x45, 0x3E, // '0'
        0x00, 0x42, 0x7F, 0x40, 0x00, // '1'
        0x42, 0x61, 0x51, 0x49, 0x46, // '2'
        0x21, 0x41, 0x45, 0x4B, 0x31, // '3'
        0x18, 0x14, 0x12, 0x7F, 0x10, // '4'
        0x27, 0x45, 0x45, 0x45, 0x39, // '5'
        0x3C, 0x4A, 0x49, 0x49, 0x30, // '6'
        0x01, 0x71, 0x09, 0x05, 0x03, // '7'
        0x36, 0x49, 0x49, 0x49, 0x36, // '8'
        0x06, 0x49, 0x49, 0x29, 0x1E, // '9'
        0x00, 0x36, 0x36, 0x00, 0x00, // ':'
        0x00, 0x56, 0x36, 0x00, 0x00, // ';'
        0x08, 0x14, 0x22, 0x41, 0x00, // '<'
        0x14, 0x14, 0x14, 0x14, 0x14, // '='
        0x00, 0x41, 0x22, 0x14, 0x08, // '>'
        0x02, 0x01, 0x51, 0x09, 0x06, // '?'
        0x32, 0x49, 0x79, 0x41, 0x3E, // '@'
        0x7E, 0x11, 0x11, 0x11, 0x7E, // 'A'
        0x7F, 0x49, 0x49, 0x49, 0x36, // 'B'
        0x3E, 0x41, 0x41, 0x41, 0x22, // 'C'
        0x7F, 0x41, 0x41, 0x22, 0x1C, // 'D'
        0x7F, 0x49, 0x49, 0x49, 0x41, // 'E'
        0x7F, 0x09, 0x09, 0x09, 0x01, // 'F'
        0x3E, 0x41, 0x49, 0x49, 0x7A, // 'G'
        0x7F, 0x08, 0x08, 0x08, 0x7F, // 'H'
        0x00, 0x41, 0x7F, 0x41, 0x00, // 'I'
        0x20, 0x40, 0x41, 0x3F, 0x01, // 'J'
        0x7F, 0x08, 0x14, 0x22, 0x41, // 'K'
        0x7F, 0x40, 0x40, 0x40, 0x40, // 'L'
        0x7F, 0x02, 0x0C, 0x02, 0x7F, // 'M'
        0x7F, 0x04, 0x08, 0x10, 0x7F, // 'N'
        0x3E, 0x41, 0x41, 0x41, 0x3E, // 'O'
        0x7F, 0x09, 0x09, 0x09, 0x06, // 'P'
        0x3E, 0x41, 0x51, 0x21, 0x5E, // 'Q'
        0x7F, 0x09, 0x19, 0x29, 0x46, // 'R'
        0x46, 0x49, 0x49, 0x49, 0x31, // 'S'
        0x01, 0x01, 0x7F, 0x01, 0x01, // 'T'
        0x3F, 0x40, 0x40, 0x40, 0x3F, // 'U'
        0x1F, 0x20, 0x40, 0x20, 0x1F, // 'V'
        0x3F, 0x40, 0x38, 0x40, 0x3F, // 'W'
        0x63, 0x14, 0x08, 0x14, 0x63, // 'X'
        0x07, 0x08, 0x70, 0x08, 0x07, // 'Y'
        0x61, 0x51, 0x49, 0x45, 0x43, // 'Z'
        0x00, 0x7F, 0x41, 0x41, 0x00, // '['
        0x02, 0x04, 0x08, 0x10, 0x20, // '\'
        0x00, 0x41, 0x41, 0x7F, 0x00, // ']'
        0x04, 0x02, 0x01, 0x02, 0x04, // '^'
        0x40, 0x40, 0x40, 0x40, 0x40, // '_'
        0x00, 0x01, 0x02, 0x04, 0x00, // '`'
        0x20, 0x54, 0x54, 0x54, 0x78, // 'a'
        0x7F, 0x48, 0x44, 0x44, 0x38, // 'b'
        0x38, 0x44, 0x44, 0x44, 0x20, // 'c'
        0x38, 0x44, 0x44, 0x48, 0x7F, // 'd'
        0x38, 0x54, 0x54, 0x54, 0x18, // 'e'
        0x08, 0x7E, 0x09, 0x01, 0x02, // 'f'
        0x0C, 0x52, 0x52, 0x52, 0x3E, // 'g'
        0x7F, 0x08, 0x04, 0x04, 0x78, // 'h'
        0x00, 0x44, 0x7D, 0x40, 0x00, // 'i'
        0x20, 0x40, 0x44, 0x3D, 0x00, // 'j'
        0x7F, 0x10, 0x28, 0x44, 0x00, // 'k'
        0x00, 0x41, 0x7F, 0x40, 0x00, // 'l'
        0x7C, 0x04, 0x18, 0x04, 0x78, // 'm'
        0x7C, 0x08, 0x04, 0x04, 0x78, // 'n'
        0x38, 0x44, 0x44, 0x44, 0x38, // 'o'
        0x7C, 0x14, 0x14, 0x14, 0x08, // 'p'
        0x08, 0x14, 0x14, 0x18, 0x7C, // 'q'
        0x7C, 0x08, 0x04, 0x04, 0x08, // 'r'
        0x48, 0x54, 0x54, 0x54, 0x20, // 's'
        0x04, 0x3F, 0x44, 0x40, 0x20, // 't'
        0x3C, 0x40, 0x40, 0x20, 0x7C, // 'u'
        0x1C, 0x20, 0x40, 0x20, 0x1C, // 'v'
        0x3C, 0x40, 0x30, 0x40, 0x3C, // 'w'
        0x44, 0x28, 0x10, 0x28, 0x44, // 'x'
        0x0C, 0x50, 0x50, 0x50, 0x3C, // 'y'
        0x44, 0x64, 0x54, 0x4C, 0x44, // 'z'
        0x00, 0x08, 0x36, 0x41, 0x00, // '{'
        0x00, 0x00, 0x7F, 0x00, 0x00, // '|'
        0x00, 0x41, 0x36, 0x08, 0x00, // '}'
        0x10, 0x08, 0x08, 0x10, 0x08, // '~'
    };

    private final int logicalW;
    private final int logicalH;
    private final int fbW;
    private final int fbH;
    private final Orientation orientation;
    private final int[] pixels;
    private int colour = 0xFF000000; // black

    Canvas(int logicalW, int logicalH, int fbW, int fbH, Orientation orientation) {
        this.logicalW = logicalW;
        this.logicalH = logicalH;
        this.fbW = fbW;
        this.fbH = fbH;
        this.orientation = orientation;
        this.pixels = new int[logicalW * logicalH];
        Arrays.fill(pixels, 0xFFFFFFFF); // white background
    }

    void setBlack() { colour = 0xFF000000; }
    void setWhite() { colour = 0xFFFFFFFF; }

    static int stringWidth(String text, int scale) {
        return text.length() * (FONT_W + 1) * scale;
    }

    // topY is the top of the glyph cell (bit-0 row of the font data).
    void drawString(String text, int x, int topY, int scale) {
        int cx = x;
        for (int i = 0; i < text.length(); i++) {
            int ch = text.charAt(i);
            if (ch < 0x20 || ch > 0x7E) ch = 0x20;
            int base = (ch - 0x20) * 5;
            for (int col = 0; col < FONT_W; col++) {
                int bits = FONT[base + col] & 0xFF;
                for (int row = 0; row < FONT_H; row++) {
                    if ((bits & (1 << row)) != 0) {
                        for (int sy = 0; sy < scale; sy++) {
                            for (int sx = 0; sx < scale; sx++) {
                                setPixel(cx + col * scale + sx, topY + row * scale + sy);
                            }
                        }
                    }
                }
            }
            cx += (FONT_W + 1) * scale; // 1px inter-glyph gap
        }
    }

    void drawLine(int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            setPixel(x0, y0);
            if (x0 == x1 && y0 == y1) break;
            int e2 = err * 2;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    // AWT drawRect semantics: inclusive [x..x+w] × [y..y+h]
    void drawRect(int x, int y, int w, int h) {
        drawLine(x, y, x + w, y);
        drawLine(x + w, y, x + w, y + h);
        drawLine(x + w, y + h, x, y + h);
        drawLine(x, y + h, x, y);
    }

    // drawRoundRect without AWT arcs — square corners are fine on eInk.
    void drawRoundRect(int x, int y, int w, int h) {
        drawRect(x, y, w, h);
    }

    // AWT fillRect semantics: exclusive [x..x+w) × [y..y+h)
    void fillRect(int x, int y, int w, int h) {
        for (int py = y; py < y + h; py++) {
            for (int px = x; px < x + w; px++) {
                setPixel(px, py);
            }
        }
    }

    // fillRoundRect — square corners acceptable on eInk.
    void fillRoundRect(int x, int y, int w, int h) {
        fillRect(x, y, w, h);
    }

    // fillOval using scan-line approach; (x,y,w,h) is the bounding box.
    void fillOval(int x, int y, int w, int h) {
        double rx = w / 2.0;
        double ry = h / 2.0;
        double cx = x + rx;
        double cy = y + ry;
        for (int py = y; py < y + h; py++) {
            double dy = (py + 0.5 - cy) / ry;
            double span = rx * Math.sqrt(Math.max(0.0, 1.0 - dy * dy));
            int x0 = (int) Math.ceil(cx - span);
            int x1 = (int) Math.floor(cx + span);
            for (int px = x0; px <= x1; px++) {
                setPixel(px, py);
            }
        }
    }

    // Fill a polygon defined by n vertices using scan-line rasterisation.
    void fillPolygon(int[] xs, int[] ys, int n) {
        if (n < 3) return;
        int minY = ys[0];
        int maxY = ys[0];
        for (int i = 1; i < n; i++) {
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        var intersections = new ArrayList<Integer>();
        for (int py = minY; py <= maxY; py++) {
            intersections.clear();
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                int y0 = ys[i], y1 = ys[j];
                if ((y0 <= py && y1 > py) || (y1 <= py && y0 > py)) {
                    int ix = xs[i] + (py - y0) * (xs[j] - xs[i]) / (y1 - y0);
                    intersections.add(ix);
                }
            }
            intersections.sort(Integer::compare);
            for (int k = 0; k + 1 < intersections.size(); k += 2) {
                int x0 = intersections.get(k);
                int x1 = intersections.get(k + 1);
                for (int px = x0; px <= x1; px++) {
                    setPixel(px, py);
                }
            }
        }
    }

    // Blit a pre-rendered monochrome ARGB pixel array at position (dx, dy).
    void drawImage(int[] argb, int w, int h, int dx, int dy) {
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int c = argb[py * w + px];
                int r = (c >>> 16) & 0xFF;
                int g = (c >>> 8) & 0xFF;
                int b = c & 0xFF;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                if (lum < 128) {
                    int savedColour = colour;
                    colour = 0xFF000000;
                    setPixel(dx + px, dy + py);
                    colour = savedColour;
                }
            }
        }
    }

    // Pack the logical pixel buffer into a packed 1-bit-per-pixel framebuffer byte
    // array, applying the orientation mapping from logical (x,y) to framebuffer (fx,fy).
    // White pixels → bit 1; black pixels → bit 0 (eInk convention).
    byte[] packMonochrome() {
        int fbRowBytes = (fbW + 7) / 8;
        byte[] out = new byte[fbRowBytes * fbH];
        Arrays.fill(out, (byte) 0xFF);
        for (int y = 0; y < logicalH; y++) {
            for (int x = 0; x < logicalW; x++) {
                int rgb = pixels[y * logicalW + x];
                int r = (rgb >>> 16) & 0xFF;
                int g = (rgb >>> 8) & 0xFF;
                int bv = rgb & 0xFF;
                int lum = (r * 299 + g * 587 + bv * 114) / 1000;
                if (lum < 128) {
                    int[] mapped = mapToFramebuffer(x, y);
                    int fx = mapped[0];
                    int fy = mapped[1];
                    int idx = fy * fbRowBytes + (fx / 8);
                    int bit = 7 - (fx % 8);
                    out[idx] = (byte) (out[idx] & ~(1 << bit));
                }
            }
        }
        return out;
    }

    private void setPixel(int x, int y) {
        if (x >= 0 && x < logicalW && y >= 0 && y < logicalH) {
            pixels[y * logicalW + x] = colour;
        }
    }

    private int[] mapToFramebuffer(int lx, int ly) {
        return switch (orientation) {
            case PORTRAIT -> new int[]{lx, ly};
            case LANDSCAPE -> new int[]{ly, fbH - 1 - lx};
            case PORTRAIT_INVERTED -> new int[]{fbW - 1 - lx, fbH - 1 - ly};
            case LANDSCAPE_INVERTED -> new int[]{fbW - 1 - ly, lx};
        };
    }
}
