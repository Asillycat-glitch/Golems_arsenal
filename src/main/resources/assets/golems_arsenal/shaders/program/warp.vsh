#version 150

// 与 vanilla 的 blit.vsh 同构：PostPass 传进来的 Position 是像素坐标（0..宽/高），
// ProjMat 是 0..宽/高 的正交矩阵。不要自己改 gl_Position 的 z —— 0.2 是沿用的固定值。
in vec4 Position;

uniform mat4 ProjMat;
uniform vec2 OutSize;

out vec2 texCoord;

void main() {
    vec4 outPos = ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);

    // 纹理坐标原点在左下，和 main framebuffer 的存储方向一致（vanilla 后处理也这么取）。
    texCoord = Position.xy / OutSize;
}
