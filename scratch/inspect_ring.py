"""
避开中心十字臂, 只看 0.4R~0.78R 环带, 这个环不包含十字臂 (十字臂在 <0.4R 区域)
"""
import cv2
import numpy as np

IMG_PATH = r'C:\Users\91299\Desktop\opencv\opencv\scratch\A4_paper_with_two_black_quadra_2026-08-03T06-02-12.png'
img = cv2.imread(IMG_PATH)
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

cx, cy, R = 688, 384, 230

# 提取 ROI
roi = gray[cy-R:cy+R, cx-R:cx+R].copy()
H, W = roi.shape
yy, xx = np.ogrid[:H, :W]
dy = yy - R
dx = xx - R
dist = np.sqrt(dx*dx + dy*dy)
angle = np.degrees(np.arctan2(dy, dx))
angle[angle < 0] += 360

# 不同环带的灰度
rings = [
    ("0.3R~0.45R (中心十字臂区)", 0.30, 0.45),
    ("0.45R~0.6R (中间环)", 0.45, 0.60),
    ("0.6R~0.78R (外环带)", 0.60, 0.78),
]
for name, r0, r1 in rings:
    m_ring = (dist >= R*r0) & (dist < R*r1)
    print(f"\n=== {name} ===")
    for q in range(4):
        m = m_ring & (angle >= q*90) & (angle < (q+1)*90)
        if m.sum() == 0: continue
        pixels = roi[m]
        print(f"  Q{q} ({q*90:3d}°~{(q+1)*90:3d}°): mean={pixels.mean():6.1f}, "
              f"median={int(np.median(pixels)):3d}, dark%={(pixels<80).mean()*100:5.1f}%, "
              f"bright%={(pixels>180).mean()*100:5.1f}%")

# 也试一下圆心 ~ 0.4R 的小环, 避开中心十字
print(f"\n=== 0.4R~0.55R (象限中部, 无十字) ===")
m_ring = (dist >= R*0.40) & (dist < R*0.55)
for q in range(4):
    m = m_ring & (angle >= q*90) & (angle < (q+1)*90)
    if m.sum() == 0: continue
    pixels = roi[m]
    print(f"  Q{q} ({q*90:3d}°~{(q+1)*90:3d}°): mean={pixels.mean():6.1f}, "
          f"median={int(np.median(pixels)):3d}, dark%={(pixels<80).mean()*100:5.1f}%, "
          f"bright%={(pixels>180).mean()*100:5.1f}%")
