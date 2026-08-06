package com.example.targettracker.config

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 完整标定数据：包含相机内参矩阵和畸变系数（棋盘格标定）
 *
 * 与旧版 DistanceCalibrator 的区别：
 *  - 旧版仅存储 fx/fy（基于单帧 pinhole 近似），无畸变系数
 *  - 新版存储完整 3x3 相机矩阵 + 5个畸变系数（k1,k2,p1,p2,k3）
 */
data class CalibrationData(
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val cameraMatrix: DoubleArray = DoubleArray(9),
    val distCoeffs: DoubleArray = DoubleArray(5),
    val reprojectionError: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isValid: Boolean
        get() = cameraMatrix[0] > 0.0 && imageWidth > 0 && imageHeight > 0

    val fx: Double get() = cameraMatrix[0]
    val fy: Double get() = cameraMatrix[4]
    val cx: Double get() = cameraMatrix[2]
    val cy: Double get() = cameraMatrix[5]

    fun toJson(): JSONObject = JSONObject().apply {
        put("imageWidth", imageWidth)
        put("imageHeight", imageHeight)
        put("cameraMatrix", JSONArray().apply {
            cameraMatrix.forEach { put(it) }
        })
        put("distCoeffs", JSONArray().apply {
            distCoeffs.forEach { put(it) }
        })
        put("reprojectionError", reprojectionError)
        put("timestamp", timestamp)
        put("version", 2)
    }

    companion object {
        fun fromJson(json: JSONObject): CalibrationData {
            val version = json.optInt("version", 1)
            if (version < 2) {
                // 兼容旧版（仅 fx/fy）
                val fx = json.optDouble("fx", 0.0)
                val fy = json.optDouble("fy", 0.0)
                val w = json.optInt("imageWidth", 0)
                val h = json.optInt("imageHeight", 0)
                return CalibrationData(
                    imageWidth = w,
                    imageHeight = h,
                    cameraMatrix = doubleArrayOf(fx, 0.0, w / 2.0, 0.0, fy, h / 2.0, 0.0, 0.0, 1.0),
                    distCoeffs = DoubleArray(5)
                )
            }
            val cmArr = json.getJSONArray("cameraMatrix")
            val dcArr = json.getJSONArray("distCoeffs")
            return CalibrationData(
                imageWidth = json.getInt("imageWidth"),
                imageHeight = json.getInt("imageHeight"),
                cameraMatrix = DoubleArray(9) { cmArr.getDouble(it) },
                distCoeffs = DoubleArray(5) { dcArr.getDouble(it) },
                reprojectionError = json.optDouble("reprojectionError", 0.0),
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
        }

        fun save(prefs: SharedPreferences, data: CalibrationData) {
            prefs.edit()
                .putString("calibration_data", data.toJson().toString())
                .apply()
        }

        fun load(prefs: SharedPreferences): CalibrationData? {
            val jsonStr = prefs.getString("calibration_data", null) ?: return null
            return try {
                fromJson(JSONObject(jsonStr))
            } catch (_: Exception) {
                null
            }
        }
    }
}
