import cv2
import numpy as np
from dataclasses import dataclass
from typing import Optional, Dict

@dataclass
class DetectionResult:
    """检测结果数据结构"""
    success: bool                               # 是否成功
    target_id: int = -1                         # 靶标分配的 ID
    corners: Optional[np.ndarray] = None        # 图像角点 (拟合椭圆数据)
    center: Optional[np.ndarray] = None         # 图像中靶标中心 (cx, cy)
    rvec: Optional[np.ndarray] = None           # 旋转向量
    tvec: Optional[np.ndarray] = None           # 平移向量 (mm)
    quality: float = 0.0                        # 检测质量 (0-1)


class TargetDetector:
    """
    专精的多四象限靶标检测器
    """
    def __init__(self, config: dict):
        self.config = config
        
        # 光流法状态缓存
        # 结构: { target_id: {"gray": prev_gray, "klt_pts": np.ndarray} }
        self.tracked_targets = {}
        
        self.default_sizes = config.get("default_sizes_mm", [200.0, 100.0, 50.0])
        # {target_id: size_mm} 动态设定的尺寸
        self.target_sizes = {} 
        
    def set_target_size(self, target_id: int, size_mm: float):
        self.target_sizes[target_id] = size_mm
        
    def get_target_size(self, target_id: int) -> float:
        if target_id in self.target_sizes:
            return self.target_sizes[target_id]
        if target_id < len(self.default_sizes):
            return self.default_sizes[target_id]
        return self.default_sizes[-1] if self.default_sizes else 200.0

    def detect(self, image: np.ndarray, camera_matrix: np.ndarray, dist_coeffs: np.ndarray) -> Dict[int, DetectionResult]:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)
        thresh = cv2.adaptiveThreshold(blurred, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, 
                                       cv2.THRESH_BINARY_INV, 21, 10)
        _, thresh_global = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
        thresh = cv2.bitwise_or(thresh, thresh_global)
        
        contours, hierarchy = cv2.findContours(thresh, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
        
        valid_candidates = []
        if contours and hierarchy is not None:
            hierarchy = hierarchy[0]
            for i, (c, h) in enumerate(zip(contours, hierarchy)):
                area = cv2.contourArea(c)
        valid_candidates = []
        if contours and hierarchy is not None:
            hierarchy = hierarchy[0]
            for i, (c, h) in enumerate(zip(contours, hierarchy)):
                area = cv2.contourArea(c)
                if area < 300:
                    continue
                
                children_count = 0
                child_idx = h[2]
                child_centroids = []
                while child_idx != -1:
                    child_contour = contours[child_idx]
                    if cv2.contourArea(child_contour) > area * 0.03:
                        children_count += 1
                        M = cv2.moments(child_contour)
                        if M["m00"] != 0:
                            child_centroids.append(np.array([M["m10"] / M["m00"], M["m01"] / M["m00"]]))
                    child_idx = hierarchy[child_idx][0]
                    
                if len(c) >= 5:
                    ellipse = cv2.fitEllipse(c)
                    _, (axis_a, axis_b), _ = ellipse
                    if max(axis_a, axis_b) == 0: continue
                    ratio = min(axis_a, axis_b) / max(axis_a, axis_b)
                    
                    # 具备至少 1 个子轮廓且形状接近圆形/椭圆
                    if children_count >= 1 and ratio > 0.35:
                        if len(child_centroids) < 2:
                            cx_e, cy_e = ellipse[0]
                            c_pts = np.array([[cx_e, cy_e], [cx_e, cy_e]], dtype=np.float32)
                        else:
                            c_pts = np.array(child_centroids[:2], dtype=np.float32)
                            
                        valid_candidates.append({
                            "ellipse": ellipse,
                            "centroids": c_pts
                        })

        # ── 稳定 ID 分配: 最近邻匹配 (带 30 帧历史缓存，防止丢失时 ID 变动或暴涨) ──
        cand_centers = [(cand["centroids"][0] + cand["centroids"][1]) / 2.0 for cand in valid_candidates]
        
        # 上一帧已知靶标的中心
        prev_centers = {}
        for tid, data in self.tracked_targets.items():
            prev_centers[tid] = data["center"]
        
        assigned_ids = {}
        used_tids = set()
        
        if prev_centers and cand_centers:
            pairs = []
            for ci, cc in enumerate(cand_centers):
                for tid, pc in prev_centers.items():
                    dist = np.linalg.norm(cc - pc)
                    pairs.append((dist, ci, tid))
            pairs.sort()
            
            for dist, ci, tid in pairs:
                if ci in assigned_ids or tid in used_tids:
                    continue
                if dist < 150.0:  # 150 像素内都算同一个靶标
                    assigned_ids[ci] = tid
                    used_tids.add(tid)
        
        # 为未匹配的新候选分配 ID
        existing_ids = list(self.tracked_targets.keys())
        next_id = (max(existing_ids) + 1) if existing_ids else 0
        for ci in range(len(valid_candidates)):
            if ci not in assigned_ids:
                assigned_ids[ci] = next_id
                next_id += 1
        
        # 保留未匹配到的已知旧靶标历史 (最多保留 30 帧，防止一帧识别不到就重置 ID)
        new_tracked_targets = {}
        shared_gray = gray.copy()
        
        for tid, data in self.tracked_targets.items():
            if tid not in used_tids:
                m_cnt = data.get("missing_count", 0) + 1
                if m_cnt <= 30:  # 容忍最多 30 帧的短暂丢失
                    new_tracked_targets[tid] = {
                        "gray": data["gray"],
                        "klt_pts": data["klt_pts"],
                        "center": data["center"],
                        "missing_count": m_cnt
                    }
        
        results = {}
        for ci, cand in enumerate(valid_candidates):
            target_id = assigned_ids[ci]
            child_centroids = cand["centroids"]
            ellipse = cand["ellipse"]
            (cx_ell, cy_ell), (axis_a, axis_b), angle = ellipse
            
            center_pt = (child_centroids[0] + child_centroids[1]) / 2.0
            
            # 更新追踪状态
            new_tracked_targets[target_id] = {
                "gray": shared_gray,
                "klt_pts": child_centroids.copy(),
                "center": center_pt,
                "missing_count": 0
            }
            
            fx, fy = camera_matrix[0, 0], camera_matrix[1, 1]
            cx0, cy0 = camera_matrix[0, 2], camera_matrix[1, 2]
            
            if np.linalg.norm(child_centroids[0] - child_centroids[1]) > 1e-3:
                centroid_dist = np.linalg.norm(child_centroids[0] - child_centroids[1])
                pixel_diameter = 0.8 * (centroid_dist / 0.58) + 0.2 * ((axis_a + axis_b) / 2.0)
            else:
                pixel_diameter = (axis_a + axis_b) / 2.0
            
            marker_size = self.get_target_size(target_id)
            z = ((fx + fy) / 2.0 * marker_size) / max(pixel_diameter, 1.0)
            x = (center_pt[0] - cx0) * z / fx
            y = (center_pt[1] - cy0) * z / fy
            
            tvec = np.array([x, y, z], dtype=np.float64)
            rvec = np.zeros(3, dtype=np.float64)
            
            results[target_id] = DetectionResult(
                success=True,
                target_id=target_id,
                center=center_pt,
                corners=np.array([[cx_ell, cy_ell, axis_a, axis_b, angle]]),
                rvec=rvec, tvec=tvec,
                quality=1.0
            )

        self.tracked_targets = new_tracked_targets
        return results

    def draw_detections(self, image: np.ndarray, results: Dict[int, DetectionResult]) -> np.ndarray:
        output = image.copy()
        for t_id, res in results.items():
            if not res.success: continue
            
            if res.corners is not None:
                c_arr = np.atleast_2d(res.corners)
                if c_arr.shape[1] == 5:
                    cx_e, cy_e, a_e, b_e, ang_e = c_arr[0]
                    ellipse = ((float(cx_e), float(cy_e)), (float(a_e), float(b_e)), float(ang_e))
                    cv2.ellipse(output, ellipse, (0, 255, 0), 2)
                
            if res.center is not None:
                c_center = np.asarray(res.center).flatten()
                if len(c_center) >= 2:
                    cx, cy = int(c_center[0]), int(c_center[1])
                    cv2.circle(output, (cx, cy), 8, (0, 255, 255), -1)
                    cv2.putText(output, f"ID: {t_id} ({self.get_target_size(t_id):.0f}mm)", (cx + 15, cy - 15), 
                                cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 255), 2)
        return output
