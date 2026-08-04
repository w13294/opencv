package com.example.targettracker.config

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 标定数据持久化。
 *
 * 焦距与"哪颗摄像头 + 什么变焦倍率"强绑定 (后摄是逻辑多摄, 变焦会切物理镜头,
 * 焦距是跳变的), 因此按 cameraId + zoom 档位分别保存, 切换后自动套用对应标定值。
 *
 * 内参存的是标定当时的分辨率, 使用时由调用方按当前分辨率线性缩放。
 */
object CalibrationStore {
    private const val TAG = "CalibStore"
    private const val PREF = "target_tracker_calib"
    private const val KEY = "entries"

    /** 变焦分档: 同一物理镜头内焦距随变焦线性变化, 按 0.1x 粒度归档 */
    private fun keyOf(cameraId: String, zoom: Float): String =
        "$cameraId@%.1f".format(zoom)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 读取全部标定条目 */
    fun loadAll(ctx: Context): Map<String, CalibrationData> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = mutableMapOf<String, CalibrationData>()
            for (k in obj.keys()) {
                out[k] = fromJson(obj.getJSONObject(k))
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "loadAll failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 取指定摄像头/变焦的标定值。
     * 若该变焦档无记录, 回退到同一摄像头 1.0x 的记录并按变焦比换算焦距。
     */
    fun load(ctx: Context, cameraId: String, zoom: Float): CalibrationData? {
        val all = loadAll(ctx)
        all[keyOf(cameraId, zoom)]?.let { return it }

        // 回退: 用基准档 (1.0x) 按变焦比线性外推
        val base = all[keyOf(cameraId, 1.0f)] ?: return null
        if (zoom <= 0f) return base
        val z = zoom.toDouble()
        val cm = base.cameraMatrix.copyOf()
        cm[0] = cm[0] * z
        cm[4] = cm[4] * z
        return base.copy(cameraMatrix = cm)
    }

    /** 保存一条标定值 */
    fun save(ctx: Context, cameraId: String, zoom: Float, data: CalibrationData) {
        try {
            val raw = prefs(ctx).getString(KEY, null)
            val obj = if (raw != null) JSONObject(raw) else JSONObject()
            obj.put(keyOf(cameraId, zoom), toJson(data))
            prefs(ctx).edit().putString(KEY, obj.toString()).apply()
            Log.i(TAG, "saved calib for ${keyOf(cameraId, zoom)}")
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}")
        }
    }

    /** 清除指定摄像头/变焦档的标定 */
    fun clear(ctx: Context, cameraId: String, zoom: Float) {
        try {
            val raw = prefs(ctx).getString(KEY, null) ?: return
            val obj = JSONObject(raw)
            obj.remove(keyOf(cameraId, zoom))
            prefs(ctx).edit().putString(KEY, obj.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "clear failed: ${e.message}")
        }
    }

    /** 清除全部标定 */
    fun clearAll(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    private fun toJson(d: CalibrationData): JSONObject = JSONObject().apply {
        put("cameraMatrix", JSONArray().also { a -> d.cameraMatrix.forEach { a.put(it) } })
        put("distCoeffs", JSONArray().also { a -> d.distCoeffs.forEach { a.put(it) } })
        put("imageWidth", d.imageWidth)
        put("imageHeight", d.imageHeight)
        put("reprojectionError", d.reprojectionError)
        put("isValid", d.isValid)
    }

    private fun fromJson(o: JSONObject): CalibrationData {
        fun arr(name: String, size: Int): DoubleArray {
            val a = o.optJSONArray(name) ?: return DoubleArray(size)
            return DoubleArray(size) { if (it < a.length()) a.optDouble(it, 0.0) else 0.0 }
        }
        return CalibrationData(
            cameraMatrix = arr("cameraMatrix", 9),
            distCoeffs = arr("distCoeffs", 5),
            imageWidth = o.optInt("imageWidth", 1280),
            imageHeight = o.optInt("imageHeight", 720),
            reprojectionError = o.optDouble("reprojectionError", 0.0),
            isValid = o.optBoolean("isValid", false)
        )
    }
}
