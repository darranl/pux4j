package dev.pux4j.ui.core;

/**
 * Dual-plane 4-gray pixel data for SSD1675A. Always triggers a full refresh.
 * Shade lookup: (bwPlane=1, redPlane=1)=white, (1,0)=light grey, (0,1)=dark grey, (0,0)=black.
 * Required byte count per plane: {@code ceil(logicalWidth * logicalHeight / 8)}.
 */
public record FourGrayFrame(byte[] bwPlane, byte[] redPlane) implements FrameData {}
