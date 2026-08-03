import cv2
import numpy as np
import json

def debug_detect(gray, log):
    blurred = cv2.medianBlur(gray, 5)
    _, thresh = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    closed = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel)

    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    log.append(f"总轮廓数: {len(contours)}")
    log.append(f"thresh 非零像素: {cv2.countNonZero(thresh)} / {thresh.size}")

    img_area = gray.shape[0] * gray.shape[1]
    for idx, contour in enumerate(contours):
        area = cv2.contourArea(contour)
        reason = ""
        if area < 200:
            reason = f"面积过小 {area:.0f} < 200"
        elif area > img_area * 0.4:
            reason = f"面积过大 {area:.0f} > {img_area*0.4:.0f}"
        elif len(contour) < 5:
            reason = f"点数不足 {len(contour)} < 5"
        else:
            ellipse = cv2.fitEllipse(contour)
            (cx, cy), (w, h), angle = ellipse
            ratio = min(w, h) / max(w, h)
            if ratio < 0.6:
                reason = f"非圆 ratio={ratio:.2f}"
            else:
                radius = (w + h) / 4.0
                margin = radius * 0.5 + 10
                if cx < margin or cx > gray.shape[1] - margin or cy < margin or cy > gray.shape[0] - margin:
                    reason = f"太靠边缘 cx={cx:.0f} cy={cy:.0f}"
                else:
                    # 象限分析
                    r = radius * 0.8
                    x0 = max(0, int(cx - r)); y0 = max(0, int(cy - r))
                    x1 = min(gray.shape[1]-1, int(cx + r)); y1 = min(gray.shape[0]-1, int(cy + r))
                    q_black = [0,0,0,0]; q_cx=[0,0,0,0]; q_cy=[0,0,0,0]
                    bt=0; wt=0
                    for y in range(y0, y1+1):
                        for x in range(x0, x1+1):
                            dx=x-cx; dy=y-cy
                            if dx*dx+dy*dy > r*r: continue
                            ang = np.degrees(np.arctan2(dy, dx))
                            if ang<0: ang+=360
                            q = int(ang/90)%4
                            if gray[y,x]<128:
                                q_black[q]+=1; q_cx[q]+=x; q_cy[q]+=y; bt+=1
                            else: wt+=1
                    black_quads = [q for q in range(4) if q_black[q]>50]
                    ratio_bw = bt/wt if wt>0 else 999
                    reason = f"椭圆通过! 黑象限={black_quads}, 黑白比={ratio_bw:.2f}"
                    if len(black_quads) != 2:
                        reason += " -> 黑象限数!=2, 拒绝"
                    elif abs(black_quads[0]-black_quads[1]) != 2:
                        reason += " -> 非对角, 拒绝"
                    elif ratio_bw < 0.3 or ratio_bw > 3.0:
                        reason += " -> 黑白比超范围, 拒绝"
                    else:
                        reason += " -> 通过!"
        log.append(f"  轮廓{idx}: 面积={area:.0f}, {reason}")

def main():
    log = []
    img = cv2.imread("scratch/test_target.png")
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    log.append(f"图像尺寸: {gray.shape}")
    debug_detect(gray, log)
    with open("scratch/debug_out.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(log))
    print("done")

if __name__ == "__main__":
    main()
