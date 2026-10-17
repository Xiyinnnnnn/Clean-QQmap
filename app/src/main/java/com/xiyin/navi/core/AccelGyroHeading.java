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
 * 方向融合器：<b>以加速度计（重力）为基线、以陀螺仪做短时角速度积分修正</b>。
 *
 * <p><b>磁力计的角色（仅初始化，不参与连续）</b>：磁力计是唯一能给出"相对地磁北的
 * 绝对方位"的传感器，但它在铁磁物/电流/金属建筑/车内/地库等环境磁场下一扰就飘——
 * 这正是旧版箭头"飘逸不准"的根因。因此本类只在<b>刚启动时</b>读它，用几帧平均求出
 * 一个<b>初始北方</b>作为绝对参考，随后<b>立即注销磁力计</b>，连续运行阶段<b>再不读取</b>。
 * 没有磁力计的设备则退化为"由外部种子(移动方向/定位航向)初始化"。
 *
 * <p><b>连续跟踪（无磁力计）</b>：
 * <ol>
 *   <li><b>加速度基线</b>：低通得到重力向量（Android 加速度计在静止时指向<b>上</b>，
 *       即 +9.81·up），归一化为地垂线。</li>
 *   <li><b>陀螺仪修正</b>：把角速度投影到地垂线上得到真正的航向角速度，积分即可在
 *       两次外部锚定之间平滑补间。注意符号：绕地垂线（向下，右旋为正=顺时针）的
 *       角速度 = -(ω·ĝ_up)。</li>
 *   <li><b>静止冻结</b>：既不旋转也无线性加速度时停止积分，抑制陀螺零偏漂移。</li>
 * </ol>
 *
 * <p>输出为相对正北的方位角（0=北，90=东，顺时针，0~360），语义与地图 Marker 的
 * {@code setRotation} 一致；尚未初始化时返回 {@link Float#NaN}（调用方回退）。
 *
 * <p><b>用途定位</b>：本类是"设备朝向"的短期平滑估计，主界面箭头的<b>真实方向</b>以
 * {@link CourseTracker}（移动方向）为基线，本类只在静止/低速（路口试探性旋转）时辅助。
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
    /** 初始定北所需磁力计采样帧数（平均以抗噪，sin/cos 平均免疫 0/360 环绕） */
    private static final int MAG_INIT_SAMPLES = 6;

    public interface Listener {
        /** @param azimuthDegrees 相对正北方位角，0~360，顺时针 */
        void onHeading(float azimuthDegrees);
    }

    private final List<Listener> listeners = new ArrayList<>();

    private SensorManager sensorManager;
    private Sensor accelSensor;
    private Sensor gyroSensor;
    private Sensor magneticSensor;
    private boolean registered;

    private final float[] gravity = new float[3];   // 低通后的加速度计读数（device 系，静止时指向上）
    private boolean hasGravity;
    private float lastAccelMag = STANDARD_G;

    private long lastGyroNs;
    private long stillSinceMs;
    private long lastEmitMs;

    private double azimuth;        // 融合方位（0=北，顺时针，0~360）
    private boolean anchored;      // 是否已获得绝对锚（磁力计初始化 或 外部种子）
    private int anchorCount;

    // 磁力计一次性初始化
    private boolean magInitialized;   // 是否已完成初始化（完成后不再读磁力计）
    private double magSin;            // sin/cos 累加（免疫 0/360）
    private double magCos;
    private int magSamples;

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
                return;
            }
            // 均注册到主线程（调用线程），回调与 Marker 更新同一线程，无需加锁
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
            registered = true;

            // 磁力计：仅用于「一次性初始定北」，采样完成后会立即注销，不参与连续定位
            if (!magInitialized) {
                magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
                if (magneticSensor != null) {
                    sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_UI);
                    Log.i(TAG, "磁力计已注册（仅用于一次性初始定北）");
                } else {
                    Log.w(TAG, "无磁力计，方向将由外部种子(移动方向/定位航向)初始化");
                }
            } else {
                Log.i(TAG, "磁力计已初始化过，本次不再注册（不参与连续定位）");
            }
            Log.i(TAG, "方向融合启动：加速度基线 + 陀螺仪修正");
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
        // 保留 azimuth/anchored/magInitialized：暂停恢复后方向不丢、也不必重新定北
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
     * 喂入一个<b>绝对</b>方向作为初始锚（仅在没有磁力计、或磁力计尚未完成初始化时使用），
     * 把方位钉回真北。磁力计成功初始化后不再调用（磁力计不参与连续定位）。
     */
    public void setReferenceBearing(float bearingDegrees) {
        if (Float.isNaN(bearingDegrees) || magInitialized) {
            return;
        }
        azimuth = normalize360(bearingDegrees);
        anchored = true;
        anchorCount++;
        emit(System.currentTimeMillis(), true);
    }

    /** 当前融合方位（0~360，0=北，顺时针）；尚未初始化时返回 {@link Float#NaN}。 */
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
        magInitialized = false;
        magSin = 0;
        magCos = 0;
        magSamples = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        try {
            int type = event.sensor.getType();
            if (type == Sensor.TYPE_ACCELEROMETER) {
                onAccel(event);
            } else if (type == Sensor.TYPE_GYROSCOPE) {
                onGyro(event);
            } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
                onMagnetic(event);
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

    /**
     * 磁力计：仅用于一次性初始定北。需要已知重力方向（水平基准）才能解出方位；
     * 累积若干帧做 sin/cos 平均后写入初始方位，并<b>立即注销磁力计</b>。
     */
    private void onMagnetic(SensorEvent event) {
        if (magInitialized || !hasGravity) {
            return; // 已完成初始化 / 尚无重力基准 → 忽略
        }
        float[] r = new float[9];
        float[] i = new float[9];
        // 标准用法：重力向量传加速度计读数（静止时指向上）；不可解算则跳过本帧
        if (!SensorManager.getRotationMatrix(r, i, gravity, event.values)) {
            return;
        }
        float[] orientation = new float[3];
        SensorManager.getOrientation(r, orientation);          // ori[0]=方位角(rad)
        double az = Math.toDegrees(orientation[0]);            // 0=北，顺时针
        double rad = Math.toRadians(az);
        magSin += Math.sin(rad);
        magCos += Math.cos(rad);
        magSamples++;
        if (magSamples < MAG_INIT_SAMPLES) {
            return;
        }
        // sin/cos 平均（免疫 0/360 环绕）→ 初始绝对方位
        azimuth = normalize360(Math.toDegrees(Math.atan2(magSin / magSamples, magCos / magSamples)));
        anchored = true;
        anchorCount++;
        magInitialized = true;
        unregisterMagnetometer();
        Log.i(TAG, String.format(Locale.CHINA,
                "磁力计初始化完成：初始北方=%d°（%d 帧平均），已注销磁力计，此后不参与连续定位",
                (int) azimuth, magSamples));
        emit(System.currentTimeMillis(), true);
    }

    /** 注销磁力计（初始化完成后调用，此后不再读取）。 */
    private void unregisterMagnetometer() {
        try {
            if (sensorManager != null && magneticSensor != null) {
                sensorManager.unregisterListener(this, magneticSensor);
            }
        } catch (Throwable t) {
            Log.w(TAG, "注销磁力计失败: " + t);
        }
        magneticSensor = null;
    }

    /**
     * 陀螺仪：把角速度投影到地垂线得到航向角速度并积分。
     *
     * <p>符号依据：Android 加速度计读数在静止时指向<b>上</b>（+9.81·up），故
     * {@code û = normalize(gravity)} 为地垂线向上方向；绕地垂线（向下，右旋为正 =
     * 顺时针）的角速度 = {@code -(ω·û)}，与方位角（0=北，顺时针）增大的方向一致。
     */
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

        // 归一化加速度计读数（指向上）作为地垂线 û
        double ux = gravity[0], uy = gravity[1], uz = gravity[2];
        double um = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (um < 1e-3) {
            return;
        }
        ux /= um;
        uy /= um;
        uz /= um;

        double wx = event.values[0] * GYRO_TO_RADS;
        double wy = event.values[1] * GYRO_TO_RADS;
        double wz = event.values[2] * GYRO_TO_RADS;
        double gyroMag = Math.sqrt(wx * wx + wy * wy + wz * wz);

        // 航向角速度 = -(ω·û)（顺时针为正，rad/s）
        double bearingRate = -(wx * ux + wy * uy + wz * uz);

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
        // 磁力计仅初始化用，不做连续精度提示
    }

    /** 供状态栏显示的简短描述。 */
    public String describe() {
        if (!registered) {
            return "融合方向(不可用)";
        }
        if (!anchored) {
            return "融合方向(待定北)";
        }
        return String.format(Locale.CHINA, "融合方向 %d°(加速度基线+陀螺%s)",
                (int) azimuth, magInitialized ? "·磁力计已注销" : "");
    }

    private static double normalize360(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }
}
