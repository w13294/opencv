"""
画出大靶的环带结构, 看清 0.78R 内外各是什么
"""
import cv2
import numpy as np

IMG_PATH = r'C:\Users\91299\Desktop\opencv\opencv\scratch\A4_paper_with_two_black_quadra_2026-08-03T06-02-12.png'
img = cv2.imread(IMG_PATH)
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

cx, cy, R = 688, 384, 230

# 提取 ROI 并放大
roi = gray[cy-R:cy+R, cx-R:cx+R].copy()
roi_big = cv2.resize(roi, (920, 920), interpolation=cv2.INTER_NEAREST)
cv2.imwrite(r'C:\Users\91299\Desktop\opencv\opencv\scratch\debug_real\12_roi_big.png', roi_big)

# 二值化 (Otsu 不取反, 黑=0 白=255)
_, bin_roi = cv2.threshold(roi, 0, 255, cv2.THRESH_OTSU)
bin_big = cv2.resize(bin_roi, (920, 920), interpolation=cv2.INTER_NEAREST)
cv2.imwrite(r'C:\Users\91299\Desktop\opencv\opencv\scratch\debug_real\13_bin_roi_big.png', bin_big)

# 画环带
vis = cv2.cvtColor(roi, cv2.COLOR_GRAY2BGR)
colors = [(0,0,255), (0,255,0), (255,0,0), (255,255,0), (0,255,255)]
for i, r_frac in enumerate([0.0, 0.30, 0.45, 0.60, 0.78, 1.0]):
    cv2.circle(vis, (R, R), int(R*r_frac), colors[i % 5], 1)
cv2.imwrite(r'C:\Users\91299\Desktop\opencv\opencv\scratch\debug_real\14_rings.png',
            cv2.resize(vis, (920, 920), interpolation=cv2.INTER_NEAREST))

# 测每个环带宽度上的灰度 (沿 x 轴正向扫描)
print("沿 +x 轴扫描中心水平线 (y=R):")
for x in range(0, 2*R, 10):
    r = x - R  # 距中心
    frac = r / R if R > 0 else 0
    val = roi[R, x] if 0 <= x < roi.shape[1] else 0
    bar = '#' * int(val/5)
    print(f"  x={x:3d} (r/R={frac:+.2f}): gray={val:3d}  {bar}")
