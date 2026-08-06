import cv2
import numpy as np

def debug_detect(gray, log):
    blurred = cv2.medianBlur(gray, 5)
    _, thresh = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    closed = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel)

    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    log.append(f"total contours: {len(contours)}")
    log.append(f"thresh nonzero: {cv2.countNonZero(thresh)} / {thresh.size}")
    log.append(f"thresh mean: {thresh.mean():.1f}")

    img_area = gray.shape[0] * gray.shape[1]
    # 只打印面积最大的 5 个轮廓
    sorted_c = sorted(contours, key=cv2.contourArea, reverse=True)[:5]
    for idx, contour in enumerate(sorted_c):
        area = cv2.contourArea(contour)
        log.append(f"  contour{idx}: area={area:.0f} ({100*area/img_area:.1f}%), npts={len(contour)}")
        if len(contour) >= 5:
            (cx, cy), (w, h), angle = cv2.fitEllipse(contour)
            ratio = min(w, h)/max(w, h)
            log.append(f"    ellipse: center=({cx:.0f},{cy:.0f}), ratio={ratio:.2f}")

def main():
    log = []
    img = cv2.imread("scratch/test_target.png")
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    log.append(f"image: {gray.shape}, gray mean={gray.mean():.1f}")
    debug_detect(gray, log)
    with open("scratch/debug_out.txt", "w", encoding="utf-8-sig") as f:
        f.write("\n".join(log))

if __name__ == "__main__":
    main()
