"""
V5: 用最大圆形轮廓作为外环 (只取最大的)
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

contours, _ = cv2.findContours(closed, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
img_area = h * w

# 只取面积大且圆度高的轮廓作为外环候选
outer_candidates = []
for c in contours:
    a = cv2.contourArea(c)
    if a < img_area * 0.01:
        continue
    if len(c) < 5:
        continue
    try:
        e = cv2.fitEllipse(c)
    except:
        continue
    w_e, h_e = e[1]
    r = min(w_e, h_e) / max(w_e, h_e)
    if r < 0.7:  # 圆形度要更高 (排除黑色象限那种扁的)
        continue
    cx, cy = e[0]
    radius = (w_e + h_e) / 4.0
    if cx < radius or cx > w - radius or cy < radius or cy > h - radius:
        continue
    outer_candidates.append((c, e, cx, cy, radius, a))
# 按面积降序
outer_candidates.sort(key=lambda x: -x[5])
print(f"Circular candidates: {len(outer_candidates)}")
for c, e, cx, cy, r, a in outer_candidates[:5]:
    print(f"  area={a:.0f} center=({cx:.0f},{cy:.0f}) R={r:.0f}")

# 对每个外环, 检查其内部 (距中心 < 0.7R) 的所有小轮廓
final_targets = []
for oc, oe, ocx, ocy, oR, oa in outer_candidates:
    inner_blacks = []
    for c in contours:
        if c is oc:
            continue
        ca = cv2.contourArea(c)
        # 内部象限面积应在外环的 1%~25% 之间
        if ca < oa * 0.01 or ca > oa * 0.25:
            continue
        M = cv2.moments(c)
        if M["m00"] < 1:
            continue
        cqx = M["m10"] / M["m00"]
        cqy = M["m01"] / M["m00"]
        dist = np.sqrt((cqx - ocx)**2 + (cqy - ocy)**2)
        if dist > oR * 0.85:
            continue
        inner_blacks.append({'area': ca, 'cx': cqx, 'cy': cqy, 'contour': c})
    print(f"\nOuter ({ocx:.0f},{ocy:.0f}) R={oR:.0f}: {len(inner_blacks)} inner blacks")
    if len(inner_blacks) != 4:
        continue
    # 面积相近
    areas = sorted([ib['area'] for ib in inner_blacks])
    if areas[0] / areas[3] < 0.5:
        print(f"   areas too different: {areas}")
        continue
    # 4 象限的角度分布 (两两差 60°-120°)
    angles = sorted([np.degrees(np.arctan2(ib['cy']-ocy, ib['cx']-ocx)) % 360 for ib in inner_blacks])
    diffs = []
    for i in range(4):
        a1 = angles[i]
        a2 = angles[(i+1)%4]
        if i == 3:
            a2 += 360
        diffs.append(a2 - a1)
    if not all(45 < d < 135 for d in diffs):
        print(f"   angles not 90° apart: {angles}, diffs={diffs}")
        continue
    final_targets.append({'outer_ellipse': oe, 'center': (ocx, ocy), 'radius': oR,
                          'quads': inner_blacks, 'outer_contour': oc})
    print(f"   [VALID TARGET]")

# 去重: 同一个外环, 内部白色洞 (R=206) 也满足, 会被同时识别为靶, 需要合并
# 策略: 找靶的 "最大外环" 即可, 它内部的所有 4 象限就是特征点
# 用 R 排除: 排除内层被外层包含的环
# 简单做法: 最终结果按 R 降序, 如果新靶中心已经被前面的靶覆盖, 跳过
filtered = []
for t in final_targets:
    is_dup = False
    for f in filtered:
        dx = t['center'][0] - f['center'][0]
        dy = t['center'][1] - f['center'][1]
        dist = np.sqrt(dx*dx + dy*dy)
        if dist < f['radius'] * 0.5:  # 太靠近
            is_dup = True
            break
    if not is_dup:
        filtered.append(t)

print(f"\n=== Final targets (deduped): {len(filtered)} ===")
for t in filtered:
    print(f"  Center {t['center']}, R={t['radius']:.0f}, {len(t['quads'])} quads")

# 画结果
vis = img.copy()
for t in filtered:
    cv2.ellipse(vis, t['outer_ellipse'], (0, 255, 255), 3)
    cv2.putText(vis, "TARGET", (int(t['center'][0])-30, int(t['center'][1])+5),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 255), 2)
    for q in t['quads']:
        cv2.circle(vis, (int(q['cx']), int(q['cy'])), 6, (0, 0, 255), -1)
cv2.imwrite(os.path.join(OUT_DIR, '18_final_v5.png'),
            cv2.resize(vis, (1200, 1200*h//w)))
print(f"\nOutput: {OUT_DIR}/18_final_v5.png")
