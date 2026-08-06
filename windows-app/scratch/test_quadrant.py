import cv2
import numpy as np

def create_quadrant_target(size=400):
    img = np.ones((size, size, 3), dtype=np.uint8) * 255
    center = (size // 2, size // 2)
    ring_radius = size // 2 - 20
    inner_radius = ring_radius - 20
    
    cv2.circle(img, center, ring_radius, (0, 0, 0), -1)
    cv2.circle(img, center, inner_radius, (255, 255, 255), -1)
    cv2.ellipse(img, center, (inner_radius, inner_radius), 0, 270, 360, (0, 0, 0), -1)
    cv2.ellipse(img, center, (inner_radius, inner_radius), 0, 90, 180, (0, 0, 0), -1)

    return img

def detect_quadrant(img):
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    gray_blur = cv2.GaussianBlur(gray, (5, 5), 0)
    
    circles = cv2.HoughCircles(gray_blur, cv2.HOUGH_GRADIENT, 1, 50,
                               param1=50, param2=30, minRadius=20, maxRadius=300)
    
    if circles is not None:
        circles = np.uint16(np.around(circles))
        best_circle = circles[0, 0]
        cx, cy, r = best_circle[0], best_circle[1], best_circle[2]
        
        corners = np.array([[[np.float32(cx), np.float32(cy)]]])
        criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 100, 0.001)
        win_size = (int(r)//4, int(r)//4)
        if win_size[0] < 5: win_size = (5, 5)
        
        refined_corners = cv2.cornerSubPix(gray, corners, win_size, (-1, -1), criteria)
        return refined_corners[0][0]
    return None

img = create_quadrant_target()
cv2.imwrite("test_target.png", img)
center = detect_quadrant(img)
print("Detected center:", center)
