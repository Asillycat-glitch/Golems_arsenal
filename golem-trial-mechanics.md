# Golem Trial (傀儡试炼核心) — exact mechanics

Source analysed: `[傀儡地牢] golemdungeons-1.0.7.jar` (modId `golemdungeons`, deps: l2library ≥2.5.3, modulargolems ≥2.6.34, forge 47.x, MC 1.20.1).

Evidence base (written outside `src/`):
- `gd-jar/` — raw jar extraction (`D:\编程文件\Asillycat's mod\1.20.1\golems_arsenal\gd-jar`)
- `gd-jar-src/` — Vineflower 1.10.1 decompile of golemdungeons classes
- `gd-jar-mg/`, `gd-jar-mg-src/`, `gd-jar-mg-src2/` — modulargolems-2.7.3 extraction + decompile

> Note: writes to `dev/` were denied by Windows file permissions in this session, so extraction went to the workspace root instead.

## 1. Trial start, waves, spawnCost / triggerRange / minY / maxY

**Where a trial id lives.** The `golem_spawner` block placed by the structures carries the trial id in its block-entity NBT, and the blockstate is `state=idle`:

```
...trial||golemdungeons:factory_remnant||||id||golemdungeons:golem_spawner||pos||...||state
```
Evidence: NBT dump of `gd-jar/data/golemdungeons/structures/abandoned_factory/room.nbt`, `.../piglin_factory/piglin_factory.nbt` (`golemdungeons:piglin_legacy`), `.../sculk_factory/root.nbt` (`golemdungeons:sculk_infestation`); blockstate `Name||golemdungeons:golem_spawner||Properties||facing||north` and `state||idle`.

**Start condition** (`GolemTrialBlockEntity.tick()`, `gd-jar-src/xkmc/golemdungeons/content/spawner/GolemTrialBlockEntity.java:123-249`):
- Requires `trial != null` (set by `HostileSummonWand` right-click, `content/item/HostileSummonWand.java:107-121` → `be.setTrial(entry.targetTrial)`).
- Requires **no** currently tracked players (`trialPlayer.isEmpty()`) and cooldown expired (`time - lastTime >= lastCost`, line 181).
- At least one player passing `isTriggeringPlayer` (line 188): not spectator, not dead, not `FakePlayer`, and `config.isInRange(player, blockPos)`.
- On start: `trialPlayer` = all such players, boss bar created, `data.start(...)`, blockstate → `ACTIVATED`, plus `lastCost = 0; lastTime = time` (lines 197-210).
- While running, every 5 ticks players that pass `isValidPlayer` (alive, non-spectator, **within 24 blocks** — `m_20238_ <= 576.0`, line 388) are added to `trialPlayer` and to the boss bar (lines 211-235).

**triggerRange / minY / maxY** — `TrialConfig.isInRange` (`gd-jar-src/.../content/config/TrialConfig.java:65-72`):
```java
Vec3 diff = player.position().subtract(Vec3.atCenterOf(pos));
if (Math.abs(diff.z) > triggerRange) return false;
if (Math.abs(diff.x) > triggerRange) return false;
return diff.y > minY && diff.y < maxY;   // strict inequalities
```
So: a **box** of ±triggerRange on X and Z, and a strict vertical band `(minY, maxY)`, measured from the spawner's block-center. Defaults `triggerRange=12, minY=-4, maxY=6, spawnCost=1000` (TrialConfig.java:22-28). Example: `factory_remnant` = 22 / −5 / 16; `piglin_legacy` = 10 / −6 / 6.

**Wave advance** (`content/spawner/TrialData.java:57-161`):
- `WAVE_DELAY = 40` ticks, `SUMMON_DELAY = 10` ticks, `COMPLETE_DELAY = 60` ticks (lines 27-29).
- `tickTrial`: if a wave is transitioning out (`entryCount == 0`) **and** `trialGolems` is empty → if `wave >= config.list.size()` → `complete()`; else `initWave()`.
- `initWave` (145-161): `wave++`, `entryCount = list.get(wave-1).size()`, `entryIndex = -1`, `toSummon = 0`, `totalMob = Σ num`, `mobIndex = 0`, `totalHealth = 0`, `nextAction = time + 40`.
- `tickSummon` (97-143): when `entryIndex < 0` or `toSummon == 0`, advance `entryIndex`; past the end of the wave → `entryCount = 0, mobIndex = 0, entryIndex = 0` (**wave cleared**). Otherwise spawn **one** entity per call, then `nextAction = time + 10` (one spawn per 10 ticks = 0.5 s).
- A wave is therefore cleared when every spawned entity is gone; the *next* wave only begins once `trialGolems` is empty, 40 ticks after the wave's last spawn.
- Empty wave arrays (see `trial/raids.json`, first four waves are `[]`) are passed through instantly.

**spawnCost — this is a cooldown/pacing cost, not a per-spawn price.** Two places consume it:
1. `TrialData.tickSummon:121` → `be.addCost(config.spawnCost, time)` after **every successful single spawn**.
2. `GolemTrialBlockEntity.addCost` (`GolemTrialBlockEntity.java:262-274`):
```java
long since = time - lastTime;
long remain = lastCost - since;
lastCost = (remain < 0) ? cost : cost + remain;
lastTime = time;
```
`lastCost` is therefore "cost points still to be burned off", decreasing by **1 per game tick** implicitly. Since `tickSummon` only runs when `time >= nextAction` (10 ticks), each spawn's cost must *also* be paid before the next: burning `lastCost` back to ≤ ~10–40 takes `spawnCost` ticks in total across the wave.
- `trialPlayer.isEmpty()` blocks restart while `time - lastTime < lastCost` (line 181).
- With the trial stopped, `stop()` puts the block into `CHARGING`; the CHARGING → `IDLE` flip happens at line 174-176 once `lastCost + lastTime < time`.
- Numeric consequence: `spawnCost = 400` ⇒ ≈ 400 ticks ≈ 20 s of charge total per spawn batch/trial; `200` ⇒ 10 s; `1000` ⇒ 50 s. Player-facing timer: `GolemTrialBlockEntity.getText` (409-428) computes `remain = lastCost - (now - lastTime)` and renders `"Charging: %d:%02d"` (`%d:%02d` format string, lang `modulargoleons.tooltip.charge_time` / `tooltip.charge_time` = "充能中: %s").
- **`trial_medal`** (`content/item/TrialMedal.java:24-39`) right-click on a spawner whose state is `CHARGING` calls `be.clearCharging()`, which zeroes `lastCost` and sets the blockstate back to `IDLE` — i.e. it skips the remaining charge/cooldown instantly (creatively no item is consumed).

## 2. Victory and failure conditions

**Victory** — reach the end of `config.list` with an empty `trialGolems` list (`TrialData.java:75-83`), then:
- `GolemTrialBlockEntity.complete` (295-334): fires `GDTriggers.COMPLETE` for each tracked player, blockstate → `VICTORY`.
- Reward: `level.getServer().getLootData().getLootTable(config.reward)`, params `LootContextParamSets.EMPTY` with only `LootContextParams.ORIGIN` = spawner center (line 305). **No luck, no killer, no player parameter.**
- Then, **at `pos.above()`**: if `generateChest == true` and that block is air, it is set to a vanilla **chest** (`Blocks.CHEST`), the items are inserted into its `IItemHandler`, and anything that does not fit is dropped as an entity at `pos.above()` (lines 307-332). If `generateChest == false`, or if the block is not air, **all** loot is dropped as items at `pos.above()`.
- Only `twilightforest:stronghold_defender` and the trial configs with `"generateChest": true` make a chest; the three vanilla-dungeon trials (`factory_remnant`, `piglin_legacy`, `sculk_infestation`) are `false` → loot drops on the floor.
- Reward loot tables live at `gd-jar/data/golemdungeons/loot_tables/trial_reward/*.json` (`type: minecraft:chest`, e.g. `piglin_legacy` gives a golem material + 12-16 ghast tears/blaze rods/ender pearls + 16-24 obsidian + 3-6 netherite smithing template + (nether star | 4-8 wither skulls | `golemdungeons:trial_medal`)).
- Advancements: `advancements/dungeons/defeat_*.json` are driven by the `trial_complete` criterion keyed on the trial id (`content/init/data/advancement/TrialCompleteTrigger.java`).

**Failure / interruption:** there is **no failure state and no time limit.**
- Tracking loss (`isValidTracked`, line 393-395): entity dead **or** different level **or** > 2304 blocks² (48 blocks) from the spawner ⇒ the golem UUID is dropped from `trialGolems` (`TrialData.java:89-92`). Note this is *distance-based*, so walking a golem 48+ blocks away removes it from the wave.
- Player loss (lines 134-145): each tick, tracked players that are offline, spectator, dead, or failing `isValidTracked` (**>48 blocks** from the spawner or different dimension) are removed. **As soon as `trialPlayer` is empty → `stop()`** → trial aborted, `data.stop()` kills every tracked trial golem (`golem.discard()`, `TrialData.java:171-183`), blockstate → `CHARGING`, boss bar removed.
- The aborted trial restarts from **wave 1** when a player returns after the charge expires; there is no penalty and no partial progress.
- Whether the *player* dies is otherwise irrelevant — the trial continues if another tracked player is still within range.

## 3. How spawned golems are built

`SpawnConfig.summon` (`gd-jar-src/.../content/config/SpawnConfig.java:126-175`) and `createGolem` (213-259):
1. Pick a golem `EntityType` from a weighted table built from the spawn JSON's `types` (weight must be > 0).
2. `createGolem` picks one material per golem part from `materials` (a material is excluded from a part if that part's item is in its `forbid` list, line 93), then parses each into a real `GolemMaterial` (`p.toItem().parseMaterial(...)`, line 224) — i.e. **the trial golem's base stats come from `data/modulargolems/golemmaterials` / the material named, not from any dungeon-specific stat table.**
3. Material `bonus` upgrades: for each part's material, each `{upgrade, chance}` rolls `r.nextDouble() < chance`, capped at `max = golem.values().length` parts (lines 226-231).
4. `upgradeChance` array (line 236-245): iterated in order, **breaks at the first roll that fails** (`if (r.nextDouble() > prob) break;`). Default when absent: `DEF_CHANCE = {1.0, 0.5, 0.5, 0.5}` (line 47). E.g. `piglin_humanoid_melee.json` `[1.0, 1.0, 0.7, 0.7]` ⇒ 1 upgrade guaranteed, second guaranteed, third 70 %, fourth 70 % **only if** the third passed. Upgrades come from the weighted `upgrades` list, always filtered through `fitsOn/validate` so slot counts and modifier max levels stay legal.
5. `golem.create(sl)` then `e.onCreate(mats, ups, uuid)` where the uuid is the **hostile faction's uuid** (`HostileGolemRegistry.getFaction(faction).uuid`, lines 248-250; `HostileFaction.uuid = MathHelper.getUUIDFromString(id.toString())`). This makes `AbstractGolemEntity.isHostile()` true (`HostileGolemRegistry.isHostile` checks `UUID_MAP.containsKey(ownerId)`).
6. Ranged goals (`BaseRangedAttackGoal`) get a random initial delay 0-99 ticks (lines 252-256).
7. **Equipment** (`attachEquipments`, 261-274): for each `equipments` group whose `type` equals the entity's type, pick a weighted `target` set id and apply the `golemdungeons_config/equipment/<id>` config — `EquipmentSetInfo.apply` rolls one weighted entry per slot, sets the stack, and for `Mob`s also sets the **equipment drop chance** (`EquipmentConfig.setEquipment` → `mob.setDropChance`, `content/config/EquipmentConfig.java:83-88`). `additional` slots are golem-specific (`ExtraEquipmentSlot`: `BACKUP`, `ARROW`, `LEFT_SHOULDER`, `RIGHT_SHOULDER`); those apply to `SweepGolemEntity` and can auto-add Infinity to a bow (`Enchantments.INFINITY_ARROWS`, line 92-94).
   - Note this also means equipment configs feed *non-golem* spawn ids too (`cataclysm:*`, `twilightforest:*`), with the same code path.
   - Item stacks can carry `enchantLevel`, which random-enchants via `EnchantmentHelper.enchantItem` (line 128). The JSON also carries `dropChance` (default `0.085F`, line 118) — that is the *vanilla equipment drop chance*, unrelated to spawnCost.
8. **Mounts** (`SpawnConfig.summon:145-169`): if `mounts` is non-empty and `r.nextDouble() < mountChance`, pick a weighted mount type. If the mount type is itself a registered golem type it is built with its own materials/upgrades; otherwise `mountType.create(level)` and horses are force-tamed (`Horse.setTamed(true)`). The mount gets equipment too, the golem is `startRiding(mount)`ed, and **`summon` returns the mount**, not the golem. `GolemUtils.recursiveAdd(level, e)` (`util/GolemUtils.java:10-17`) recursively `level.addFreshEntity` + repositions the whole vehicle/passenger tree, which is how the riding golem also ends up in the trial's `trialGolems` list (`TrialData.java:125-132` iterates `e.getSelfAndPassengers()`).
   - Default `mountChance` is `0.0` with empty `mounts` in every shipped trial spawn config, so mounts are off by default; the data supports it (`piglin_*` configs even ship `factory_horse_armor.json` / `illager_horse_armor.json` equipment sets for non-golem mounts).

**Scaling with player count / difficulty: none.** `SpawnConfig.summon(ServerLevel)` takes only the level; there is no player count, no `Difficulty` parameter, and no scaling constant anywhere in `SpawnConfig`/`TrialData`/`GolemTrialBlockEntity`. Wave sizes are literally the `num` values in the trial JSON. The only difficulty interaction is whatever modulargolems' own hostile-golem code does (it uses the vanilla difficulty for mob damage/attributes like any mob).

## 4. Player golems vs trial golems

Both directions work; nothing is disabled.
- **Trial golem → player golem: yes.** `HostileFaction.hostileGolemAttacks(LivingEntity)` (`gd-jar-mg-src/HostileFaction.java:45-55`) returns true for `target instanceof OwnableEntity && getOwner() instanceof Player` — a player's golem qualifies. `DungeonFaction` additionally returns true if the target is the attacker of an ally (`content/faction/DungeonFaction.java:19-38`). `AbstractGolemEntity.canAttack` (`gd-jar-mg-src/AbstractGolemEntity.java:732-777`) returns **true immediately** at line 749 when `this.isHostile() != other.isHostile()`.
- **Player golem → trial golem: yes, subject to the player's target filter.** Same method: line 748-751 returns true because the player golem is non-hostile and the trial golem is hostile. That shortcut happens *after* the ownership checks (a trial golem is not owned by that player, and `getOwner()` of a hostile golem resolves the faction uuid to nothing). The fallback at line 762-771 would consult the owner's `GolemConfigEntry.targetFilter`; its default predicate is `DefaultFilterCard.defaultPredicate` (`gd-jar-mg-src2/DefaultFilterCard.java:20-30`) = any `Enemy` (except creeper) **or** any `AbstractGolemEntity.isHostile()`, so trial golems are valid default targets. Changing the player's filter cards can suppress this, but the line-749 shortcut for differing hostility applies regardless.
- **Trial golems are mutually allied**: same faction uuid, and `canAttack` returns false for hostile-vs-hostile with equal `getOwnerUUID()` (line 753-755); `HostileFaction.isAlliedTo` (line 57-63) also treats same-faction hostile golems as allies.
- **No friendly fire from the dungeon mod either**: `GDAttackListener` (`events/GDAttackListener.java:19-26`) cancels a `LivingAttackEvent` when the victim is an `AbstractGolemEntity` and `victim.isAlliedTo(attacker)`.
- Spawned golems are put into **GUARD mode anchored to their spawn point** (`TrialData.java:130` `golem.setMode(GolemModes.GUARD.getID(), pos)`): `GolemModes.GUARD = ("guard", positioned=true, movable=true, wander=true)` (`gd-jar-mg-src/GolemModes.java:12-14`), so they hold position but can move to fight, and `GolemMode.canChangeDimensions()` is false — a guard golem cannot follow a player through a portal (this is also why a golem leaving the level drops out of `trialGolems`).
- `GolemTrialBlockEntity.configureGolem` is an **empty override** (line 366-367) — the dungeon adds no extra per-golem configuration beyond the mode.

## 5. Config options

**The mod's own `ForgeConfigSpec` has nothing for trials/difficulty/timings.** `init/data/GDConfig.java` defines only:
- `enableRaidGolems` (bool, default `true`) — "Enable adding hostile golems to raids"
- `flameSwordDamage` (0.25, 0..10), `flameSwordLoot` (0.33, 0..1), `sculkScytheDamage` (0.25, 0..10), `fierySwordDamage` (1.0, 0..10)
- `GDConfig.Client` is empty.
Written to `l2_configs/golemdungeons-client.toml` / `l2_configs/golemdungeons-common.toml` (path built in `GDConfig.register`).

**All trial tuning is data-pack, not config**: `data/<namespace>/golemdungeons_config/trial/*.json` (`list`, `spawnCost`, `triggerRange`, `minY`, `maxY`, `generateChest`, `reward`), `.../spawn/*.json` (types/materials/upgrades/equipments/faction/mountChance/mounts/upgradeChance), `.../equipment/*.json` (per-slot weighted items, enchant level, drop chance). Gather type macros: `TrialConfig.list` = `COLLECT`, `SpawnConfig.types/materials` = `MAP_OVERWRITE` (a datapack can replace them), `SpawnConfig.equipments/upgrades` = `COLLECT`. The client modifier `GDConfig.CLIENT` cannot tune waves; a modpack must override the JSON.

**Hardcoded timing constants a datapack cannot change** (`TrialData.java:27-29`): wave transition delay 40 t, 10 t between individual spawns, 60 t victory banner linger; plus `GolemTrialBlockEntity` constants: 5-tick player recheck interval (line 211), 20-tick idle recheck (line 173), 48-block tracked radius (2304.0, lines 388/394), 24-block `isValidPlayer` radius (576.0, line 388), 1 point/tick cost burn.

## Explicitly undetermined / caveats

- **`SpawnConfig.summon` integer `cost` semantics verified for `spawnCost > 0`.** `TrialConfig.spawnCost` has no lower bound in the JSON schema; if a datapack sets a cost *below* the current `remain`, `addCost` still adds rather than clamps, so a negative/1-tick cost would effectively remove pacing entirely. Not exercised by any shipped file.
- **The exact ordering of the CHARGING → IDLE flip vs. trial restart when `lastCost == 0`** (`GolemTrialBlockEntity.java:174-210`) is read from decompiled bytecode, not from comments; the boolean condition is `lastCost + lastTime < time`, which I read as "cooldown elapsed". Behaviourally every shipped config behaves as described, but an unusual save-state where `lastTime` is in the future could flip this.
- **`config.faction` ids resolve to `HostileGolemRegistry` entries only if the providing mod is loaded.** `golemdungeons:factory_remnant|illagers_creation|piglin_legacy|sculk_infestation` are always registered (`content/faction/DungeonFactionRegistry.java`); `cataclysm:*` and `twilightforest:*` factions are gated behind `ModList.get().isLoaded(...)` (`init/GolemDungeons.java:55-90`). If the partner mod is missing, `getFaction` returns null and the golem falls back to `HostileGolemRegistry.DEFAULT` (`SpawnConfig.java:249`) — its faction allies/banner change, and `DungeonFaction` special-casing is lost.
- **Whether any shipped trial wave contains a non-golem entity** could not be exhaustively confirmed for the cataclysm/twilight configs without resolving their entity types in those jars. If a wave target is not an `AbstractGolemEntity` (or riding one), `TrialData` never adds it to `trialGolems`, so the wave could clear while it is still alive. All checked `golemdungeons:*` targets are golem types.
- I did **not** find any "trial disables player golems" mechanic, any per-trial player-count multiplier, any wave-size config option, or any time limit — absence of evidence here is across every relevant class (`TrialData`, `TrialTicker`, `GolemTrialBlockEntity`, `SpawnConfig`, `TrialConfig`, `GDConfig`), not just a search miss.
- The `l2serial`-injected field population (`@SerialField`/`@OnInject`) was read from the decompiled golemdungeons/modulargolems side; the l2serial library itself was not decompiled, so the exact JSON→field name aliasing (e.g. whether `generauteChest` misspellings would be tolerated) is untested.
- Viewing/state visuals (`GolemTrialRenderer`, `TrialOverlay`, `HDModelPaths` in `content/client`) were not analysed in depth — they only affect rendering/overlay text.
