#version 120

varying vec3 v_ModelPos;
varying vec3 v_Normal;
varying vec3 v_ViewDir;

void main() {
    v_ModelPos = gl_Vertex.xyz;
    vec4 viewPos = gl_ModelViewMatrix * gl_Vertex;
    v_ViewDir = -viewPos.xyz;
    v_Normal = gl_NormalMatrix * gl_Normal;
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
}
