import cv2
import numpy as np

def analyze_quadrants(gray, center, radius):
    r = radius * 0.8
    x0 = max(0, int(center[0] - r)); y0 = max(0, int(center[1] - r))
    x1 = min(gray.shape[1]-1, int(center[0] + r)); y1 = min(gray.shape[0]-1, int(center[1] + r))
    q_black = [0,0,0,0]; q_cx=[0,0,0,0]; q_cy=[0,0,0,0]
    bt=0; wt=0
    for y in range(y0, y1+1):
        for x in range(x0, x1+1):
            dx=x-center[0]; dy=y-center[1]
            if dx*dx+dy*dy > r*r: continue
            ang = np.degrees(np.arctan2(dy, dx))
            if ang<0: ang+=360
            q = int(ang/90)%4
            if gray[y,x]<128:
                q_black[q]+=1; q_cx[q]+=x; q_cy[q]+=y; bt+=1
            else: wt+=1
    black_quads = [q for q in range(4) if q_black[q]>50]
    ratio_bw = bt/wt if wt>0 else 999
    return black_quads, ratio_bw, q_black

def main():
    img = cv2.imread("scratch/test_target.png")
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    blurred = cv2.medianBlur(gray, 5)
    _, thresh = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    closed = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel)
    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    # 找面积最大的轮廓
    big = max(contours, key=cv2.contourArea)
    area = cv2.contourArea(big)
    (cx, cy), (w, h), angle = cv2.fitEllipse(big)
    ratio = min(w, h)/max(w, h)
    radius = (w+h)/4.0

    log = []
    log.append(f"big contour: area={area:.0f}, center=({cx:.0f},{cy:.0f}), ratio={ratio:.2f}, radius={radius:.0f}")
    log.append(f"img size: {gray.shape}")

    # 边缘检查
    margin = radius*0.5 + 10
    edge_ok = cx >= margin and cx <= gray.shape[1]-margin and cy >= margin and cy <= gray.shape[0]-margin
    log.append(f"margin={margin:.0f}, edge_ok={edge_ok}")

    # 象限分析
    black_quads, ratio_bw, q_black = analyze_quadrants(gray, (cx, cy), radius)
    log.append(f"q_black counts: {q_black}")
    log.append(f"black_quads: {black_quads}, count={len(black_quads)}")
    log.append(f"b/w ratio: {ratio_bw:.2f}")
    log.append(f"diag check: {abs(black_quads[0]-black_quads[1]) if len(black_quads)==2 else 'N/A'}")

    # 也用 ROI 的灰度均值检查 (反相后ROI内应主要是白=靶)
    roi = gray[int(cy-radius):int(cy+radius), int(cx-radius):int(cx+radius)]
    log.append(f"roi gray mean (original): {roi.mean():.1f}")
    roi_inv = thresh[int(cy-radius):int(cy+radius), int(cx-radius):int(cx+radius)]
    log.append(f"roi thresh mean (INV): {roi_inv.mean():.1f} (high=mostly target)")

    with open("scratch/debug_out.txt", "w", encoding="utf-8-sig") as f:
        f.write("\n".join(log))

if __name__ == "__main__":
    main()
