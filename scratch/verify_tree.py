"""
用 RETR_TREE 找所有轮廓 (包括内部黑色块), 看能否识别出 4 个黑色象限
"""
import cv2
import numpy as np

IMG_PATH = r'C:\Users\91299\Desktop\opencv\opencv\scratch\A4_paper_with_two_black_quadra_2026-08-03T06-02-12.png'
img = cv2.imread(IMG_PATH)
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
h, w = gray.shape

# Otsu 二值化 (黑=0 白=255)
_, thresh = cv2.threshold(cv2.medianBlur(gray, 5), 0, 255, cv2.THRESH_OTSU)
kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
closed = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel)

# RETR_TREE 找全部
contours, hierarchy = cv2.findContours(closed, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
print(f"total contours: {len(contours)}")

if hierarchy is not None:
    h_arr = hierarchy[0]
    # 找外环 (无父) - 应该是 h_arr[i][3] == -1
    outer = []
    children = {}
    for i, h_info in enumerate(h_arr):
        parent = h_info[3]
        if parent == -1:
            outer.append(i)
        else:
            children.setdefault(parent, []).append(i)

    print(f"\n顶层 (无父) 轮廓: {len(outer)}")
    for i in outer:
        a = cv2.contourArea(contours[i])
        x, y, ww, hh = cv2.boundingRect(contours[i])
        print(f"  [{i}] area={a:.0f}, bbox=({x},{y},{ww},{hh})")
        if i in children:
            print(f"     children: {children[i]}")
            for ci in children[i]:
                ca = cv2.contourArea(contours[ci])
                cbox = cv2.boundingRect(contours[ci])
                ccx = cbox[0] + cbox[2]//2
                ccy = cbox[1] + cbox[3]//2
                print(f"       [{ci}] area={ca:.0f}, bbox={cbox}, center=({ccx},{ccy})")

# 画所有轮廓 (不同颜色)
vis = img.copy()
for i, c in enumerate(contours):
    a = cv2.contourArea(c)
    if a < 100:
        continue
    color = (int(i*37) % 256, int(i*73) % 256, int(i*113) % 256)
    cv2.drawContours(vis, [c], -1, color, 2)
    x, y, ww, hh = cv2.boundingRect(c)
    cv2.putText(vis, str(i), (x, y-3), cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1)
cv2.imwrite(r'C:\Users\91299\Desktop\opencv\opencv\scratch\debug_real\15_tree.png',
            cv2.resize(vis, (800, 800*h//w)))
print("\nSaved 15_tree.png")
