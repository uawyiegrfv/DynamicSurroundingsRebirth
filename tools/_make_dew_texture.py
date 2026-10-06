# -*- coding: utf-8 -*-
"""
露珠贴图生成器 —— dew_drop.png（8x8，雨露/晨露共用的静止珠形态）。

设计约束（都来自实测教训）：
1. 左右镜像对称：WaterDripParticle 的广告牌只绕 Y 轴转向玩家，贴图左右边缘
   会随视角互换——不对称贴图会读成"珠子在转"。vanilla drip_hang 同样对称。
2. 高光居中偏上：同上，偏侧高光会随旋转闪变。
3. 硬像素：边缘 1px alpha 渐减即可，不做半透明糊边（icicle 贴图管线同款）。
4. 机器检查：对称性 / 单连通 / 覆盖率 / 高光位置 / 孤立像素，违例即报错退出。
"""
import os
import numpy as np
import cv2

# ---- 三仓输出路径 ----
REPOS = [
    r"C:\qianyi\claude code\dsurround-forge-1.20.1",
    r"C:\qianyi\claude code\dsurround-neoforge-1.21.1",
    r"C:\qianyi\claude code\dsurround-neoforge-26.1",
]

W = H = 8

def build():
    """返回 (H, W, 4) uint8 RGBA 画布。"""
    img = np.zeros((H, W, 4), np.uint8)

    # 圆盘轮廓：每行的 x 范围（半径 3.2，圆心 (3.5, 4.0)）
    rows = {
        1: (3, 4),
        2: (2, 5),
        3: (1, 6),
        4: (1, 6),
        5: (1, 6),
        6: (2, 5),
        7: (3, 4),
    }
    EDGE_ALPHA = 210
    for y, (x0, x1) in rows.items():
        for x in range(x0, x1 + 1):
            on_edge = (x == x0 or x == x1) and y in (2, 6)  # 侧缘收窄行算边缘
            if y in (1, 7) or on_edge:
                a = EDGE_ALPHA
            else:
                a = 255
            if y <= 2:
                r, g, b = 205, 232, 252      # 上体：近白淡蓝
            elif y <= 4:
                r, g, b = 178, 216, 248      # 中体
            elif y <= 5:
                r, g, b = 160, 204, 244
            else:
                r, g, b = 108, 158, 224      # 下缘：深蓝（承重感）
            img[y, x] = (r, g, b, a)

    # 高光：居中偏上 2x1 + 1px 副高光（镜像安全）
    img[2, 3] = (242, 250, 255, 255)
    img[2, 4] = (236, 246, 255, 255)
    img[3, 3] = (226, 240, 255, 255)
    img[3, 4] = (226, 240, 255, 255)
    # 镜像 mottle：一对对称的微亮像素
    img[5, 2] = (196, 228, 250, 255)
    img[5, 5] = (196, 228, 250, 255)
    return img

def check(img):
    errs = []
    a = img[:, :, 3].astype(np.int32)
    rgb = img[:, :, :3].astype(np.int32)
    opaque = a >= 128

    # 1. 左右镜像对称
    if not np.array_equal(a, a[:, ::-1]):
        errs.append("alpha 不左右对称（广告牌绕 Y 旋转会闪变）")
    diff = np.abs(rgb - rgb[:, ::-1]).max()
    if diff > 8:
        errs.append(f"颜色不左右对称（最大差 {diff} > 8）")

    # 2. 单连通 + 填充率
    n, _ = cv2.connectedComponents(opaque.astype(np.uint8), connectivity=8)
    if n != 2:  # 0 背景 + 1 前景
        errs.append(f"不透明像素不单连通（components={n - 1}）")
    ys, xs = np.nonzero(opaque)
    if len(ys):
        bh, bw = ys.max() - ys.min() + 1, xs.max() - xs.min() + 1
        fill = opaque.sum() / (bh * bw)
        if fill < 0.68:  # 圆的理论上限 = π/4 ≈ 0.785，0.68 以下才是真破洞
            errs.append(f"外接框填充率 {fill:.2f} < 0.68（轮廓破洞）")

    # 3. 覆盖率
    cov = 100.0 * opaque.sum() / (W * H)
    if not (40 <= cov <= 58):
        errs.append(f"覆盖率 {cov:.0f}% 越界（期望 40-58）")

    # 4. 高光位置：中心列、上半
    bright = rgb[:, :, :3].sum(axis=2)
    bright[~opaque] = 0
    hy, hx = np.unravel_index(np.argmax(bright), bright.shape)
    if hx not in (3, 4):
        errs.append(f"最亮像素列 {hx} 不在中心列 3/4")
    if hy > 3:
        errs.append(f"最亮像素行 {hy} 不在上半（>3）")

    # 5. 无孤立像素
    pads = np.pad(opaque, 1)
    nb = sum(pads[dy:dy + H, dx:dx + W] for dy in (0, 1, 2) for dx in (0, 1, 2)) - opaque
    if (opaque & (nb == 0)).any():
        errs.append("存在孤立像素")

    return errs, cov

def main():
    img = build()
    errs, cov = check(img)
    if errs:
        for e in errs:
            print("CHECK FAIL:", e)
        raise SystemExit(1)
    print(f"machine checks passed (coverage {cov:.0f}%)")

    # 预览（16x 最近邻放大）
    prev = cv2.resize(img, (W * 16, H * 16), interpolation=cv2.INTER_NEAREST)
    bg = np.full_like(prev, 40)
    a = prev[:, :, 3:4].astype(np.float32) / 255.0
    prev = (prev[:, :, :3].astype(np.float32) * a + bg[:, :, :3].astype(np.float32) * (1 - a)).astype(np.uint8)
    out_prev = r"C:\qianyi\_foliage_survey\dew_drop_preview.png"
    cv2.imwrite(out_prev, cv2.cvtColor(prev, cv2.COLOR_RGB2BGR))
    print("preview:", out_prev)

    for repo in REPOS:
        tdir = os.path.join(repo, "src", "main", "resources", "assets", "dsurround", "textures", "particle")
        jdir = os.path.join(repo, "src", "main", "resources", "assets", "dsurround", "particles")
        os.makedirs(tdir, exist_ok=True)
        os.makedirs(jdir, exist_ok=True)
        # RGBA -> PNG（cv2 用 BGRA）
        bgra = cv2.cvtColor(img, cv2.COLOR_RGBA2BGRA)
        ok, buf = cv2.imencode(".png", bgra)
        assert ok
        with open(os.path.join(tdir, "dew_drop.png"), "wb") as f:
            f.write(buf.tobytes())
        with open(os.path.join(jdir, "dew_drop.json"), "w", encoding="utf-8") as f:
            f.write('{\n  "textures": [\n    "dsurround:dew_drop"\n  ]\n}')
        print("written:", repo)

if __name__ == "__main__":
    main()
