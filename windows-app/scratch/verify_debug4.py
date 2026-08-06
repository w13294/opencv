import cv2
import numpy as np

def analyze_quadrants(thresh, center, radius):
    r = radius * 0.8
    x0 = max(0, int(center[0] - r)); y0 = max(0, int(center[1] - r))
    x1 = min(thresh.shape[1]-1, int(center[0] + r)); y1 = min(thresh.shape[0]-1, int(center[1] + r))
    q_black = [0,0,0,0]; q_cx=[0,0,0,0]; q_cy=[0,0,0,0]; bt=0; wt=0
    for y in range(y0, y1+1):
        for x in range(x0, x1+1):
            dx=x-center[0]; dy=y-center[1]
            if dx*dx+dy*dy > r*r: continue
            ang = np.degrees(np.arctan2(dy, dx))
            if ang<0: ang+=360
            q = int(ang/90)%4
            if thresh[y,x] > 128:
                q_black[q]+=1; q_cx[q]+=x; q_cy[q]+=y; bt+=1
            else: wt+=1
    black_quads = [q for q in range(4) if q_black[q]>50]
    ratio = bt/wt if wt>0 else 999
    return black_quads, ratio, q_black, bt, wt

def main():
    img = cv2.imread("scratch/test_target.png")
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    blurred = cv2.medianBlur(gray, 5)
    _, thresh = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    closed = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel)
    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    big = max(contours, key=cv2.contourArea)
    (cx, cy), (w, h), angle = cv2.fitEllipse(big)
    radius = (w+h)/4.0

    black_quads, ratio, q_black, bt, wt = analyze_quadrants(closed, (cx, cy), radius)
    log = []
    log.append(f"closed mean in ROI center: {closed[int(cy), int(cx)]:.0f}")
    log.append(f"q_black: {q_black}")
    log.append(f"black_quads: {black_quads} (count={len(black_quads)})")
    log.append(f"b/w ratio: {ratio:.2f}, black_total={bt}, white_total={wt}")
    log.append(f"thresh unique values in ROI: {np.unique(closed[int(cy-radius*0.8):int(cy+radius*0.8), int(cx-radius*0.8):int(cx+radius*0.8)])}")

    with open("scratch/debug_out.txt", "w", encoding="utf-8-sig") as f:
        f.write("\n".join(log))

if __name__ == "__main__":
    main()
