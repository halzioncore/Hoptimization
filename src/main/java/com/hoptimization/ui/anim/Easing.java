package com.hoptimization.ui.anim;

/**
 * Standard easing curves for UI animations.
 * No bounce or overshoot curves are permitted by the design specification.
 */
public enum Easing {
    /**
     * Constant-speed interpolation.
     */
    LINEAR {
        @Override
        public float apply(float t) {
            return clamp01(t);
        }
    },

    /**
     * Fast start with smooth cubic deceleration.
     */
    EASE_OUT_CUBIC {
        @Override
        public float apply(float t) {
            float clamped = clamp01(t);
            float inv = 1.0f - clamped;
            return 1.0f - inv * inv * inv;
        }
    },

    /**
     * Pronounced deceleration for window open/close transitions.
     */
    EASE_OUT_QUINT {
        @Override
        public float apply(float t) {
            float clamped = clamp01(t);
            float inv = 1.0f - clamped;
            float inv2 = inv * inv;
            return 1.0f - inv2 * inv2 * inv;
        }
    };

    /**
     * Evaluates the easing curve for normalized progress in [0, 1].
     *
     * @param t normalized elapsed progress
     * @return eased progress clamped to [0, 1]
     */
    public abstract float apply(float t);

    private static float clamp01(float t) {
        if (t <= 0.0f) {
            return 0.0f;
        }
        if (t >= 1.0f) {
            return 1.0f;
        }
        return t;
    }
}
