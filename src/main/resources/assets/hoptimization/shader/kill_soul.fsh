#version 120

uniform sampler2D u_Texture;
uniform sampler2D u_Lightmap;
uniform int u_UseLightmap;
uniform vec4 u_SoulColor;
uniform float u_SoulMix;
uniform float u_Alpha;
uniform float u_Flash;

varying vec3 v_EyeNormal;
varying vec3 v_EyePos;
varying vec3 v_LitColor;
varying vec3 v_VertexColor;
varying float v_LightFactor;
varying float v_VertexAlpha;

void main() {
    vec4 tex = texture2D(u_Texture, gl_TexCoord[0].st);
    if (tex.a < 0.004) {
        discard;
    }

    vec3 lightmap = (u_UseLightmap != 0) ? texture2D(u_Lightmap, gl_TexCoord[1].st).rgb : vec3(1.0);
    vec3 litBase = tex.rgb * v_LitColor * lightmap;

    vec3 outRgb;
    float outAlpha = tex.a * v_VertexAlpha * u_Alpha;

    if (u_SoulMix > 0.0) {
        vec3 baseSample = tex.rgb * v_VertexColor;
        float lum = dot(baseSample, vec3(0.299, 0.587, 0.114));
        // Lift deep shadows while keeping bright-vs-dark contrast so eyes and facial details remain readable.
        float lifted = mix(0.30, 1.0, pow(max(lum, 0.0001), 0.72));
        float formShade = mix(0.76, 1.0, v_LightFactor);
        vec3 soulTone = u_SoulColor.rgb * lifted * formShade;

        outRgb = mix(litBase, soulTone, clamp(u_SoulMix, 0.0, 1.0));

        float nLenSq = dot(v_EyeNormal, v_EyeNormal);
        float vLenSq = dot(v_EyePos, v_EyePos);
        vec3 N = (nLenSq > 0.000001) ? (v_EyeNormal * inversesqrt(nLenSq)) : vec3(0.0, 0.0, 1.0);
        vec3 V = (vLenSq > 0.000001) ? (-v_EyePos * inversesqrt(vLenSq)) : vec3(0.0, 0.0, 1.0);
        float ndotv = clamp(abs(dot(N, V)), 0.0, 1.0);
        float fresnel = pow(max(1.0 - ndotv, 0.0001), 2.2);

        vec3 rimColor = mix(u_SoulColor.rgb, vec3(1.0), 0.35);
        outRgb += rimColor * (fresnel * 0.45);

        float flash = clamp(u_Flash, 0.0, 1.0);
        vec3 flashColor = mix(u_SoulColor.rgb, vec3(1.0), 0.70);
        outRgb = mix(outRgb, flashColor, flash * 0.72) + flashColor * (flash * 0.25);

        outAlpha = clamp(outAlpha * (1.0 + fresnel * 0.15), 0.0, 1.0);
    } else {
        outRgb = litBase;
        outAlpha = clamp(outAlpha, 0.0, 1.0);
    }

    if (outAlpha < 0.002) {
        discard;
    }

    gl_FragColor = vec4(outRgb, outAlpha);
}
