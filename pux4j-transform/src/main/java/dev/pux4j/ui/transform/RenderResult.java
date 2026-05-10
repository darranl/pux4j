package dev.pux4j.ui.transform;

import dev.pux4j.ui.core.FrameData;

/** Produced by {@link RenderSession#prepare}. The complete work item for the display thread. */
public record RenderResult(FrameData frame, RefreshDecision decision) {}
