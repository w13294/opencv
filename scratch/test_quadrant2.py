import cv2
import numpy as np

img = cv2.imread("scratch/test_target.png")
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

ret, corners = cv2.findChessboardCorners(gray, (1, 1), flags=cv2.CALIB_CB_ADAPTIVE_THRESH)
print("findChessboardCorners (1, 1):", ret, corners)

# Try goodFeaturesToTrack
corners = cv2.goodFeaturesToTrack(gray, maxCorners=10, qualityLevel=0.01, minDistance=10)
if corners is not None:
    print("goodFeaturesToTrack:\n", corners)
    
    # refine
    criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 100, 0.001)
    refined = cv2.cornerSubPix(gray, corners, (11, 11), (-1, -1), criteria)
    print("refined:\n", refined)

