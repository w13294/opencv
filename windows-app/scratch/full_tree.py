"""
打印完整 hierarchy, 看清结构
"""
import cv2
import numpy as np

IMG_PATH = r'C:\Users\91299\Desktop\opencv\opencv\scratch\A4_paper_with_two_black_quadra_2026-08-03T06-02-12.png'
img = cv2.imread(IMG_PATH)
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
_, thresh = cv2.threshold(cv2.medianBlur(gray, 5), 0, 255, cv2.THRESH_OTSU)
kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
closed = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel)

contours, hierarchy = cv2.findContours(closed, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
print(f"Total: {len(contours)}")
h_arr = hierarchy[0]
print(f"\nIndex | Area | Bbox | Center | Parent | Child | Next | Prev")
for i, c in enumerate(contours):
    a = cv2.contourArea(c)
    x, y, ww, hh = cv2.boundingRect(c)
    cx, cy = x + ww//2, y + hh//2
    p, c_idx, n, prv = h_arr[i]
    print(f"  [{i:2d}] {a:7.0f} ({x:4d},{y:4d},{ww:3d},{hh:3d}) ({cx:4d},{cy:3d}) "
          f"p={p:2d} c={c_idx:2d} n={n:2d} prev={prv:2d}")

# 关键: 找到大靶外环的子轮廓链
# 外环 [5] -> 子 [9] -> 子 [7, 8, 10, ?]
# 5 周围还有没有其他子?
print(f"\n[5] children: {h_arr[5][2]}")  # first child
# 遍历 5 的所有后代
def get_all_descendants(h_arr, idx, depth=0):
    first_child = h_arr[idx][2]
    res = []
    if first_child == -1:
        return res
    cur = first_child
    while cur != -1:
        res.append((cur, depth+1))
        res.extend(get_all_descendants(h_arr, cur, depth+1))
        cur = h_arr[cur][0]  # next sibling
    return res

print(f"\n[5] descendants:")
for d, depth in get_all_descendants(h_arr, 5):
    a = cv2.contourArea(contours[d])
    x, y, ww, hh = cv2.boundingRect(contours[d])
    print(f"  {'  '*depth}[{d:2d}] area={a:7.0f} bbox=({x},{y},{ww},{hh})")

print(f"\n[11] descendants (小靶):")
for d, depth in get_all_descendants(h_arr, 11):
    a = cv2.contourArea(contours[d])
    x, y, ww, hh = cv2.boundingRect(contours[d])
    print(f"  {'  '*depth}[{d:2d}] area={a:7.0f} bbox=({x},{y},{ww},{hh})")
