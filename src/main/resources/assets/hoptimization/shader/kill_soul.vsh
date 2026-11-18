#version 120

varying vec3 v_EyeNormal;
varying vec3 v_EyePos;
varying vec3 v_LitColor;
varying vec3 v_VertexColor;
varying float v_LightFactor;
varying float v_VertexAlpha;

void main() {
    // Use ftransform() for exact depth invariance with the fixed-function depth prepass (GL_EQUAL).
    gl_Position = ftransform();
    gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
    gl_TexCoord[1] = gl_TextureMatrix[1] * gl_MultiTexCoord1;

    vec4 eyePos4 = gl_ModelViewMatrix * gl_Vertex;
    v_EyePos = eyePos4.xyz;

    vec3 rawNormal = gl_NormalMatrix * gl_Normal;
    float normalLenSq = dot(rawNormal, rawNormal);
    vec3 eyeNormal = (normalLenSq > 0.000001) ? (rawNormal * inversesqrt(normalLenSq)) : vec3(0.0, 0.0, 1.0);
    v_EyeNormal = eyeNormal;

    vec3 l0 = gl_LightSource[0].position.xyz;
    vec3 l1 = gl_LightSource[1].position.xyz;
    float l0LenSq = dot(l0, l0);
    float l1LenSq = dot(l1, l1);
    float diff0 = (l0LenSq > 0.000001) ? max(dot(eyeNormal, l0 * inversesqrt(l0LenSq)), 0.0) : 0.0;
    float diff1 = (l1LenSq > 0.000001) ? max(dot(eyeNormal, l1 * inversesqrt(l1LenSq)), 0.0) : 0.0;

    vec3 lighting = gl_LightModel.ambient.rgb
        + gl_LightSource[0].ambient.rgb + gl_LightSource[0].diffuse.rgb * diff0
        + gl_LightSource[1].ambient.rgb + gl_LightSource[1].diffuse.rgb * diff1;
    lighting = clamp(lighting, 0.0, 1.0);

    v_VertexColor = gl_Color.rgb;
    v_VertexAlpha = gl_Color.a;
    v_LitColor = lighting * gl_Color.rgb;
    v_LightFactor = clamp(dot(lighting, vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
}
