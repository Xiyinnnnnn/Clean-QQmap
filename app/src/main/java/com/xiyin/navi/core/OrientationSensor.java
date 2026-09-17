package com.xiyin.navi.core;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备朝向传感器：让地图上的箭头随身体方位旋转。
 *
 * <p>为什么必须用传感器：GPS 的 bearing（航向）只有在<b>移动</b>时才有意义，
 * 站着不动时系统给的恒为 0，所以箭头不会转。设备朝向必须由传感器求得。
 *
 * <p>实现：
 * <ol>
 *   <li>优先 {@link Sensor#TYPE_ROTATION_VECTOR}（融合了陀螺仪/加速度计/磁力计，最稳最准）</li>
 *   <li>不可用时回退 加速度计 + 磁力计 组合</li>
 * </ol>
 * 输出为相对正北的方位角（0=北，90=东，顺时针），与地图 Marker 的 rotation 语义一致。
 * 带低通滤波抑制抖动。
 */
public final class OrientationSensor implements SensorEventListener {

    private static final String TAG = "Orientation";

    public interface Listener {
        /** @param azimuthDegrees 相对正北的方位角，0~360，顺时针 */
        void onOrientation(float azimuthDegrees);
    }

    /** 低通滤波系数：越小越平滑但越迟钝（0.15 是手感与稳定的折中） */
    private static final float LOW_PASS_ALPHA = 0.15f;

    private final List<Listener> listeners = new ArrayList<>();
    private SensorManager sensorManager;
    private WindowManager windowManager;
    private Sensor rotationVectorSensor;
    private Sensor accelSensor;
    private Sensor magneticSensor;
    private boolean usingRotationVector;
    private boolean registered;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];
    private final float[] gravity = new float[3];
    private final float[] geomagnetic = new float[3];
    private boolean hasGravity;
    private boolean hasGeomagnetic;

    private float smoothedAzimuth = -1f;

    /** 启动（幂等）。无传感器时静默失败，不影响其他功能。 */
    public void start(Context context) {
        if (registered) {
            return;
        }
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) {
                Log.w(TAG, "无 SensorManager，箭头将退化为 GPS 航向");
                return;
            }
            windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotationVectorSensor != null) {
                sensorManager.registerListener(this, rotationVectorSensor,
                        SensorManager.SENSOR_DELAY_UI);
                usingRotationVector = true;
                registered = true;
                Log.i(TAG, "使用 ROTATION_VECTOR 传感器（最准）");
                return;
            }

            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (accelSensor != null && magneticSensor != null) {
                sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI);
                sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_UI);
                usingRotationVector = false;
                registered = true;
                Log.i(TAG, "使用 加速度计+磁力计 组合");
            } else {
                Log.w(TAG, "设备无可用方向传感器，箭头将退化为 GPS 航向");
            }
        } catch (Throwable t) {
            Log.w(TAG, "启动方向传感器失败: " + t);
        }
    }

    public void stop() {
        try {
            if (sensorManager != null && registered) {
                sensorManager.unregisterListener(this);
            }
        } catch (Throwable t) {
            Log.w(TAG, "停止方向传感器失败: " + t);
        }
        registered = false;
        hasGravity = false;
        hasGeomagnetic = false;
        smoothedAzimuth = -1f;
    }

    public boolean isAvailable() {
        return registered;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        try {
            if (usingRotationVector) {
                if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
                    return;
                }
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                computeAzimuth();
                return;
            }

            // 加速度计 + 磁力计 组合
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, gravity, 0, 3);
                hasGravity = true;
            } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3);
                hasGeomagnetic = true;
            }
            if (!hasGravity || !hasGeomagnetic) {
                return;
            }
            float[] r = new float[9];
            if (!SensorManager.getRotationMatrix(r, null, gravity, geomagnetic)) {
                return;
            }
            System.arraycopy(r, 0, rotationMatrix, 0, 9);
            computeAzimuth();
        } catch (Throwable t) {
            Log.w(TAG, "onSensorChanged 异常: " + t);
        }
    }

    /** 从旋转矩阵求方位角；按屏幕方向做坐标系重映射，保证竖屏/横屏都正确。 */
    private void computeAzimuth() {
        float[] remapped = new float[9];
        try {
            int rotation = Surface.ROTATION_0;
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                rotation = windowManager.getDefaultDisplay().getRotation();
            }
            switch (rotation) {
                case Surface.ROTATION_90:
                    SensorManager.remapCoordinateSystem(rotationMatrix,
                            SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped);
                    break;
                case Surface.ROTATION_180:
                    SensorManager.remapCoordinateSystem(rotationMatrix,
                            SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped);
                    break;
                case Surface.ROTATION_270:
                    SensorManager.remapCoordinateSystem(rotationMatrix,
                            SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped);
                    break;
                default:
                    System.arraycopy(rotationMatrix, 0, remapped, 0, 9);
                    break;
            }
        } catch (Throwable t) {
            System.arraycopy(rotationMatrix, 0, remapped, 0, 9);
        }

        SensorManager.getOrientation(remapped, orientation);
        float azimuth = (float) Math.toDegrees(orientation[0]);
        azimuth = normalize(azimuth);

        // 低通滤波：处理跨越 0/360 的环绕
        if (smoothedAzimuth < 0) {
            smoothedAzimuth = azimuth;
        } else {
            float delta = azimuth - smoothedAzimuth;
            if (delta > 180f) {
                delta -= 360f;
            } else if (delta < -180f) {
                delta += 360f;
            }
            smoothedAzimuth = normalize(smoothedAzimuth + LOW_PASS_ALPHA * delta);
        }

        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onOrientation(smoothedAzimuth);
            } catch (Throwable t) {
                Log.w(TAG, "listener 异常: " + t);
            }
        }
    }

    private static float normalize(float deg) {
        float d = deg % 360f;
        if (d < 0) {
            d += 360f;
        }
        return d;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.w(TAG, "磁场传感器精度不可靠，建议做 8 字校准以提升方向准确度");
        }
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
}
