"""
可视化模块
  - 实时图像标注 (位移、位姿、轨迹)
  - 数据记录 (CSV)
  - Matplotlib 实时图表 (可选)
"""

import cv2
import numpy as np
import csv
import os
import time
from datetime import datetime
from typing import Optional, Deque, List
from collections import deque
from threading import Lock

try:
    from .detector import DetectionResult
    from .measure import DisplacementResult
except ImportError:
    from detector import DetectionResult
    from measure import DisplacementResult


class DataLogger:
    """CSV 数据记录器"""

    def __init__(self, log_dir: str = "logs"):
        self.log_dir = log_dir
        self.csv_file: Optional[str] = None
        self.csv_writer = None
        self.file_handle = None
        self.lock = Lock()
        self.row_count = 0

    def start(self, prefix: str = "measurement"):
        """开始记录"""
        os.makedirs(self.log_dir, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.csv_file = os.path.join(self.log_dir, f"{prefix}_{timestamp}.csv")

        self.file_handle = open(self.csv_file, 'w', newline='', encoding='utf-8')
        self.csv_writer = csv.writer(self.file_handle)
        self.csv_writer.writerow([
            "timestamp", "frame",
            "x_mm", "y_mm", "z_mm",
            "raw_x_mm", "raw_y_mm", "raw_z_mm",
            "disp_2d_mm", "disp_3d_mm",
            "roll_rad", "pitch_rad", "yaw_rad",
            "quality", "is_outlier"
        ])
        self.file_handle.flush()
        self.row_count = 0
        print(f"  数据记录已启动: {self.csv_file}")

    def log(self, frame_num: int, disp: DisplacementResult):
        """记录一条数据"""
        if self.csv_writer is None:
            return

        with self.lock:
            self.csv_writer.writerow([
                f"{disp.timestamp:.3f}", frame_num,
                f"{disp.x:.4f}", f"{disp.y:.4f}", f"{disp.z:.4f}",
                f"{disp.raw_x:.4f}", f"{disp.raw_y:.4f}", f"{disp.raw_z:.4f}",
                f"{disp.displacement_2d:.4f}", f"{disp.displacement_3d:.4f}",
                f"{disp.roll:.6f}", f"{disp.pitch:.6f}", f"{disp.yaw:.6f}",
                f"{disp.detection_quality:.3f}", int(disp.is_outlier)
            ])
            self.row_count += 1

            # 每100条刷盘
            if self.row_count % 100 == 0:
                self.file_handle.flush()

    def stop(self):
        """停止记录"""
        with self.lock:
            if self.file_handle:
                self.file_handle.flush()
                self.file_handle.close()
                self.file_handle = None
                self.csv_writer = None
                print(f"  数据记录已停止 ({self.row_count} 条 → {self.csv_file})")


class RealTimeVisualizer:
    """
    实时可视化

    显示内容:
      - 左上: 位移数值 (x, y, z, 总位移)
      - 右上: 靶标状态与质量
      - 中下: 位移轨迹 (x-y 平面)
      - 底部: 操作提示
    """

    def __init__(self, config: dict, resolution=(1920, 1080)):
        self.config = config
        self.colors = config["colors"]
        self.font = cv2.FONT_HERSHEY_SIMPLEX
        self.font_scale = config["font_scale"]

        # 轨迹缓冲区
        self.traj_len = config["trajectory_length"]
        self.traj_x: Deque[float] = deque(maxlen=self.traj_len)
        self.traj_y: Deque[float] = deque(maxlen=self.traj_len)

        # 轨迹面板
        self.traj_size = 300
        self.traj_scale = 1.0  # 动态缩放
        self.traj_origin = (0, 0)  # 轨迹面板在图像中的起始位置

        # FPS 计算
        self.fps_buffer: Deque[float] = deque(maxlen=30)
        self.last_frame_time = time.time()

        # 数据记录器
        self.data_logger: Optional[DataLogger] = None

    def start_logging(self, log_dir: str = "logs"):
        """开始数据记录"""
        self.data_logger = DataLogger(log_dir)
        self.data_logger.start()

    def stop_logging(self):
        """停止数据记录"""
        if self.data_logger:
            self.data_logger.stop()
            self.data_logger = None

    def render(self, frame: np.ndarray,
               detect_result: DetectionResult,
               disp_result: DisplacementResult,
               frame_num: int,
               engine_stats: dict) -> np.ndarray:
        """
        渲染一帧

        参数:
            frame:           原始帧 (会被修改)
            detect_result:   检测结果
            disp_result:     位移结果
            frame_num:       帧序号
            engine_stats:    引擎统计信息
        返回:
            标注后的图像
        """
        display = frame.copy()
        h, w = display.shape[:2]

        # 计算 FPS
        now = time.time()
        dt = now - self.last_frame_time
        self.last_frame_time = now
        if dt > 0:
            self.fps_buffer.append(1.0 / dt)
            fps = np.mean(self.fps_buffer)
        else:
            fps = 0

        # --- 绘制检测结果 ---
        if detect_result.success:
            # 角点与中心
            if detect_result.corners is not None:
                pts = detect_result.corners.astype(np.int32).reshape(-1, 2)
                cv2.polylines(display, [pts], True, self.colors["target"], 2)
                for pt in pts:
                    cv2.circle(display, tuple(pt), 3, self.colors["corner"], -1)

            if detect_result.center is not None:
                cx, cy = detect_result.center.astype(int)
                cv2.circle(display, (cx, cy), 6, (0, 255, 255), -1)
                cv2.circle(display, (cx, cy), 8, (0, 255, 255), 2)
        else:
            cv2.putText(display, "TARGET NOT FOUND",
                        (w // 2 - 120, h // 2), self.font, 1.2, (0, 0, 255), 3)

        # --- 绘制位移轨迹 ---
        if detect_result.success:
            self.traj_x.append(disp_result.x)
            self.traj_y.append(disp_result.y)

        self._draw_trajectory(display)

        # --- 绘制HUD面板 ---
        self._draw_hud(display, disp_result, detect_result, fps, frame_num, engine_stats)

        # --- 数据记录 ---
        if self.data_logger:
            self.data_logger.log(frame_num, disp_result)

        return display

    def _draw_hud(self, display: np.ndarray,
                  disp: DisplacementResult,
                  detect: DetectionResult,
                  fps: float, frame_num: int, stats: dict):
        """绘制 HUD 信息面板"""
        h, w = display.shape[:2]
        line_h = 26
        x0, y0 = 15, 40

        # 半透明背景
        overlay = display.copy()
        panel_h = 12 * line_h + 20
        cv2.rectangle(overlay, (5, 5), (370, panel_h), (30, 30, 30), -1)
        cv2.addWeighted(overlay, 0.6, display, 0.4, 0, display)

        def put(text, row, color=(255, 255, 255)):
            y = y0 + row * line_h
            cv2.putText(display, text, (x0, y), self.font,
                        self.font_scale, color, 1, cv2.LINE_AA)

        # 归零状态
        status = "READY" if stats["zeroed"] else "ZEROING..."
        status_color = (0, 255, 0) if stats["zeroed"] else (0, 200, 255)
        put(f"STATUS: {status}", 0, status_color)

        # 位移数据
        put(f"FPS: {fps:.1f}  Frame: {frame_num}", 1, (180, 180, 180))
        put("", 2)
        put(f"X (水平): {disp.x:+8.3f} mm", 3, (0, 255, 0))
        put(f"Y (垂直): {disp.y:+8.3f} mm", 4, (0, 255, 0))
        put(f"Z (深度): {disp.z:+8.3f} mm", 5, (0, 255, 255))
        put(f"2D位移:  {disp.displacement_2d:8.3f} mm", 6, (255, 200, 0))
        put(f"3D位移:  {disp.displacement_3d:8.3f} mm", 7, (255, 200, 0))

        put("", 8)
        put(f"质量: {detect.quality:.2f}  {'!' if disp.is_outlier else ''}",
            9, (0, 255, 0) if detect.quality > 0.5 else (0, 165, 255))
        put(f"异常率: {stats['outlier_rate']:.1f}%", 10, (180, 180, 180))

        # 底部操作提示
        tips = "[r]重新归零  [m]切换模式  [s]截图  [q]退出"
        cv2.putText(display, tips, (15, h - 15), self.font,
                    0.5, (160, 160, 160), 1, cv2.LINE_AA)

        # 精度指示器 (右上角)
        if abs(disp.x) < 0.001 and abs(disp.y) < 0.001:
            prec_text = "精度: < 1 um (噪声水平)"
            prec_color = (100, 255, 100)
        elif abs(disp.x) < 1.0 and abs(disp.y) < 1.0:
            prec_text = f"精度: {max(abs(disp.x), abs(disp.y)):.1f} um"
            prec_color = (100, 255, 100)
        else:
            prec_text = f"位移: {disp.displacement_2d:.1f} mm"
            prec_color = (255, 255, 255)

        cv2.putText(display, prec_text, (w - 300, 35), self.font,
                    0.55, prec_color, 1, cv2.LINE_AA)

    def _draw_trajectory(self, display: np.ndarray):
        """在图像右下角绘制位移轨迹"""
        h, w = display.shape[:2]
        traj_w, traj_h = self.traj_size, self.traj_size

        # 轨迹面板位置 (右下角)
        margin = 20
        x1 = w - traj_w - margin
        y1 = h - traj_h - margin

        # 半透明背景
        overlay = display.copy()
        cv2.rectangle(overlay, (x1 - 5, y1 - 5),
                      (x1 + traj_w + 5, y1 + traj_h + 5), (30, 30, 30), -1)
        cv2.addWeighted(overlay, 0.5, display, 0.5, 0, display)

        # 边框
        cv2.rectangle(display, (x1, y1), (x1 + traj_w, y1 + traj_h),
                      (100, 100, 100), 1)

        # 标题
        cv2.putText(display, "Trajectory (X-Y)", (x1 + 5, y1 + 20),
                    self.font, 0.45, (180, 180, 180), 1, cv2.LINE_AA)

        # 中心点
        cx, cy = x1 + traj_w // 2, y1 + traj_h // 2
        cv2.line(display, (x1, cy), (x1 + traj_w, cy), (60, 60, 60), 1)
        cv2.line(display, (cx, y1), (cx, y1 + traj_h), (60, 60, 60), 1)
        cv2.circle(display, (cx, cy), 3, (100, 100, 100), -1)

        # 绘制轨迹
        if len(self.traj_x) < 2:
            return

        # 动态缩放
        all_x = np.array(self.traj_x)
        all_y = np.array(self.traj_y)
        max_range = max(np.abs(all_x).max(), np.abs(all_y).max(), 0.5)
        scale = (traj_w / 2 - 15) / max_range

        points = []
        for tx, ty in zip(self.traj_x, self.traj_y):
            px = int(cx + tx * scale)
            py = int(cy - ty * scale)  # Y轴翻转 (图像坐标)
            points.append((px, py))

        # 连线
        for i in range(1, len(points)):
            alpha = i / len(points)
            color = (
                int(self.colors["trajectory"][0] * alpha),
                int(self.colors["trajectory"][1] * alpha),
                int(self.colors["trajectory"][2] * alpha),
            )
            cv2.line(display, points[i - 1], points[i], color, 1, cv2.LINE_AA)

        # 当前点
        if points:
            cv2.circle(display, points[-1], 5, (0, 255, 255), -1)

        # 比例尺
        bar_mm = 1.0  # 1mm
        bar_px = int(bar_mm * scale)
        bar_y = y1 + traj_h - 15
        bar_x = x1 + 10
        cv2.line(display, (bar_x, bar_y), (bar_x + bar_px, bar_y),
                 (255, 255, 255), 2)
        cv2.putText(display, f"{bar_mm}mm", (bar_x, bar_y - 5),
                    self.font, 0.35, (200, 200, 200), 1, cv2.LINE_AA)


class OfflineAnalyzer:
    """
    离线分析工具 — 加载CSV数据, 绘制位移曲线
    """

    @staticmethod
    def plot_from_csv(csv_path: str):
        """从CSV文件绘制位移曲线"""
        import matplotlib
        matplotlib.use('TkAgg')
        import matplotlib.pyplot as plt

        data = np.loadtxt(csv_path, delimiter=',', skiprows=1,
                          usecols=(0, 2, 3, 4, 8, 9))

        time_sec = data[:, 0] - data[0, 0]
        x, y, z = data[:, 1], data[:, 2], data[:, 3]
        disp_2d, disp_3d = data[:, 4], data[:, 5]

        fig, axes = plt.subplots(2, 2, figsize=(14, 10))
        fig.suptitle("Target Displacement Analysis", fontsize=14)

        # X/Y/Z vs 时间
        ax = axes[0, 0]
        ax.plot(time_sec, x, 'r-', label='X', linewidth=0.5)
        ax.plot(time_sec, y, 'g-', label='Y', linewidth=0.5)
        ax.plot(time_sec, z, 'b-', label='Z', linewidth=0.5)
        ax.set_xlabel('Time (s)')
        ax.set_ylabel('Displacement (mm)')
        ax.legend()
        ax.grid(True, alpha=0.3)

        # 总位移 vs 时间
        ax = axes[0, 1]
        ax.plot(time_sec, disp_2d, 'c-', label='2D', linewidth=0.5)
        ax.plot(time_sec, disp_3d, 'm-', label='3D', linewidth=0.5)
        ax.set_xlabel('Time (s)')
        ax.set_ylabel('Total Displacement (mm)')
        ax.legend()
        ax.grid(True, alpha=0.3)

        # X-Y 轨迹
        ax = axes[1, 0]
        ax.plot(x, y, 'b-', linewidth=0.3)
        ax.plot(x[0], y[0], 'go', label='Start')
        ax.plot(x[-1], y[-1], 'ro', label='End')
        ax.set_xlabel('X (mm)')
        ax.set_ylabel('Y (mm)')
        ax.axis('equal')
        ax.legend()
        ax.grid(True, alpha=0.3)

        # 位移直方图
        ax = axes[1, 1]
        ax.hist(x, bins=50, alpha=0.5, color='r', label='X')
        ax.hist(y, bins=50, alpha=0.5, color='g', label='Y')
        ax.set_xlabel('Displacement (mm)')
        ax.set_ylabel('Count')
        ax.legend()

        plt.tight_layout()
        plt.show()
