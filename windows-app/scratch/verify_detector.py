"""
验证新靶标检测算法 (Python 版, 对应 Kotlin TargetDetector 重写版)
在生成的测试靶标图上验证能否正确识别
"""
import cv2
import numpy as np
import os

def analyze_quadrants(thresh, center, radius):
    """在圆形 ROI 内做象限像素统计 (使用反相二值图, 靶=白)"""
    r = radius * 0.8
    x0 = max(0, int(center[0] - r))
    y0 = max(0, int(center[1] - r))
    x1 = min(thresh.shape[1] - 1, int(center[0] + r))
    y1 = min(thresh.shape[0] - 1, int(center[1] + r))
    if x1 <= x0 or y1 <= y0:
        return None

    q_black = [0, 0, 0, 0]
    q_cx = [0.0, 0.0, 0.0, 0.0]
    q_cy = [0.0, 0.0, 0.0, 0.0]
    black_total = 0
    white_total = 0

    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            dx = x - center[0]
            dy = y - center[1]
            if dx * dx + dy * dy > r * r:
                continue
            angle = np.degrees(np.arctan2(dy, dx))
            if angle < 0:
                angle += 360.0
            q = int(angle / 90.0) % 4
            pixel = thresh[y, x]
            if pixel > 128:  # 反相后靶=白(255), 底=黑(0)
                q_black[q] += 1
                q_cx[q] += x
                q_cy[q] += y
                black_total += 1
            else:
                white_total += 1

    # 黑象限必须有显著白色像素 (排除十字臂边界的少量干扰)
    # 阈值设为 5000: 真实黑象限约 70 万像素, 十字臂边界仅约 1600
    black_quads = [q for q in range(4) if q_black[q] > 5000]
    if len(black_quads) != 2:
        return None
    a, b = black_quads
    if abs(a - b) != 2:
        return None  # 必须对角

    bq1 = (q_cx[a] / q_black[a], q_cy[a] / q_black[a])
    bq2 = (q_cx[b] / q_black[b], q_cy[b] / q_black[b])
    ratio = black_total / white_total if white_total > 0 else 999
    if ratio < 0.3 or ratio > 3.0:
        return None

    return {"valid": True, "ratio": ratio, "bq1": bq1, "bq2": bq2,
            "black_quads": black_quads}

def detect(gray):
    blurred = cv2.medianBlur(gray, 5)
    # 白底黑靶: 用 THRESH_BINARY (黑->白), 不反相, 这样黑色靶区域变成白色轮廓
    _, thresh = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    closed = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel)

    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    img_area = gray.shape[0] * gray.shape[1]
    candidates = []
    for contour in contours:
        area = cv2.contourArea(contour)
        if area < 200 or area > img_area * 0.7:
            continue
        if len(contour) < 5:
            continue
        ellipse = cv2.fitEllipse(contour)
        (cx, cy), (w, h), angle = ellipse
        ratio = min(w, h) / max(w, h)
        if ratio < 0.6:
            continue
        radius = (w + h) / 4.0

        # 边缘排除
        margin = radius * 0.5 + 10
        if cx < margin or cx > gray.shape[1] - margin or \
           cy < margin or cy > gray.shape[0] - margin:
            continue

        res = analyze_quadrants(closed, (cx, cy), radius)
        if res is None:
            continue

        balance = 1.0 - min(1.0, abs(res["ratio"] - 1.0))
        quality = max(0.3, min(1.0, 0.5 * balance + 0.5 * ratio))

        candidates.append({
            "center": (cx, cy),
            "radius": radius,
            "area": area,
            "quality": quality,
            "black_quads": res["black_quads"],
            "ratio": res["ratio"]
        })

    return candidates

def main():
    # 测试 1: 单靶
    img = cv2.imread("scratch/test_target.png")
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    cands = detect(gray)
    print(f"[SINGLE] candidates: {len(cands)}")
    for i, c in enumerate(cands):
        print(f"  target {i}: center=({c['center'][0]:.0f}, {c['center'][1]:.0f}), "
              f"radius={c['radius']:.0f}, quality={c['quality']:.2f}, "
              f"black_quads={c['black_quads']}, b/w_ratio={c['ratio']:.2f}")

    # 测试 2: 多靶
    multi_path = "target_multi_quadrant.png"
    if os.path.exists(multi_path):
        img2 = cv2.imread(multi_path)
        gray2 = cv2.cvtColor(img2, cv2.COLOR_BGR2GRAY)
        cands2 = detect(gray2)
        print(f"[MULTI] candidates: {len(cands2)}")
        for i, c in enumerate(cands2):
            print(f"  target {i}: center=({c['center'][0]:.0f}, {c['center'][1]:.0f}), "
                  f"radius={c['radius']:.0f}, quality={c['quality']:.2f}, "
                  f"black_quads={c['black_quads']}, b/w_ratio={c['ratio']:.2f}")
    else:
        print(f"[MULTI] {multi_path} not found, skipped")

if __name__ == "__main__":
    main()
