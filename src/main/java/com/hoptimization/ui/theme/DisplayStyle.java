package com.hoptimization.ui.theme;

import java.util.Locale;

/**
 * Global UI surface display style ({@link #STANDARD} solid cards vs {@link #FROSTED_GLASS} blurred glass).
 */
public enum DisplayStyle {
    STANDARD("standard"),
    FROSTED_GLASS("frosted_glass");

    private final String id;

    DisplayStyle(String id) {
        this.id = id;
    }

    /**
     * Returns the stable serialization identifier ({@code "standard"} or {@code "frosted_glass"}).
     *
     * @return style identifier
     */
    public String getId() {
        return this.id;
    }

    /**
     * Resolves a {@link DisplayStyle} from its identifier (case-insensitive), or returns {@code null}.
     *
     * @param id style identifier
     * @return matching {@link DisplayStyle} or {@code null}
     */
    public static DisplayStyle fromId(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if ("standard".equals(normalized)) {
            return STANDARD;
        }
        if ("frosted_glass".equals(normalized) || "frosted".equals(normalized) || "glass".equals(normalized)) {
            return FROSTED_GLASS;
        }
        return null;
    }

    /**
     * Resolves a {@link DisplayStyle} from its identifier, falling back to {@code fallback} when unknown.
     *
     * @param id       style identifier
     * @param fallback fallback style
     * @return resolved style
     */
    public static DisplayStyle fromIdOrDefault(String id, DisplayStyle fallback) {
        DisplayStyle parsed = fromId(id);
        return parsed != null ? parsed : (fallback != null ? fallback : STANDARD);
    }
}
