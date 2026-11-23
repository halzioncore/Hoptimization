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
    float fresnel = pow(1.0 - ndotv, 2.0);

    // Dense 3D domain-warped topographic contour cracks across head, torso, and limbs
    vec3 p = v_ModelPos * 0.52;
    vec3 w = p + vec3(
        sin(p.y * 1.9 + p.z * 1.4 + u_FlowPhase * 0.45) * 0.65,
        cos(p.z * 1.8 - p.x * 1.5 - u_FlowPhase * 0.35) * 0.65,
        sin(p.x * 1.7 + p.y * 1.6 + u_FlowPhase * 0.40) * 0.65
    );
    float field = sin(w.x * 1.35 + w.y * 0.95) * 0.55
                + cos(w.y * 1.65 - w.z * 1.25) * 0.45
                + sin((w.x + w.z) * 2.15 + w.y * 0.70) * 0.30;

    float phase = field * (u_Density * 2.1) + u_FlowPhase * 0.12;
    float dist = abs(fract(phase) - 0.5);

    // Razor-sharp luminous crack filament + soft surrounding glow
    float filament = 1.0 - smoothstep(0.015, 0.068, dist);
    float lineGlow = (1.0 - smoothstep(0.040, 0.220, dist)) * 0.48;

    float lineAlpha = (filament * 0.95 + lineGlow) * (0.45 + 0.55 * u_GlowStrength);
    float rimAlpha = fresnel * (0.62 * u_GlowStrength + 0.22);
    float alpha = clamp(u_FillAlpha + lineAlpha + rimAlpha, 0.0, 0.98);

    vec3 haloColor = mix(u_Color, vec3(1.0), 0.32);
    vec3 coreColor = mix(u_Color, vec3(1.0), 0.74);
    vec3 rgb = mix(haloColor, coreColor, clamp(filament * 0.92 + fresnel * 0.35, 0.0, 1.0));
    gl_FragColor = vec4(rgb, alpha);
}
