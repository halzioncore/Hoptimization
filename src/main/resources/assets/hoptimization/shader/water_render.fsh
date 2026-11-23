#version 120

uniform sampler2D u_StateTex;
uniform vec2 u_TexelSize;
uniform vec4 u_TopColor;
uniform vec4 u_BottomColor;
uniform vec4 u_AccentColor;
uniform float u_IsLight;

varying vec2 v_TexCoord;

void main() {
    float hL = texture2D(u_StateTex, v_TexCoord - vec2(u_TexelSize.x, 0.0)).r * 2.0 - 1.0;
    float hR = texture2D(u_StateTex, v_TexCoord + vec2(u_TexelSize.x, 0.0)).r * 2.0 - 1.0;
    float hD = texture2D(u_StateTex, v_TexCoord - vec2(0.0, u_TexelSize.y)).r * 2.0 - 1.0;
    float hU = texture2D(u_StateTex, v_TexCoord + vec2(0.0, u_TexelSize.y)).r * 2.0 - 1.0;

    vec2 grad = vec2(hR - hL, hU - hD);
    vec3 normal = normalize(vec3(-grad.x * 2.8, -grad.y * 2.8, 1.0));

    // Calm vertical base gradient (v_TexCoord.y = 1 at top, 0 at bottom).
    vec3 baseRgb = mix(u_BottomColor.rgb, u_TopColor.rgb, v_TexCoord.y);

    // Subtle top-down directional specular + theme-tinted caustic crest on ripples.
    vec3 lightDir = normalize(vec3(-0.35, 0.55, 0.75));
    vec3 viewDir = vec3(0.0, 0.0, 1.0);
    vec3 halfVec = normalize(lightDir + viewDir);
    float spec = pow(max(dot(normal, halfVec), 0.0), 28.0);
    float crest = clamp(length(grad) * 2.4, 0.0, 1.0);

    float tintStrength = mix(0.28, 0.18, u_IsLight);
    vec3 rippleTint = mix(baseRgb, u_AccentColor.rgb, crest * tintStrength);
    vec3 outRgb = clamp(rippleTint + u_AccentColor.rgb * (spec * 0.36) + vec3(spec * 0.14), 0.0, 1.0);

    gl_FragColor = vec4(outRgb, 1.0);
}
