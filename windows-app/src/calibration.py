"""
相机标定模块
  - 使用棋盘格进行张正友标定法
  - 支持亚像素角点精化
  - 保存/加载标定参数
  - 评估重投影误差
"""

import cv2
import numpy as np
import os
import time
from pathlib import Path


class CameraCalibrator:
    """相机标定器"""

    def __init__(self, chessboard_size=(9, 6), square_size_mm=30.0):
        """
        参数:
            chessboard_size: 棋盘格内角点数 (cols, rows)
            square_size_mm:   棋盘格每格实际尺寸 (mm)
        """
        self.chessboard_size = chessboard_size
        self.square_size_mm = square_size_mm

        # 标定结果
        self.camera_matrix = None      # (3,3) 内参矩阵
        self.dist_coeffs = None        # 畸变系数
        self.rvecs = None              # 每张图的旋转向量
        self.tvecs = None              # 每张图的平移向量
        self.reprojection_error = 0.0  # 平均重投影误差
        self.image_size = None

        # 准备世界坐标系中的点 (0,0,0), (1,0,0), ..., (8,5,0)
        self.objp = np.zeros((chessboard_size[0] * chessboard_size[1], 3), np.float32)
        self.objp[:, :2] = np.mgrid[0:chessboard_size[0],
                                     0:chessboard_size[1]].T.reshape(-1, 2)
        self.objp *= square_size_mm  # 转换为实际尺寸(mm)

    def capture_calibration_images(self, camera_id=0, num_images=20, delay=2.0):
        """
        自动采集标定图像

        参数:
            camera_id:  摄像头ID
            num_images: 采集数量
            delay:      每张间隔 (秒)
        返回:
            images: 采集的标定图像列表
        """
        cap = cv2.VideoCapture(camera_id)
        if not cap.isOpened():
            raise RuntimeError(f"无法打开摄像头 {camera_id}")

        # 设置高分辨率
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 3840)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 2160)

        images = []
        print(f"\n{'='*60}")
        print(f"  相机标定 - 自动采集模式")
        print(f"  将采集 {num_images} 张图像, 间隔 {delay}秒")
        print(f"  请将棋盘格以不同角度和位置朝向相机")
        print(f"  按 'q' 退出, 按 'c' 手动触发采集")
        print(f"{'='*60}\n")

        collected = 0
        last_capture_time = time.time()
        manual_mode = False

        while collected < num_images:
            ret, frame = cap.read()
            if not ret:
                continue

            display = frame.copy()

            # 尝试检测棋盘格
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            found, corners = cv2.findChessboardCorners(
                gray, self.chessboard_size, None)

            if found:
                # 亚像素精化
                corners_sub = cv2.cornerSubPix(
                    gray, corners, (11, 11), (-1, -1),
                    (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.001))

                cv2.drawChessboardCorners(display, self.chessboard_size,
                                          corners_sub, found)

            # 显示状态
            cv2.putText(display, f"Collected: {collected}/{num_images}",
                        (20, 50), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)
            cv2.putText(display, f"Chessboard: {'FOUND' if found else 'not found'}",
                        (20, 90), cv2.FONT_HERSHEY_SIMPLEX, 0.8,
                        (0, 255, 0) if found else (0, 0, 255), 2)
            cv2.putText(display, "[c] capture  [q] quit  [m] toggle manual",
                        (20, display.shape[0] - 20), cv2.FONT_HERSHEY_SIMPLEX,
                        0.6, (200, 200, 200), 1)

            cv2.imshow("Camera Calibration", cv2.resize(display, (1280, 720)))
            key = cv2.waitKey(1) & 0xFF

            if key == ord('q'):
                break
            elif key == ord('m'):
                manual_mode = not manual_mode
                print(f"模式切换: {'手动' if manual_mode else '自动'}")
            elif key == ord('c'):
                manual_mode = True  # 手动模式下 c 触发采集

            # 自动或手动采集
            should_capture = False
            if manual_mode:
                if key == ord('c') and found:
                    should_capture = True
            else:
                if found and (time.time() - last_capture_time) > delay:
                    should_capture = True

            if should_capture:
                images.append(frame.copy())
                collected += 1
                last_capture_time = time.time()
                print(f"  [{collected}/{num_images}] 已采集 — "
                      f"棋盘格{'已检测' if found else '未检测'}")

        cap.release()
        cv2.destroyAllWindows()

        if len(images) < 3:
            raise RuntimeError(f"标定至少需要3张图像, 仅采集到 {len(images)} 张")

        print(f"\n  共采集 {len(images)} 张图像\n")
        return images

    def calibrate(self, images):
        """
        执行标定

        参数:
            images: 标定图像列表
        返回:
            (rms, camera_matrix, dist_coeffs, rvecs, tvecs)
        """
        print(f"{'='*60}")
        print(f"  执行相机标定...")
        print(f"{'='*60}")

        self.image_size = (images[0].shape[1], images[0].shape[0])

        # 存储对象点和图像点
        objpoints = []  # 世界坐标中的3D点
        imgpoints = []  # 图像中的2D点
        valid_count = 0

        for i, img in enumerate(images):
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            found, corners = cv2.findChessboardCorners(gray, self.chessboard_size, None)

            if found:
                # 亚像素精化
                corners_sub = cv2.cornerSubPix(
                    gray, corners, (11, 11), (-1, -1),
                    (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.001))
                objpoints.append(self.objp)
                imgpoints.append(corners_sub)
                valid_count += 1
            else:
                print(f"  警告: 第 {i+1} 张图像未检测到棋盘格, 已跳过")

        if valid_count < 3:
            raise RuntimeError(f"标定需要至少3张有效图像, 仅 {valid_count} 张")

        print(f"  有效图像: {valid_count}/{len(images)}")

        # 执行标定
        flags = (cv2.CALIB_FIX_K3 +        # 固定k3 (五参数模型足够)
                 cv2.CALIB_ZERO_TANGENT_DIST)  # 零切向畸变 (长焦镜头通常很低)

        rms, self.camera_matrix, self.dist_coeffs, self.rvecs, self.tvecs = \
            cv2.calibrateCamera(objpoints, imgpoints, self.image_size,
                                None, None, flags=flags)

        self.reprojection_error = rms

        # 计算每张图像的重投影误差
        per_image_errors = []
        for i in range(len(objpoints)):
            imgpoints2, _ = cv2.projectPoints(
                objpoints[i], self.rvecs[i], self.tvecs[i],
                self.camera_matrix, self.dist_coeffs)
            # 统一形状和类型，避免 cv2.norm 因维度/类型不匹配报错
            pts1 = np.asarray(imgpoints[i], dtype=np.float32).reshape(-1, 2)
            pts2 = np.asarray(imgpoints2, dtype=np.float32).reshape(-1, 2)
            error = cv2.norm(pts1, pts2, cv2.NORM_L2) / len(pts2)
            per_image_errors.append(error)

        self._print_results(per_image_errors)
        return rms, self.camera_matrix, self.dist_coeffs, self.rvecs, self.tvecs

    def _print_results(self, per_image_errors):
        """打印标定结果"""
        print(f"\n{'='*60}")
        print(f"  标定完成")
        print(f"{'='*60}")
        print(f"\n  内参矩阵 K:\n{self.camera_matrix}")
        print(f"\n  畸变系数: {self.dist_coeffs.ravel()}")
        print(f"\n  平均重投影误差 (RMS): {self.reprojection_error:.4f} 像素")
        print(f"  最大重投影误差: {max(per_image_errors):.4f} 像素")
        print(f"  最小重投影误差: {min(per_image_errors):.4f} 像素")
        print(f"\n  焦距: fx={self.camera_matrix[0,0]:.2f}, fy={self.camera_matrix[1,1]:.2f} 像素")
        print(f"  主点: cx={self.camera_matrix[0,2]:.2f}, cy={self.camera_matrix[1,2]:.2f} 像素")

        # 质量评估
        if self.reprojection_error < 0.15:
            quality = "优秀 ✓"
        elif self.reprojection_error < 0.30:
            quality = "良好 ✓"
        elif self.reprojection_error < 0.50:
            quality = "一般 ⚠"
        else:
            quality = "较差 ✗ — 建议重新标定"
        print(f"\n  标定质量: {quality}")

    def save(self, filepath):
        """保存标定参数"""
        os.makedirs(os.path.dirname(filepath), exist_ok=True)
        np.savez(filepath,
                 camera_matrix=self.camera_matrix,
                 dist_coeffs=self.dist_coeffs,
                 image_size=self.image_size,
                 reprojection_error=self.reprojection_error)
        print(f"  标定参数已保存: {filepath}")

    def load(self, filepath):
        """加载标定参数"""
        data = np.load(filepath)
        self.camera_matrix = data['camera_matrix']
        self.dist_coeffs = data['dist_coeffs']
        self.image_size = tuple(data['image_size'])
        self.reprojection_error = float(data['reprojection_error'])
        print(f"  标定参数已加载: {filepath}")
        print(f"  平均重投影误差: {self.reprojection_error:.4f} 像素")
        return True

    def undistort(self, image):
        """校正畸变图像"""
        if self.camera_matrix is None or self.dist_coeffs is None:
            raise RuntimeError("请先标定或加载标定参数")
        return cv2.undistort(image, self.camera_matrix, self.dist_coeffs)

    def get_optimal_new_camera_matrix(self, alpha=1.0):
        """获取优化后的内参矩阵 (用于畸变校正+裁剪)"""
        return cv2.getOptimalNewCameraMatrix(
            self.camera_matrix, self.dist_coeffs, self.image_size, alpha)[0]


# ============================================================
# 命令行入口
# ============================================================
if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="相机标定工具")
    parser.add_argument("--camera", type=int, default=0, help="摄像头ID")
    parser.add_argument("--num", type=int, default=20, help="采集数量")
    parser.add_argument("--delay", type=float, default=2.0, help="采集间隔 (秒)")
    parser.add_argument("--cols", type=int, default=9, help="棋盘格列数")
    parser.add_argument("--rows", type=int, default=6, help="棋盘格行数")
    parser.add_argument("--square", type=float, default=30.0, help="棋盘格大小 (mm)")
    parser.add_argument("--output", type=str, default="calib/camera_params.npz")
    args = parser.parse_args()

    calib = CameraCalibrator((args.cols, args.rows), args.square)
    images = calib.capture_calibration_images(args.camera, args.num, args.delay)
    calib.calibrate(images)
    calib.save(args.output)
