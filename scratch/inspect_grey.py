"""
直接看大靶内部的真实灰度分布 (不用 Otsu)
"""
import cv2
import numpy as np

IMG_PATH = r'C:\Users\91299\Desktop\opencv\opencv\scratch\A4_paper_with_two_black_quadra_2026-08-03T06-02-12.png'
img = cv2.imread(IMG_PATH)
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

cx, cy, R = 688, 384, 230

# 提取大靶 0.78R ROI 灰度
roi = gray[cy-R:cy+R, cx-R:cx+R].copy()
H, W = roi.shape
yy, xx = np.ogrid[:H, :W]
dy = yy - R
dx = xx - R
dist = np.sqrt(dx*dx + dy*dy)
mask_inner = dist < R * 0.78
mask_outer = (dist >= R * 0.78) & (dist < R * 1.0)

# 内部 4 象限灰度
angle = np.degrees(np.arctan2(dy, dx))
angle[angle < 0] += 360

print("内圆 0.78R 各象限灰度统计:")
for q in range(4):
    m = mask_inner & (angle >= q*90) & (angle < (q+1)*90)
    pixels = roi[m]
    print(f"  Q{q} ({q*90:3d}°~{(q+1)*90:3d}°): mean={pixels.mean():.0f}, std={pixels.std():.0f}, "
          f"min={pixels.min()}, max={pixels.max()}, median={int(np.median(pixels))}")
    # 直方图
    hist, _ = np.histogram(pixels, bins=8, range=(0, 256))
    print(f"     hist: {hist.tolist()}")

# 外环 (0.78R ~ 1.0R) 应该是黑色靶外环
print("\n外环 0.78R~1.0R 灰度统计:")
for q in range(4):
    m = mask_outer & (angle >= q*90) & (angle < (q+1)*90)
    pixels = roi[m]
    print(f"  Q{q}: mean={pixels.mean():.0f}, min={pixels.min()}, max={pixels.max()}")

# 整张图的灰度直方图
hist, _ = np.histogram(gray.ravel(), bins=16, range=(0, 256))
print(f"\n整图灰度直方图 (16 bin): {hist.tolist()}")
