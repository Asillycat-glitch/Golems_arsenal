# 开发环境运行（runClient / runServer）

> **状态：`golems_arsenal` 的开发环境至今没有被真正跑通过。**
> 本仓库一直以来的验证方式是「`gradlew build` 打出 jar → 丢进整合包测试」。
> 这份文档把已知的障碍和当前的配置状态写清楚，免得下次再从零踩一遍。

## 现在怎么验证

```
gradlew build
copy build\libs\golems_arsenal-0.3.jar <整合包>\mods\
```

然后进游戏看：创造栏出现「傀儡军械库」这一页、能量武士刀/能源锤/追踪机械弓能用、
升级能装在傀儡上、特技升级（键刃回旋 / 狮子斩 / 剑雨 / 冲撞 / 凤凰天驱）能触发。

## 已知障碍：依赖自带 mixin 的 refmap

`build.gradle` 的 `runs.configureEach` 里必须有这两行，否则**连启动都做不到**：

```groovy
property 'mixin.env.remapRefMap', 'true'
property 'mixin.env.refMapRemappingFile', "${projectDir}/build/createSrgToMcp/output.srg"
```

原因：本家（Modular Golems）与 l2library 都自带 mixin，而它们 jar 里的 refmap 存的是
**SRG 名**。开发环境跑的是 **official 名**，不告诉 Mixin 去哪张表做映射的话，
依赖里的 mixin 连目标方法都找不到，会在 `l2library.mixins.json:EntityMixin` 就炸。

`output.srg` 不需要手工准备：它由 ForgeGradle 的 `createSrgToMcp` 任务生成，
而该任务在 `runClient` 的依赖链里（可用 `gradlew runClient --dry-run` 确认），
所以第一次跑 `runClient` 时会自动产出来。

### 加上之后仍可能过不去的下一关

姊妹仓库 `dragon_golems` 的 `docs/DEV-RUN.md` 记录了更靠后的一处失败（**尚未在本仓库复现确认**，
因为上面这两行是刚补上的）：l2library 的 `AbstractArrowMixin` 影子字段在源码里就是拿 SRG 名
（`f_36697_`）写的，而它的 refmap 里没有这条字段映射，所以只有生产环境能命中，
开发环境必然 `Shadow field ... was not located`。

这属于**上游 jar 的写法问题**，本仓库改不了。如果确实撞上了，可选：

1. 换一份影子字段写法正常的 l2library；
2. 在 `run/mods` 放一份自己重编的 l2library；
3. 接受现状，继续用「打 jar 进整合包」验证。

## `run/mods` 里需要放什么

`build.gradle` 里 `implementation` 的那批进的是**模块路径**；只有在 `run/mods`
里的才会**作为一个 mod 被加载**。所以开发环境跑起来至少需要：

| 文件 | 为什么 |
|---|---|
| `modulargolems-2.7.3.jar` | 本家。与整合包里那份相同 |
| `l2library-2.5.3-slim.jar` | **必须 slim 版**。完整版 jarjar 了 `Registrate-MC1.20-1.3.11.jar`，会和 `build.gradle` 里 implementation 的 Registrate 撞成两个同名模块，启动直接 `ResolutionException: Module Registrate contains package com.tterrag.registrate.util.entry` |
| `mixinextras-forge-0.2.0-beta.8.jar` | slim 版不带它，而本家/l2 的 mixin 用了 MixinExtras 的 `@WrapOperation`，缺了会 `ClassMetadataNotFoundException: ...wrapinjector.Operation`。从整合包的完整版 l2library 里解出来即可 |

`run/` 在 `.gitignore` 里，不进版本库 —— 换机器或清过缓存后这三个 jar 要重新放。

## 本仓库自己的 mixin 与 refmap

⚠️ **`src/main/resources/golems_arsenal.refmap.json` 是手工维护的，不是构建产物。**
Mixin 的注解处理器在本项目里不生成它（`build/generated/sources/annotationProcessor/`
实测为空，用 `--rerun-tasks` 强制重编也仍然为空）。

现在哪个 mixin 需要 refmap 条目、哪个不需要，规则写在
[`DEPENDENCIES.md`](DEPENDENCIES.md#-refmap-是手工维护的)。**要记住的只有一句**：
凡新增/修改注入 **vanilla 成员**的 mixin，必须手工往那个 refmap 里补条目，
否则会表现为「开发环境一切正常、打出来的 jar 在生产里静默不生效」。

## 这份文档还没写的

- `runClient` 在本仓库的**实际**首次启动结果（上面第 2 节那处失败是否真的会发生）
- `runServer` 是否可用
- 需要的 JDK（`build.gradle` 指定 `JavaLanguageVersion.of(17)`）
