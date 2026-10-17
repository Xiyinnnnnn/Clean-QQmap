package com.xiyin.navi.core;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 方向融合器：<b>以加速度计（重力）为基准、以陀螺仪做短时角速度积分修正</b>，
 * <b>完全不使用磁力计</b>。
 *
 * <p><b>为什么不引入磁力计</b>：磁力计给出的是"相对地磁北"的绝对方位，但它对
 * 铁磁物、电流、金属建筑、车内/地库等环境磁场极其敏感，磁场一扰方向就"飘逸"，
 * 这正是旧版箭头乱飘的根因。本类彻底不读取 {@link Sensor#TYPE_MAGNETIC_FIELD}。
 *
 * <p><b>没有磁力计时绝对方位从哪来（关键）</b>：重力只提供一条地垂线（上/下），
 * 单靠加速度计<b>在物理上无法确定水平方位</b>（缺少一条水平参考）。因此本类的做法是：
 * <ol>
 *   <li><b>加速度基线</b>：对加速度计做低通得到重力向量 {@code g}（device 坐标系，指向下），
 *       归一化为地垂线。它定义了"水平基准框架"，并把陀螺仪读数中绕地垂线的分量
 *       {@code ω·ĝ} 提取为真正的<b>航向角速度</b>（不受手机俯仰/横滚姿态影响）。</li>
 *   <li><b>陀螺仪修正</b>：对该航向角速度积分 → 得到短时、平滑、无磁干扰的方向变化，
 *       两次定位之间由它补间，箭头不再一格一格跳。</li>
 *   <li><b>绝对锚定</b>：由外部（{@link #setReferenceBearing(float)}）喂入
 *       "移动方向 / GPS 航向"这类<b>绝对</b>方向，把积分结果钉回真北；移动时持续校正，
 *       因此不会长期漂移。</li>
 *   <li><b>静止冻结</b>：当设备既不旋转（角速度极小）也不受线性加速度时，停止积分，
 *       抑制陀螺零偏在长时间静止下的缓慢漂移（此时转身会被角速度检测到，照常跟随）。</li>
 * </ol>
 *
 * <p>输出为相对正北的方位角（0=北，90=东，顺时针，0~360），语义与地图 Marker 的
 * {@code setRotation} 一致；在获得绝对锚定之前返回 {@link Float#NaN}（调用方回退到
 * 移动方向推算）。真正"看哪个轴"= 与移动方向锚定时所代表的轴一致（默认手机顶部朝向）。
 */
public final class AccelGyroHeading implements SensorEventListener {

    private static final String TAG = "AccelGyroHeading";

    /** 陀螺仪原始值 → rad/s（Android 陀螺仪单位 1/180 °/s = π/180 rad/s） */
    private static final double GYRO_TO_RADS = Math.PI / 180.0;
    /** 重力低通系数（越小越平滑，抑制运动加速度与抖动对基准的污染） */
    private static final float GRAVITY_LP_ALPHA = 0.15f;
    /** 标准重力加速度 (m/s²) */
    private static final float STANDARD_G = 9.80665f;
    /** 静止判定：角速度模长阈值 (rad/s)，约 3.4°/s */
    private static final float STILL_GYRO_RADS = 0.06f;
    /** 静止判定：加速度模长偏离标准重力的阈值 (m/s²) */
    private static final float STILL_ACC_DEV = 0.45f;
    /** 连续静止该时长后冻结方位积分（抑制陀螺零偏漂移） */
    private static final long STILL_FREEZE_MS = 400L;
    /** 单帧积分的最大 dt（秒），防止主线程卡顿后角度猛跳 */
    private static final float MAX_DT_S = 0.2f;
    /** 回调节流间隔（毫秒），避免高频刷新 Marker */
    private static final long EMIT_MIN_INTERVAL_MS = 60L;

    public interface Listener {
        /** @param azimuthDegrees 相对正北方位角，0~360，顺时针 */
        void onHeading(float azimuthDegrees);
    }

    private final List<Listener> listeners = new ArrayList<>();

    private SensorManager sensorManager;
    private Sensor accelSensor;
    private Sensor gyroSensor;
    private boolean registered;

    private final float[] gravity = new float[3];   // 低通后的重力（device 系，指向下）
    private boolean hasGravity;
    private float lastAccelMag = STANDARD_G;

    private long lastGyroNs;
    private long stillSinceMs;
    private long lastEmitMs;

    private double azimuth;        // 融合方位（0=北，顺时针，0~360）
    private boolean anchored;      // 是否已获得绝对锚定
    private int anchorCount;

    /** 启动（幂等）。无陀螺仪时静默失败，getHeading() 一直返回 NaN，由调用方兜底。 */
    public void start(Context context) {
        if (registered) {
            return;
        }
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) {
                Log.w(TAG, "无 SensorManager，方向融合不可用");
                return;
            }
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (accelSensor == null) {
                Log.w(TAG, "设备无加速度计，方向融合不可用");
                return;
            }
            if (gyroSensor == null) {
                Log.w(TAG, "设备无陀螺仪，方向融合退化为移动方向兜底");
                // 仍注册加速度计无意义 → 直接不注册
                return;
            }
            // 均注册到主线程（调用线程），回调与 Marker 更新同一线程，无需加锁
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
            registered = true;
            Log.i(TAG, "方向融合启动：加速度基线 + 陀螺仪修正（不使用磁力计）");
        } catch (Throwable t) {
            Log.w(TAG, "启动方向融合失败: " + t);
        }
    }

    public void stop() {
        try {
            if (sensorManager != null && registered) {
                sensorManager.unregisterListener(this);
            }
        } catch (Throwable t) {
            Log.w(TAG, "停止方向融合失败: " + t);
        }
        registered = false;
        hasGravity = false;
        lastGyroNs = 0;
        stillSinceMs = 0;
        anchored = false;
        anchorCount = 0;
        azimuth = 0;
    }

    public boolean isAvailable() {
        return registered;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * 喂入一个<b>绝对</b>方向（移动方向 COG 或 GPS 航向）作为锚，把融合方位钉回真北。
     * 移动时持续调用即可持续校正漂移。
     */
    public void setReferenceBearing(float bearingDegrees) {
        if (Float.isNaN(bearingDegrees)) {
            return;
        }
        azimuth = normalize360(bearingDegrees);
        anchored = true;
        anchorCount++;
        emit(System.currentTimeMillis(), true);
    }

    /** 当前融合方位（0~360，0=北，顺时针）；尚未锚定或不可用时返回 {@link Float#NaN}。 */
    public float getHeading() {
        return anchored ? (float) azimuth : Float.NaN;
    }

    public boolean hasHeading() {
        return anchored;
    }

    public void reset() {
        azimuth = 0;
        anchored = false;
        anchorCount = 0;
        lastGyroNs = 0;
        stillSinceMs = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        try {
            int type = event.sensor.getType();
            if (type == Sensor.TYPE_ACCELEROMETER) {
                onAccel(event);
            } else if (type == Sensor.TYPE_GYROSCOPE) {
                onGyro(event);
            }
        } catch (Throwable t) {
            Log.w(TAG, "onSensorChanged 异常: " + t);
        }
    }

    /** 加速度计：低通得到重力基准，并记录模长用于静止判定。 */
    private void onAccel(SensorEvent event) {
        float ax = event.values[0], ay = event.values[1], az = event.values[2];
        lastAccelMag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (!hasGravity) {
            gravity[0] = ax;
            gravity[1] = ay;
            gravity[2] = az;
            hasGravity = true;
            return;
        }
        gravity[0] += (ax - gravity[0]) * GRAVITY_LP_ALPHA;
        gravity[1] += (ay - gravity[1]) * GRAVITY_LP_ALPHA;
        gravity[2] += (az - gravity[2]) * GRAVITY_LP_ALPHA;
    }

    /** 陀螺仪：把角速度投影到地垂线上得到航向角速度，积分更新融合方位。 */
    private void onGyro(SensorEvent event) {
        long nowNs = event.timestamp;
        if (lastGyroNs == 0) {
            lastGyroNs = nowNs;
            return;
        }
        float dt = (nowNs - lastGyroNs) / 1_000_000_000f;
        lastGyroNs = nowNs;
        if (dt <= 0f || !hasGravity) {
            return;
        }
        if (dt > MAX_DT_S) {
            dt = MAX_DT_S;
        }

        // 归一化重力（指向下）作为地垂线
        double gx = gravity[0], gy = gravity[1], gz = gravity[2];
        double gm = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (gm < 1e-3) {
            return;
        }
        gx /= gm;
        gy /= gm;
        gz /= gm;

        double wx = event.values[0] * GYRO_TO_RADS;
        double wy = event.values[1] * GYRO_TO_RADS;
        double wz = event.values[2] * GYRO_TO_RADS;
        double gyroMag = Math.sqrt(wx * wx + wy * wy + wz * wz);

        // 航向角速度：ω·ĝ（顺时针为正），与手机俯仰/横滚无关
        double bearingRate = wx * gx + wy * gy + wz * gz; // rad/s

        // 静止冻结：既不旋转也不受线性加速度，停止积分（抑制陀螺零偏漂移）
        boolean still = gyroMag < STILL_GYRO_RADS
                && Math.abs(lastAccelMag - STANDARD_G) < STILL_ACC_DEV;
        long nowMs = System.currentTimeMillis();
        if (still) {
            if (stillSinceMs == 0) {
                stillSinceMs = nowMs;
            } else if (nowMs - stillSinceMs > STILL_FREEZE_MS) {
                bearingRate = 0;
            }
        } else {
            stillSinceMs = 0;
        }

        azimuth = normalize360(azimuth + Math.toDegrees(bearingRate * dt));
        if (anchored) {
            emit(nowMs, false);
        }
    }

    private void emit(long nowMs, boolean force) {
        if (!force && nowMs - lastEmitMs < EMIT_MIN_INTERVAL_MS) {
            return;
        }
        lastEmitMs = nowMs;
        float value = (float) azimuth;
        for (Listener l : listeners) {
            try {
                l.onHeading(value);
            } catch (Throwable t) {
                Log.w(TAG, "listener 异常: " + t);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不涉及磁力计，无需校准提示
    }

    /** 供状态栏显示的简短描述。 */
    public String describe() {
        if (!registered) {
            return "融合方向(不可用)";
        }
        if (!anchored) {
            return "融合方向(待锚定)";
        }
        return String.format(Locale.CHINA, "融合方向 %d°(加速度基线+陀螺)",
                (int) azimuth);
    }

    private static double normalize360(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }
}
