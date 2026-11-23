#version 120

uniform sampler2D u_BlurTex;
uniform vec2 u_ViewportSize;
uniform vec2 u_HalfSize;
uniform vec4 u_Radii;
uniform vec4 u_TintColor;
uniform vec4 u_BorderColor;
uniform float u_BorderThickness;
uniform float u_HighlightStrength;
uniform float u_MasterAlpha;

varying vec2 v_LocalPos;
varying float v_PixelScale;

float sdRoundedBox(vec2 p, vec2 halfSize, vec4 radii) {
    vec2 sideRadii = (p.x > 0.0) ? radii.yz : radii.xw;
    float r = (p.y < 0.0) ? sideRadii.x : sideRadii.y;
    r = clamp(r, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    float pxUnit = 1.0 / v_PixelScale;
    float aa = 0.5 * pxUnit;

    float dist = sdRoundedBox(v_LocalPos, u_HalfSize, u_Radii);
    float shapeAlpha = 1.0 - smoothstep(-aa, aa, dist);
    if (shapeAlpha <= 0.001 || u_MasterAlpha <= 0.001) {
        discard;
    }

    vec2 screenUv = clamp(gl_FragCoord.xy / max(u_ViewportSize, vec2(1.0)), vec2(0.001), vec2(0.999));
    vec3 backdrop = texture2D(u_BlurTex, screenUv).rgb;

    // Gentle vibrancy boost so frosted glass feels luminous rather than muddy grey.
    float luma = dot(backdrop, vec3(0.2126, 0.7152, 0.0722));
    backdrop = clamp(mix(vec3(luma), backdrop, 1.14), 0.0, 1.0);

    // Composite translucent theme surface tint over the blurred backdrop.
    vec3 glassRgb = mix(backdrop, u_TintColor.rgb, clamp(u_TintColor.a, 0.0, 1.0));

    // Subtle top-down glass sheen + crisp inner frost rim along the top edge.
    float verticalGrad = clamp(0.5 - (v_LocalPos.y / max(u_HalfSize.y * 2.0, 1.0)), 0.0, 1.0);
    float sheen = pow(verticalGrad, 2.2) * u_HighlightStrength;
    float innerRim = (1.0 - smoothstep(-2.0 * pxUnit, -0.5 * pxUnit, dist)) * 0.0;
    float topRim = smoothstep(-2.5 * pxUnit, 0.0, dist) * verticalGrad * u_HighlightStrength * 1.4;
    glassRgb = clamp(glassRgb + vec3(sheen + topRim + innerRim), 0.0, 1.0);

    // Subtle acrylic micro-grain to break up 8-bit gradient banding.
    float grain = (hash12(gl_FragCoord.xy) - 0.5) * 0.018;
    glassRgb = clamp(glassRgb + vec3(grain), 0.0, 1.0);

    vec3 outRgb = glassRgb;
    if (u_BorderThickness > 0.0 && u_BorderColor.a > 0.0) {
        float innerEdge = -u_BorderThickness;
        float borderMask = smoothstep(innerEdge - aa, innerEdge + aa, dist);
        float borderA = u_BorderColor.a * borderMask;
        outRgb = mix(outRgb, u_BorderColor.rgb, borderA);
    }

    float finalAlpha = shapeAlpha * clamp(u_MasterAlpha, 0.0, 1.0);
    if (finalAlpha <= 0.001) {
        discard;
    }

    gl_FragColor = vec4(outRgb, finalAlpha);
}
