# -*- coding: utf-8 -*-
"""
积雪单元贴图生成器 —— snow_dot_a/b, snow_clump_a/b, snow_blob（8x8，三仓）。

美术口径（维护者 2026-10-06）：
- 直角为主、不要精细。露珠贴图当年做成了"珍珠/白砂糖"（太圆、太亮、太细），被否；
  雪单元的正确形态是**块状的雪**：整行整列的矩形台阶，没有圆弧、没有高光点。
- 不透明渲染层（OPAQUE sheet）：形状内 alpha 恒 255，靠 alpha discard 抠掉背景
  （icicle 同款理由：硬像素小 quad 在半透明层远距离会闪）。
- 颜色只有两阶：雪白主体 + 冷蓝阴影（只在最下行），不上高光。

机器检查（相对 dew 管线的差异）：
- 保留：单连通、无孤立像素、外接框填充率。
- 新增：**逐行连续**（每行的不透明像素必须是一整段——直角块状的结构保证）。
- 放弃：左右镜像对称（雪不规则；贴面渲染的镜像问题对不规则形不成立）。
"""
import os
import numpy as np
import cv2

REPOS = [
    r"C:\qianyi\claude code\dsurround-forge-1.20.1",
    r"C:\qianyi\claude code\dsurround-neoforge-1.21.1",
    r"C:\qianyi\claude code\dsurround-neoforge-26.1",
]

W = H = 8
SNOW = (246, 250, 255)     # 雪白（冷调，非纯白）
SHADE = (203, 219, 242)    # 冷蓝阴影（最下行）

def rows_to_img(rows):
    """rows: {y: (x0, x1)} 每行一段（含端点）。底行上阴影色。"""
    img = np.zeros((H, W, 4), np.uint8)
    ymax = max(rows)
    for y, (x0, x1) in rows.items():
        for x in range(x0, x1 + 1):
            r, g, b = (SHADE if y == ymax else SNOW)
            img[y, x] = (r, g, b, 255)
    return img

def build():
    """返回 {name: img}。全部直角台阶，无斜边无圆弧。"""
    out = {}
    # 雪点 A：2x2 小块 + 底行阴影
    out["snow_dot_a"] = rows_to_img({3: (3, 4), 4: (3, 4)})
    # 雪点 B：错位变体（3 宽，左移一像素）
    out["snow_dot_b"] = rows_to_img({4: (2, 4), 5: (2, 4)})
    # 雪粒团 A：三级台阶
    out["snow_clump_a"] = rows_to_img({2: (3, 5), 3: (2, 6), 4: (2, 6)})
    # 雪粒团 B：宽扁变体
    out["snow_clump_b"] = rows_to_img({3: (1, 5), 4: (1, 6), 5: (2, 6)})
    # 雪团：整块厚雪（单元上限形态，仍是局部块）
    out["snow_blob"] = rows_to_img({1: (2, 6), 2: (1, 6), 3: (1, 6), 4: (1, 6), 5: (2, 5)})
    return out

def check(name, img):
    errs = []
    a = img[:, :, 3].astype(np.int32)
    opaque = a > 0

    # 1. 单连通（8 邻域）
    n, _ = cv2.connectedComponents(opaque.astype(np.uint8), connectivity=8)
    if n != 2:
        errs.append(f"不单连通（components={n - 1}）")

    # 2. 逐行连续：每行不透明像素必须是一整段（直角块状结构保证）
    for y in range(H):
        xs = np.nonzero(opaque[y])[0]
        if len(xs) and (xs[-1] - xs[0] + 1) != len(xs):
            errs.append(f"y={y} 行内断开（非整段）")

    # 3. 无孤立像素（每个不透明像素至少 1 个 4 邻域同伴）
    for y in range(H):
        for x in range(W):
            if not opaque[y, x]:
                continue
            nb = 0
            for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
                ny, nx2 = y + dy, x + dx
                if 0 <= ny < H and 0 <= nx2 < W and opaque[ny, nx2]:
                    nb += 1
            if nb == 0:
                errs.append(f"孤立像素 ({x},{y})")

    # 4. 外接框填充率 ≥ 0.55（块状应填得满；圆只有 0.785 上限，块状理应更高）
    ys, xs = np.nonzero(opaque)
    bh, bw = ys.max() - ys.min() + 1, xs.max() - xs.min() + 1
    fill = opaque.sum() / (bh * bw)
    if fill < 0.55:
        errs.append(f"填充率 {fill:.2f} < 0.55")

    # 5. 尺寸下限：雪点 ≥2px 宽，其余 ≥3px
    min_w = 2 if "dot" in name else 3
    if bw < min_w:
        errs.append(f"宽 {bw} < {min_w}")
    return errs, fill

def main():
    shapes = build()
    previews = []
    for name, img in shapes.items():
        errs, fill = check(name, img)
        if errs:
            for e in errs:
                print(f"CHECK FAIL [{name}]:", e)
            raise SystemExit(1)
        print(f"[{name}] ok (fill {fill:.2f})")
        prev = cv2.resize(img, (W * 16, H * 16), interpolation=cv2.INTER_NEAREST)
        bg = np.full_like(prev, 40)
        a = prev[:, :, 3:4].astype(np.float32) / 255.0
        prev = (prev[:, :, :3].astype(np.float32) * a + bg[:, :, :3].astype(np.float32) * (1 - a)).astype(np.uint8)
        previews.append((name, prev))

    # 预览网格
    ch, cw = H * 16 + 4, W * 16 + 4
    grid = np.zeros((ch, cw * len(previews), 3), np.uint8)
    for i, (name, prev) in enumerate(previews):
        grid[:prev.shape[0], i * cw:i * cw + prev.shape[1]] = prev
    out_prev = r"C:\qianyi\_foliage_survey\snow_units_preview.png"
    cv2.imwrite(out_prev, cv2.cvtColor(grid, cv2.COLOR_RGB2BGR))
    print("preview:", out_prev)

    for repo in REPOS:
        tdir = os.path.join(repo, "src", "main", "resources", "assets", "dsurround", "textures", "particle")
        jdir = os.path.join(repo, "src", "main", "resources", "assets", "dsurround", "particles")
        os.makedirs(tdir, exist_ok=True)
        os.makedirs(jdir, exist_ok=True)
        for name, img in shapes.items():
            bgra = cv2.cvtColor(img, cv2.COLOR_RGBA2BGRA)
            ok, buf = cv2.imencode(".png", bgra)
            assert ok
            with open(os.path.join(tdir, name + ".png"), "wb") as f:
                f.write(buf.tobytes())
            with open(os.path.join(jdir, name + ".json"), "w", encoding="utf-8") as f:
                f.write('{\n  "textures": [\n    "dsurround:%s"\n  ]\n}' % name)
        print("written:", repo)

if __name__ == "__main__":
    main()
