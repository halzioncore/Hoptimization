#version 120

varying vec3 v_ModelPos;
varying vec3 v_Normal;
varying vec3 v_ViewDir;

uniform vec3 u_Color;
uniform float u_Density;
uniform float u_FlowPhase;
uniform float u_GlowStrength;
uniform float u_FillAlpha;

void main() {
    vec3 N = normalize(v_Normal);
    vec3 V = normalize(v_ViewDir);
    float ndotv = clamp(abs(dot(N, V)), 0.0, 1.0);
    float fresnel = pow(1.0 - ndotv, 2.15);

    // Smooth 3D topographic elevation field (mechanical-keyboard contour style)
    vec3 p = v_ModelPos * 0.14;
    float elevation = p.y * 0.85
        + sin(p.x * 2.3 + p.z * 1.7 + u_FlowPhase * 0.65) * 0.45
        + cos(p.z * 2.1 - p.x * 1.4 - u_FlowPhase * 0.45) * 0.40
        + sin((p.x + p.y) * 1.55 + u_FlowPhase * 0.35) * 0.25;

    float phase = elevation * u_Density + u_FlowPhase * 0.14;
    float distToLine = abs(fract(phase) - 0.5);
    float contour = 1.0 - smoothstep(0.032, 0.115, distToLine);
    float contourHalo = (1.0 - smoothstep(0.05, 0.26, distToLine)) * 0.35;

    float lineAlpha = (contour * 0.88 + contourHalo) * (0.45 + 0.55 * u_GlowStrength);
    float rimAlpha = fresnel * (0.55 * u_GlowStrength + 0.20);
    float alpha = clamp(u_FillAlpha + lineAlpha + rimAlpha, 0.0, 0.96);

    vec3 brightLine = mix(u_Color, vec3(1.0), 0.38);
    vec3 rgb = mix(u_Color * 0.82, brightLine, clamp(contour * 0.85 + fresnel * 0.45, 0.0, 1.0));
    gl_FragColor = vec4(rgb, alpha);
}
