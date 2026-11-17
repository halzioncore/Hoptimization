#version 120

uniform vec2 u_ViewportSize;

varying vec2 v_LocalPos;
varying float v_PixelScale;

void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    v_LocalPos = gl_MultiTexCoord0.xy;

    // Derive physical pixels per logical unit from the active MVP matrix so
    // SDF anti-aliasing stays 1px crisp under uiScale, zoom animations, or previews.
    vec2 ndcX = (gl_ModelViewProjectionMatrix * vec4(1.0, 0.0, 0.0, 0.0)).xy;
    vec2 ndcY = (gl_ModelViewProjectionMatrix * vec4(0.0, 1.0, 0.0, 0.0)).xy;
    vec2 pxX = ndcX * 0.5 * u_ViewportSize;
    vec2 pxY = ndcY * 0.5 * u_ViewportSize;
    v_PixelScale = max(0.5 * (length(pxX) + length(pxY)), 0.0001);
}
