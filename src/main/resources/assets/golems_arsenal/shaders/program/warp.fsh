#version 150

/*
 * 屏幕空间「空间凹陷 / 背景折射」测试 shader。
 *
 * 两种模式（uniform Mode）：
 *   0 = 球形折射：按像素射线与球求交，只有"球在背景前面"的像素才偏移；
 *   1 = 热浪：屏幕下半部分做低频 UV 抖动（余热那一秒的空气翻腾）。
 *
 * 坐标约定：
 *   texCoord 的原点在左下（与 main framebuffer 一致）；
 *   CamForward/CamRight/CamUp 是世界空间的相机基向量；
 *   DepthSampler 是 AFTER_PARTICLES 阶段拷下来的主深度（fabulous 下 AFTER_LEVEL 的深度已被覆盖）。
 */

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;

uniform vec2 OutSize;
uniform float Time;

uniform float Mode;
uniform float UseDepth;
uniform vec3 Center;
uniform float Radius;
uniform float Strength;
uniform float Progress;
uniform vec2 CenterUV;
uniform float ScreenRadius;

uniform vec3 CamPos;
uniform vec3 CamForward;
uniform vec3 CamRight;
uniform vec3 CamUp;
uniform float TanHalfFov;
uniform float Aspect;
uniform float Near;
uniform float Far;

in vec2 texCoord;
out vec4 fragColor;

float linearDepth(float depth) {
    float ndc = depth * 2.0 - 1.0;
    return (2.0 * Near * Far) / (Far + Near - ndc * (Far - Near));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

void main() {
    vec2 uv = texCoord;
    vec3 color = texture(DiffuseSampler, uv).rgb;

    if (Mode < 0.5) {
        // ------------------------------------------------ 球形背景折射
        // 深度拷贝失败时（比如主目标被别的模组改成了 stencil 格式），退化成"不遮挡"：
        // 球内的背景一律折射，虽然会穿墙，但至少不是完全没效果。
        float sceneZ = UseDepth > 0.5 ? linearDepth(texture(DepthSampler, uv).r) : Far;

        vec2 ndc = uv * 2.0 - 1.0;
        vec3 dir = normalize(CamForward
                + CamRight * (ndc.x * TanHalfFov * Aspect)
                + CamUp * (ndc.y * TanHalfFov));
        float cosF = max(dot(dir, CamForward), 0.0001);
        float sceneT = sceneZ / cosF;

        vec3 oc = CamPos - Center;
        float b = dot(dir, oc);
        float c = dot(oc, oc) - Radius * Radius;
        float disc = b * b - c;

        float mask = 0.0;
        float chord = 0.0;
        if (disc > 0.0) {
            float sq = sqrt(disc);
            float t0 = -b - sq;
            float t1 = -b + sq;
            if (t1 > 0.0) {
                float front = max(t0, 0.0);
                // 背景必须比球的近表面更远，才说明这一像素是"透过球看过去"的。
                // 球与地面相交的地方会自然被地面挡住（delta 很小 → 0），不会把地面一起扭了。
                float delta = sceneT - front;
                mask = smoothstep(0.0, Radius * 0.12, delta);
                chord = clamp((t1 - front) / (2.0 * Radius), 0.0, 1.0);
            }
        }
        if (mask <= 0.0) {
            fragColor = vec4(color, 1.0);
            return;
        }

        vec2 d = (uv - CenterUV) * vec2(Aspect, 1.0);
        float prof = clamp(length(d) / max(ScreenRadius, 1.0E-5), 0.0, 1.0);
        // 0 at center and edge, peaks around 0.57: 中心不位移，边缘平滑收口
        float lens = prof * (1.0 - prof * prof);
        vec2 radial = normalize(d + vec2(1.0E-6, 0.0));

        // 采样更靠外的像素 → 背景看起来被"压"向球心
        float amount = Strength * lens * mask * pow(chord, 0.7) * Progress;
        vec2 src = uv + radial * amount;

        // 轻微色散：R/B 的偏移略大于 G，玻璃/空间折射的观感主要来自这一点
        vec3 refracted;
        refracted.r = texture(DiffuseSampler, src + radial * amount * 0.10).r;
        refracted.g = texture(DiffuseSampler, src).g;
        refracted.b = texture(DiffuseSampler, src - radial * amount * 0.10).b;

        // 冷色边缘 + 中心轻微压暗，让球壳读起来像"空间本身凹下去"
        float rim = smoothstep(0.80, 0.98, prof) * (1.0 - smoothstep(1.00, 1.12, prof));
        refracted += vec3(0.10, 0.24, 0.45) * rim * 0.55 * Progress;
        float inner = 1.0 - smoothstep(0.0, 1.0, prof);
        refracted *= mix(1.0, 0.92, inner * 0.25);

        color = mix(color, refracted, clamp(mask * Progress, 0.0, 1.0));
    } else {
        // ------------------------------------------------ 余热 / 热浪
        // y=0 是屏幕底部；越低越强，到 0.62 以上完全消失（不能把天空也扭了）。
        float band = 1.0 - smoothstep(0.0, 0.62, uv.y);
        float n1 = noise(vec2(uv.x * 14.0, uv.y * 9.0 - Time * 1.6));
        float n2 = noise(vec2(uv.x * 31.0 + 7.0, uv.y * 21.0 - Time * 2.7));
        vec2 off = vec2((n1 - 0.5) * 2.0, (n2 - 0.5)) * 0.0075 * band * Progress;
        vec2 src = uv + off;

        vec3 refracted;
        refracted.r = texture(DiffuseSampler, src + off * 0.6).r;
        refracted.g = texture(DiffuseSampler, src).g;
        refracted.b = texture(DiffuseSampler, src - off * 0.6).b;

        color = mix(color, refracted, band * Progress * 0.9);
    }

    fragColor = vec4(color, 1.0);
}
