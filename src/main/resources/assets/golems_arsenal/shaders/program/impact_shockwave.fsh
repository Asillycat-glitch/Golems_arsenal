#version 150

/*
 * 无色冲击波：只对背景做屏幕空间折射，不添加任何颜色。
 * 环的半径由 CPU 投影后的 CenterUV / ScreenRadius 给出。
 */

uniform sampler2D DiffuseSampler;

uniform vec2 CenterUV;
uniform float ScreenRadius;
uniform float Intensity;
uniform float Aspect;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 passthrough = texture(DiffuseSampler, texCoord).rgb;

    if (Intensity <= 0.001 || ScreenRadius <= 0.0001) {
        fragColor = vec4(passthrough, 1.0);
        return;
    }

    vec2 d = (texCoord - CenterUV) * vec2(Aspect, 1.0);
    float r = length(d) / ScreenRadius;

    // 只在轮廓附近取一圈很窄的环：内部和外部都保持原画面。
    float ring = smoothstep(0.78, 0.96, r) * (1.0 - smoothstep(1.00, 1.12, r));

    vec2 dir = normalize(d + vec2(1.0E-6, 0.0));
    float amount = 0.075 * Intensity * ring;
    vec2 distorted = clamp(texCoord + dir * amount, vec2(0.001), vec2(0.999));

    vec3 refracted = texture(DiffuseSampler, distorted).rgb;
    fragColor = vec4(mix(passthrough, refracted, Intensity), 1.0);
}
