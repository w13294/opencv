"""
新算法: RETR_TREE 找外环, 然后取其"孙轮廓" = 4 个黑色象限
验证对 2 个靶都能识别
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

# 预处理
_, thresh = cv2.threshold(cv2.medianBlur(gray, 5), 0, 255, cv2.THRESH_OTSU)
kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
closed = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel)

# RETR_TREE
contours, hierarchy = cv2.findContours(closed, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
h_arr = hierarchy[0]
print(f"total contours: {len(contours)}")

def get_first_child(h_arr, idx):
    return h_arr[idx][2]

def get_all_children(h_arr, idx):
    """获取 idx 的所有直接子轮廓 (同级链)"""
    res = []
    cur = h_arr[idx][2]
    while cur != -1:
        res.append(cur)
        cur = h_arr[cur][0]  # next sibling
    return res

# ──── 找外环候选 (无父) ────
img_area = h * w
candidates = []
for i in range(len(contours)):
    if h_arr[i][3] != -1:  # 有父
        continue
    a = cv2.contourArea(contours[i])
    if a < img_area * 0.01 or a > img_area * 0.5:
        continue
    if len(contours[i]) < 5:
        continue
    # 拟合椭圆
    try:
        ellipse = cv2.fitEllipse(contours[i])
    except:
        continue
    w_e, h_e = ellipse[1]
    ratio = min(w_e, h_e) / max(w_e, h_e)
    if ratio < 0.6:
        continue
    cx, cy = ellipse[0]
    radius = (w_e + h_e) / 4.0
    margin = radius * 0.3
    if cx < margin or cx > w - margin or cy < margin or cy > h - margin:
        continue

    # 取第一层子轮廓 (内部白色洞)
    first_child = h_arr[i][2]
    if first_child == -1:
        continue
    # 第二层子轮廓 = 黑色象限 (4 块)
    quads = get_all_children(h_arr, first_child)
    if len(quads) != 4:
        print(f"  [{i}] outer ring but has {len(quads)} quads, not 4")
        continue
    # 验证 4 个象限: 面积相近 + 中心分布在 4 个方向
    quad_data = []
    for q in quads:
        a_q = cv2.contourArea(contours[q])
        if a_q < 100:
            continue
        # 用矩计算质心
        M = cv2.moments(contours[q])
        if M["m00"] < 1:
            continue
        qx = M["m10"] / M["m00"]
        qy = M["m01"] / M["m00"]
        quad_data.append({'idx': q, 'area': a_q, 'cx': qx, 'cy': qy, 'angle': np.degrees(np.arctan2(qy-cy, qx-cx)) % 360})
    if len(quad_data) != 4:
        print(f"  [{i}] only {len(quad_data)} valid quads")
        continue
    # 4 个象限面积应相近
    areas = [d['area'] for d in quad_data]
    a_max, a_min = max(areas), min(areas)
    if a_min / a_max < 0.5:  # 面积差异不能太大
        print(f"  [{i}] quad areas too different: {areas}")
        continue
    # 4 个中心应分布在 4 个大致 90° 方向
    angles = sorted([d['angle'] for d in quad_data])
    diffs = [angles[(i+1)%4] - angles[i] for i in range(4)]
    # 修复: 最后一个 diff 应该 < 第一个 (环绕)
    # 更简单: 任意相邻两角差应在 60° ~ 120°
    if not all(60 < d < 120 for d in diffs):
        print(f"  [{i}] quad angles not 90° apart: {angles}, diffs={diffs}")
        continue

    candidates.append({
        'outer_idx': i,
        'ellipse': ellipse,
        'cx': cx, 'cy': cy, 'radius': radius,
        'ratio': ratio,
        'quads': quad_data
    })
    print(f"  ✓ [{i}] VALID target: center=({cx:.0f},{cy:.0f}), R={radius:.0f}, "
          f"4 quads areas={[int(d['area']) for d in quad_data]}")

# 画结果
vis = img.copy()
for c in candidates:
    cv2.ellipse(vis, c['ellipse'], (0, 255, 255), 3)
    cv2.putText(vis, f"ID{c['candidates']}".replace("'candidates'", "'xxx'") if False else "T",
                (int(c['cx'])-20, int(c['cy'])+5),
                cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 255, 255), 2)
    for q in c['quads']:
        cv2.circle(vis, (int(q['cx']), int(q['cy'])), 5, (0, 0, 255), -1)
cv2.imwrite(os.path.join(OUT_DIR, '16_final.png'),
            cv2.resize(vis, (1200, 1200*h//w)))
print(f"\n=== Total valid targets: {len(candidates)} ===")
print(f"Output: {OUT_DIR}/16_final.png")
