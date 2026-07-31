# -*- coding: utf-8 -*-
"""
靶标视觉定位位移测量系统 — 主程序
=====================================

功能:
  - 实时检测靶标 (ArUco / 棋盘格 / 圆形网格)
  - 亚像素精确定位
  - 6DOF 位姿估计 (PnP)
  - 相对位移计算 (相对于初始参考位置)
  - 卡尔曼滤波 + 滑动平均噪声抑制
  - 实时可视化 (位移数值、轨迹、HUD)
  - CSV 数据记录

使用方法:
  python main.py                          # 默认: ArUco 模式
  python main.py --mode chessboard        # 棋盘格模式
  python main.py --mode circles           # 圆形网格模式
  python main.py --calibrate              # 先标定相机
  python main.py --analyze logs/xxx.csv   # 离线分析

键盘操作:
  r - 重新归零 (设置当前位置为参考零点)
  m - 切换检测模式 (aruco / chessboard / circles)
  s - 截图保存当前帧
  q - 退出程序
  l - 开始/停止 CSV 数据记录
"""

import cv2
import numpy as np
import sys
import os
import time
import argparse
from datetime import datetime

# 添加 src 到路径
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from src.config import TARGET, MEASURE, DISPLAY, LOGGING, CAMERA
from src.calibration import CameraCalibrator
from src.detector import TargetDetector, DetectionResult
from src.measure import MultiTargetEngine, DisplacementResult
from src.visualizer import RealTimeVisualizer


class TargetMeasurementSystem:
    """
    靶标位移测量系统主控
    """

    def __init__(self, calibration_file: str = None,
                 mode: str = "aruco",
                 camera_id: int = 0):
        """
        参数:
            calibration_file: 相机标定文件路径
            mode:             检测模式 "aruco" | "chessboard" | "circles"
            camera_id:        摄像头ID
        """
        # 加载相机标定参数
        self.calib_loaded = False
        self.camera_matrix = np.eye(3, dtype=np.float64)
        self.dist_coeffs = np.zeros((1, 5), dtype=np.float64)

        if calibration_file and os.path.exists(calibration_file):
            calib = CameraCalibrator()
            calib.load(calibration_file)
            self.camera_matrix = calib.camera_matrix
            self.dist_coeffs = calib.dist_coeffs
            self.calib_loaded = True
            print(f"  ✓ 相机标定参数已加载 (重投影误差: {calib.reprojection_error:.4f}px)")
        else:
            print("  ⚠ 未加载标定参数, 使用默认内参 (精度将受影响)")
            print("    建议先运行: python main.py --calibrate")

        # 初始化模块
        self.detector = TargetDetector(TARGET)
        self.detector.set_mode(mode)

        self.engine = MultiTargetEngine(MEASURE)

        self.visualizer = RealTimeVisualizer(DISPLAY)

        # 摄像头
        self.camera_id = camera_id
        self.cap = None

        # 运行状态
        self.running = False
        self.frame_num = 0
        self.logging_active = False
        self.screenshot_dir = "screenshots"

    def start(self):
        """启动实时测量"""
        # 打开摄像头
        self.cap = cv2.VideoCapture(self.camera_id)
        if not self.cap.isOpened():
            raise RuntimeError(f"无法打开摄像头 {self.camera_id}")

        # 设置分辨率
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, CAMERA["resolution"][0])
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, CAMERA["resolution"][1])
        self.cap.set(cv2.CAP_PROP_FPS, CAMERA["fps"])
        actual_w = self.cap.get(cv2.CAP_PROP_FRAME_WIDTH)
        actual_h = self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT)
        print(f"  摄像头分辨率: {actual_w:.0f}x{actual_h:.0f}")

        # 主循环
        self.running = True
        print(f"\n{'='*60}")
        print(f"  靶标视觉定位位移测量系统")
        print(f"  模式: {self.detector.mode}")
        print(f"  靶标距离: {MEASURE['target_distance_mm']/1000:.1f} m")
        print(f"  目标精度: {MEASURE['required_precision_um']} um")
        print(f"  正在归零... (请保持靶标静止)")
        print(f"  [r]归零 [m]模式 [l]记录 [s]截图 [q]退出")
        print(f"{'='*60}\n")

        self._main_loop()

    def _main_loop(self):
        """主循环"""
        # 显示窗口
        win_name = "Target Displacement Measurement"
        cv2.namedWindow(win_name, cv2.WINDOW_NORMAL)

        while self.running:
            ret, frame = self.cap.read()
            if not ret:
                print("  无法读取摄像头帧")
                time.sleep(0.1)
                continue

            self.frame_num += 1
            timestamp = time.time()

            # --- 步骤1: 检测靶标 ---
            detect_results = self.detector.detect(
                frame, self.camera_matrix, self.dist_coeffs)

            # --- 步骤2: 计算位移 ---
            disp_results = self.engine.measure_all(
                detect_results, timestamp)

            # --- 提取主靶标结果用于单靶标显示 ---
            detect_result = DetectionResult(success=False)
            disp_result = DisplacementResult(timestamp=timestamp)
            if disp_results:
                primary_id = sorted(disp_results.keys())[0]
                disp_result = disp_results[primary_id]
                detect_result = detect_results.get(primary_id, DetectionResult(success=False))
                disp_result.target_id = primary_id

            # --- 步骤3: 可视化 ---
            stats = self.engine.get_global_stats() if hasattr(self.engine, 'get_global_stats') else {}
            display_frame = self.visualizer.render(
                frame, detect_result, disp_result, self.frame_num, stats)

            # 显示
            display_resized = cv2.resize(display_frame, (1280, 720))
            cv2.imshow(win_name, display_resized)

            # 键盘事件
            key = cv2.waitKey(1) & 0xFF
            self._handle_key(key)

            # 打印状态 (每秒一次)
            if self.frame_num % 30 == 0 and detect_result.success:
                self._print_status(disp_result, stats)

        self._cleanup()

    def _handle_key(self, key: int):
        """处理键盘事件"""
        if key == ord('q') or key == 27:  # q 或 ESC
            self.running = False
        elif key == ord('r'):
            self.engine.reset_zero()
            print("  → 重新归零...")
        elif key == ord('m'):
            modes = ["aruco", "chessboard", "circles", "quadrant"]
            idx = modes.index(self.detector.mode)
            new_idx = (idx + 1) % len(modes)
            self.detector.set_mode(modes[new_idx])
            self.engine.reset_zero()
        elif key == ord('l'):
            if self.logging_active:
                self.visualizer.stop_logging()
                self.logging_active = False
            else:
                self.visualizer.start_logging(LOGGING["log_dir"])
                self.logging_active = True
        elif key == ord('s'):
            os.makedirs(self.screenshot_dir, exist_ok=True)
            ts = datetime.now().strftime("%Y%m%d_%H%M%S")
            fname = os.path.join(self.screenshot_dir, f"screenshot_{ts}.png")
            cv2.imwrite(fname, self.cap.read()[1])
            print(f"  → 截图已保存: {fname}")

    def _print_status(self, disp, stats):
        """打印运行状态"""
        active = stats.get("active_targets", 0)
        mode_str = f"[{self.detector.mode}]"
        disp_str = f"X={disp.x:+7.2f} Y={disp.y:+7.2f} Z={disp.z:+7.2f} mm" if hasattr(disp, 'x') else "无数据"
        total_str = f"2D={disp.displacement_2d:.3f}mm" if hasattr(disp, 'displacement_2d') else ""
        qual_str = f"Q={disp.detection_quality:.2f}" if hasattr(disp, 'detection_quality') else ""

        log_icon = "[L]" if self.logging_active else "[ ]"
        print(f"  {mode_str} 靶标x{active} | {disp_str} | {total_str} "
              f"| {qual_str} {log_icon}")

    def _cleanup(self):
        """清理资源"""
        if self.cap:
            self.cap.release()
        cv2.destroyAllWindows()
        if self.visualizer.data_logger:
            self.visualizer.stop_logging()

        print(f"\n{'='*60}")
        print(f"  测量结束")
        stats = self.engine.get_global_stats() if hasattr(self.engine, 'get_global_stats') else {}
        print(f"  总帧数: {stats.get('total_frames', 0)}")
        print(f"  活跃靶标: {stats.get('active_targets', 0)}")
        print(f"{'='*60}")


def run_calibration(args):
    """运行相机标定流程"""
    print("\n" + "="*60)
    print("  相机标定向导")
    print("="*60)
    print("\n  准备:")
    print("  1. 打印一张棋盘格 (如 9x6 内角点, 每格 30mm)")
    print("  2. 将棋盘格固定在平整表面上")
    print("  3. 在不同角度/距离下朝向相机")
    print(f"  4. 程序将自动采集 {args.num} 张图像用于标定\n")

    confirm = input("  开始标定? [Y/n]: ").strip().lower()
    if confirm and confirm != 'y':
        print("  已取消")
        return

    calib = CameraCalibrator(
        chessboard_size=(args.cols, args.rows),
        square_size_mm=args.square,
    )
    images = calib.capture_calibration_images(args.camera, args.num, args.delay)
    calib.calibrate(images)
    calib.save(args.output)


def run_analysis(args):
    """离线分析CSV数据"""
    from src.visualizer import OfflineAnalyzer
    OfflineAnalyzer.plot_from_csv(args.analyze)


def main():
    parser = argparse.ArgumentParser(
        description="靶标视觉定位位移测量系统",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python main.py                          # 启动 GUI (默认)
  python main.py --gui                    # 启动 GUI
  python main.py --cli                    # 命令行模式
  python main.py --calibrate              # 相机标定
  python main.py --analyze logs/xxx.csv   # 离线分析
        """,
    )

    run_group = parser.add_mutually_exclusive_group()
    run_group.add_argument("--gui", action="store_true", default=True,
                           help="启动图形界面 (默认)")
    run_group.add_argument("--cli", action="store_true",
                           help="启动命令行模式")
    parser.add_argument("--calibrate", action="store_true",
                        help="运行相机标定向导")
    parser.add_argument("--analyze", type=str, metavar="CSV_FILE",
                        help="离线分析CSV数据并绘图")
    parser.add_argument("--mode", type=str, default="aruco",
                        choices=["aruco", "chessboard", "circles", "quadrant"],
                        help="靶标检测模式 (默认: aruco)")
    parser.add_argument("--camera", type=int, default=0,
                        help="摄像头ID (默认: 0)")
    parser.add_argument("--calib", type=str, default="calib/camera_params.npz",
                        help="相机标定文件路径")

    # 标定参数
    parser.add_argument("--num", type=int, default=20,
                        help="标定图像数量 (默认: 20)")
    parser.add_argument("--delay", type=float, default=2.0,
                        help="标定采集间隔 (默认: 2秒)")
    parser.add_argument("--cols", type=int, default=9,
                        help="棋盘格内角列数 (默认: 9)")
    parser.add_argument("--rows", type=int, default=6,
                        help="棋盘格内角行数 (默认: 6)")
    parser.add_argument("--square", type=float, default=30.0,
                        help="棋盘格每格大小 mm (默认: 30)")
    parser.add_argument("--output", type=str, default="calib/camera_params.npz",
                        help="标定输出文件")

    args = parser.parse_args()

    # 标定模式
    if args.calibrate:
        run_calibration(args)
        return

    # 分析模式
    if args.analyze:
        run_analysis(args)
        return

    # 命令行模式
    if args.cli:
        system = TargetMeasurementSystem(
            calibration_file=args.calib,
            mode=args.mode,
            camera_id=args.camera,
        )
        system.start()
        return

    # 默认: GUI 模式
    from src.gui import run_gui
    run_gui()


if __name__ == "__main__":
    main()
