"""
检查大靶内部的实际灰度分布
"""
import cv2
import numpy as np

IMG_PATH = r'C:\Users\91299\Desktop\opencv\opencv\scratch\A4_paper_with_two_black_quadra_2026-08-03T06-02-12.png'
img = cv2.imread(IMG_PATH)
h, w = img.shape[:2]
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
print(f"image: {w}x{h}, gray: min={gray.min()}, max={gray.max()}, mean={gray.mean():.0f}")

# 大靶中心 (688, 384), r=230
cx, cy, R = 688, 384, 230

# 提取整个 ROI 并显示
roi = gray[cy-R:cy+R, cx-R:cx+R]
print(f"ROI shape: {roi.shape}, mean={roi.mean():.0f}, std={roi.std():.0f}")
cv2.imwrite(r'C:\Users\91299\Desktop\opencv\opencv\scratch\debug_real\06_roi.png', roi)

# 内圆 0.78R 区域
r_inner = int(R * 0.78)
yy, xx = np.ogrid[:roi.shape[0], :roi.shape[1]]
dist = np.sqrt((xx - R)**2 + (yy - R)**2)
mask_inner = dist < r_inner
inner = roi[mask_inner]
print(f"inner 0.78R: mean={inner.mean():.0f}, std={inner.std():.0f}, min={inner.min()}, max={inner.max()}")
print(f"  histogram: {np.histogram(inner, bins=10, range=(0, 255))[0]}")

# 4 象限灰度 (在内部)
yy2, xx2 = np.meshgrid(np.arange(roi.shape[0]), np.arange(roi.shape[1]), indexing='ij')
dx = xx2 - R
dy = yy2 - R
angle = np.degrees(np.arctan2(dy, dx))
angle[angle < 0] += 360
quad = (angle / 90).astype(int) % 4
m_inner = dist < r_inner
for q in range(4):
    m = m_inner & (quad == q)
    pixels = roi[m]
    print(f"  Q{q}: mean={pixels.mean():.0f}, std={pixels.std():.0f}, cnt={m.sum()}, min={pixels.min()}, max={pixels.max()}")

# 取一张 Otsu 后的二值化图, 看看二值图里大靶内部是什么样
blurred = cv2.medianBlur(gray, 5)
ret, thresh = cv2.threshold(blurred, 0, 255, cv2.THRESH_OTSU)
print(f"\nOtsu (without INV) = {ret}")
# 提取大靶的 ROI 二值图
roi_t = thresh[cy-R:cy+R, cx-R:cx+R]
cv2.imwrite(r'C:\Users\91299\Desktop\opencv\opencv\scratch\debug_real\07_roi_otsu.png', roi_t)
for q in range(4):
    m = m_inner & (quad == q)
    pixels = roi_t[m]
    print(f"  Q{q} (binary): black={ (pixels<128).sum()}, white={ (pixels>=128).sum()}")

# 输出 4 象限分割的可视化
vis = roi.copy()
colors = [(0,0,255), (0,255,0), (255,0,0), (255,255,0)]
for q in range(4):
    vis_quad = vis.copy()
    vis_quad[~(m_inner & (quad == q))] = 128
    cv2.imwrite(f'C:\\Users\\91299\\Desktop\\opencv\\opencv\\scratch\\debug_real\\08_q{q}.png', vis_quad)
