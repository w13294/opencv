import cv2
import numpy as np

def generate_quadrant(size_px=200):
    board = np.ones((size_px + 100, size_px + 100, 3), dtype=np.uint8) * 255
    center = ((size_px + 100) // 2, (size_px + 100) // 2)
    ring_radius = size_px // 2
    inner_radius = int(ring_radius * 0.8)
    
    cv2.circle(board, center, ring_radius, (0, 0, 0), -1)
    cv2.circle(board, center, inner_radius, (255, 255, 255), -1)
    cv2.ellipse(board, center, (inner_radius, inner_radius), 0, 90, 180, (0, 0, 0), -1)
    cv2.ellipse(board, center, (inner_radius, inner_radius), 0, 270, 360, (0, 0, 0), -1)
    return board

img = generate_quadrant()
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
_, thresh = cv2.threshold(gray, 127, 255, cv2.THRESH_BINARY_INV)

# Find contours with tree hierarchy
contours, hierarchy = cv2.findContours(thresh, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)

print(f"Total contours: {len(contours)}")
if hierarchy is not None:
    hierarchy = hierarchy[0]
    for i, (c, h) in enumerate(zip(contours, hierarchy)):
        area = cv2.contourArea(c)
        if area > 100:
            # h: [Next, Previous, First_Child, Parent]
            print(f"Contour {i}: Area={area:.1f}, Hierarchy={h}")
            
            # Count immediate children
            children = 0
            child_idx = h[2]
            while child_idx != -1:
                children += 1
                child_idx = hierarchy[child_idx][0]
            print(f"  -> Children: {children}")

cv2.imwrite("scratch/test_topology.png", thresh)
