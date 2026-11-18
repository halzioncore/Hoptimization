#version 120

uniform sampler2D u_Texture;
uniform vec3 u_BaseColor;
uniform float u_Opacity;
uniform float u_Posterize;
uniform float u_IsOccluded;
uniform float u_LightMix;

varying vec4 v_VertexColor;
varying float v_FaceShade;

void main() {
    vec4 tex = texture2D(u_Texture, gl_TexCoord[0].st);
    if (tex.a < 0.004) {
        discard;
    }

    float texLum = dot(clamp(tex.rgb * v_VertexColor.rgb, 0.0, 1.0), vec3(0.299, 0.587, 0.114));
    float lum = clamp(mix(texLum, v_FaceShade, clamp(u_LightMix, 0.0, 0.5)), 0.0, 1.0);

    vec3 shadowCol = u_BaseColor * 0.20 + vec3(0.05, 0.05, 0.07);
    vec3 midDarkCol = mix(shadowCol, u_BaseColor * 0.72, 0.68);
    vec3 midLightCol = u_BaseColor * 0.94;
    vec3 highCol = mix(u_BaseColor, vec3(0.92, 0.93, 0.95), 0.42);

    vec3 monoRgb;
    if (u_Posterize > 0.5) {
        float t1 = smoothstep(0.20, 0.27, lum);
        float t2 = smoothstep(0.47, 0.54, lum);
        float t3 = smoothstep(0.75, 0.82, lum);
        monoRgb = mix(shadowCol, midDarkCol, t1);
        monoRgb = mix(monoRgb, midLightCol, t2);
        monoRgb = mix(monoRgb, highCol, t3);
    } else {
        float s1 = clamp(lum / 0.35, 0.0, 1.0);
        float s2 = clamp((lum - 0.35) / 0.33, 0.0, 1.0);
        float s3 = clamp((lum - 0.68) / 0.32, 0.0, 1.0);
        monoRgb = mix(shadowCol, midDarkCol, s1);
        monoRgb = mix(monoRgb, midLightCol, s2);
        monoRgb = mix(monoRgb, highCol, s3);
    }

    if (u_IsOccluded > 0.5) {
        monoRgb = mix(monoRgb, u_BaseColor * 0.35 + vec3(0.06), 0.45);
    }

    float outAlpha = clamp(tex.a * v_VertexColor.a * u_Opacity, 0.0, 1.0);
    if (outAlpha <= 0.002) {
        discard;
    }

    gl_FragColor = vec4(monoRgb, outAlpha);
}
