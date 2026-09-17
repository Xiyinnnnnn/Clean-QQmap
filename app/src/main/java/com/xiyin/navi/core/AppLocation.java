package com.xiyin.navi.core;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 定位中枢 v3：**只用 Android 原生 GPS**，先避开腾讯融合定位。
 *
 * <p>为什么这么做：融合定位（含基站/WiFi 反推）在信号差时会给较大偏差的结果，
 * 表现为"位置乱飘/不准"。v3 直接取系统 GPS 卫星定位，并做精度过滤。
 *
 * <p>采样：{@link AppConst#LOCATION_INTERVAL_MS}（5 秒一次）。
 * 网络定位默认<b>关闭</b>（{@link #USE_NETWORK_PROVIDER}），需要时改 true 即可启用兜底。
 * 全部为真实定位，无任何模拟来源。
 */
public final class AppLocation {

    private static final String TAG = "AppLocation";

    /**
     * 是否启用系统网络定位（NETWORK_PROVIDER）作为兜底。
     * 默认 false：网络定位精度差，会拉偏车标；只有 GPS 长期无结果时才考虑开。
     */
    public static final boolean USE_NETWORK_PROVIDER = false;

    /** 可接受的 GPS 精度上限（米）。差于此值的定位直接丢弃，避免显示乱飘的点。 */
    public static final float MAX_ACCEPT_ACCURACY_M = 200f;

    public interface Listener {
        void onFix(LocationFix fix);
    }

    /** 定位可用性状态（供 UI 给出明确提示） */
    public interface StatusListener {
        void onStatus(String message);
    }

    private static final AppLocation INSTANCE = new AppLocation();

    private final List<Listener> listeners = new ArrayList<>();
    private final List<StatusListener> statusListeners = new ArrayList<>();

    private Context appContext;
    private LocationManager locationManager;
    private boolean started;

    /**
     * 定位持有者（以调用方类名为标识，如 MainActivity / CarNaviActivity）。
     *
     * <p>为什么要引用计数：多个页面共享同一个定位单例。
     * 页面切换时（如「主界面 onResume」先于「导航页 onDestroy」执行），
     * 若导航页销毁就无条件 stop，会把主界面刚启动的定位一起关掉，
     * 表现为"退出导航后定位失效"。只有最后一个持有者释放时才真正停止。
     */
    private final java.util.Set<String> owners = new java.util.HashSet<>();
    private boolean gpsStatusRegistered;
    private boolean fallbackPosted;

    private volatile LocationFix lastFix;
    private volatile long lastFixTime;
    private volatile int fixCount;
    private volatile int rejectedCount;
    private volatile int satellites;
    private volatile boolean gpsProviderEnabled;

    private final Handler main = new Handler(Looper.getMainLooper());

    private AppLocation() {}

    public static AppLocation get() {
        return INSTANCE;
    }

    // ==================== 启动 / 停止 ====================

    /** 启动原生 GPS 定位（按持有者幂等）。进入 App/导航页应立即调用。 */
    public synchronized void start(Context context) {
        if (context == null) {
            Log.w(TAG, "start() 缺 context");
            return;
        }
        appContext = context.getApplicationContext();
        final String owner = context.getClass().getName();

        if (owners.contains(owner)) {
            Log.i(TAG, "持有者 " + owner + " 已启动过，忽略重复调用");
            return;
        }
        owners.add(owner);

        if (started) {
            Log.i(TAG, "定位已在运行，仅新增持有者 " + owner + "（当前 " + owners + "）");
            return;
        }
        started = true;
        Log.i(TAG, "==== 启动原生定位（GPS 优先，间隔 " + AppConst.LOCATION_INTERVAL_MS + "ms）====");

        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            notifyStatus("未授予定位权限，无法定位");
            Log.e(TAG, "缺少 ACCESS_FINE_LOCATION 权限，定位无法启动");
            started = false;
            owners.remove(owner);
            return;
        }

        locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            notifyStatus("系统定位服务不可用");
            Log.e(TAG, "LocationManager 不可用");
            started = false;
            owners.remove(owner);
            return;
        }

        gpsProviderEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        Log.i(TAG, "GPS_PROVIDER 可用=" + gpsProviderEnabled);
        if (!gpsProviderEnabled) {
            notifyStatus("系统 GPS 开关未打开，请到「设置-位置信息」中开启");
        }

        // 主定位源：系统 GPS
        requestProvider(LocationManager.GPS_PROVIDER);

        // 可选兜底：系统网络定位（默认关闭，因为精度差）
        if (USE_NETWORK_PROVIDER) {
            requestProvider(LocationManager.NETWORK_PROVIDER);
        }

        registerGpsStatus();

        // 首帧兜底：1.5s 内无结果时先用系统 lastKnown 让界面有内容
        if (!fallbackPosted) {
            fallbackPosted = true;
            main.postDelayed(() -> {
                if (lastFix == null) {
                    Location fb = getBestLastKnown();
                    if (fb != null) {
                        Log.i(TAG, "首帧兜底 lastKnown: " + fb.getProvider()
                                + " acc=" + fb.getAccuracy());
                        publish(toFix(fb, "lastKnown/" + fb.getProvider()));
                        notifyStatus("使用上次已知位置，正在等待 GPS 信号…");
                    } else {
                        Log.w(TAG, "无任何定位，且无 lastKnown");
                        notifyStatus("等待 GPS 信号…（请到室外或窗边，首次定位可能需要 30 秒）");
                    }
                }
            }, 1500);
        }
    }

    /** 释放某个持有者的定位占用；仅当没有其他持有者时才真正停止定位。 */
    public synchronized void stop(Context context) {
        if (context != null) {
            final String owner = context.getClass().getName();
            if (owners.remove(owner)) {
                Log.i(TAG, "持有者 " + owner + " 释放定位（剩余 " + owners + "）");
            }
        }
        if (!owners.isEmpty()) {
            Log.i(TAG, "仍有持有者 " + owners + "，保持定位运行");
            return;
        }
        try {
            if (locationManager != null) {
                locationManager.removeUpdates(locationListener);
                if (gpsStatusRegistered) {
                    locationManager.removeGpsStatusListener(gpsStatusListener);
                    gpsStatusRegistered = false;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "停止定位失败: " + t);
        }
        started = false;
        fallbackPosted = false;
        Log.i(TAG, "==== 定位已停止 ====");
    }

    // ==================== 定位源 ====================

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null) {
                return;
            }
            final float acc = location.getAccuracy();
            final String provider = location.getProvider();

            // 精度过滤：避免把乱飘的点画到地图上 / 喂给导航
            if (acc > MAX_ACCEPT_ACCURACY_M) {
                rejectedCount++;
                Log.w(TAG, "[丢弃] " + provider + " acc=" + acc + "m 超过 "
                        + MAX_ACCEPT_ACCURACY_M + "m（累计丢弃 " + rejectedCount + "）");
                return;
            }

            LocationFix fix = toFix(location, provider);
            Log.i(TAG, "[定位] " + fix + " 卫星=" + satellites);
            publish(fix);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.i(TAG, "provider 开启: " + provider);
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                gpsProviderEnabled = true;
                notifyStatus("GPS 已开启，正在定位…");
            }
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.w(TAG, "provider 关闭: " + provider);
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                gpsProviderEnabled = false;
                notifyStatus("系统 GPS 已关闭，请重新打开");
            }
        }
    };

    private void requestProvider(String provider) {
        try {
            if (!locationManager.isProviderEnabled(provider)) {
                Log.w(TAG, provider + " 未开启，跳过");
                return;
            }
            // 5 秒一次、位移超过 0 米即回调（由系统按最小间隔节流）
            locationManager.requestLocationUpdates(provider,
                    AppConst.LOCATION_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper());
            Log.i(TAG, "已注册 " + provider + " @" + AppConst.LOCATION_INTERVAL_MS + "ms");
        } catch (Throwable t) {
            Log.w(TAG, "注册失败 " + provider + ": " + t);
        }
    }

    /** 读取卫星数，用于确认 GPS 是否真的在工作。 */
    @SuppressWarnings("deprecation")
    private final GpsStatus.Listener gpsStatusListener = event -> {
        try {
            if (locationManager == null) {
                return;
            }
            GpsStatus status = locationManager.getGpsStatus(null);
            if (status == null) {
                return;
            }
            int used = 0;
            for (GpsSatellite s : status.getSatellites()) {
                if (s.usedInFix()) {
                    used++;
                }
            }
            satellites = used;
        } catch (Throwable ignore) {
        }
    };

    @SuppressWarnings("deprecation")
    private void registerGpsStatus() {
        try {
            if (locationManager != null && !gpsStatusRegistered) {
                locationManager.addGpsStatusListener(gpsStatusListener);
                gpsStatusRegistered = true;
                Log.i(TAG, "GPS 卫星状态监听已注册");
            }
        } catch (Throwable t) {
            Log.w(TAG, "注册卫星状态失败: " + t);
        }
    }

    private Location getBestLastKnown() {
        try {
            if (locationManager == null) {
                return null;
            }
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!USE_NETWORK_PROVIDER && LocationManager.NETWORK_PROVIDER.equals(p)) {
                    continue;
                }
                try {
                    Location l = locationManager.getLastKnownLocation(p);
                    if (l == null || System.currentTimeMillis() - l.getTime() > 30 * 60 * 1000L) {
                        continue;
                    }
                    if (best == null || l.getAccuracy() < best.getAccuracy()) {
                        best = l;
                    }
                } catch (Throwable ignore) {
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把系统原生 Location 转成统一结果。
     *
     * <p><b>必须走 {@link LocationFix#fromWgs84}</b>：Android 原生 GPS 给的是 WGS-84，
     * 而腾讯地图/导航用的是 GCJ-02，不转换会偏 300~600 米
     * （这正是"首页定位点错误、导航页却准"的原因——导航页靠绑路掩盖了偏移）。
     */
    private LocationFix toFix(Location l, String provider) {
        return LocationFix.fromWgs84(l.getLatitude(), l.getLongitude(), l.getAltitude(),
                l.getAccuracy(),
                l.hasBearing() ? l.getBearing() : 0f,
                l.hasSpeed() ? l.getSpeed() : 0f,
                l.getTime(), provider, LocationFix.STATUS_OK, "native-gps");
    }

    // ==================== 分发 ====================

    private void publish(LocationFix fix) {
        if (fix == null || !fix.isValid()) {
            return;
        }
        lastFix = fix;
        lastFixTime = System.currentTimeMillis();
        fixCount++;
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onFix(fix);
            } catch (Throwable t) {
                Log.w(TAG, "listener 异常: " + t);
            }
        }
    }

    private void notifyStatus(String message) {
        Log.i(TAG, "[状态] " + message);
        for (StatusListener l : new ArrayList<>(statusListeners)) {
            try {
                l.onStatus(message);
            } catch (Throwable t) {
                Log.w(TAG, "status listener 异常: " + t);
            }
        }
    }

    // ==================== 读取 ====================

    public boolean isStarted() {
        return started;
    }

    public LocationFix getLastFix() {
        return lastFix;
    }

    public boolean hasFix() {
        return lastFix != null;
    }

    /** 最近一次定位距今毫秒；无定位返回 -1。 */
    public long getAgeMs() {
        return lastFixTime == 0 ? -1 : System.currentTimeMillis() - lastFixTime;
    }

    public int getFixCount() {
        return fixCount;
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    /** 参与定位的卫星数（0 表示还没锁定或未注册监听）。 */
    public int getSatellites() {
        return satellites;
    }

    public boolean isGpsProviderEnabled() {
        return gpsProviderEnabled;
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

    public void addStatusListener(StatusListener listener) {
        if (listener != null && !statusListeners.contains(listener)) {
            statusListeners.add(listener);
        }
    }

    public void removeStatusListener(StatusListener listener) {
        if (listener != null) {
            statusListeners.remove(listener);
        }
    }
}
