"""一次性校验脚本：确认本次新增/改动的 JSON 资源在 UTF-8 下合法，
并检查两份语言文件的键集合是否对齐（只报差异，不改任何文件）。"""
import json
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parents[1]
res = root / "src" / "main" / "resources"

targets = []
for pat in ("*alloy_energy*", "radiation_*"):
    targets += sorted((res / "assets/golems_arsenal/models/item").glob(pat + ".json"))
    targets += sorted((res / "data/golems_arsenal/recipes").glob(pat + ".json"))
targets += sorted((res / "data/golems_arsenal/tags/items").glob("energy_*.json"))
lang_dir = res / "assets/golems_arsenal/lang"

bad = 0
for path in targets:
    try:
        json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001 - 一次性校验脚本
        print(f"!! 非法 JSON: {path.name} -> {exc}")
        bad += 1

langs = {}
for name in ("zh_cn.json", "en_us.json"):
    path = lang_dir / name
    try:
        langs[name] = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        print(f"!! 语言文件非法: {name} -> {exc}")
        bad += 1
        langs[name] = {}

print(f"JSON 校验：{len(targets)} 个资源文件，非法 {bad} 个")
for name, data in langs.items():
    print(f"  {name}: {len(data)} 键")

if len(langs) == 2:
    zh, en = set(langs["zh_cn.json"]), set(langs["en_us.json"])
    only_zh, only_en = sorted(zh - en), sorted(en - zh)
    print(f"  仅 zh 有：{only_zh if only_zh else '（无）'}")
    print(f"  仅 en 有：{only_en if only_en else '（无）'}")

    new_keys = [
        "item.golems_arsenal.infused_alloy_energy_upgrade",
        "item.golems_arsenal.elite_alloy_energy_upgrade",
        "item.golems_arsenal.ultimate_alloy_energy_upgrade",
        "item.golems_arsenal.radiation_generator_upgrade_1",
        "item.golems_arsenal.radiation_generator_upgrade_3",
        "item.golems_arsenal.radiation_shielding_upgrade_3",
        "upgrade.golems_arsenal.radiation_generator",
        "upgrade.golems_arsenal.radiation_generator.desc",
        "upgrade.golems_arsenal.radiation_shielding",
        "upgrade.golems_arsenal.radiation_shielding.desc",
    ]
    print("\n新增键抽查（中文原文）：")
    missing = 0
    for key in new_keys:
        value = langs["zh_cn.json"].get(key)
        if value is None:
            print(f"  !! 缺失 {key}")
            missing += 1
        else:
            print(f"  {key} = {value}")
    print(f"  缺失 {missing} 个")

# 占位符数量必须一致：带 %s 的键在两种语言里个数要相同，否则游戏内格式化会出错
if len(langs) == 2:
    print("\n占位符数量比对（%s / %d 这类）：")
    mismatched = []
    for key in sorted(set(langs["zh_cn.json"]) & set(langs["en_us.json"])):
        z, e = langs["zh_cn.json"][key], langs["en_us.json"][key]
        if not isinstance(z, str) or not isinstance(e, str):
            continue
        if z.count("%s") != e.count("%s") or z.count("%d") != e.count("%d"):
            mismatched.append((key, z, e))
    if mismatched:
        for key, z, e in mismatched:
            print(f"  !! {key}\n     zh: {z}\n     en: {e}")
    else:
        print("  全部一致")
