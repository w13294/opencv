import cv2
import numpy as np
import sys
import os

# Add src to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from src.detector import TargetDetector

def debug_detection(img_path):
    print(f"Loading {img_path}")
    img = cv2.imread(img_path)
    if img is None:
        print("Failed to load image")
        return
    
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    
    # Let's manually step through _detect_quadrant logic to see what it finds
    # 1. Threshold
    _, thresh = cv2.threshold(gray, 127, 255, cv2.THRESH_BINARY_INV)
    cv2.imwrite("scratch/thresh_debug.png", thresh)
    
    contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    print(f"Found {len(contours)} contours")
    if not contours:
        return
        
    # Sort contours by area
    contours = sorted(contours, key=cv2.contourArea, reverse=True)
    
    for i, c in enumerate(contours[:5]):
        area = cv2.contourArea(c)
        print(f"Contour {i} area: {area}")
        if len(c) >= 5:
            ellipse = cv2.fitEllipse(c)
            (cx_ell, cy_ell), (axis_a, axis_b), angle = ellipse
            ratio = min(axis_a, axis_b) / max(axis_a, axis_b) if max(axis_a, axis_b) > 0 else 0
            print(f"  Ellipse axis: {axis_a:.1f}x{axis_b:.1f}, ratio: {ratio:.2f}")
            
            # Draw it
            img_c = img.copy()
            cv2.drawContours(img_c, [c], -1, (0, 0, 255), 2)
            cv2.ellipse(img_c, ellipse, (0, 255, 0), 2)
            cv2.imwrite(f"scratch/contour_{i}_debug.png", img_c)

if __name__ == "__main__":
    # We don't have the raw screenshot file path easily accessible unless we know where it's saved.
    # The user uploaded the image to the chat.
    pass
