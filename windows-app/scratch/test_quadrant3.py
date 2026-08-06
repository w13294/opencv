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

    # add some perspective distortion
    pts1 = np.float32([[0,0], [size,0], [0,size], [size,size]])
    pts2 = np.float32([[50,50], [size-50, 20], [20, size-20], [size-20, size-50]])
    M = cv2.getPerspectiveTransform(pts1, pts2)
    img_warp = cv2.warpPerspective(img, M, (size, size), borderValue=(255,255,255))
    return img_warp

def detect_quadrant(img):
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    
    # 1. Find the center saddle point
    corners = cv2.goodFeaturesToTrack(gray, maxCorners=10, qualityLevel=0.01, minDistance=20)
    if corners is None: return None
    
    # We want the corner that is closest to the center of the largest black blob
    _, thresh = cv2.threshold(gray, 127, 255, cv2.THRESH_BINARY_INV)
    contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours: return None
    
    largest_contour = max(contours, key=cv2.contourArea)
    M = cv2.moments(largest_contour)
    if M["m00"] == 0: return None
    cx = int(M["m10"] / M["m00"])
    cy = int(M["m01"] / M["m00"])
    
    # find corner closest to (cx, cy)
    best_corner = min(corners, key=lambda c: (c[0][0]-cx)**2 + (c[0][1]-cy)**2)[0]
    
    # refine center
    best_corner = cv2.cornerSubPix(gray, np.float32([[best_corner]]), (11, 11), (-1, -1),
                                   (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.001))[0][0]
    
    # 2. Extract white quadrants to find the orientation lines
    # Actually, if we just find the contours of the white regions inside the black ring
    _, thresh_white = cv2.threshold(gray, 127, 255, cv2.THRESH_BINARY)
    contours_w, _ = cv2.findContours(thresh_white, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    
    # Filter for the two white quadrants (they should have area similar to R^2 * pi/4, and be close to center)
    quadrants = []
    for c in contours_w:
        area = cv2.contourArea(c)
        if 1000 < area < (img.shape[0]*img.shape[1]/2):
            # check distance to center
            M = cv2.moments(c)
            if M["m00"] > 0:
                qcx = M["m10"] / M["m00"]
                qcy = M["m01"] / M["m00"]
                dist = np.sqrt((qcx - best_corner[0])**2 + (qcy - best_corner[1])**2)
                if dist < 200: # heuristic
                    quadrants.append(c)
                    
    # Sort by area descending, take top 2
    quadrants.sort(key=cv2.contourArea, reverse=True)
    if len(quadrants) >= 2:
        quad_contours = quadrants[:2]
        
        # 3. Find intersections of quadrant edges with the outer ring.
        # Too complex. Let's simplify.
        # We can just fit an ellipse to the largest black contour (the outer ring).
        if len(largest_contour) > 5:
            ellipse = cv2.fitEllipse(largest_contour)
            center_e, axes, angle = ellipse
            
            # Draw for debugging
            img_out = img.copy()
            cv2.circle(img_out, (int(best_corner[0]), int(best_corner[1])), 5, (0,0,255), -1)
            cv2.ellipse(img_out, ellipse, (0,255,0), 2)
            cv2.imwrite("scratch/debug_quadrant.png", img_out)
            
            return best_corner, ellipse
    return best_corner, None

img = create_quadrant_target()
cv2.imwrite("scratch/test_quadrant_target.png", img)
res = detect_quadrant(img)
print("Detected:", res)

