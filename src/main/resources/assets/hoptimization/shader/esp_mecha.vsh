#version 120

varying vec3 v_EyeNormal;
varying vec3 v_EyePos;
varying vec3 v_LocalPos;
varying vec4 v_Color;

void main() {
    // Use ftransform() for exact depth invariance with the fixed-function depth prepass.
    gl_Position = ftransform();
    v_EyeNormal = gl_NormalMatrix * gl_Normal;
    v_EyePos = (gl_ModelViewMatrix * gl_Vertex).xyz;
    v_LocalPos = gl_Vertex.xyz;
    v_Color = gl_Color;
}
