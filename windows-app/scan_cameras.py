# -*- coding: utf-8 -*-
"""扫描可用摄像头，辅助找到正确的 camera_id"""
import cv2
import sys
sys.stdout.reconfigure(encoding='utf-8')

print("=" * 50)
print("  摄像头扫描工具")
print("=" * 50)

# Windows 上扫描前 5 个索引
for i in range(5):
    for backend_name, backend in [("DSHOW", cv2.CAP_DSHOW),
                                   ("MSMF", cv2.CAP_MSMF),
                                   ("ANY", cv2.CAP_ANY)]:
        cap = cv2.VideoCapture(i, backend)
        if cap.isOpened():
            w = cap.get(cv2.CAP_PROP_FRAME_WIDTH)
            h = cap.get(cv2.CAP_PROP_FRAME_HEIGHT)
            fps = cap.get(cv2.CAP_PROP_FPS)
            print(f"  [OK] camera_id={i} [{backend_name}] {w:.0f}x{h:.0f} @ {fps:.1f}fps")
            cap.release()
            break
        cap.release()
    else:
        print(f"  [--] camera_id={i} - no camera")

print()
print("将 [OK] 对应的 camera_id 填入 src/config.py 的 CAMERA['camera_id']")
print("=" * 50)
