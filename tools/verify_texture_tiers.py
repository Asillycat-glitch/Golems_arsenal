"""核对四个附件的「边框色 ↔ 原文件名」对应关系。

之前我按"用户给的左右顺序"去映射 hash，但用户上传时的原文件名本身也是证据
（golem_energy_upgrade_base.png 就应该是基础档）。本脚本把两边的证据都摆出来，
不写任何文件，只打印。
"""

import pathlib

from PIL import Image

ATTACH = pathlib.Path(r"C:\Users\24404\.dsh\attachments\v1\objects")

CASES = [
    ("golem_energy_upgrade_base.png", "26301daac4848fb4ac214ae8abd1037daba66d5c864914d5b34790a05be0787b"),
    ("golem_energy_upgrade - 副本.png", "a6baf7a9c103e0e5fdd03d3b46860b937833f816d0e85dbbe4e93eeea23ae973"),
    ("golem_energy_upgrade - 本.png", "dc19fe5d2925b477ea8ee2b6544bf02f5062f06ebae5ac55ca2406c13c62e1c6"),
    ("golem_energy_upgrade.png", "1653b67037fec5904946e460420d5b0e02972ca297cc85100ac0813db7f5ffce"),
]


def border_color(path: pathlib.Path) -> tuple:
    """取左上角像素（(0,0) 恒在边框上）与上边中点，两者应同为边框色。"""
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    return img.getpixel((0, 0)), img.getpixel((w // 2, 0)), f"{w}x{h}"


def main() -> None:
    for name, sha in CASES:
        p = ATTACH / sha[:2] / sha
        if not p.is_file():
            print(f"{name}: 附件缺失 {sha}")
            continue
        tl, top, size = border_color(p)
        # 描述颜色主调，便于肉眼确认
        r, g, b = tl[0], tl[1], tl[2]
        if b > r and b > g:
            tone = "紫/蓝调"
        elif g > r and g > b:
            tone = "绿调"
        elif r > g and r > b:
            tone = "红调"
        else:
            tone = "青/灰调"
        print(f"{name:<32} {size}  角点=({r},{g},{b})  上中=({top[0]},{top[1]},{top[2]})  -> {tone}")


if __name__ == "__main__":
    main()
