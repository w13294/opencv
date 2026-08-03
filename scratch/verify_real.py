"""
使用真实手机截图验证检测算法, 输出每个步骤的中间结果
"""
import cv2
import numpy as np
import os

IMG_PATH = r'C:\Users\91299\Desktop\opencv\opencv\scratch\A4_paper_with_two_black_quadra_2026-08-03T06-02-12.png'
OUT_DIR = r'C:\Users\91299\Desktop\opencv\opencv\scratch\debug_real'
os.makedirs(OUT_DIR, exist_ok=True)

img = cv2.imread(IMG_PATH)
print(f"image shape: {img.shape}")
h, w = img.shape[:2]
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
print(f"gray range: [{gray.min()}, {gray.max()}]")

# 1) 中值滤波
blurred = cv2.medianBlur(gray, 5)

# 2) Otsu + INV
ret, thresh = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
print(f"Otsu threshold = {ret}")
cv2.imwrite(os.path.join(OUT_DIR, '01_thresh.png'), thresh)

# 3) 闭运算
kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
closed = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel)
cv2.imwrite(os.path.join(OUT_DIR, '02_closed.png'), closed)

# 4) 找轮廓
contours, hierarchy = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
print(f"contours found: {len(contours)}")
img_area = h * w

# 画所有轮廓
vis_all = img.copy()
cv2.drawContours(vis_all, contours, -1, (0, 255, 0), 2)
cv2.imwrite(os.path.join(OUT_DIR, '03_all_contours.png'), vis_all)

# 按面积排序看看前10个
areas = [(i, cv2.contourArea(c)) for i, c in enumerate(contours)]
areas.sort(key=lambda x: -x[1])
print("Top 10 contour areas:")
for i, a in areas[:10]:
    print(f"  contour[{i}]: area={a:.0f} ({a*100/img_area:.1f}%)")

# 5) 模拟 findQuadrantCandidates
print("\n=== findQuadrantCandidates ===")
candidates = []
for idx, contour in enumerate(contours):
    area = cv2.contourArea(contour)
    if area < 200 or area > img_area * 0.7:
        continue
    if len(contour) < 5:
        continue
    try:
        ellipse = cv2.fitEllipse(contour)
    except Exception as e:
        continue

    w_e, h_e = ellipse[1]
    ratio = min(w_e, h_e) / max(w_e, h_e) if max(w_e, h_e) > 0 else 0
    cx, cy = ellipse[0]
    radius = (w_e + h_e) / 4.0

    print(f"  contour[{idx}]: area={area:.0f}, ratio={ratio:.2f}, center=({cx:.0f},{cy:.0f}), r={radius:.0f}")

    if ratio < 0.6:
        print(f"    -> SKIP: ratio too low")
        continue

    margin = radius * 0.5 + 10
    if cx < margin or cx > w - margin or cy < margin or cy > h - margin:
        print(f"    -> SKIP: too close to edge")
        continue

    # 象限分析
    r = radius * 0.8
    x0, y0 = max(0, int(cx - r)), max(0, int(cy - r))
    x1, y1 = min(w - 1, int(cx + r)), min(h - 1, int(cy + r))
    if x1 <= x0 or y1 <= y0:
        continue

    q_black = [0.0, 0.0, 0.0, 0.0]  # 每象限"靶像素"数(反相后白色)
    q_cx = [0.0, 0.0, 0.0, 0.0]
    q_cy = [0.0, 0.0, 0.0, 0.0]
    black_count = 0
    white_count = 0

    roi = closed[y0:y1+1, x0:x1+1]
    print(f"    ROI: x=[{x0},{x1}] y=[{y0},{y1}]")

    for y in range(y0, y1+1):
        for x in range(x0, x1+1):
            dx = x - cx
            dy = y - cy
            if dx*dx + dy*dy > r*r:
                continue
            angle = np.degrees(np.arctan2(dy, dx))
            if angle < 0:
                angle += 360
            q = int(angle / 90) % 4
            pixel = closed[y, x]
            if pixel > 128:
                q_black[q] += 1
                q_cx[q] += x
                q_cy[q] += y
                black_count += 1
            else:
                white_count += 1

    print(f"    q_black = {[f'{v:.0f}' for v in q_black]}")
    black_quads = sum(1 for v in q_black if v > 5000)
    print(f"    black_quadrants (>{5000}) = {black_quads}")

    if black_quads != 2:
        print(f"    -> SKIP: black_quads != 2")
        continue

    black_qs = [i for i, v in enumerate(q_black) if v > 5000]
    a, b = black_qs
    if abs(a - b) != 2:
        print(f"    -> SKIP: not diagonal")
        continue

    ratio_bw = black_count / white_count if white_count > 0 else 999
    print(f"    -> VALID! quad=[{a},{b}] black_white_ratio={ratio_bw:.2f}")
    candidates.append((idx, ellipse, q_black))

# 6) 在原图上画候选
vis = img.copy()
for idx, ellipse, _ in candidates:
    cv2.ellipse(vis, ellipse, (0, 255, 255), 3)
    cx, cy = ellipse[0]
    cv2.putText(vis, f"#{idx}", (int(cx)-30, int(cy)+5),
                cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 0, 255), 2)
cv2.imwrite(os.path.join(OUT_DIR, '04_candidates.png'), vis)
print(f"\nTotal candidates: {len(candidates)}")
print(f"Output saved to {OUT_DIR}")
