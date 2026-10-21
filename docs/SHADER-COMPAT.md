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

> **屏幕空间空气波保留**（`ImpactShockwaveFx.SCREEN_WARP_ENABLED = true`）。
> 实机截图里那层被夸"很漂亮"的大球面震荡就是它；这是本模组目前最接近参考图
> 空气被推开的层次，所以不再关闭。

`OrbitalImpactRenderer` 现在负责：
- 一条带拖尾的贴地环带；
- 弹体残留热痕（`drawTrail`）；
- 64 条向外/向上的火星（`drawSparks`）；
- 短闪光柱 + 橙白两层短寿命火球（爆炸第一版）。

```text
0% ───── 55% ───── 100%   冲击波
  膨胀到 3R     淡出
0% ─ 40%                  屏幕震动（很短的一次）
```

**旧版本的 QUADS bug** 已不存在：`ShockwaveShell` 已删除，不再有可见球壳/三角面板。

**“两拨冲击波”**：之前 `ImpactShockwaveFx` 对每个 `OrbitalImpactEntity` 都起一圈；
同一次打击如果同时/连续出现两个冲击实体，就会看到两圈同心波。现在加了两层收束：

- `ImpactShockwaveFx`：16 格内、16 tick 内只保留最早的一圈；
- `OrbitalImpactRenderer#isPrimaryImpact`：同一个区域内只让 ID 最小的冲击实体画主体，
  另一个实体不会重复画环带/闪光。

### 2.5 主体：日冕「五雷天罚」更新版（蓝白 + 空气折射）

参考视频：[【日冕】神州阵营拓展协议——五雷天罚特效更新](https://www.bilibili.com/video/BV1RCqDYKEZ9/)
（已用 ffmpeg 抽帧确认：白蓝半球 → 黑灰烟 + 碎片，不是橙色火球。）

**按用户的判断：参考里没有真正的闪电。** 那些细长、扭动的“电弧”是
**高速弹体拖尾把空气加热后，背景/拖尾被折射**出来的形状，不是独立闪电几何。
所以这一版：

- **删除 8 道电弧**（`drawArcs`）；
- 新增 `TrailRefractionFx`：沿弹体拖尾的屏幕投影做无色波浪折射；
- 保留白蓝能量半球、蓝白环带/火星、灰烟 + 灰烬 + 熔岩 + 电火花。

现在的落地主体：

- **可见的贴地蓝白环带 + 白蓝能量半球保留**（上一版误删了，这次恢复）；
- **屏幕空间空气波只允许一个活动波**：`ImpactShockwaveFx` 改成单 `activeWave`，
  有新冲击时不再叠加第二圈；`AFTER_LEVEL` 还加了 2ms 防抖，避免多 pass 环境一次画面合成两遍；
- 48 条更短、更细、亮度更低的蓝白火星；
- 灰烟 + 灰烬 + 熔岩 + 电火花；
- 不再画落点残留的实体光柱。

颜色从暖金改成白蓝。`GuidanceBeamRenderer` 也同步成白蓝主光/电蓝辉光；
如果之后确认引导也该保持暖金，再把这一处单独回退。

**关于“两次屏幕空间空气波”**：真正原因是实体生命周期 **26 tick**、波只有 **24 tick**；
波结束后扫描仍能看到同一个旧冲击实体，于是又起一圈。现在两层限制：

- `CONSUMED_IMPACTS` 记录已经起过波的实体 ID，同一个实体不会再起第二圈；
- 同一时间只允许一个 `activeWave`，渲染端再加 2ms 防抖。

日志会打印 `[GA-Wave] start id=... active=1`，同一个 id 只应出现一次。

**光影下过曝的参考：秘法涡流 `arcanevortex:nebula_bow`**：

- 它不是用全亮实体几何去叠，而是 `nebula_cube` **全屏后处理**：采样
  `MainColorSampler` / `MainDepthSampler`，在屏幕空间做体积 raymarch；
- 颜色先 `clamp` 到 0..1，再做 `sum = sum*sum*(3-2sum)` 的软压缩；
- 最后用 `scene * (1-alpha) + volume.rgb` 做 **alpha 合成**，输出 alpha=1，并写回深度；
- 因为它发生在场景之后、且输出有界，光影包的 bloom 不会把一个全亮实体当成光源。

我们的环带/半球/火星仍是 `entityTranslucentEmissive` 实体几何，所以 bloom 会抓；
这版先把亮度/白色分量压下来，下一步要彻底解决就得把这些光效移到类似 `nebula_cube`
的**后处理合成**里，而不是继续调实体 alpha。

**火星调整（光影下更容易过曝）**：

- 数量 64 → **48**；
- 单条长度 `back` 0.12 → **0.055**；
- 宽度 0.045 → **0.022**，并按随机数再做 0.55~1.30 的粗细变化；
- alpha 0.85 → **0.62**；
- 颜色压低白色分量（白蓝 → 电蓝），避免在 bloom 里糊成一片白。

**弹体 / 拖尾（这一版缩小）**：

- 头部 1.8× → **0.55×**；拖尾 80 → **28 格**；alpha 1.0 → **0.55**；
- 不再画头部冲击套环；
- 颜色白蓝 → 电蓝 → 深蓝；
- **新增拖尾空气折射**：`TrailRefractionFx` 把主画面沿拖尾投影做低频波浪偏移，
  不添加颜色，只让背景和拖尾“折弯”。

> 上一版实机里那几根**巨大白色光柱**，就是 80 格拖尾 + 1.8× 头部 + 残留光柱叠出来的；
> 它把空气折射完全盖住了。这一版先把实体拖尾压小，让折射成为主要视觉。

### 2.6 瞄准聚合的“外围突然消失”修复

真正原因不是叠加爆闪，而是**六条光根本没走到圆心**：

- `AIM_CONVERGE_TICKS` 写的是 16 tick；
- 但 `pillarTravelFraction` 用的行程曲线（`PILLAR_TRAVEL = 7.95`）要到 **20 tick** 才走完；
- 所以聚合相位结束时半径还剩约 **0.26R**，然后相位一切换，六条光直接跳到圆心/被中心光柱替换——看起来就是“在外边一点点突然消失”。

修复：

- 聚合改为 **20 tick**（8 旋转 + 20 聚合 + 12 放大，总时长仍是 40 tick = 2 秒）；
- 半径真正到 0 之后，再用 **4 tick 交叉淡出**：六条在圆心淡出、中心大光柱淡入；
- 不再出现六条在外围消失、中心突然出现的跳变。

## 3. 涉及的文件

```
Config.java                             新增 orbital_beam.shader_effect_brightness
client/ShaderEffects.java               光影检测 + 倍率 + 暖色 tint
client/ImpactShockwaveFx.java           单个屏幕空间空气波 + 屏幕震动
client/TrailRefractionFx.java           弹体拖尾的空气折射（无色）
client/OrbitalImpactRenderer.java       环带/半球/闪光柱/短细火星
client/GuidanceBeamRenderer.java        瞄准光柱 + 聚合爆闪修复（蓝白）
client/OrbitalBeamRenderer.java         压 alpha / 暖色 / 背面剔除
client/OrbitalProjectileRenderer.java   蓝白小头部/短拖尾（28 格）
client/BeaconPillarRenderer.java        受击光柱只压 alpha（保留绿色）
client/GenmuZeroRenderer.java           剑气 aura/wake 的 alpha 统一乘 ShaderEffects.brightness
client/OrbitalSigilRenderer.java        法阵 alpha 乘 brightness + 暖色 tint
client/RocketRenderer.java              火箭模型全亮色乘 0.55~1.0，避免纯白光团
assets/.../shaders/program/impact_shockwave.*  无色空气波 shader
assets/.../shaders/program/trail_refraction.*  拖尾空气折射 shader
base/entity/OrbitalImpactEntity.java    爆炸粒子/烟尘/熔岩
```

## 4. 怎么测

开光影测，重点看蓝白效果在 bloom 下会不会糊成一片白。

```
/ga shot 14
```
或
```
/ga full 14
```

看**落点**：

- **屏幕空间空气波只有一层**（`ImpactShockwaveFx` 单 `activeWave`）；
- 贴地蓝白环带和白蓝能量半球**已恢复**，它们是可见的地面/半球层，不是第二圈屏幕空间波；
- **没有**电弧/闪电几何；
- 中央有短闪光柱；
- 48 条更短、更细、偏电蓝的火星，不应再是长直白线；
- 灰烟/灰烬/熔岩 + 碎片；
- 屏幕短促震一下；
- 引导光柱是白蓝/电蓝。

看**弹体拖尾**：

- 弹体下落时，拖尾周围背景应该出现轻微波浪/折弯；
- 不是一条条独立电弧；
- 如果完全没有折射，可能是 `TrailRefractionFx` 后处理被光影包覆盖，日志里会有 `[GA-Trail] 拖尾空气折射管线就绪`。

看**瞄准收束**：

- 六条光应该一路收到圆心，不会在离中心还有一点的地方突然消失；
- 到圆心后约 4 tick 内淡出，中心大光柱同时淡入；
- 整体是连续过渡，不是“外围消失 → 中心出现”。

如果环带太窄/尾迹太短：调 `OrbitalImpactRenderer` 里的 `lead`、`trail`、`alpha`。
如果瞄准还是跳变：把 `/ga full` 最后 1 秒录屏/截图发我，我再按相位边界收。

## 5. 参考视频与下一步

参考已确认：[【日冕】神州阵营拓展协议——五雷天罚特效更新](https://www.bilibili.com/video/BV1RCqDYKEZ9/)。

已用 ffmpeg 抽帧确认它的层次：

1. 蓝白雷柱/电弧先到；
2. 白蓝半球能罩（高亮、几乎不透明）；
3. 半球很快碎掉，留下黑灰烟、灰烬、碎片和少量橙色地面火光。

当前版本是第一版复刻，和参考的差距：

- 半球还是几何球，表面没有参考里的噪声/闪电纹理；
- 电弧是直的折线，分叉还不够多；
- 烟是原版灰烟，形状不如参考的团状黑烟；
- 还没有屏幕闪白/命中定格。

下一步优先做：半球噪声纹理、电弧分叉、黑烟 billboard、屏幕闪白；
这些都还需要 1~2 轮实机调。

## 6. 已知限制

- **已接 `ShaderEffects` 的特效**：天基激光整条线（引导 / 主光束 / 弹体 / 落点 / 受击光柱）、
  Genmu Zero 剑气的 aura/wake、轨道法阵、火箭模型。
  `KeyBladeRenderer` 是普通物品模型、`GolemComboLayer` 是半透明拖影，不走全亮自发光，
  这轮没有改它们的渲染路径。
- MakeUp 的 bloom 在夜里阈值极低，**不可能既保持"纯白高亮"又不 bloom**；
  现在的目标是"蓝白、有轮廓、可读"，不是"完全不发光"。
- 如果某个包把半透明实体写进不透明 gbuffer（alpha 被丢弃），
  压 alpha 不会生效，只能靠 tint 和背面剔除；这也是为什么这次同时改了颜色。
- 光影包切换后最多 1 秒（20 tick）生效，不需要重进世界。

## 6. 下一步

先用 MakeUp 实测这一版：

1. 屏幕空间空气波是不是只有一层（日志 `[GA-Wave] start ... active=1`）；
2. 环带/半球恢复后表现是否回到"够用"；
3. Genmu Zero / 法阵 / 火箭在光影下是否也不再炸白。

彻底的方案仍是把落点光效移到类似 `nebula_cube` 的后处理合成里；
这一版只是先给所有自发光/加法特效统一接上 `ShaderEffects` 刹车。

拿到这两个答案后再决定是否：
- 把同样的倍率接到 Genmu Zero / 键刃 / 其他特效；
- 或者对某些包直接禁用高亮层，只保留"暗色轮廓 + 少量火星"。
