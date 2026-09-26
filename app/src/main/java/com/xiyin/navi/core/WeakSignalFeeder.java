package com.xiyin.navi.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tencent.map.navi.data.AttachedLocation;
import com.tencent.map.navi.data.GpsLocation;

/**
 * 弱信号兜底喂位器（v4.3 新增）。
 *
 * <p><b>问题：</b>GPS 弱信号时，{@link AppLocation} 的精度过滤（acc&gt;200m 丢弃）
 * 会让 SDK 长时间收不到新位置，状态机"饿死"，卡在路口反复重播当前指令。
 *
 * <p><b>对策：</b>持续记录 SDK 绑路回调里的「绑路位置」（{@link AttachedLocation}
 * 的 attachedLat/Lng，SDK 已把它吸附到路线上）。一旦超过
 * {@link #WEAK_SIGNAL_THRESHOLD_MS} 没有收到新的实测 fix，就改用「绑路位置」喂给
 * SDK，provider 标 {@link GpsLocation#LOW_CONF_PROVIDER}，让状态机知道用户在跟线走，
 * 继续推进、不卡死重播。实测 fix 一恢复，立即切回喂实测位置。
 *
 * <p>本类只做"喂哪个位置"的决策，不生成任何文案、不碰 TTS。
 */
public final class WeakSignalFeeder {

    private static final String TAG = "WeakSignal";

    /** 超过该毫秒数没有新 fix，判定为弱信号，改用绑路位置兜底。 */
    private static final long WEAK_SIGNAL_THRESHOLD_MS = 3000L;

    /** 兜底 tick 周期（毫秒）。 */
    private static final long TICK_MS = 1000L;

    /** 喂位出口：由各导航页提供（内部调 manager.updateLocation）。 */
    public interface LocationUpdater {
        void updateLocation(GpsLocation loc, int status, String reason);
    }

    private final LocationUpdater updater;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 最近一次收到可用实测 fix 的时间 */
    private volatile long lastGoodFixTime;
    /** 最近一次 SDK 绑路位置（弱信号时的兜底源） */
    private volatile AttachedLocation lastAttached;
    private volatile boolean running;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            tick();
            main.postDelayed(this, TICK_MS);
        }
    };

    public WeakSignalFeeder(LocationUpdater updater) {
        this.updater = updater;
    }

    /** 每收到一个可用的实测 fix 就调用（由 onFix 转发），刷新"信号正常"时间戳。 */
    public void onGoodFix() {
        lastGoodFixTime = System.currentTimeMillis();
    }

    /** SDK 绑路回调：记录最新绑路位置（弱信号时的兜底源）。 */
    public void onAttached(AttachedLocation attached) {
        if (attached != null && attached.isValid()) {
            lastAttached = attached;
        }
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        lastGoodFixTime = System.currentTimeMillis();
        main.postDelayed(ticker, TICK_MS);
        Log.i(TAG, "弱信号兜底已启动（阈值 " + WEAK_SIGNAL_THRESHOLD_MS + "ms）");
    }

    public void stop() {
        running = false;
        main.removeCallbacks(ticker);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        // 信号正常（最近有 fix）→ 不需要兜底
        if (now - lastGoodFixTime < WEAK_SIGNAL_THRESHOLD_MS) {
            return;
        }
        AttachedLocation attached = lastAttached;
        if (attached == null) {
            return;  // 还没有绑路位置，无从兜底
        }
        // 弱信号：用绑路位置喂 SDK，provider 标 low_conf
        GpsLocation g = new GpsLocation();
        g.setLatitude(attached.getAttachedLatitude());
        g.setLongitude(attached.getAttachedLongitude());
        g.setAccuracy(attached.getAccuracy());
        g.setDirection(attached.getDirection());
        g.setVelocity(attached.getVelocity());
        g.setAltitude(attached.getAltitude());
        g.setProvider(GpsLocation.LOW_CONF_PROVIDER);
        g.setTime(attached.getTime());
        updater.updateLocation(g, GpsLocation.GPS_STATUS_AVAILABLE, "weak-signal/attached");
        Log.i(TAG, "[弱信号兜底] 喂绑路位置 "
                + attached.getAttachedLatitude() + "," + attached.getAttachedLongitude());
    }
}
