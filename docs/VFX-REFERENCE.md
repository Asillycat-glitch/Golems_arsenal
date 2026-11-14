# VFX Reference: Cataclysm / Iron's Spells / Arcane Vortex

调研对象（测试包内实际 jar）：

- `L_Enders_Cataclysm-3.30.jar`
- `[Iron的法术与魔法书] irons_spellbooks-1.20.1-3.15.4.jar`
- `ArcaneVortex [Alpha]-0.6.8-1.20.1-Forge.jar`

三个模组的路线完全不同，但结论很一致：**大而亮的“面”越少，光影下越稳。**

---

## 1. Cataclysm：技能 = 粒子层 + 短时序 + 屏幕反馈

Cataclysm 没有自定义 shader，全部走原版粒子管线。它的技能表现主要由这些类组成：

- `CustomExplodeParticle`：爆炸主粒子，多个 Factory（Flare / Ignis / Lightning），同一套粒子换颜色和贴图；
- `LightningStormParticle` / `LightningParticle` / `CircleLightningParticle`：雷击、雷暴、雷环；
- `Shock_WaveParticle`：贴地/空中的冲击波，**一张纹理四边形**，不是 3D 球；
- `AfterImageParticle`：残影；
- `LightTrailParticle` / `AbstractTrailParticle`：光迹；
- `Dust_Blast_Particle` / `Dust_Pillar_Particle`：尘爆/尘柱；
- `Custom_Poof_Particle`、`FlameJetParticle`、`EM_PulseParticle` 等。

具体实现特征：

- 大量使用 **纹理 sheet / 单张粒子纹理**；
- 用 `ParticleOptions` 传 **颜色、尺寸、旋转、速度**；
- `Shock_WaveParticle`：40 tick 生命周期，`size += 0.4/tick`，alpha 每 tick 线性下降；
- 爆炸/雷击粒子把“形状”交给 **2D 贴图**，代码只控制时序、缩放、颜色；
- 没有全亮自发光的大球/大柱，因此 shaderpack 的 bloom 不会抓到一个巨大白面。

**可借鉴：**

- 冲击波/爆炸的第一层用 **大尺寸 sprite**，不是半球网格；
- 一张贴图通过 `scale`、`alpha`、`color` 做出 3~4 种爆炸；
- 短生命周期 + 快速放大 + 快速 alpha 衰减；
- 技能冲击感来自 **粒子 + 屏幕震动 + 音效**，不是把几何体调亮。

---

## 2. Iron's Spells：TextureSheetParticle 是主力

Iron's Spells 也没有自定义 shader，光效几乎全是 `TextureSheetParticle` 和原版粒子渲染类型。

直接看几个类：

### `BlastwaveParticle`

- 继承 `TextureSheetParticle`；
- 8 帧 `blastwave_1..8` 动画；
- 生命周期 **8 tick**；
- `targetSize` 从 0.75× 到 1× 的缓出放大；
- 颜色来自 `BlastwaveParticleOptions`，并乘一个 0.85~0.99 的随机亮度；
- 渲染类型是原版 `ParticleRenderType` 的 sheet 变体（不同粒子用 opaque/translucent/lit 等），
  不是自定义 emissive 几何。

### `ElectricityParticle`

- 4 帧 `electricity_1..4`；
- 同样是 `TextureSheetParticle`，原版粒子 sheet。

### 其他资源

`assets/irons_spellbooks/textures/particle/` 里有完整的一套：

- `blastwave_1..8`：冲击波；
- `electricity_1..4`：电弧；
- `shockwave`：单张冲击波；
- `big_smoke_0..11`、`fiery_smoke_0..9`：烟；
- `fire_*`、`spark_*`、`embers_*`、`ender_slash_*`、`trace_*` 等。

这些贴图要么是 **亮芯 + 暗边**，要么是 **低 alpha 烟雾**；颜色本身不是纯白大面。

**可借鉴：**

- 用 `TextureSheetParticle` 做爆炸/烟/电弧，**直接进入 shaderpack 的粒子 pass**；
- 8 帧左右的短动画就足够，不需要每帧程序化几何；
- 粒子尺寸、颜色、寿命都从 Options 传入，方便一个类复用多种技能；
- 对光影最友好的不是“更聪明的 shader”，而是 **不要把全亮几何画得太大**。

---

## 3. Arcane Vortex：唯一大量用自定义 shader 的参考

Arcane Vortex 有两套完全不同的技术：

### 3.1 `nebula_cube`：全屏后处理体积

`assets/arcanevortex/shaders/core/nebula_cube.fsh`：

- 全屏 quad，`gl_Position = vec4(Position, 1.0)`；
- 采样 `MainColorSampler` / `MainDepthSampler`；
- 在屏幕空间对包围盒做 volume raymarch；
- 颜色按密度分层：`colDeep / colMid / colBright / colHot`，都是 **偏暗的紫/粉**；
- 结果先 `clamp(sum, 0, 1)`，再做 `sum = sum*sum*(3-2sum)` 软压缩；
- 最后 `scene * (1-alpha) + volume.rgb` **alpha 合成**，输出 alpha=1，并写回深度。

这就是它不曝光的原因：

1. **不是全亮实体几何**，shaderpack 的 bloom 不会在 gbuffer 阶段抓到一个白色表面；
2. 输出颜色 **有界且做过软压缩**；
3. 合成发生在场景之后，和背景按 alpha 混合，而不是无限加光；
4. 写回深度，和场景遮挡一致。

### 3.2 `warp`：屏幕拷贝折射

`warp.fsh` / `warp.json` 做的是：

- 拷贝主画面；
- 在球面/环的轮廓附近按法线方向偏移 UV；
- 无额外颜色，只折射背景。

`ShockwaveRenderer` 用 `SceneCopyTarget` + 自定义 `RenderType` 画一个膨胀球壳，shader 只采样拷贝画面。

**可借鉴：**

- 真正的“空气波/折射/体积光”放到 **后处理**；
- 后处理输出必须 **clamp / tone-map / alpha 合成**；
- 不要把后处理当成“再加一层颜色”，否则还是会过曝。

---

## 4. 三者对比

| 模组 | 主要技术 | 自定义 shader | 光影下稳定的原因 |
|---|---|---|---|
| Cataclysm | 原版粒子 + 纹理 sheet + Options | 无 | 粒子进入原版粒子 pass，颜色/尺寸受控，没有全亮大几何 |
| Iron's Spells | `TextureSheetParticle` + 原版粒子 sheet | 无 | 8 帧左右短动画、亮芯小、烟/火是贴图而不是白面 |
| Arcane Vortex | 后处理体积 + 屏幕拷贝折射 + 少量网格 | 有 | 输出 clamp/软压缩/alpha 合成，写深度，不在 gbuffer 叠全亮面 |

---

## 5. 对我们模组的结论

结合这次天基炮的实机问题，优先级很明确：

1. **爆炸/烟/火花优先用贴图 billboard**（类似 Iron's 的 blastwave/electricity/smoke），不要继续用光滑半球/球体去调 alpha；
2. **亮芯要小**：白芯只占画面很小一块，外层用暗边/饱和色，不要整面近白；
3. **短时序**：主闪光 2~4 tick，冲击波 8~12 tick，烟 20~40 tick；不要所有层都活 1 秒；
4. **空气波/折射/体积光走后处理**：`warp` / `nebula_cube` 的思路，输出 clamp + alpha 合成；
5. **屏幕反馈**：Cataclysm 的打击感很大一部分来自屏幕震动/音效/短暂停顿，不是更亮的几何；
6. **粒子 Options**：颜色、尺寸、寿命、旋转参数化，一个类复用到多个技能；
7. **不要在光影下用大面积 `entityTranslucentEmissive`**：shaderpack 会把它当 bloom 源，压 alpha 也未必有用。

---

## 6. 当前天基炮的状态

- 构图、弹体、落点、冲击波时序已经基本定型；
- 这一轮只做光影表现：`ShaderEffects.colorScale()` 同时压 RGB，引导/弹体/落点/法阵/剑气/火箭都接了；
- 下一步如果还要继续提升，不是再调参数，而是把落点主光效迁移到 **类似 `nebula_cube` 的后处理合成**，或者换成 **Iron's 式纹理 sheet**。

> 这份文档只做技术参考，不改变当前激光的构图。
