#version 120

varying vec4 v_VertexColor;
varying float v_FaceShade;

void main() {
    // Use ftransform() for exact depth invariance with the fixed-function depth prepass (GL_EQUAL).
    gl_Position = ftransform();
    gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
    v_VertexColor = gl_Color;

    vec3 rawNormal = gl_NormalMatrix * gl_Normal;
    float normalLenSq = dot(rawNormal, rawNormal);
    vec3 N = (normalLenSq > 0.000001) ? (rawNormal * inversesqrt(normalLenSq)) : vec3(0.0, 1.0, 0.0);

    vec3 rawLight = vec3(0.28, 0.78, 0.56);
    float lightLenSq = dot(rawLight, rawLight);
    vec3 L = (lightLenSq > 0.000001) ? (rawLight * inversesqrt(lightLenSq)) : vec3(0.0, 1.0, 0.0);

    v_FaceShade = clamp(dot(N, L) * 0.5 + 0.5, 0.0, 1.0);
}
