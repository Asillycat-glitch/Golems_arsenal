# 背景折射试验台（分支 `experiment/background-refraction`）

> **这是测试版特效，不接入任何正式技能。**
> 天基激光的落点冲击仍然是原来的球 / 环 / 火星；本分支只回答一个问题：
> **“背景被空间推开”这件事，在当前整合包里到底能做到什么程度。**
>
> 分支：`experiment/background-refraction`（从 `main` 切出）
> 触发：客户端命令 `/gafx ...`，不经过服务端、不改伤害、不改判定。

---

## 1. 怎么用

把 `build/libs/golems_arsenal-0.3.jar` 丢进整合包的 `mods/`，进游戏后：

```
/gafx warp              # 真·屏幕空间折射（默认半径 6，4 秒）
/gafx warp 14 6         # 半径 14，6 秒
/gafx shell             # 纯几何球壳（不需要后处理）
/gafx flow              # 向内收束的彩色光流
/gafx heat              # 余热空气翻腾（屏幕下半部分）
/gafx all               # 折射 + 球壳 + 光流，一起看
/gafx off               # 关掉
/gafx status            # 当前模式 / 后处理管线 / 光影护栏状态
/gafx bypass            # 开/关 Oculus 护栏（强行试后处理）
```

落点规则：**准星 24 格内命中的第一个方块/实体**；什么都没指到就放在眼前 8 格。
所以：

- 对着**远处地面**按 → 球心贴地，看到的是“扣在地上的半个罩子”；
- 对着天空按 → 球悬在眼前；
- 站得离球心太近（距离 < 半径）→ 相机会在球内，整个屏幕都会扭。想看清边界，站到半径外。

`radius` 参数范围 1–32，`seconds` 参数范围 1–60。默认半径 6、4 秒。

---

## 2. 四种模式分别在验证什么

| 模式 | 技术 | 验证的问题 | 光影下 |
|---|---|---|---|
| `warp` | 拷贝主画面 + 深度，`PostPass` 全屏 shader 偏移 UV | 后处理管线能不能用、深度遮挡对不对、观感像不像“空间被压凹” | 默认拒绝，`bypass` 可强试 |
| `shell` | 纯几何球壳（菲涅耳亮边） | 不做折射时，**边界 = 伤害半径** 这件事能不能读出来 | 应该能用 |
| `flow` | 纯几何向内螺旋光流 | 没有后处理时，怎么用“流向”表达“空间在收” | 应该能用 |
| `heat` | 屏幕空间噪声扰动（下半屏） | 余热那 1 秒的“空气还没凉”能不能不用倒计时图标表达 | 默认拒绝，`bypass` 可强试 |
| `all` | `warp` + `shell` + `flow` | 组合起来是不是比单独一层更像“炮造成的空间凹陷” | 默认拒绝 |

### 看 `warp` 时重点检查

1. **球内**的背景网格/方块边缘有没有被压向球心（不是整屏错位）；
2. **球外**应该完全不动；
3. 贴着地面的球，**地面以下**不应该把地面扭了（这是深度遮挡在起作用）；
4. 球的轮廓有没有一条冷色亮边（区分“玻璃球”和“空气扭曲”）；
5. 转动视角时，扭曲区域应该**锁在世界坐标的球上**，而不是跟着屏幕走。

### 看 `shell` / `flow` 时重点检查

1. 球壳边界和 `/gafx shell <半径>` 给的半径是否一致（这是“边界 = 伤害范围”的核心）；
2. 球心贴地时，地面以下的部分应该被真实深度挡住，只看到上半球；
3. 光流的方向是不是**向心**的，而不是向外炸。

---

## 3. 技术路线（和最初方案的差异）

最初的计划是 `ShaderInstance` + 自己搭 `RenderTarget` + 手写全屏 quad。实际实现换成了：

```
AFTER_PARTICLES
  ├─ 画球壳 / 光流（真实深度还在，能被地面挡住）
  └─ 把主目标深度 copyDepthFrom 到自己的 TextureTarget
AFTER_LEVEL
  └─ PostPass("golems_arsenal:warp", main → swap)
     PostPass("blit",               swap → main)
```

改成 `PostPass` 的原因：

- 全屏 quad、正交矩阵、`DiffuseSampler`、`InSize/OutSize/Time` 这些样板它都处理好了；
- `addAuxAsset("DepthSampler", ...)` 可以直接把深度纹理挂进 shader，
  不用自己算 FBO 附件和 sampler 单元；
- 主画面 → `swap` → 主画面，天然避开“采样正在渲染的纹理”的反馈环。

**深度为什么要在 `AFTER_PARTICLES` 先拷一份**：
fabulous 画质下，`GameRenderer` 会在 `AFTER_LEVEL` 之前用透明链把画面合回主目标，
那一趟全屏 pass 会把主目标深度覆盖掉。等 `AFTER_LEVEL` 再采样，深度已经是坏的。
如果深度拷贝失败（例如别的模组把主目标改成了 stencil 格式），
shader 会退化成“不做遮挡”——至少还有扭曲，只是会穿墙。日志里会有一条 `[GA-FX] 主目标深度无法拷贝`。

**资源重载**：按 F3+T 之后，旧的 `PostPass` 里的 program 会失效。
`RefractionReloadHandler` 会在重载后置一个标志，下一次渲染时在渲染线程里重建管线。
所以改 `warp.fsh` 后按 F3+T 就能生效，不用重启。

---

## 4. 光影（Oculus / Iris / OptiFine）

这些加载器会整体接管渲染管线，后处理可能被覆盖、黑屏或只剩一层 UI。
所以默认：

- 检测到 `oculus` / `iris` / `optifine` → `warp` / `all` / `heat` **直接拒绝**，并提示；
- `/gafx bypass` 可以强行试一次；
- `shell` / `flow` 不走后处理，应该仍然可用。

**如果 `warp` 在光影包里表现异常，不要花时间修 shader**——先确认 `/gafx bypass` 后是不是能出画面；
不能出就说明这条路线在这个整合包里不可用，回到 `shell` + `flow` 的组合。

---

## 5. 已知限制 / 下一步

### 限制

- **不是真的“折射率”**：是屏幕空间 UV 位移 + 深度遮挡，物理上不严格；
- 球心在相机背后、或球完全在屏幕外时，这一帧直接跳过（没有裁剪逻辑）；
- 深度拷贝只覆盖不透明场景；半透明物体（水、玻璃）不参与遮挡；
- `warp` 的强度是固定的 `0.115`（UV 单位），没有做距离衰减；近处大球会很夸张；
- `shell` / `flow` 是加法混合，重叠多的地方会偏亮；目前靠压低单层 alpha 控制。

### 下一步（按价值）

1. **先把 `shell` 接到天基激光的落点**：只改 `OrbitalImpactRenderer`，把“平面环”换/补成“球壳边界”，
   半径直接用 `OrbitalImpactEntity.radius()`，不动伤害逻辑。这是最稳、最解决“范围看不出来”的一步。
2. `flow` 只作为球壳的附属层（少量、向内），不要单独当主特效。
3. `warp` 等确认某个整合包/光影组合可用后，再考虑做成“可配置开关 + 自动降级”；
   不要在 `main` 里默认开。
4. 如果 `warp` 在无光影环境下观感明显更好，可以考虑：
   - 无 Oculus 时启用后处理；
   - 有 Oculus 时自动用 `shell` + `flow`。

---

## 6. 代码位置

```
src/main/java/a_silly_cat/golems_arsenal/client/refraction/
  RefractionFx.java            状态机 / 时间曲线 / 光影护栏 / tick
  RefractionCommand.java       /gafx 客户端命令
  RefractionRenderEvents.java  AFTER_PARTICLES（几何 + 深度拷贝）/ AFTER_LEVEL（后处理）
  RefractionWorldFx.java       球壳 + 向内光流（纯几何）
  RefractionPostFx.java        PostPass 管线 / 深度拷贝 / 资源重建
  RefractionReloadHandler.java F3+T 后标记重建

src/main/resources/assets/golems_arsenal/shaders/program/
  warp.json                    program 声明（uniform / sampler / blend）
  warp.vsh                     全屏 quad（与 vanilla blit.vsh 同构）
  warp.fsh                     球形折射 + 热浪
```

离线验证：`warp.vsh` / `warp.fsh` 已用一个独立的 LWJGL 3.2 上下文编译、链接、实际 draw 过
（AMD Radeon 780M，GL 3.2 Core），并用同一套 uniform 渲染了球面/热浪预览图；
这只能证明 shader 语法和执行没问题，**不能替代游戏内观感测试**。
