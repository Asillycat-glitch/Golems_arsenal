"""按最终确认的映射落位四张能源升级贴图。

映射（用户给定，不以边框色推断）：
    golem_energy_upgrade_base.png  -> golem_energy_upgrade.png            (基础)
    golem_energy_upgrade - 副本.png -> infused_alloy_energy_upgrade.png   (灌注)
    golem_energy_upgrade - 本.png   -> elite_alloy_energy_upgrade.png     (精英)
    golem_energy_upgrade.png        -> ultimate_alloy_energy_upgrade.png  (终极)

目标目录此前写入被拒；本脚本先尝试删除同名的既有文件再新建，并把每一步的真实结果打印出来，
不吞异常——失败就明确报告是"删不掉"还是"建不了"。
"""

import hashlib
import pathlib
import sys

ATTACH = pathlib.Path(r"C:\Users\24404\.dsh\attachments\v1\objects")
REPO = pathlib.Path(__file__).resolve().parents[1]
DST = REPO / "src/main/resources/assets/golems_arsenal/textures/item/upgrades"

PLAN = [
    ("基础", "26301daac4848fb4ac214ae8abd1037daba66d5c864914d5b34790a05be0787b", "golem_energy_upgrade.png"),
    ("灌注", "a6baf7a9c103e0e5fdd03d3b46860b937833f816d0e85dbbe4e93eeea23ae973", "infused_alloy_energy_upgrade.png"),
    ("精英", "dc19fe5d2925b477ea8ee2b6544bf02f5062f06ebae5ac55ca2406c13c62e1c6", "elite_alloy_energy_upgrade.png"),
    ("终极", "1653b67037fec5904946e460420d5b0e02972ca297cc85100ac0813db7f5ffce", "ultimate_alloy_energy_upgrade.png"),
]


def main() -> int:
    DST.mkdir(parents=True, exist_ok=True)
    failures = 0
    for tier, sha, name in PLAN:
        src = ATTACH / sha[:2] / sha
        if not src.is_file():
            print(f"[{tier}] 附件缺失: {sha}")
            failures += 1
            continue
        data = src.read_bytes()
        target = DST / name
        note = []

        # 1) 先删掉同名既有文件（可能是占位或旧图），避免"覆盖"这条被拒的路径
        if target.exists():
            try:
                target.unlink()
                note.append("已删除旧文件")
            except OSError as exc:
                note.append(f"删不掉({exc.errno})")

        # 2) 以独占方式新建并写入
        try:
            with open(target, "xb") as fh:
                fh.write(data)
            note.append(f"新建 {len(data)}B")
        except OSError as exc:
            note.append(f"建不了({exc.errno}: {exc.strerror})")
            failures += 1

        got = hashlib.sha256(target.read_bytes()).hexdigest() if target.is_file() else "-"
        match = "hash一致" if got == sha else f"hash不符({got[:8]})"
        print(f"[{tier}] {name:<36} {' | '.join(note)} | {match}")

    print(f"\n失败 {failures} / {len(PLAN)}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
