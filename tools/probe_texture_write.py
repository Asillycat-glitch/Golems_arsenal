"""探针：确认能否在 assets 的 textures 目录写二进制（PNG）。
只写一个 1x1 的测试 png，随后删除；不碰任何既有文件。"""

import pathlib
import sys

from PIL import Image

REPO = pathlib.Path(__file__).resolve().parents[1]
TARGET_DIRS = [
    REPO / "src/main/resources/assets/golems_arsenal/textures/entity",
    REPO / "src/main/resources/assets/golems_arsenal/textures/item/upgrades",
]


def main() -> int:
    failures = 0
    for d in TARGET_DIRS:
        d.mkdir(parents=True, exist_ok=True)
        probe = d / "zz_probe.png"
        try:
            Image.new("RGBA", (1, 1), (255, 255, 255, 255)).save(probe, "PNG")
            print(f"OK   {d.relative_to(REPO)}  可写（{probe.stat().st_size} B）")
            probe.unlink()
            print(f"     已清理探针")
        except OSError as exc:
            print(f"DENY {d.relative_to(REPO)}  {exc}")
            failures += 1
    print(f"\n不可写目录数: {failures} / {len(TARGET_DIRS)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
