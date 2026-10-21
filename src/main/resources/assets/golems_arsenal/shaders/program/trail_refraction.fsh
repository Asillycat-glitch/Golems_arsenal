#version 150

/*
 * 弹体拖尾的空气折射：不添加颜色，只把已经渲染好的背景沿拖尾法线方向
 * 做低频波浪偏移。看起来是热空气把弹体拖尾/背景折弯，而不是闪电。
 */

uniform sampler2D DiffuseSampler;

uniform vec2 TrailUV0;
uniform vec2 TrailUV1;
uniform float Width;
uniform float Intensity;
uniform float Aspect;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 passthrough = texture(DiffuseSampler, texCoord).rgb;

    if (Intensity <= 0.001 || Width <= 0.0001) {
        fragColor = vec4(passthrough, 1.0);
        return;
    }

    vec2 ab = TrailUV1 - TrailUV0;
    float len2 = dot(ab, ab);
    float t = len2 <= 1.0E-7 ? 0.0 : clamp(dot(texCoord - TrailUV0, ab) / len2, 0.0, 1.0);
    vec2 closest = TrailUV0 + ab * t;

    vec2 delta = (texCoord - closest) * vec2(Aspect, 1.0);
    float dist = length(delta);
    float mask = 1.0 - smoothstep(0.0, Width, dist);
    if (mask <= 0.001) {
        fragColor = vec4(passthrough, 1.0);
        return;
    }

    vec2 dir = normalize(delta + vec2(1.0E-6, 0.0));
    // 两层不同频率：一层慢的“热浪”，一层快的“抖动”
    float slow = sin(texCoord.y * 95.0 + Time * 9.0 + sin(texCoord.x * 38.0) * 2.4);
    float fast = sin(texCoord.y * 260.0 - Time * 23.0 + texCoord.x * 90.0);
    float amount = (slow * 0.009 + fast * 0.003) * Intensity * mask;

    vec2 distorted = clamp(texCoord + dir * amount, vec2(0.001), vec2(0.999));
    vec3 refracted = texture(DiffuseSampler, distorted).rgb;

    fragColor = vec4(mix(passthrough, refracted, Intensity * mask), 1.0);
}
