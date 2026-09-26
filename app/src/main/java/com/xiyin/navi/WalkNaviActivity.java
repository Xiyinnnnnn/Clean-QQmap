package com.xiyin.navi;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.TextView;

import com.tencent.map.navi.TencentRouteSearchCallback;
import com.tencent.map.navi.data.NaviPoi;
import com.tencent.map.navi.car.NaviMode;
import com.tencent.map.navi.data.RouteData;
import com.tencent.map.navi.ui.car.CarNaviInfoPanel;
import com.tencent.map.navi.walk.TencentWalkNaviManager;
import com.tencent.map.navi.walk.WalkNaviView;
import com.xiyin.navi.core.AppConst;
import com.xiyin.navi.core.AppLocation;
import com.xiyin.navi.core.LocationFix;
import com.xiyin.navi.core.KeyManager;
import com.xiyin.navi.core.NavUtil;
import com.xiyin.navi.core.OffRouteMonitor;
import com.xiyin.navi.core.NaviListenerAdapter;
import com.xiyin.navi.core.TtsSpeaker;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 步行实时导航（官方链路）。
 * v2：startNavi 幂等；定位启动即开；真实位置注入。
 */
public class WalkNaviActivity extends AppCompatActivity implements AppLocation.Listener {

    private static final String TAG = "MyNaviWalk";

    private TencentWalkNaviManager walkManager;
    private WalkNaviView walkNaviView;
    private TextView tipText;
    private final TtsSpeaker tts = new TtsSpeaker();
    private final NaviListenerAdapter listener = new NaviListenerAdapter(tts);
    private final Handler main = new Handler(Looper.getMainLooper());

    private final AtomicBoolean naviStarted = new AtomicBoolean(false);
    private final AtomicBoolean routeSearched = new AtomicBoolean(false);
    private final AtomicBoolean viewInited = new AtomicBoolean(false);
    /** 偏航监控：外部注入定位时 SDK 不会自动重算，必须自行判定 */
    private OffRouteMonitor offRouteMonitor;

    private NaviPoi start;
    private NaviPoi dest;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_walk_navi);

        tipText = findViewById(R.id.navi_tip);
        walkNaviView = findViewById(R.id.walk_navi_view);

        Intent intent = getIntent();
        start = new NaviPoi(intent.getDoubleExtra(AppConst.EXTRA_START_LAT, 0),
                intent.getDoubleExtra(AppConst.EXTRA_START_LNG, 0));
        dest = new NaviPoi(intent.getDoubleExtra(AppConst.EXTRA_DEST_LAT, 0),
                intent.getDoubleExtra(AppConst.EXTRA_DEST_LNG, 0));
        Log.i(TAG, "进入步行导航 起点=" + start.getLatitude() + "," + start.getLongitude()
                + " 终点=" + dest.getLatitude() + "," + dest.getLongitude());

        tts.init(this);

                // 进程被系统回收后可能直接从本页恢复，此处再注入一次 Key，
        // 保证导航 View 的静态 options 不为空（必须在 View 创建之前）
        KeyManager.applyKey(getApplicationContext(), KeyManager.getEffectiveKey(this));

        // v3.0.1 修复模式混淆：进入本模式前，确保定位适配器单例按本模式重建
        com.xiyin.navi.core.GeoAdapterFix.ensureMode(1);
        walkManager = new TencentWalkNaviManager(getApplicationContext());
        walkManager.setInternalTtsEnabled(false);
        // 官方 View 必须注册为监听器（负责地图绘制/更新），再加自己的语音监听
        walkManager.addTencentNaviListener(walkNaviView);
        walkManager.addTencentNaviListener(listener);

        // 偏航监控：SDK 自动重算依赖其内部定位；本 App 用外部定位，需自行判定并触发
        offRouteMonitor = new OffRouteMonitor(AppConst.OFF_ROUTE_THRESHOLD_WALK_M, () -> {
            try {
                if (walkManager != null) {
                    Log.w(TAG, "调用 onOffRoute() 触发重算");
                    walkManager.onOffRoute();
                }
            } finally {
                if (offRouteMonitor != null) {
                    offRouteMonitor.onRerouteFinished();
                }
            }
        });
        listener.setEvents(new NaviListenerAdapter.Events() {
            @Override public void onStarted() { }
            @Override public void onStopped() { }
            @Override public void onArrived() { }
            @Override public void onAttached(com.tencent.map.navi.data.AttachedLocation attached) {
                if (offRouteMonitor != null) {
                    offRouteMonitor.onAttachedLocation(attached);
                }
            }
            @Override public void onOffRoute() {
                if (offRouteMonitor != null) {
                    offRouteMonitor.onSdkOffRoute();
                }
            }
        });

        AppLocation.get().start(this);
        AppLocation.get().addListener(this);

        searchRoute();
    }

    private void searchRoute() {
        if (!routeSearched.compareAndSet(false, true)) {
            Log.w(TAG, "算路已发起过，忽略重复调用");
            return;
        }
        tipText.setText(R.string.planning);
        try {
            walkManager.searchRoute(start, dest, new TencentRouteSearchCallback() {
                @Override
                public void onRouteSearchFailure(int code, String msg) {
                    Log.e(TAG, "算路失败 code=" + code + " msg=" + msg);
                    tipText.setText(getString(R.string.plan_failed) + code + " " + msg);
                    routeSearched.set(false);
                }

                @Override
                public void onRouteSearchSuccess(ArrayList<RouteData> route) {
                    Log.i(TAG, "算路成功，路线数=" + (route == null ? 0 : route.size()));
                    if (offRouteMonitor != null) {
                        offRouteMonitor.onRerouteFinished();
                    }
                    startRealNavi();
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "searchRoute 异常: " + t);
            tipText.setText(getString(R.string.plan_failed) + t);
            routeSearched.set(false);
        }
    }

    private void startRealNavi() {
        if (!naviStarted.compareAndSet(false, true)) {
            Log.w(TAG, "startNavi 已执行过，跳过重复调用（防重复开始导航）");
            return;
        }
        initNaviView();
        try {
            if (walkManager.isNavigating()) {
                Log.w(TAG, "SDK 已在导航中，跳过 startNavi");
                tipText.setVisibility(TextView.GONE);
                return;
            }
            walkManager.startNavi(0);
            Log.i(TAG, "startNavi 已调用（仅一次）");
            // 导航真正开始后再设一次视角，防止 startNavi 内部重置为默认 2.5D
            applyNaviViewMode();
            if (AppConst.NAVI_OVERHEAD_VIEW) {
                walkNaviView.setNaviFixingProportion2D(0.5f, 0.75f);
            }
            tipText.setVisibility(TextView.GONE);
        } catch (Throwable t) {
            Log.e(TAG, "startNavi 异常: " + t);
            naviStarted.set(false);
            tipText.setText(getString(R.string.plan_failed) + t);
        }
    }

    @Override
    public void onFix(LocationFix fix) {
        if (walkManager == null || fix == null) {
            return;
        }
        walkManager.updateLocation(NavUtil.toGpsLocation(fix), fix.status, fix.reason);
    }

    /** 视角：需求为「完全俯视」，即 2D 地图朝北（SDK 默认是 2.5D 车头朝上） */
    private void applyNaviViewMode() {
        try {
            if (walkNaviView == null) {
                return;
            }
            NaviMode mode = AppConst.NAVI_OVERHEAD_VIEW
                    ? NaviMode.MODE_2DMAP_TOWARDS_NORTH
                    : NaviMode.MODE_3DCAR_TOWARDS_UP;
            walkNaviView.setNaviMode(mode);
            Log.i(TAG, "导航视角设置为: " + mode);
        } catch (Throwable t) {
            Log.w(TAG, "设置导航视角失败: " + t);
        }
    }

    private void initNaviView() {
        if (!viewInited.compareAndSet(false, true)) {
            return;
        }
        main.post(() -> {
            if (walkNaviView == null) {
                return;
            }
            walkNaviView.setNaviPanelEnabled(true);
            CarNaviInfoPanel panel = walkNaviView.showNaviInfoPanel();
            panel.setOnNaviInfoListener(() -> finish());
            applyNaviViewMode();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (walkNaviView != null) {
            walkNaviView.onStart();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (walkNaviView != null) {
            walkNaviView.onRestart();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLocation.get().addListener(this);
        if (walkNaviView != null) {
            walkNaviView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (walkNaviView != null) {
            walkNaviView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (walkNaviView != null) {
            walkNaviView.onStop();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        try {
            if (walkManager != null) {
                walkManager.stopNavi();
                walkManager.removeTencentNaviListener(listener);
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopNavi 失败: " + t);
        }
        tts.stop();
        tts.release();
        AppLocation.get().removeListener(this);
        AppLocation.get().stop(this);
        if (walkNaviView != null) {
            walkNaviView.onDestroy();
        }
        super.onDestroy();
    }
}
