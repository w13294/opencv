"""
修复版: 直接用灰度图做象限分析, 避开反相二值化的连通性问题
思路:
  1. Otsu 找外环 (用 RETR_EXTERNAL, 简单可靠)
  2. 灰度图上对 ROI (0.8R) 统计每象限平均灰度
  3. 黑色象限灰度低, 白色象限灰度高
  4. 验证: 2 个对角象限灰度低 + 2 个对角象限灰度高, 且差异显著
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
print(f"image: {w}x{h}, gray range: [{gray.min()}, {gray.max()}]")

# 中值滤波 + Otsu + INV (用于找外环)
blurred = cv2.medianBlur(gray, 5)
ret, thresh = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
print(f"Otsu = {ret}")
kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
closed = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel)

# 找所有轮廓
contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
print(f"contours: {len(contours)}")
img_area = h * w

candidates = []
for idx, contour in enumerate(contours):
    area = cv2.contourArea(contour)
    if area < 200 or area > img_area * 0.7:
        continue
    if len(contour) < 5:
        continue
    try:
        ellipse = cv2.fitEllipse(contour)
    except:
        continue

    w_e, h_e = ellipse[1]
    ratio = min(w_e, h_e) / max(w_e, h_e)
    cx, cy = ellipse[0]
    radius = (w_e + h_e) / 4.0
    if ratio < 0.6:
        continue
    margin = radius * 0.5 + 10
    if cx < margin or cx > w - margin or cy < margin or cy > h - margin:
        continue

    # ──── 在灰度图上做 ROI 象限分析 ────
    # 关键修复: 用 GRAY 图, 不是 thresh; 直接算每象限平均灰度
    r = radius * 0.85  # 略放大一点, 覆盖到象限黑区
    x0, y0 = max(0, int(cx - r)), max(0, int(cy - r))
    x1, y1 = min(w - 1, int(cx + r)), min(h - 1, int(cy + r))
    if x1 <= x0 or y1 <= y0:
        continue

    # 排除外环区: 只统计 r < 0.78R 的内部区域 (避开外环)
    r_inner = radius * 0.78

    q_sum = [0.0, 0.0, 0.0, 0.0]
    q_cnt = [0, 0, 0, 0]

    # 用 numpy 向量化加速
    y_range = np.arange(y0, y1+1)
    x_range = np.arange(x0, x1+1)
    yy, xx = np.meshgrid(y_range, x_range, indexing='ij')
    dx = xx - cx
    dy = yy - cy
    dist2 = dx*dx + dy*dy
    # 只在内圆 0.78R 范围内统计
    mask = dist2 <= r_inner * r_inner
    if mask.sum() < 100:
        continue

    # 角度 (atan2 返回 [-pi, pi], 转换到 [0, 360))
    angle = np.degrees(np.arctan2(dy, dx))
    angle[angle < 0] += 360
    quad = (angle / 90).astype(int) % 4  # 0:右, 1:下, 2:左, 3:上

    roi_gray = gray[y0:y1+1, x0:x1+1]
    for q in range(4):
        m = mask & (quad == q)
        if m.sum() > 0:
            q_sum[q] = float(roi_gray[m].mean())
            q_cnt[q] = int(m.sum())

    print(f"  contour[{idx}]: area={area:.0f}, ratio={ratio:.2f}, center=({cx:.0f},{cy:.0f}), r={radius:.0f}")
    print(f"    q_mean_gray = {[f'{v:.0f}' for v in q_sum]} (cnt={[c for c in q_cnt]})")

    # 排序象限灰度: 低的=黑象限, 高的=白象限
    sorted_q = sorted(range(4), key=lambda q: q_sum[q])
    black_qs = sorted_q[:2]  # 灰度最低的2个
    white_qs = sorted_q[2:]  # 灰度最高的2个

    # 验证 1: 黑白象限灰度差要够大
    black_gray = (q_sum[black_qs[0]] + q_sum[black_qs[1]]) / 2
    white_gray = (q_sum[white_qs[0]] + q_sum[white_qs[1]]) / 2
    diff = white_gray - black_gray
    print(f"    black_gray={black_gray:.0f}, white_gray={white_gray:.0f}, diff={diff:.0f}")

    if diff < 30:  # 黑白灰度差至少30
        print(f"    -> SKIP: contrast too low")
        continue

    # 验证 2: 黑象限必须对角 (差为 2)
    a, b = black_qs
    if abs(a - b) != 2:
        print(f"    -> SKIP: black quads not diagonal: {black_qs}")
        continue

    # 验证 3: 灰度绝对值合理性 (黑象限<150, 白象限>100)
    if black_gray > 150:
        print(f"    -> SKIP: black quads too bright")
        continue
    if white_gray < 100:
        print(f"    -> SKIP: white quads too dark")
        continue

    print(f"    -> VALID! black quads={black_qs}")
    candidates.append((idx, ellipse, black_qs, q_sum))

# 画结果
vis = img.copy()
for idx, ellipse, black_qs, q_sum in candidates:
    cv2.ellipse(vis, ellipse, (0, 255, 255), 3)
    cx, cy = ellipse[0]
    cv2.putText(vis, f"#{idx}", (int(cx)-30, int(cy)+5),
                cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 0, 255), 2)
    # 标出黑象限
    for q in black_qs:
        ang = (q * 90 + 45) * np.pi / 180
        px = int(cx + (ellipse[1][0]/2) * 0.5 * np.cos(ang))
        py = int(cy + (ellipse[1][1]/2) * 0.5 * np.sin(ang))
        cv2.circle(vis, (px, py), 8, (0, 0, 255), -1)
cv2.imwrite(os.path.join(OUT_DIR, '05_candidates_v2.png'), vis)
print(f"\nTotal candidates: {len(candidates)}")
print(f"Output: {OUT_DIR}")
