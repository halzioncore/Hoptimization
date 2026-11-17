#version 120

uniform vec2 u_HalfSize;
uniform vec4 u_Radii;
uniform vec4 u_FillColor;
uniform vec4 u_BorderColor;
uniform float u_BorderThickness;

varying vec2 v_LocalPos;
varying float v_PixelScale;

float sdRoundedBox(vec2 p, vec2 halfSize, vec4 radii) {
    // u_Radii = (topLeft, topRight, bottomRight, bottomLeft) in Y-down screen space.
    vec2 sideRadii = (p.x > 0.0) ? radii.yz : radii.xw;
    float r = (p.y < 0.0) ? sideRadii.x : sideRadii.y;
    r = clamp(r, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

void main() {
    float pxUnit = 1.0 / v_PixelScale;
    float aa = 0.5 * pxUnit;

    float dist = sdRoundedBox(v_LocalPos, u_HalfSize, u_Radii);
    float shapeAlpha = 1.0 - smoothstep(-aa, aa, dist);
    if (shapeAlpha <= 0.001) {
        discard;
    }

    vec3 outRgb = u_FillColor.rgb;
    float outAlpha = u_FillColor.a;

    if (u_BorderThickness > 0.0 && u_BorderColor.a > 0.0) {
        float innerEdge = -u_BorderThickness;
        float borderMask = smoothstep(innerEdge - aa, innerEdge + aa, dist);
        // Composite border OVER fill so translucent borders do not punch transparent holes in opaque fills.
        float srcA = u_BorderColor.a * borderMask;
        float dstA = outAlpha;
        float combinedA = srcA + dstA * (1.0 - srcA);
        if (combinedA > 0.0001) {
            outRgb = (u_BorderColor.rgb * srcA + outRgb * dstA * (1.0 - srcA)) / combinedA;
        } else {
            outRgb = vec3(0.0);
        }
        outAlpha = combinedA;
    }

    float finalAlpha = outAlpha * shapeAlpha;
    if (finalAlpha <= 0.001) {
        discard;
    }

    gl_FragColor = vec4(outRgb, finalAlpha);
}
