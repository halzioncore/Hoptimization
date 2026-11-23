#version 120

uniform sampler2D u_StateTex;
uniform vec2 u_TexelSize;
uniform vec2 u_Aspect;
uniform vec2 u_MousePos;
uniform vec2 u_PrevMousePos;
uniform float u_MouseMoved;
uniform float u_BrushRadius;

varying vec2 v_TexCoord;

float distToSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float denom = max(dot(ba, ba), 1e-6);
    float h = clamp(dot(pa, ba) / denom, 0.0, 1.0);
    return length(pa - ba * h);
}

void main() {
    vec2 state = texture2D(u_StateTex, v_TexCoord).rg;
    float curr = state.r * 2.0 - 1.0;
    float prev = state.g * 2.0 - 1.0;

    float n = texture2D(u_StateTex, v_TexCoord + vec2(0.0, u_TexelSize.y)).r * 2.0 - 1.0;
    float s = texture2D(u_StateTex, v_TexCoord - vec2(0.0, u_TexelSize.y)).r * 2.0 - 1.0;
    float e = texture2D(u_StateTex, v_TexCoord + vec2(u_TexelSize.x, 0.0)).r * 2.0 - 1.0;
    float w = texture2D(u_StateTex, v_TexCoord - vec2(u_TexelSize.x, 0.0)).r * 2.0 - 1.0;

    float avg = (n + s + e + w) * 0.25;
    float next = (avg * 2.0 - prev) * 0.986;

    if (u_MouseMoved > 0.5) {
        vec2 p = v_TexCoord * u_Aspect;
        vec2 a = u_PrevMousePos * u_Aspect;
        vec2 b = u_MousePos * u_Aspect;
        float d = distToSegment(p, a, b);
        if (d < u_BrushRadius) {
            float falloff = smoothstep(u_BrushRadius, 0.0, d);
            next += falloff * 0.34;
        }
    }

    next = clamp(next, -1.0, 1.0);
    gl_FragColor = vec4(next * 0.5 + 0.5, curr * 0.5 + 0.5, 0.0, 1.0);
}
