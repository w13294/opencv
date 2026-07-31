import cv2
import numpy as np

def generate_noisy_quadrant(size_px=200):
    board = np.ones((size_px + 100, size_px + 100, 3), dtype=np.uint8) * 255
    center = ((size_px + 100) // 2, (size_px + 100) // 2)
    ring_radius = size_px // 2
    inner_radius = int(ring_radius * 0.8)
    
    cv2.circle(board, center, ring_radius, (0, 0, 0), -1)
    cv2.circle(board, center, inner_radius, (255, 255, 255), -1)
    cv2.ellipse(board, center, (inner_radius, inner_radius), 0, 90, 180, (0, 0, 0), -1)
    cv2.ellipse(board, center, (inner_radius, inner_radius), 0, 270, 360, (0, 0, 0), -1)
    
    # Add some noise (e.g., dust speck inside a white quadrant, and a hole in the black quadrant)
    cv2.circle(board, (center[0] - 40, center[1] + 20), 5, (0, 0, 0), -1)
    cv2.circle(board, (center[0] + 40, center[1] + 40), 8, (255, 255, 255), -1)
    
    return board

img = generate_noisy_quadrant()
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
_, thresh = cv2.threshold(gray, 127, 255, cv2.THRESH_BINARY_INV)

contours, hierarchy = cv2.findContours(thresh, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)

if hierarchy is not None:
    hierarchy = hierarchy[0]
    for i, (c, h) in enumerate(zip(contours, hierarchy)):
        area = cv2.contourArea(c)
        if area > 500:
            # Count significant children
            children_count = 0
            child_idx = h[2]
            while child_idx != -1:
                child_area = cv2.contourArea(contours[child_idx])
                if child_area > area * 0.05: # Child must be at least 5% of parent area
                    children_count += 1
                child_idx = hierarchy[child_idx][0]
                
            if children_count == 2:
                print(f"Found Quadrant Target candidate! Contour {i}, Area: {area}")
                ellipse = cv2.fitEllipse(c)
                print(f"  Ellipse: {ellipse}")
                
                # Get the children centroids to estimate center
                child_idx = h[2]
                child_centroids = []
                while child_idx != -1:
                    child_area = cv2.contourArea(contours[child_idx])
                    if child_area > area * 0.05:
                        M = cv2.moments(contours[child_idx])
                        if M["m00"] != 0:
                            cx = M["m10"] / M["m00"]
                            cy = M["m01"] / M["m00"]
                            child_centroids.append(np.array([cx, cy]))
                    child_idx = hierarchy[child_idx][0]
                
                if len(child_centroids) == 2:
                    est_center = (child_centroids[0] + child_centroids[1]) / 2.0
                    print(f"  Estimated center from centroids: {est_center}")
