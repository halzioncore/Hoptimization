#version 120

uniform sampler2D u_Texture;
uniform vec2 u_TexelSize;
uniform vec2 u_Direction;
uniform float u_Radius;

varying vec2 v_TexCoord;

void main() {
    vec2 stepUv = u_TexelSize * u_Direction * max(u_Radius, 0.5);
    vec3 sum = texture2D(u_Texture, v_TexCoord).rgb * 0.227027027;
    sum += texture2D(u_Texture, v_TexCoord + stepUv * 1.3846153846).rgb * 0.3162162162;
    sum += texture2D(u_Texture, v_TexCoord - stepUv * 1.3846153846).rgb * 0.3162162162;
    sum += texture2D(u_Texture, v_TexCoord + stepUv * 3.2307692308).rgb * 0.0702702703;
    sum += texture2D(u_Texture, v_TexCoord - stepUv * 3.2307692308).rgb * 0.0702702703;
    gl_FragColor = vec4(sum, 1.0);
}
