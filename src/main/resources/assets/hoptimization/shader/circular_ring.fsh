#version 120

uniform float u_Radius;
uniform float u_HalfThickness;
uniform float u_Progress;
uniform vec4 u_TrackColor;
uniform vec4 u_ProgressColor;

varying vec2 v_LocalPos;
varying float v_PixelScale;

void main() {
    float pxUnit = 1.0 / v_PixelScale;
    float aa = 0.75 * pxUnit;
    float r = length(v_LocalPos);

    // Sub-pixel anti-aliased distance field for the full background track ring.
    float trackDist = abs(r - u_Radius) - u_HalfThickness;
    float trackMask = 1.0 - smoothstep(-aa, aa, trackDist);

    float progMask = 0.0;
    float p = clamp(u_Progress, 0.0, 1.0);
    if (p >= 0.999) {
        progMask = trackMask;
    } else if (p > 0.001) {
        // Angle measured clockwise from 12 o'clock (0, -u_Radius) in Y-down screen space.
        float angle = atan(v_LocalPos.x, -v_LocalPos.y);
        if (angle < 0.0) {
            angle += 6.28318530718;
        }
        float endAngle = p * 6.28318530718;

        float arcDist = trackDist;
        if (angle > endAngle) {
            vec2 startCap = vec2(0.0, -u_Radius);
            vec2 endCap = vec2(sin(endAngle), -cos(endAngle)) * u_Radius;
            float dStart = length(v_LocalPos - startCap) - u_HalfThickness;
            float dEnd = length(v_LocalPos - endCap) - u_HalfThickness;
            arcDist = min(dStart, dEnd);
        }
        progMask = 1.0 - smoothstep(-aa, aa, arcDist);
    }

    float tA = u_TrackColor.a * trackMask;
    float pA = u_ProgressColor.a * progMask;
    float outA = pA + tA * (1.0 - pA);
    if (outA <= 0.001) {
        discard;
    }

    vec3 outRgb = (u_ProgressColor.rgb * pA + u_TrackColor.rgb * tA * (1.0 - pA)) / outA;
    gl_FragColor = vec4(outRgb, outA);
}
