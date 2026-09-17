package com.xiyin.navi.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tencent.map.navi.data.AttachedLocation;

/**
 * 偏航检测 + 触发重算（v2.6 新增）。
 *
 * <p><b>为什么必须自己做这件事：</b>
 * 本 App 的位置由外部 {@code updateLocation()} 注入（纯原生 GPS 方案），
 * SDK 内部那条"自动发现偏航并重算"的链路不一定会触发。
 * 反编译证实：SDK 的重算入口 {@code changeNaviRoute(1)} <b>只从公开方法
 * {@code onOffRoute()} 进入</b>——也就是说它把"何时算偏航"的决定权交给了调用方。
 * 官方 Demo 把定位交给 SDK 自己管，所以不用操心；我们接管了定位，就必须自己判定。
 *
 * <p><b>判定依据：</b>SDK 回调 {@code onUpdateAttachedLocation(AttachedLocation)} 里
 * 同时给出两个坐标：
 * <ul>
 *   <li>实际位置：{@code getLatitude()/getLongitude()}</li>
 *   <li>绑路位置：{@code getAttachedLatitude()/getAttachedLongitude()}</li>
 * </ul>
 * 两者距离就是"离路线多远"，比自己去算点到折线距离更准（SDK 已做过路线吸附）。
 *
 * <p>为防误触发，要求<b>连续 {@link AppConst#OFF_ROUTE_CONFIRM_COUNT} 次</b>超阈值才重算，
 * 且两次重算之间有 {@link AppConst#REROUTE_COOLDOWN_MS} 冷却。
 */
public final class OffRouteMonitor {

    private static final String TAG = "OffRoute";

    /** 触发重算的动作，由各导航页提供（内部调用 manager.onOffRoute()） */
    public interface RerouteAction {
        void triggerReroute();
    }

    private final float thresholdMeters;
    private final RerouteAction action;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int consecutiveOffRoute;
    private long lastRerouteTime;
    private boolean rerouting;

    public OffRouteMonitor(float thresholdMeters, RerouteAction action) {
        this.thresholdMeters = thresholdMeters;
        this.action = action;
    }

    /**
     * 每次收到 {@code onUpdateAttachedLocation} 时调用。
     * 距离超阈值即累计，连续达阈值次数则触发重算。
     */
    public void onAttachedLocation(AttachedLocation attached) {
        if (attached == null || !attached.isValid()) {
            return;
        }
        // 偏航距离 = 实际位置 与 绑路位置 的球面距离
        double distance = distanceMeters(
                attached.getLatitude(), attached.getLongitude(),
                attached.getAttachedLatitude(), attached.getAttachedLongitude());

        // 定位精度太差时结果不可信，跳过判定（避免 GPS 跳点误触发重算）
        if (attached.getAccuracy() > 100f) {
            Log.i(TAG, "精度不足(" + attached.getAccuracy() + "m)，跳过偏航判定");
            return;
        }

        Log.d(TAG, String.format(java.util.Locale.CHINA,
                "偏航距离 %.1fm（阈值 %.0fm）连续%d次", distance, thresholdMeters, consecutiveOffRoute));

        if (distance <= thresholdMeters) {
            if (consecutiveOffRoute != 0) {
                Log.i(TAG, "已回到路线");
            }
            consecutiveOffRoute = 0;
            return;
        }

        consecutiveOffRoute++;
        if (consecutiveOffRoute < AppConst.OFF_ROUTE_CONFIRM_COUNT) {
            Log.i(TAG, "疑似偏航 " + String.format(java.util.Locale.CHINA, "%.0fm", distance)
                    + "，待确认（" + consecutiveOffRoute + "/" + AppConst.OFF_ROUTE_CONFIRM_COUNT + "）");
            return;
        }
        tryReroute(distance);
    }

    /** 收到 SDK 的 onOffRoute() 回调时调用：说明 SDK 自己也发现偏航了。 */
    public void onSdkOffRoute() {
        Log.w(TAG, "SDK 上报偏航，直接触发重算");
        tryReroute(-1);
    }

    private void tryReroute(double distance) {
        long now = System.currentTimeMillis();
        if (rerouting) {
            Log.i(TAG, "重算进行中，忽略");
            return;
        }
        if (now - lastRerouteTime < AppConst.REROUTE_COOLDOWN_MS) {
            Log.i(TAG, "重算冷却中（剩余 "
                    + (AppConst.REROUTE_COOLDOWN_MS - (now - lastRerouteTime)) / 1000 + "s），暂不重算");
            return;
        }
        rerouting = true;
        lastRerouteTime = now;
        consecutiveOffRoute = 0;
        Log.w(TAG, ">>> 确认偏航，触发重新算路"
                + (distance > 0 ? String.format(java.util.Locale.CHINA, "（偏离 %.0fm）", distance) : ""));
        main.post(() -> {
            try {
                action.triggerReroute();
            } catch (Throwable t) {
                Log.e(TAG, "触发重算失败: " + t);
            }
        });
    }

    /** 重算完成后调用，解除进行中标记。 */
    public void onRerouteFinished() {
        rerouting = false;
    }

    public double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371000.0 * 2 * Math.asin(Math.sqrt(a));
    }
}
