#version 120

uniform float u_Time;
uniform float u_ScanlineStrength;
uniform float u_FresnelStrength;
uniform float u_ShadeContrast;

varying vec3 v_EyeNormal;
varying vec3 v_EyePos;
varying vec3 v_LocalPos;
varying vec4 v_Color;

void main() {
    if (v_Color.a <= 0.002) {
        discard;
    }

    float nLenSq = dot(v_EyeNormal, v_EyeNormal);
    float vLenSq = dot(v_EyePos, v_EyePos);
    vec3 N = (nLenSq > 0.000001) ? (v_EyeNormal * inversesqrt(nLenSq)) : vec3(0.0, 0.0, 1.0);
    vec3 V = (vLenSq > 0.000001) ? (-v_EyePos * inversesqrt(vLenSq)) : vec3(0.0, 0.0, 1.0);

    // Pre-normalized tactical key light in eye space (upper-front-right) for calm two-tone facets.
    vec3 L = vec3(0.3196, 0.7791, 0.5394);
    float rawBand = clamp(dot(N, L) * 0.5 + 0.5, 0.0, 1.0);
    float twoTone = mix(rawBand, smoothstep(0.32, 0.66, rawBand), 0.72);
    float floorShade = clamp(1.0 - clamp(u_ShadeContrast, 0.0, 0.45), 0.55, 1.0);
    float facetShade = mix(floorShade, 1.04, twoTone);

    float ndotv = clamp(abs(dot(N, V)), 0.0, 1.0);
    float fresnel = pow(max(1.0 - ndotv, 0.0001), 2.4);

    vec3 rimColor = mix(v_Color.rgb, vec3(1.0), 0.55);
    vec3 outRgb = v_Color.rgb * facetShade;
    outRgb = mix(outRgb, rimColor, clamp(fresnel * u_FresnelStrength, 0.0, 0.85));

    float scanStrength = clamp(u_ScanlineStrength, 0.0, 1.0);
    float scanBoost = 0.0;
    float scanAlphaScale = 1.0;
    if (scanStrength > 0.001) {
        // Scroll upward along -Y in 1/16 MC model space for crisp cockpit HUD scanlines.
        float wave = 0.5 + 0.5 * sin(v_LocalPos.y * 3.8 + u_Time * 4.2);
        float band = smoothstep(0.56, 0.98, wave);
        scanBoost = band * 0.14 * scanStrength;
        scanAlphaScale = 1.0 - (1.0 - band) * 0.08 * scanStrength;
    }

    outRgb = clamp(outRgb + rimColor * scanBoost, 0.0, 1.0);
    float outAlpha = clamp(v_Color.a * scanAlphaScale + fresnel * 0.12 * u_FresnelStrength * v_Color.a, 0.0, 1.0);
    if (outAlpha <= 0.002) {
        discard;
    }

    gl_FragColor = vec4(outRgb, outAlpha);
}
