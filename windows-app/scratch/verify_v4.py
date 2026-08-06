"""
直接用 Otsu 输出的二值图, 找所有"接近圆"的轮廓 (不管 hierarchy),
按"圆内含 4 个圆角矩形"作为靶标判据
"""
import cv2
import numpy as np
import os

IMG_PATH = r'C:\Users\91299\Desktop\opencv\opencv\scratch\A4_paper_with_two_black_quadra_2026-08-03T06-02-12.png'
OUT_DIR = r'C:\Users\91299\Desktop\opencv\opencv\scratch\debug_real'
os.makedirs(OUT_DIR, exist_ok=True)

img = cv2.imread(IMG_PATH)
h, w = img.shape[:2]
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

_, thresh = cv2.threshold(cv2.medianBlur(gray, 5), 0, 255, cv2.THRESH_OTSU)
kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
closed = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel)

# RETR_LIST 简单拿所有
contours, _ = cv2.findContours(closed, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
print(f"total: {len(contours)}")

# 按面积分两组: 大的 (外环) vs 小的 (内部黑色象限)
sorted_contours = sorted(contours, key=lambda c: -cv2.contourArea(c))
for i, c in enumerate(sorted_contours[:10]):
    a = cv2.contourArea(c)
    if len(c) < 5:
        continue
    try:
        e = cv2.fitEllipse(c)
    except:
        continue
    w_e, h_e = e[1]
    r = min(w_e, h_e) / max(w_e, h_e)
    cx, cy = e[0]
    print(f"  [{i}] area={a:7.0f} ratio={r:.2f} center=({cx:.0f},{cy:.0f}) R={(w_e+h_e)/4:.0f}")

# ──── 找外环 (圆形度高 + 面积较大 + 远离边缘) ────
img_area = h * w
outer_candidates = []
for c in contours:
    a = cv2.contourArea(c)
    if a < img_area * 0.01 or a > img_area * 0.4:
        continue
    if len(c) < 5:
        continue
    try:
        e = cv2.fitEllipse(c)
    except:
        continue
    w_e, h_e = e[1]
    r = min(w_e, h_e) / max(w_e, h_e)
    if r < 0.6:
        continue
    cx, cy = e[0]
    radius = (w_e + h_e) / 4.0
    if cx < radius or cx > w - radius or cy < radius or cy > h - radius:
        continue
    outer_candidates.append((c, e, cx, cy, radius, a))
print(f"\n圆形候选: {len(outer_candidates)}")
for c, e, cx, cy, r, a in outer_candidates:
    print(f"  area={a:.0f}, center=({cx:.0f},{cy:.0f}), R={r:.0f}")

# ──── 对每个外环候选, 检查其内部 (距中心 < 0.7R) 的所有小轮廓 ────
# 如果正好有 4 个大小相近的小轮廓, 且它们的中心分布在 4 个方向, 则是靶标
final_targets = []
for oc, oe, ocx, ocy, oR, oa in outer_candidates:
    inner_blacks = []
    for c in contours:
        if c is oc:
            continue
        ca = cv2.contourArea(c)
        if ca < 100 or ca > oa * 0.3:  # 象限面积应远小于外环
            continue
        # 质心
        M = cv2.moments(c)
        if M["m00"] < 1:
            continue
        cqx = M["m10"] / M["m00"]
        cqy = M["m01"] / M["m00"]
        # 必须在靶内
        dist = np.sqrt((cqx - ocx)**2 + (cqy - ocy)**2)
        if dist > oR * 0.85:
            continue
        # 形状: 圆形度/椭圆度
        if len(c) < 5:
            continue
        try:
            ce = cv2.fitEllipse(c)
        except:
            continue
        cw, ch = ce[1]
        cr = min(cw, ch) / max(cw, ch)
        if cr < 0.4:  # 黑色象限大致是"三角扇形", 椭圆拟合会扁
            continue
        inner_blacks.append({'area': ca, 'cx': cqx, 'cy': cqy, 'ratio': cr, 'contour': c, 'ellipse': ce})
    print(f"\nOuter ({ocx:.0f},{ocy:.0f}) R={oR:.0f}: {len(inner_blacks)} inner blacks")
    for ib in inner_blacks:
        ang = np.degrees(np.arctan2(ib['cy']-ocy, ib['cx']-ocx)) % 360
        print(f"   area={ib['area']:.0f}, center=({ib['cx']:.0f},{ib['cy']:.0f}), angle={ang:.0f}°, ratio={ib['ratio']:.2f}")
    if len(inner_blacks) != 4:
        continue
    # 面积相近 (前 70% 排序)
    areas = sorted([ib['area'] for ib in inner_blacks])
    if areas[0] / areas[3] < 0.5:
        continue
    # 4 个中心角度两两差在 [60°, 120°]
    angles = sorted([np.degrees(np.arctan2(ib['cy']-ocy, ib['cx']-ocx)) % 360 for ib in inner_blacks])
    diffs = [angles[(i+1)%4] - angles[i] for i in range(4)]
    # 处理跨 360° 边界
    if angles[0] < 30 and angles[3] > 330:
        diffs[0] = (360 - angles[3]) + angles[0]
    if not all(45 < d < 135 for d in diffs):
        continue
    final_targets.append({'outer_ellipse': oe, 'center': (ocx, ocy), 'radius': oR,
                          'quads': inner_blacks})

print(f"\n=== Final targets: {len(final_targets)} ===")
for t in final_targets:
    print(f"  Center {t['center']}, R={t['radius']:.0f}, {len(t['quads'])} quads")

# 画结果
vis = img.copy()
for t in final_targets:
    cv2.ellipse(vis, t['outer_ellipse'], (0, 255, 255), 3)
    for q in t['quads']:
        cv2.circle(vis, (int(q['cx']), int(q['cy'])), 6, (0, 0, 255), -1)
cv2.imwrite(os.path.join(OUT_DIR, '17_final.png'),
            cv2.resize(vis, (1200, 1200*h//w)))
print(f"\nOutput: {OUT_DIR}/17_final.png")
