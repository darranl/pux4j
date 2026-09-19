// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * Maps logical display coordinates to native framebuffer coordinates (and back), given
 * a framebuffer size and viewing {@link Orientation}.
 *
 * <p>The native framebuffer is always portrait-shaped (narrow x tall — see, e.g.,
 * {@code Ssd1680DisplayDriver.WIDTH}/{@code HEIGHT}); the logical canvas is wide-shaped
 * for {@link Orientation#LANDSCAPE}/{@link Orientation#LANDSCAPE_INVERTED} and
 * portrait-shaped for {@link Orientation#PORTRAIT}/{@link Orientation#PORTRAIT_INVERTED}.
 *
 * <p>Ported verbatim from the hardware-verified {@code Canvas.mapToFramebuffer} in
 * {@code pux4j-validation} — the landscape mappings are a proper 90-degree rotation (axis
 * swap plus exactly one flip), not a mirror/transpose (axis swap plus zero or two flips),
 * which has the wrong handedness for a physical screen rotation. Direction empirically
 * confirmed on LittleRaspberry (hat-2in13v4, {@code LANDSCAPE_INVERTED}) 2026-08-30: an
 * earlier transpose-based attempt rendered the correct aspect ratio and right-way-up, but
 * mirrored left-right.
 *
 * <p>Internally, each orientation is represented as one affine transform
 * {@code native = [[a b][c d]] * logical + [tx ty]} rather than as separate per-axis
 * {@code switch} expressions. This is deliberate: the mirror-vs-rotation bug above was
 * exactly a case where the X formula and the Y formula were each individually plausible
 * and jointly wrong — writing them as two independent switches invites that mistake to
 * recur, one axis at a time. As one 2x2 matrix, {@code determinant == +1} is a single
 * checkable fact that rules out a mirror, and it is checked once, at construction, for
 * every orientation this type can ever represent — not left to a test someone has to
 * remember to write or extend. The inverse (native -&gt; logical) is computed generically
 * from the same matrix rather than hand-derived a second time, so there is exactly one
 * place per orientation where the transform is written down.
 */
public final class OrientationMapping {

    private final int framebufferWidth;
    private final int framebufferHeight;
    private final int logicalWidth;
    private final int logicalHeight;
    private final Orientation orientation;

    // native = [a b; c d] * logical + [tx; ty]
    private final int a, b, tx;
    private final int c, d, ty;
    // logical = [ia ib; ic id] * native + [itx; ity] — the inverse of the above.
    private final int ia, ib, itx;
    private final int ic, id, ity;

    private OrientationMapping(int framebufferWidth, int framebufferHeight, Orientation orientation) {
        this.framebufferWidth = framebufferWidth;
        this.framebufferHeight = framebufferHeight;
        this.orientation = orientation;
        this.logicalWidth = swapDimension(framebufferWidth, framebufferHeight, orientation);
        this.logicalHeight = swapDimension(framebufferHeight, framebufferWidth, orientation);

        // Coefficients of the forward (logical -> native) transform for each orientation,
        // read directly off Canvas.mapToFramebuffer's per-orientation formulas:
        //   LANDSCAPE:          nativeX = ly,          nativeY = fbH-1-lx
        //   LANDSCAPE_INVERTED: nativeX = fbW-1-ly,    nativeY = lx
        //   PORTRAIT:           nativeX = lx,          nativeY = ly
        //   PORTRAIT_INVERTED:  nativeX = fbW-1-lx,    nativeY = fbH-1-ly
        //
        // Deliberately a switch *expression* (assigned to a local, then unpacked into the
        // blank-final fields below) rather than a switch statement assigning the fields
        // directly: only switch expressions get javac's exhaustiveness enforcement for an
        // enum with no default arm — a plain switch statement compiles even when
        // non-exhaustive (verified: an earlier version of this code used a switch statement
        // with no default and failed to compile with "variable might not have been
        // initialized" precisely because javac does *not* treat it as exhaustive). This way,
        // adding a fifth Orientation constant without a case here is a genuine compile error,
        // not a runtime one — the "checked once, for every orientation this type can ever
        // represent" guarantee the class doc claims is real, not aspirational.
        Coefficients coefficients = switch (orientation) {
            case LANDSCAPE -> new Coefficients(0, 1, 0, -1, 0, framebufferHeight - 1);
            case LANDSCAPE_INVERTED -> new Coefficients(0, -1, framebufferWidth - 1, 1, 0, 0);
            case PORTRAIT -> new Coefficients(1, 0, 0, 0, 1, 0);
            case PORTRAIT_INVERTED -> new Coefficients(-1, 0, framebufferWidth - 1, 0, -1, framebufferHeight - 1);
        };
        a = coefficients.a(); b = coefficients.b(); tx = coefficients.tx();
        c = coefficients.c(); d = coefficients.d(); ty = coefficients.ty();

        int determinant = a * d - b * c;
        if (determinant != 1) {
            throw new AssertionError("OrientationMapping for " + orientation
                + " is not a proper rotation (determinant " + determinant
                + ", expected +1) — this is a mirror, not a rotation, and would render "
                + "backwards on real hardware. This indicates a coding error in this class, "
                + "not bad input.");
        }

        // Generic 2x2 matrix inverse: for an integer matrix with determinant +-1, the
        // inverse is exact (no rounding). logical = Minv * (native - t) = Minv*native - Minv*t.
        ia = d; ib = -b;
        ic = -c; id = a;
        itx = -(ia * tx + ib * ty);
        ity = -(ic * tx + id * ty);
    }

    // Exists only so the per-orientation coefficients can be produced by a switch
    // *expression* (see the constructor) rather than six blank-final fields assigned
    // directly by a switch statement.
    private record Coefficients(int a, int b, int tx, int c, int d, int ty) {}

    /**
     * Creates a mapping for the given native framebuffer size and orientation.
     *
     * @param framebufferWidth  native framebuffer width in pixels; must be &gt; 0
     * @param framebufferHeight native framebuffer height in pixels; must be &gt; 0
     * @param orientation       viewing orientation
     * @throws IllegalArgumentException if either dimension is &lt;= 0
     */
    public static OrientationMapping of(int framebufferWidth, int framebufferHeight, Orientation orientation) {
        if (framebufferWidth <= 0) throw new IllegalArgumentException("framebufferWidth must be > 0");
        if (framebufferHeight <= 0) throw new IllegalArgumentException("framebufferHeight must be > 0");
        return new OrientationMapping(framebufferWidth, framebufferHeight, orientation);
    }

    private static int swapDimension(int primary, int secondary, Orientation orientation) {
        return switch (orientation) {
            case LANDSCAPE, LANDSCAPE_INVERTED -> secondary;
            case PORTRAIT, PORTRAIT_INVERTED -> primary;
        };
    }

    public int framebufferWidth() {
        return framebufferWidth;
    }

    public int framebufferHeight() {
        return framebufferHeight;
    }

    public int logicalWidth() {
        return logicalWidth;
    }

    public int logicalHeight() {
        return logicalHeight;
    }

    public Orientation orientation() {
        return orientation;
    }

    /** Maps a logical X coordinate to its native framebuffer X coordinate. */
    public int nativeX(int logicalX, int logicalY) {
        return a * logicalX + b * logicalY + tx;
    }

    /** Maps a logical Y coordinate to its native framebuffer Y coordinate. */
    public int nativeY(int logicalX, int logicalY) {
        return c * logicalX + d * logicalY + ty;
    }

    /** Maps a native framebuffer X coordinate back to its logical X coordinate. */
    public int logicalX(int nativeX, int nativeY) {
        return ia * nativeX + ib * nativeY + itx;
    }

    /** Maps a native framebuffer Y coordinate back to its logical Y coordinate. */
    public int logicalY(int nativeX, int nativeY) {
        return ic * nativeX + id * nativeY + ity;
    }
}
