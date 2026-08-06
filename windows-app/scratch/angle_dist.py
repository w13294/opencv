"""
精确测量实拍图大靶黑色象限的角度位置
"""
import cv2
import numpy as np

IMG_PATH = r'C:\Users\91299\Desktop\opencv\opencv\scratch\A4_paper_with_two_black_quadra_2026-08-03T06-02-12.png'
img = cv2.imread(IMG_PATH)
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
h, w = gray.shape
print(f"image: {w}x{h}")

# 大靶 (688, 384), R=230
cx, cy, R = 688, 384, 230
gray_f = gray.astype(np.float32)

# Otsu (不取反), 黑=0, 白=255
_, binary = cv2.threshold(cv2.medianBlur(gray, 5), 0, 255, cv2.THRESH_OTSU)
cv2.imwrite(r'C:\Users\91299\Desktop\opencv\opencv\scratch\debug_real\10_binary.png', binary)

# 在 0.78R 内, 360 个 1° 角度分桶, 统计每度黑色像素数
yy, xx = np.ogrid[cy-R:cy+R, cx-R:cx+R]
dist = np.sqrt((xx - R)**2 + (yy - R)**2)
mask_inner = dist < R * 0.78

dy = yy - R
dx = xx - R
angle = np.degrees(np.arctan2(dy, dx))
angle[angle < 0] += 360

roi_bin = binary[cy-R:cy+R, cx-R:cx+R]
black_per_deg = np.zeros(360)
total_per_deg = np.zeros(360)
for a in range(360):
    m = mask_inner & (angle >= a) & (angle < a+1)
    if m.sum() > 0:
        black_per_deg[a] = (roi_bin[m] > 128).sum()
        total_per_deg[a] = m.sum()

# 黑色像素比例
ratio = black_per_deg / np.maximum(total_per_deg, 1)
print("\n角度 -> 黑色占比 (前 30°):")
for a in range(0, 360, 10):
    bar = '#' * int(ratio[a] * 50)
    print(f"  {a:3d}°: {ratio[a]:.2f}  {bar}")

# 找出连续黑色高占比的角度区间 (>40% 算黑)
threshold = 0.4
in_black = ratio > threshold
# 找连续区间
black_ranges = []
start = None
for a in range(360):
    if in_black[a] and start is None:
        start = a
    elif not in_black[a] and start is not None:
        black_ranges.append((start, a-1))
        start = None
if start is not None:
    black_ranges.append((start, 359))

print(f"\n黑色象限角度范围 (>{threshold*100:.0f}%):")
for s, e in black_ranges:
    if e - s > 5:  # 至少 5° 宽
        print(f"  {s}° ~ {e}° (宽 {e-s+1}°)")

# 把黑象限画出来
vis = img.copy()
for s, e in black_ranges:
    if e - s < 5:
        continue
    # 画扇形
    cv2.ellipse(vis, (int(cx), int(cy)), (int(R*0.78), int(R*0.78)),
                0, s, e, (0, 0, 255), 3)
cv2.imwrite(r'C:\Users\91299\Desktop\opencv\opencv\scratch\debug_real\11_black_ranges.png', vis)
