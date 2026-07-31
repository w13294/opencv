"""
位移测量核心引擎
  - 基于 PnP 的 6DOF 位姿估计
  - 相对位移计算 (相对于初始参考位置)
  - 卡尔曼滤波降噪
  - 滑动平均滤波
  - 异常值检测与剔除

关键算法:
  1. 通过 solvePnP 获取靶标相对于相机的旋转和平移
  2. 与初始参考位姿比较, 计算三轴位移量 (dx, dy, dz)
  3. 卡尔曼滤波 → 抑制测量噪声, 提升精度
  4. 滑动平均 → 进一步平滑输出
"""

import numpy as np
import cv2
from typing import Tuple, Optional, Deque
from collections import deque
from dataclasses import dataclass, field

# 尝试导入 filterpy (如果安装了)
try:
    from filterpy.kalman import KalmanFilter as FP_KalmanFilter
    HAS_FILTERPY = True
except ImportError:
    HAS_FILTERPY = False
    print("[警告] filterpy 未安装, 使用简化的卡尔曼滤波器")
    print("       安装命令: pip install filterpy")


@dataclass
class DisplacementResult:
    """位移测量结果"""
    timestamp: float = 0.0

    # 当前位姿 (mm, 弧度)
    x: float = 0.0      # 水平位移 (右为正)
    y: float = 0.0      # 垂直位移 (下为正)
    z: float = 0.0      # 深度位移 (远为正)

    # 原始测量值 (滤波前)
    raw_x: float = 0.0
    raw_y: float = 0.0
    raw_z: float = 0.0

    # 总位移
    displacement_2d: float = 0.0    # sqrt(dx^2 + dy^2)
    displacement_3d: float = 0.0    # sqrt(dx^2 + dy^2 + dz^2)

    # 角度变化
    roll: float = 0.0     # 绕X轴
    pitch: float = 0.0    # 绕Y轴
    yaw: float = 0.0      # 绕Z轴

    # 滤波状态
    filtered: bool = False
    is_outlier: bool = False

    # 检测质量
    detection_quality: float = 0.0


class SimpleKalmanFilter:
    """
    简化版一维卡尔曼滤波器 (不依赖 filterpy)
    """

    def __init__(self, process_noise=0.01, measurement_noise=0.5, init_error=1.0):
        self.q = process_noise      # 过程噪声
        self.r = measurement_noise  # 测量噪声
        self.p = init_error         # 估计误差协方差
        self.x = 0.0                # 状态 (位移)
        self.v = 0.0                # 速度
        self.initialized = False

    def update(self, measurement: float, dt: float = 1.0) -> float:
        """
        更新滤波器

        状态: [位置, 速度]
        测量: [位置]
        """
        if not self.initialized:
            self.x = measurement
            self.v = 0.0
            self.initialized = True
            return measurement

        # 保存旧值 (用于速度估计)
        old_x = self.x

        # 预测步骤
        pred_x = self.x + self.v * dt
        self.p = self.p + self.q

        # 创新量 (测量残差)
        innovation = measurement - pred_x
        
        # 方案D: 自适应死区 (Adaptive Deadband) 与 动态追踪
        # 当创新量极小(物理静止)时，极大增加测量噪声R，彻底冻结输出抖动。
        # 当创新量较大(物理移动)时，减小R以加快跟随，消除橡胶带延迟。
        deadband = 0.3  # 死区阈值 0.3mm
        if abs(innovation) < deadband:
            adaptive_r = self.r * 50.0  # 强烈平滑
            # 极低速时强制速度归零，防止缓慢漂移
            if abs(self.v) < 0.1:
                self.v = 0.0
        else:
            # 运动越快，越信任当前测量值
            adaptive_r = self.r * (deadband / abs(innovation))
            
        adaptive_r = max(adaptive_r, self.r * 0.05) # 设置下限

        # 更新步骤
        k = self.p / (self.p + adaptive_r)
        self.x = pred_x + k * innovation
        self.p = (1.0 - k) * self.p

        # 速度更新 (使用EMA平滑速度，避免导数带来的剧烈抖动)
        self.v = 0.7 * self.v + 0.3 * ((self.x - old_x) / max(dt, 0.001))

        return self.x

    def predict_only(self, dt: float = 1.0) -> float:
        """仅预测不做测量更新 — 用于检测失败的帧"""
        if not self.initialized:
            return 0.0
        self.x = self.x + self.v * dt
        self.p = self.p + self.q
        return self.x

    def reset(self):
        """重置滤波器"""
        self.x = 0.0
        self.v = 0.0
        self.p = 1.0
        self.initialized = False


class DisplacementEngine:
    """
    位移测量引擎

    核心流程:
      1. 设置初始参考位姿 (归零)
      2. 每帧检测靶标 → 获取当前位姿 (tvec, rvec)
      3. 计算相对位移 (相对于参考位姿)
      4. 卡尔曼滤波 + 滑动平均 → 精化结果
      5. 异常值检测 → 剔除突变
    """

    def __init__(self, config: dict):
        """
        参数:
            config: 配置文件中的 MEASURE 字典
        """
        self.config = config
        self.k_config = config["kalman"]

        # 参考位姿 (归零时的位姿)
        self.ref_tvec: Optional[np.ndarray] = None  # (3,) 或 (1,3)
        self.ref_rvec: Optional[np.ndarray] = None  # (3,) 或 (1,3)
        self.ref_settled = False

        # 归零期间的样本收集
        self.zero_buffer: Deque[np.ndarray] = deque(maxlen=config["zero_samples"])
        self.zero_cnt = 0
        self.zero_target = config["zero_samples"]

        # 卡尔曼滤波器 (x, y, z 三个独立滤波器)
        if HAS_FILTERPY:
            self._init_filterpy_kalman()
        else:
            self.kf_x = SimpleKalmanFilter(
                self.k_config["process_noise"],
                self.k_config["measurement_noise"],
                self.k_config["init_error"])
            self.kf_y = SimpleKalmanFilter(
                self.k_config["process_noise"],
                self.k_config["measurement_noise"],
                self.k_config["init_error"])
            self.kf_z = SimpleKalmanFilter(
                self.k_config["process_noise"] * 2,  # Z轴噪声更大
                self.k_config["measurement_noise"] * 2,
                self.k_config["init_error"] * 2)

        # 滑动平均缓冲区
        ma_win = config["moving_average_window"]
        self.ma_x: Deque[float] = deque(maxlen=ma_win)
        self.ma_y: Deque[float] = deque(maxlen=ma_win)
        self.ma_z: Deque[float] = deque(maxlen=ma_win)

        # 上一帧时间 (用于速度估计)
        self.last_time: Optional[float] = None

        # 异常值检测
        self.outlier_threshold = config["outlier_threshold_mm"]

        # 统计信息
        self.frame_count = 0
        self.outlier_count = 0
        self.consecutive_outliers = 0

    def _init_filterpy_kalman(self):
        """使用 filterpy 初始化三轴卡尔曼滤波器"""
        def _create_kf(process_noise, measurement_noise, init_error):
            kf = FP_KalmanFilter(dim_x=2, dim_z=1)  # 状态:[位置,速度] 测量:[位置]
            kf.x = np.zeros(2)
            kf.F = np.array([[1., 1.], [0., 1.]])    # dt=1 (帧间)
            kf.H = np.array([[1., 0.]])
            kf.P *= init_error
            kf.R = np.array([[measurement_noise]])
            kf.Q = np.array([[process_noise / 4, process_noise / 2],
                             [process_noise / 2, process_noise]])
            return kf

        self.kf_x = _create_kf(
            self.k_config["process_noise"],
            self.k_config["measurement_noise"],
            self.k_config["init_error"])
        self.kf_y = _create_kf(
            self.k_config["process_noise"],
            self.k_config["measurement_noise"],
            self.k_config["init_error"])
        self.kf_z = _create_kf(
            self.k_config["process_noise"] * 2,
            self.k_config["measurement_noise"] * 2,
            self.k_config["init_error"] * 2)

        self._has_filterpy = True

    def zeroing(self, tvec: np.ndarray, rvec: np.ndarray) -> bool:
        """
        归零操作 — 收集初始样本, 计算参考位姿

        参数:
            tvec: 当前平移向量
            rvec: 当前旋转向量
        返回:
            True 如果归零完成
        """
        if self.zero_cnt < self.zero_target:
            self.zero_buffer.append(tvec.flatten().copy())
            self.zero_cnt += 1
            return False

        # 归零完成 — 取平均值作为参考
        stacked = np.vstack(list(self.zero_buffer))
        self.ref_tvec = stacked.mean(axis=0)
        self.ref_rvec = rvec.flatten().copy()
        self.ref_settled = True

        # 重置滤波器
        self._reset_filters()

        print(f"\n  ✓ 归零完成 (样本数: {len(stacked)})")
        print(f"  参考位姿: x={self.ref_tvec[0]:.2f}, y={self.ref_tvec[1]:.2f}, "
              f"z={self.ref_tvec[2]:.2f} mm")
        return True

    def measure(self, tvec: np.ndarray, rvec: np.ndarray,
                timestamp: float, quality: float) -> DisplacementResult:
        """
        计算位移

        参数:
            tvec:       当前平移向量 (mm)
            rvec:       当前旋转向量
            timestamp:  时间戳
            quality:    检测质量 (0-1)
        返回:
            DisplacementResult
        """
        self.frame_count += 1

        tvec_flat = tvec.flatten()
        rvec_flat = rvec.flatten()

        # 未归零 → 自动归零
        if not self.ref_settled:
            zero_done = self.zeroing(tvec, rvec)
            if not zero_done:
                return DisplacementResult(timestamp=timestamp)

        # 计算相对位移 (相对于参考位姿)
        raw_dx = tvec_flat[0] - self.ref_tvec[0]
        raw_dy = tvec_flat[1] - self.ref_tvec[1]
        raw_dz = tvec_flat[2] - self.ref_tvec[2]

        # 计算角度变化
        raw_dr = rvec_flat - self.ref_rvec

        # 异常值检测 (只检测相对上一帧的突变，绝不能检测离原点的绝对距离)
        is_outlier = False
        
        if len(self.ma_x) >= 1:
            # 帧间跳变阈值 (例如: 1帧内移动超过 50mm 认为是异常噪点)
            jump_threshold = 50.0 
            last_x = self.ma_x[-1]
            last_y = self.ma_y[-1]
            last_z = self.ma_z[-1]
            if (abs(raw_dx - last_x) > jump_threshold or 
                abs(raw_dy - last_y) > jump_threshold or 
                abs(raw_dz - last_z) > jump_threshold * 2): # Z轴通常噪声大一倍
                is_outlier = True
        
        if len(self.ma_x) >= 3 and not is_outlier:
            # 与滑动平均的偏差 (更平滑的约束)
            ma_x = np.mean(self.ma_x)
            ma_y = np.mean(self.ma_y)
            if (abs(raw_dx - ma_x) > self.outlier_threshold or
                    abs(raw_dy - ma_y) > self.outlier_threshold or
                    quality < 0.3):
                is_outlier = True
                
        # 连续异常处理：防止系统因真实的大跳跃位移而陷入"死锁"
        if is_outlier:
            self.consecutive_outliers += 1
            if self.consecutive_outliers >= 10:
                print("  [警告] 连续 10 帧检测为异常位移，可能发生高速大移动，强制信任当前位置并重置滤波器。")
                self._reset_filters()
                is_outlier = False
                self.consecutive_outliers = 0
        else:
            self.consecutive_outliers = 0
            
        if is_outlier:
            self.outlier_count += 1

        # 卡尔曼滤波
        dt = 1.0  # 默认帧间隔
        if self.last_time is not None:
            dt = max(timestamp - self.last_time, 0.001)

        if is_outlier:
            # 异常值: 只预测不更新, 保持滤波器不被污染
            if HAS_FILTERPY:
                self.kf_x.predict()
                self.kf_y.predict()
                self.kf_z.predict()
                filt_x, filt_y, filt_z = self.kf_x.x[0], self.kf_y.x[0], self.kf_z.x[0]
            else:
                filt_x = self.kf_x.predict_only(dt)
                filt_y = self.kf_y.predict_only(dt)
                filt_z = self.kf_z.predict_only(dt)
        else:
            if HAS_FILTERPY:
                self.kf_x.predict()
                self.kf_y.predict()
                self.kf_z.predict()
                self.kf_x.update(np.array([raw_dx]))
                self.kf_y.update(np.array([raw_dy]))
                self.kf_z.update(np.array([raw_dz]))
                filt_x, filt_y, filt_z = self.kf_x.x[0], self.kf_y.x[0], self.kf_z.x[0]
            else:
                filt_x = self.kf_x.update(raw_dx, dt)
                filt_y = self.kf_y.update(raw_dy, dt)
                filt_z = self.kf_z.update(raw_dz, dt)

        # 滑动平均 (在滤波之后再做一次平滑)
        self.ma_x.append(filt_x)
        self.ma_y.append(filt_y)
        self.ma_z.append(filt_z)

        smooth_x = np.mean(self.ma_x)
        smooth_y = np.mean(self.ma_y)
        smooth_z = np.mean(self.ma_z)

        # 计算总位移
        disp_2d = np.sqrt(smooth_x**2 + smooth_y**2)
        disp_3d = np.sqrt(smooth_x**2 + smooth_y**2 + smooth_z**2)

        self.last_time = timestamp

        return DisplacementResult(
            timestamp=timestamp,
            x=smooth_x, y=smooth_y, z=smooth_z,
            raw_x=raw_dx, raw_y=raw_dy, raw_z=raw_dz,
            displacement_2d=disp_2d,
            displacement_3d=disp_3d,
            roll=raw_dr[0], pitch=raw_dr[1], yaw=raw_dr[2],
            filtered=True,
            is_outlier=is_outlier,
            detection_quality=quality,
        )

    def _reset_filters(self):
        """重置所有滤波器"""
        if HAS_FILTERPY:
            self._init_filterpy_kalman()
        else:
            self.kf_x = SimpleKalmanFilter(
                self.k_config["process_noise"],
                self.k_config["measurement_noise"],
                self.k_config["init_error"])
            self.kf_y = SimpleKalmanFilter(
                self.k_config["process_noise"],
                self.k_config["measurement_noise"],
                self.k_config["init_error"])
            self.kf_z = SimpleKalmanFilter(
                self.k_config["process_noise"] * 2,
                self.k_config["measurement_noise"] * 2,
                self.k_config["init_error"] * 2)

        self.ma_x.clear()
        self.ma_y.clear()
        self.ma_z.clear()
        self.last_time = None

    def reset_zero(self):
        """重新归零"""
        self.ref_tvec = None
        self.ref_rvec = None
        self.ref_settled = False
        self.zero_buffer.clear()
        self.zero_cnt = 0
        self._reset_filters()
        print("  已重置归零状态, 将在下一帧重新采样...")

    def maintain(self, timestamp: float) -> DisplacementResult:
        """检测失败时保持当前位置 (仅预测, 不更新)"""
        dt = 1.0
        if self.last_time is not None:
            dt = max(timestamp - self.last_time, 0.001)

        if HAS_FILTERPY:
            self.kf_x.predict()
            self.kf_y.predict()
            self.kf_z.predict()
            filt_x, filt_y, filt_z = self.kf_x.x[0], self.kf_y.x[0], self.kf_z.x[0]
        else:
            filt_x = self.kf_x.predict_only(dt)
            filt_y = self.kf_y.predict_only(dt)
            filt_z = self.kf_z.predict_only(dt)

        self.last_time = timestamp

        # 不更新滑动平均, 保持上次平滑值
        smooth_x = np.mean(self.ma_x) if self.ma_x else filt_x
        smooth_y = np.mean(self.ma_y) if self.ma_y else filt_y
        smooth_z = np.mean(self.ma_z) if self.ma_z else filt_z

        return DisplacementResult(
            timestamp=timestamp,
            x=smooth_x, y=smooth_y, z=smooth_z,
            raw_x=0, raw_y=0, raw_z=0,
            displacement_2d=np.sqrt(smooth_x**2 + smooth_y**2),
            displacement_3d=np.sqrt(smooth_x**2 + smooth_y**2 + smooth_z**2),
            filtered=True,
            is_outlier=False,
            detection_quality=0.0,
        )

    def get_stats(self) -> dict:
        """获取统计信息"""
        return {
            "frame_count": self.frame_count,
            "outlier_count": self.outlier_count,
            "outlier_rate": (self.outlier_count / max(self.frame_count, 1)) * 100,
            "zeroed": self.ref_settled,
            "zero_cnt": self.zero_cnt,
            "zero_samples": self.zero_target,
        }
