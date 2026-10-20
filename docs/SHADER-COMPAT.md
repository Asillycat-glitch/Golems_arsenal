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

### 2.4 白热球峰值

`BALL_ALPHA` 从 `1.0` 降到 `0.65`，再乘光影倍率（0.35）后是 0.2275；
加上金色 tint，夜里不再是一整颗白球。

## 3. 涉及的文件

```
Config.java                             新增 orbital_beam.shader_effect_brightness
client/ShaderEffects.java               光影检测 + 倍率 + 暖色 tint
client/GuidanceBeamRenderer.java        压 alpha / 暖色 / 背面剔除
client/OrbitalBeamRenderer.java         同上
client/OrbitalProjectileRenderer.java   同上
client/OrbitalImpactRenderer.java       球/环/拖尾/火星全部压 alpha + 球背面剔除
client/BeaconPillarRenderer.java        受击光柱只压 alpha（保留绿色）
```

## 4. 怎么测

1. 进游戏，开 MakeUp（Oculus 光影设置里选 `MakeUp-UltraFast-9.1e.zip`）；
2. `/ga full 14`；
3. 看：
   - 引导光柱是不是**金色/暖白**，而不是白柱；
   - 夜里还能不能看到光柱的轮廓（应该能，但不再炸成一片）；
   - 落点白热球是不是"金色火球 + 外圈亮边"，而不是白罩；
   - 白天光柱是否还读得出来（会偏淡，这是预期）。
4. 如果还是白：
   - 先把 `config/golems_arsenal-common.toml` 里 `shader_effect_brightness` 调到 `0.25`；
   - 如果 `0.25` 仍然纯白，说明 MakeUp 的 entity gbuffer **完全忽略 alpha**，
     仅靠压 alpha 不够，需要进一步改 RGB（或考虑在 shaderpack final 之后绘制，代价很大）。
5. 如果太暗：调到 `0.45`~`0.55`，或临时设为 `1.0` 对比。

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
