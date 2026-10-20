# 光影包下的光效过曝（MakeUp / Oculus）

> 现象：MakeUp-UltraFast-9.1e + Oculus 1.8.0 下，天基激光的引导光柱、落点白热球被放大成
> 纯白柱/白罩，白天也白成一片。日志里能看到
> `Using shaderpack: MakeUp-UltraFast-9.1e.zip`。

## 1. 根因

MakeUp 的 bloom 源在 `composite.fragment`：

```glsl
float bloom_luma = smoothstep(0.85, 1.0, luma(block_color.rgb * exposure)) * 0.5;
```

而 `composite.vertex` 的自动曝光是：

```glsl
exposure = clamp(luma(exposure_col), 0.0005, 100.0);
exposure = exp(-exposure) * 3.03 + 0.6;
exposure = mix(exposure, prev_exposure, exp(-frameTime * 1.25));
```

夜里场景 luma 很低，`exposure` 可以到 **3.6**；于是 bloom 阈值实际是：

```
luma(beam_color) > 0.85 / 3.6 ≈ 0.236
```

**只要光柱的最终颜色亮度超过 0.236，就会被当成 bloom 源**，而 bloom 的采样半径是
`0.1 * 屏幕高度`——一根细白柱会糊成一大片白。

我们的激光还是最坏情况：

- `RenderType.entityTranslucentEmissive`：全亮、不受环境光；
- 颜色是近白（`(1.0, 0.97, 0.86)`、`(1.0, 0.98, 0.90)`）；
- 几何**正反各画一遍**，同一像素叠两层 0.8~0.85 的 alpha，直接推到接近 1.0；
- 六条引导光 + 辉光层 + 亮芯 + 余晖，重叠更多层；
- 落点白热球 `BALL_ALPHA = 1.0`，整球所有面都画。

所以不是"某个颜色没调好"，是**近白 + 全亮 + 多层 alpha + bloom 阈值**四件事叠在一起。
这也是之前"改半透明混合"没用的原因：半透明只改变混合方式，没有把最终亮度降下来。

## 2. 这次改了什么

### 2.1 亮度倍率（可配置）

`Config` 新增：

```toml
[orbital_beam]
shader_effect_brightness = 0.35
```

`ShaderEffects` 通过 Iris API（Oculus 自带）检测**当前是否真的启用了光影包**；
没开光影时倍率固定 1.0，完全不影响原版观感。

- 默认 `0.35`：夜里引导光柱的最终 luma 大约 0.22~0.23，刚好压在 0.236 以下；
- 如果还白：`0.25` 甚至 `0.2`；
- 如果太暗：`0.45` / `0.55`；
- `1.0` = 关闭这个调整。

### 2.2 颜色往暖金拉

不是简单乘 alpha，而是把 G/B 压下去，保留 R：

```java
tintR = r;
tintG = g * 0.72;
tintB = b * 0.35;
```

这样白热芯变成金色，色相有余量，不会一过 threshold 就三通道同时饱和成白纸；
同时也保住了"白热芯 / 红热边"的温度层次（不是把所有颜色拉成同一个金）。

### 2.3 背面剔除

引导光柱、主光束、弹体、白热球都改成**只画朝向相机的那一半**：

- 以前正反各一遍，alpha 实际上翻倍；
- 现在一根柱子的每一像素只有一层，亮度直接减半；
- 球体也是前半球，不再前后叠加。

### 2.4 白热球 → 无色屏幕空间冲击波

`OrbitalImpactRenderer` 里原来的"白热球"已删除，落点不再有一个可见的光罩。

参考图 3–7 的连续过程是：**弹体下坠 → 命中 → 贴地扩散波 + 火花爆炸**；
图 5–7 里看起来像"罩子"的上半部分，其实是极亮的扩散波和火花在画面里重叠。
图 8 是它的**消失段**。图 2/7 里真正细微但关键的是**周围被推开的空气波**。

现在这部分由 `ImpactShockwaveFx` 完成，做法参考秘法涡流的 `warp_world`：

- 在 `AFTER_LEVEL` 用 `PostPass` 把主画面作为 `DiffuseSampler`，输出到 swap，再 blit 回主目标；
- CPU 把冲击点投影成 `CenterUV` / `ScreenRadius`，shader 只在投影圆环附近偏移 UV；
- **不添加任何颜色**，所以是"无色"的：只有背景被推开，没有白色/蓝色光罩；
- 圆环在约 55% 时长内扩到 **3× 冲击半径**（半径 14 时约 42 格），然后消失；
- `ViewportEvent.ComputeCameraAngles` 里有一次很短、随距离衰减的屏幕震动（前 40% 时长）。

这里没有用 3D 球壳/网格：玩家常常就站在落点上，网格球会把相机包在里面；
全屏圆环用投影半径驱动，站在落点上也仍然能看到一圈从脚下扩散出去。
`ShockwaveShell` / `shockWall` / 平面亮环都已删除。

`OrbitalImpactRenderer` 现在只负责：
- 弹体残留热痕（`drawTrail`）；
- 48 条向外/向上的火星（`drawSparks`）。

爆炸由实体自带的 `ParticleTypes.EXPLOSION` + 火星 + 热痕承担；冲击波不再抢颜色。

```text
0% ───── 55% ───── 100%   冲击波
  膨胀到 3R     淡出
0% ─ 40%                  屏幕震动（很短的一次）
```

**旧版本的 QUADS bug** 已不存在：`ShockwaveShell` 已删除，不再有可见球壳/三角面板。

## 3. 涉及的文件

```
Config.java                             新增 orbital_beam.shader_effect_brightness
client/ShaderEffects.java               光影检测 + 倍率 + 暖色 tint
client/ImpactShockwaveFx.java           屏幕拷贝 + 无色折射 + 屏幕震动
client/OrbitalImpactRenderer.java       只保留热痕和火星；白热球/光罩已删除
client/GuidanceBeamRenderer.java        压 alpha / 暖色 / 背面剔除
client/OrbitalBeamRenderer.java         同上
client/OrbitalProjectileRenderer.java   同上
client/BeaconPillarRenderer.java        受击光柱只压 alpha（保留绿色）
assets/.../shaders/program/impact_shockwave.*  无色冲击波 shader
```

## 4. 怎么测

1. 进游戏，开 MakeUp（Oculus 光影设置里选 `MakeUp-UltraFast-9.1e.zip`）；
2. `/ga full 14`；
3. 看**落点**（不是 `/gafx all` 的试验球）：
   - 命中瞬间：没有白色/蓝色光罩，只有背景被**径向推开一圈**的无色折射；
   - 折射环在约 0.6 秒内扩到很远，然后消失；
   - 屏幕有一次很短的震动；
   - 弹体热痕和 48 条火星还在，承担爆炸/破坏的观感；
   - 引导光柱本身仍是金色/暖白，不是白柱。
4. 如果**完全看不到折射环**：
   - 先看日志有没有 `[GA-Shockwave] shader loaded`；
   - 没有的话是自定义 core shader 没加载/被 Oculus 替换，把日志发我；
   - 有的话调大 `impact_shockwave.fsh` 里的 `0.075`（这是最大屏幕偏移比例）。
5. 如果光柱还是白：把 `config/golems_arsenal-common.toml` 里 `shader_effect_brightness` 调到 `0.25`。
6. 如果太暗：调到 `0.45`~`0.55`，或临时设为 `1.0` 对比。

## 5. 已知限制

- **只处理了天基激光这一条线**（引导 / 主光束 / 弹体 / 落点 / 受击光柱）。
  Genmu Zero 剑气、键刃拖尾、其他 `TrailRenderTypes` 特效还没接这套倍率；
  如果它们也过曝，下一轮按同样方式接 `ShaderEffects`。
- MakeUp 的 bloom 在夜里阈值极低，**不可能既保持"纯白高亮"又不 bloom**；
  现在的目标是"金色、有轮廓、可读"，不是"完全不发光"。
- 如果某个包把半透明实体写进不透明 gbuffer（alpha 被丢弃），
  压 alpha 不会生效，只能靠暖色 tint 和背面剔除；这也是为什么这次同时改了颜色。
- 光影包切换后最多 1 秒（20 tick）生效，不需要重进世界。

## 6. 下一步

先让用户用 MakeUp 实测这一版，重点回答两个问题：

1. 光柱是不是从"白柱"变成了"金色柱"？
2. `shader_effect_brightness` 在哪个值附近既能看清、又不炸？

拿到这两个答案后再决定是否：
- 把同样的倍率接到 Genmu Zero / 键刃 / 其他特效；
- 或者对某些包直接禁用高亮层，只保留"暗色轮廓 + 少量火星"。
