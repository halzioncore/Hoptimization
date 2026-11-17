#version 120

uniform vec2 u_HalfSize;
uniform vec4 u_Radii;
uniform vec4 u_ShadowColor;
uniform float u_BlurRadius;

varying vec2 v_LocalPos;
varying float v_PixelScale;

float sdRoundedBox(vec2 p, vec2 halfSize, vec4 radii) {
    vec2 sideRadii = (p.x > 0.0) ? radii.yz : radii.xw;
    float r = (p.y < 0.0) ? sideRadii.x : sideRadii.y;
    r = clamp(r, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float erfApprox(float x) {
    float s = sign(x);
    float a = abs(x);
    float t = 1.0 + (0.278393 + (0.230389 + 0.078108 * (a * a)) * a) * a;
    t *= t;
    return s - s / (t * t);
}

void main() {
    float pxUnit = 1.0 / v_PixelScale;
    float blur = max(u_BlurRadius, pxUnit);
    float dist = sdRoundedBox(v_LocalPos, u_HalfSize, u_Radii);

    // Gaussian falloff (sigma = blur * 0.5) stays C-infinity smooth across dist = 0.
    float normDist = dist * 1.41421356 / blur;
    float shadow = 0.5 * (1.0 - erfApprox(normDist));

    // Taper smoothly to zero before the quad boundary so no rectangular edge is ever visible.
    float maxRange = blur * 1.5;
    shadow *= 1.0 - smoothstep(maxRange * 0.75, maxRange, dist);

    float alpha = u_ShadowColor.a * shadow;
    if (alpha <= 0.001) {
        discard;
    }

    gl_FragColor = vec4(u_ShadowColor.rgb, alpha);
}
