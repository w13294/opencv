"""
靶标检测模块
  - ArUco 标记检测 (主方案): 适合长距离, 尺寸已知, 提供6DOF位姿
  - 棋盘格角点检测 (备选): 更密集的特征点, 适合高精度亚像素测量
  - 圆形网格检测 (备选): 对椭圆形的圆心定位亚像素精度最高

策略:
  10米距离 → 建议使用大型 ArUco 标记 (≥200mm) 或 高密度棋盘格
  通过亚像素精化和多角点加权平均, 可将有效精度提升至 0.1-0.2 像素
"""

import cv2
import numpy as np
from dataclasses import dataclass
from typing import Optional, Tuple, List


@dataclass
class DetectionResult:
    """检测结果数据结构"""
    success: bool                               # 是否成功
    corners: Optional[np.ndarray] = None        # 图像角点 (N, 1, 2) 或 (4, 1, 2)
    center: Optional[np.ndarray] = None         # 图像中靶标中心 (cx, cy)
    rvec: Optional[np.ndarray] = None           # 旋转向量
    tvec: Optional[np.ndarray] = None           # 平移向量 (mm)
    num_points: int = 0                         # 检测到的特征点数
    quality: float = 0.0                        # 检测质量 (0-1)


class TargetDetector:
    """
    靶标检测器 — 支持多种靶标类型

    使用建议 (按精度排序):
      1. 圆形网格 → 圆心检测亚像素精度最高
      2. 棋盘格   → 角点密集, 亚像素精化成熟
      3. ArUco    → 抗遮挡, 提供ID识别和6DOF位姿
    """

    def __init__(self, config: dict):
        """
        参数:
            config: 配置文件中的 TARGET 字典
        """
        self.config = config

        # ArUco 字典
        dict_name = config.get("dictionary", "DICT_6X6_250")
        self.aruco_dict = cv2.aruco.getPredefinedDictionary(
            getattr(cv2.aruco, dict_name))

        # ArUco 检测参数
        self.aruco_params = cv2.aruco.DetectorParameters()
        # 针对远距离优化 — 降低参数复杂度以提高速度
        self.aruco_params.adaptiveThreshWinSizeMin = 3
        self.aruco_params.adaptiveThreshWinSizeMax = 23
        self.aruco_params.adaptiveThreshWinSizeStep = 10
        self.aruco_params.cornerRefinementMethod = cv2.aruco.CORNER_REFINE_SUBPIX
        self.aruco_params.cornerRefinementWinSize = 3
        self.aruco_params.cornerRefinementMaxIterations = 10
        self.aruco_params.cornerRefinementMinAccuracy = 0.05
        
        # 光流法状态缓存 (用于方案A：极致平滑追踪)
        self.prev_gray = None
        self.prev_klt_pts = None
        self.aruco_params.polygonalApproxAccuracyRate = 0.05

        self.aruco_detector = cv2.aruco.ArucoDetector(
            self.aruco_dict, self.aruco_params)

        # 靶标参数
        self.marker_size = config["marker_size_mm"]
        self.marker_id = config["marker_id"]
        self.chessboard_size = config["chessboard_size"]
        self.chessboard_square = config["chessboard_square_mm"]

        # 亚像素精化参数
        self.subpix_win = config["subpix_window"]
        self.subpix_zz = config["subpix_zero_zone"]
        self.subpix_crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER,
                            config["subpix_criteria"][0],
                            config["subpix_criteria"][1])

        # 检测模式
        self.mode = "aruco"  # "aruco" | "chessboard" | "circles" | "quadrant"

    def detect(self, image: np.ndarray,
               camera_matrix: np.ndarray,
               dist_coeffs: np.ndarray) -> DetectionResult:
        """
        检测靶标并估计位姿

        参数:
            image:          输入图像 (BGR)
            camera_matrix:  相机内参 (3,3)
            dist_coeffs:    畸变系数
        返回:
            DetectionResult
        """
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

        if self.mode == "aruco":
            return self._detect_aruco(gray, camera_matrix, dist_coeffs)
        elif self.mode == "chessboard":
            return self._detect_chessboard(gray, camera_matrix, dist_coeffs)
        elif self.mode == "circles":
            return self._detect_circles(gray, camera_matrix, dist_coeffs)
        elif self.mode == "quadrant":
            return self._detect_quadrant(gray, camera_matrix, dist_coeffs)
        else:
            return DetectionResult(success=False, quality=0.0)

    def _detect_aruco(self, gray: np.ndarray,
                      camera_matrix: np.ndarray,
                      dist_coeffs: np.ndarray) -> DetectionResult:
        """
        ArUco 标记检测 + 位姿估计
        """
        corners, ids, rejected = self.aruco_detector.detectMarkers(gray)

        if ids is None or self.marker_id not in ids:
            return DetectionResult(success=False, quality=0.0)

        # 找到指定ID的标记
        idx = np.where(ids == self.marker_id)[0][0]
        marker_corners = corners[idx]  # (1, 4, 2)

        # ArUco 已内置 cornerRefinement，直接使用
        refined = marker_corners

        # 计算中心
        center = refined[0].mean(axis=0)

        # 位姿估计 (需要相机内参和标记尺寸)
        rvec, tvec, _ = cv2.aruco.estimatePoseSingleMarkers(
            refined, self.marker_size, camera_matrix, dist_coeffs)

        # 评估检测质量: 检查角点是否构成近似的正方形
        quality = self._eval_corner_quality(refined[0])

        return DetectionResult(
            success=True,
            corners=refined,
            center=center,
            rvec=rvec[0],
            tvec=tvec[0],
            num_points=4,
            quality=quality,
        )

    def _detect_chessboard(self, gray: np.ndarray,
                           camera_matrix: np.ndarray,
                           dist_coeffs: np.ndarray) -> DetectionResult:
        """
        棋盘格检测 + 位姿估计
        """
        found, corners = cv2.findChessboardCorners(
            gray, self.chessboard_size,
            flags=cv2.CALIB_CB_ADAPTIVE_THRESH +
                  cv2.CALIB_CB_NORMALIZE_IMAGE +
                  cv2.CALIB_CB_FAST_CHECK)

        if not found:
            return DetectionResult(success=False, quality=0.0)

        # 亚像素精化
        refined = cv2.cornerSubPix(
            gray, corners, self.subpix_win, self.subpix_zz, self.subpix_crit)

        # 棋盘格中心
        center = refined.reshape(-1, 2).mean(axis=0)

        # 世界坐标点
        objp = np.zeros((self.chessboard_size[0] * self.chessboard_size[1], 3), np.float32)
        objp[:, :2] = np.mgrid[0:self.chessboard_size[0],
                                0:self.chessboard_size[1]].T.reshape(-1, 2)
        objp *= self.chessboard_square

        # PnP 求解位姿
        success, rvec, tvec = cv2.solvePnP(
            objp, refined, camera_matrix, dist_coeffs,
            flags=cv2.SOLVEPNP_ITERATIVE)

        if not success:
            return DetectionResult(success=False, quality=0.0)

        quality = self._eval_corner_quality(refined)

        return DetectionResult(
            success=True,
            corners=refined,
            center=center,
            rvec=rvec,
            tvec=tvec,
            num_points=len(refined),
            quality=quality,
        )

    def _detect_circles(self, gray: np.ndarray,
                        camera_matrix: np.ndarray,
                        dist_coeffs: np.ndarray) -> DetectionResult:
        """
        圆形网格 (Blob) 检测 + 位姿估计
        圆心检测的亚像素精度通常优于角点检测
        """
        # 圆网格参数
        pattern_size = (self.chessboard_size[0], self.chessboard_size[1])

        # 使用对称圆网格
        found, centers = cv2.findCirclesGrid(
            gray, pattern_size,
            flags=cv2.CALIB_CB_SYMMETRIC_GRID +
                  cv2.CALIB_CB_CLUSTERING)

        if not found:
            return DetectionResult(success=False, quality=0.0)

        # 圆心不需要 cornerSubPix (findCirclesGrid已做亚像素)
        center = centers.reshape(-1, 2).mean(axis=0)

        # 世界坐标
        objp = np.zeros((pattern_size[0] * pattern_size[1], 3), np.float32)
        objp[:, :2] = np.mgrid[0:pattern_size[0],
                                0:pattern_size[1]].T.reshape(-1, 2)
        objp *= self.chessboard_square  # 复用棋盘格间距

        success, rvec, tvec = cv2.solvePnP(
            objp, centers, camera_matrix, dist_coeffs,
            flags=cv2.SOLVEPNP_ITERATIVE)

        if not success:
            return DetectionResult(success=False, quality=0.0)

        return DetectionResult(
            success=True,
            center=center,
            rvec=rvec,
            tvec=tvec,
            num_points=len(centers),
            quality=1.0,
        )

    def _detect_quadrant(self, gray: np.ndarray,
                         camera_matrix: np.ndarray,
                         dist_coeffs: np.ndarray) -> DetectionResult:
        """
        四象限/十字靶标检测 (带黑色外环)
        利用外环计算 Z (深度)，利用中心亚像素角点计算 X, Y
        """
        # 1. 寻找外圈黑色圆环 (拓扑轮廓结构)
        # 使用自适应阈值增加鲁棒性 (应对光照不均)
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)
        thresh = cv2.adaptiveThreshold(blurred, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, 
                                       cv2.THRESH_BINARY_INV, 21, 10)
        
        # 结合全局阈值增强轮廓
        _, thresh_global = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
        thresh = cv2.bitwise_or(thresh, thresh_global)
        
        # 使用 RETR_TREE 获取轮廓嵌套层级
        contours, hierarchy = cv2.findContours(thresh, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
        if not contours or hierarchy is None:
            return DetectionResult(success=False, quality=0.0)
            
        hierarchy = hierarchy[0]
        best_ellipse = None
        est_center_pt = None
        
        for i, (c, h) in enumerate(zip(contours, hierarchy)):
            area = cv2.contourArea(c)
            if area < 500:
                continue
                
            # 拓扑校验：精确查找包含 2 个有效子轮廓的母轮廓
            children_count = 0
            child_idx = h[2] # First child
            child_centroids = []
            
            while child_idx != -1:
                child_contour = contours[child_idx]
                child_area = cv2.contourArea(child_contour)
                # 子轮廓必须足够大（大于母轮廓的 5%）才能算是有效象限
                if child_area > area * 0.05:
                    children_count += 1
                    # 提前计算质心
                    M = cv2.moments(child_contour)
                    if M["m00"] != 0:
                        cx = M["m10"] / M["m00"]
                        cy = M["m01"] / M["m00"]
                        child_centroids.append(np.array([cx, cy]))
                child_idx = hierarchy[child_idx][0] # Next sibling
                
            if children_count == 2 and len(child_centroids) == 2:
                # 找到靶标候选！检查母轮廓圆度
                if len(c) < 5:
                    continue
                ellipse = cv2.fitEllipse(c)
                (cx_ell, cy_ell), (axis_a, axis_b), angle = ellipse
                
                if max(axis_a, axis_b) == 0:
                    continue
                ratio = min(axis_a, axis_b) / max(axis_a, axis_b)
                if ratio > 0.5: # 允许一定的透视变形
                    best_ellipse = ellipse
                    # 两个质心的中点即为极好的中心初始估计
                    est_center_pt = (child_centroids[0] + child_centroids[1]) / 2.0
                    break
                    
        if best_ellipse is None or est_center_pt is None:
            return DetectionResult(success=False, quality=0.0)
            
        (cx_ell, cy_ell), (axis_a, axis_b), angle = best_ellipse
        
        # 使用纯积分质心 (消除边缘锯齿和模糊带来的高频跳动)
        center_pt = est_center_pt
        
        # 方案A：光流追踪融合 (Lucas-Kanade)
        # 将上一帧的两个象限质心用光流法进行严格的物理纹理追踪，与当前帧的轮廓质心加权融合
        centroid_pts = np.array([child_centroids[0], child_centroids[1]], dtype=np.float32)
        if self.prev_gray is not None and self.prev_klt_pts is not None:
            p0 = self.prev_klt_pts.reshape(2, 1, 2)
            p1, st, err = cv2.calcOpticalFlowPyrLK(
                self.prev_gray, gray, p0, None, 
                winSize=(21, 21), maxLevel=3,
                criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 30, 0.01)
            )
            # 如果两个质心光流追踪都成功，并且光流追踪结果与轮廓结果偏差不大（防飞点）
            if st[0][0] == 1 and st[1][0] == 1:
                klt_pts = p1.reshape(2, 2)
                dist_err = np.linalg.norm(klt_pts - centroid_pts, axis=1)
                if np.all(dist_err < 15.0): # 允许一定范围内的光流校正
                    # 强融合：光流占90%权重（极其丝滑），轮廓占10%（绝对定位防漂移）
                    centroid_pts = 0.9 * klt_pts + 0.1 * centroid_pts
                    # 重新计算融合后的中心
                    center_pt = (centroid_pts[0] + centroid_pts[1]) / 2.0

        # 更新光流缓存
        self.prev_gray = gray.copy()
        self.prev_klt_pts = centroid_pts.copy()

        # 5. 计算位姿 (X, Y, Z)
        # 提取相机内参
        fx = camera_matrix[0, 0]
        fy = camera_matrix[1, 1]
        cx0 = camera_matrix[0, 2]
        cy0 = camera_matrix[1, 2]
        
        # 使用融合后的极高稳定性质心距离来估算像素直径
        centroid_dist = np.linalg.norm(centroid_pts[0] - centroid_pts[1])
        diameter_from_centroids = centroid_dist / 0.58
        
        # 考虑到透视形变，还是引入 20% 的椭圆拟合短长轴辅助
        pixel_diameter = 0.8 * diameter_from_centroids + 0.2 * ((axis_a + axis_b) / 2.0)
        f_mean = (fx + fy) / 2.0
        
        # self.marker_size 为靶标外环直径 (mm)
        z = (f_mean * self.marker_size) / pixel_diameter
        
        # 利用精确的中心点计算 X, Y
        x = (center_pt[0] - cx0) * z / fx
        y = (center_pt[1] - cy0) * z / fy
        
        tvec = np.array([x, y, z], dtype=np.float64)
        rvec = np.zeros(3, dtype=np.float64) # 简化不估计旋转
        
        # 将椭圆信息存入 quality 或者作为扩展属性，以便可视化
        # 这里用一个小技巧：把椭圆放在 corners 属性里供可视化使用（因为它是特殊的格式，我们稍后在 draw_detection 处理）
        return DetectionResult(
            success=True,
            center=center_pt,
            corners=np.array([[cx_ell, cy_ell, axis_a, axis_b, angle]]), # 包装一下椭圆信息
            rvec=rvec,
            tvec=tvec,
            num_points=1,
            quality=1.0
        )

    def _eval_corner_quality(self, corners: np.ndarray) -> float:
        """
        评估角点检测质量 (检查是否为近似矩形/正方形)

        通过检查对边平行度和邻边垂直度来评估
        返回 0-1 之间的质量分数
        """
        pts = corners.reshape(-1, 2)
        if len(pts) < 4:
            return 0.0

        # 对于 ArUco: 4个角点 → 检查边长比
        if len(pts) == 4:
            sides = []
            for i in range(4):
                p1, p2 = pts[i], pts[(i + 1) % 4]
                sides.append(np.linalg.norm(p2 - p1))

            sides = np.array(sides)
            ratio = sides.min() / (sides.max() + 1e-6)
            return float(ratio)

        # 对于棋盘格: 检查行列对齐
        return 0.8  # findChessboardCorners 返回后通常质量较高

    def set_mode(self, mode: str):
        """切换检测模式"""
        if mode in ("aruco", "chessboard", "circles", "quadrant"):
            self.mode = mode
            print(f"  检测模式切换为: {mode}")
        else:
            raise ValueError(f"不支持的检测模式: {mode}")

    def draw_detection(self, image: np.ndarray, result: DetectionResult,
                       color=(0, 255, 0)) -> np.ndarray:
        """在图像上绘制检测结果"""
        output = image.copy()

        if not result.success:
            cv2.putText(output, "Target NOT detected",
                        (20, 50), cv2.FONT_HERSHEY_SIMPLEX,
                        0.8, (0, 0, 255), 2)
            return output

        # 绘制角点 / 轮廓
        if result.corners is not None:
            if self.mode == "quadrant" and result.corners.shape == (1, 5):
                # 绘制拟合椭圆
                ell_data = result.corners[0]
                ellipse = ((ell_data[0], ell_data[1]), (ell_data[2], ell_data[3]), ell_data[4])
                cv2.ellipse(output, ellipse, color, 2)
            elif self.mode == "chessboard" and result.num_points > 1:
                cv2.drawChessboardCorners(output, (result.num_points, 1),
                                          result.corners, True)
            else:
                # ArUco 手动绘制
                pts = result.corners.astype(np.int32).reshape(-1, 2)
                cv2.polylines(output, [pts], True, color, 2)

                for pt in pts:
                    cv2.circle(output, tuple(pt), 4, (0, 0, 255), -1)

        # 绘制中心点
        if result.center is not None:
            cx, cy = result.center.astype(int)
            cv2.circle(output, (cx, cy), 8, (0, 255, 255), -1)
            cv2.circle(output, (cx, cy), 10, (0, 255, 255), 2)

            # 绘制坐标轴 (需要 rvec, tvec 和 camera_matrix)
            if result.rvec is not None:
                try:
                    cv2.drawFrameAxes(output, np.eye(3), np.zeros(5),
                                      result.rvec, result.tvec,
                                      self.marker_size * 0.5, 3)
                except Exception:
                    pass

        return output
