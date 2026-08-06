"""
修正 mask 索引
"""
import cv2
import numpy as np

IMG_PATH = r'C:\Users\91299\Desktop\opencv\opencv\scratch\A4_paper_with_two_black_quadra_2026-08-03T06-02-12.png'
img = cv2.imread(IMG_PATH)
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

cx, cy, R = 688, 384, 230
_, binary = cv2.threshold(cv2.medianBlur(gray, 5), 0, 255, cv2.THRESH_OTSU)

# 提取 ROI
roi = binary[cy-R:cy+R, cx-R:cx+R].copy()
print(f"ROI shape: {roi.shape}, unique: {np.unique(roi)}")

# 在 ROI 内做 mask (注意坐标是 ROI 内的)
H, W = roi.shape
yy, xx = np.ogrid[:H, :W]
dy = yy - R
dx = xx - R
dist = np.sqrt(dx*dx + dy*dy)
mask_inner = dist < R * 0.78
print(f"mask_inner sum: {mask_inner.sum()}")

# 角度
angle = np.degrees(np.arctan2(dy, dx))
angle[angle < 0] += 360

# 统计每 5° 黑色像素比例 (黑=255 in Otsu without INV)
ratio = np.zeros(72)
for i in range(72):
    a = i * 5
    m = mask_inner & (angle >= a) & (angle < a + 5)
    if m.sum() > 0:
        ratio[i] = (roi[m] > 128).mean()

print("\n角度 -> 黑色占比 (5° 分辨率):")
for i in range(72):
    a = i * 5
    bar = '#' * int(ratio[i] * 50)
    print(f"  {a:3d}°: {ratio[i]:.2f}  {bar}")

# 找连续黑区间
in_black = ratio > 0.3
ranges = []
start = None
for i in range(72):
    if in_black[i] and start is None:
        start = i
    elif not in_black[i] and start is not None:
        ranges.append((start*5, (i-1)*5))
        start = None
if start is not None:
    ranges.append((start*5, 71*5))

print(f"\n黑色连续区间 (>30%):")
for s, e in ranges:
    print(f"  {s}° ~ {e}°")
