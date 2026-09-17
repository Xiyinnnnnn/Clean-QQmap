package com.xiyin.navi;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.tencent.map.navi.TencentNaviCallback;
import com.tencent.map.navi.TencentRouteSearchCallback;
import com.tencent.map.navi.car.CarNaviView;
import com.tencent.map.navi.car.CarRouteSearchOptions;
import com.tencent.map.navi.car.NaviMode;
import com.tencent.map.navi.car.TencentCarNaviManager;
import com.tencent.map.navi.data.AttachedLocation;
import com.tencent.map.navi.data.NaviPoi;
import com.tencent.map.navi.data.NaviTts;
import com.tencent.map.navi.data.ParallelRoadStatus;
import com.tencent.map.navi.data.RouteData;
import com.tencent.map.navi.ui.car.CarNaviInfoPanel;
import com.tencent.tencentmap.mapsdk.maps.model.LatLng;
import com.xiyin.navi.core.AppConst;
import com.xiyin.navi.core.AppLocation;
import com.xiyin.navi.core.LocationFix;
import com.xiyin.navi.core.KeyManager;
import com.xiyin.navi.core.NavUtil;
import com.xiyin.navi.core.OffRouteMonitor;
import com.xiyin.navi.core.TtsSpeaker;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 驾车实时导航（官方链路）。
 *
 * <p>v2 修复：用原子标志保证 {@code startNavi()} 只执行一次，
 * 避免任何回调重入导致「一直重复开始导航」。
 */
public class CarNaviActivity extends AppCompatActivity implements AppLocation.Listener {

    private static final String TAG = "MyNaviCar";

    private TencentCarNaviManager naviManager;
    private CarNaviView carNaviView;
    private TextView tipText;
    private final TtsSpeaker tts = new TtsSpeaker();

    /** 幂等保护：确保 startNavi 只调用一次 */
    private final AtomicBoolean naviStarted = new AtomicBoolean(false);
    /** 幂等保护：确保算路只发起一次 */
    private final AtomicBoolean routeSearched = new AtomicBoolean(false);
    /** 偏航监控：SDK 的自动重算依赖其内部定位，我们用外部定位，必须自行判定 */
    private OffRouteMonitor offRouteMonitor;

    private NaviPoi start;
    private NaviPoi dest;
    private String destName;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_navi);

        tipText = findViewById(R.id.navi_tip);
        carNaviView = findViewById(R.id.car_navi_view);

        Intent intent = getIntent();
        start = new NaviPoi(intent.getDoubleExtra(AppConst.EXTRA_START_LAT, 0),
                intent.getDoubleExtra(AppConst.EXTRA_START_LNG, 0));
        dest = new NaviPoi(intent.getDoubleExtra(AppConst.EXTRA_DEST_LAT, 0),
                intent.getDoubleExtra(AppConst.EXTRA_DEST_LNG, 0));
        destName = intent.getStringExtra(AppConst.EXTRA_DEST_NAME);
        Log.i(TAG, "进入驾车导航 起点=" + start.getLatitude() + "," + start.getLongitude()
                + " 终点=" + dest.getLatitude() + "," + dest.getLongitude());

        tts.init(this);

        offRouteMonitor = new OffRouteMonitor(AppConst.OFF_ROUTE_THRESHOLD_CAR_M,
                () -> {
                    try {
                        if (naviManager != null) {
                            Log.w(TAG, "调用 onOffRoute() 触发重算");
                            naviManager.onOffRoute();
                        }
                    } finally {
                        if (offRouteMonitor != null) {
                            offRouteMonitor.onRerouteFinished();
                        }
                    }
                });

                // 进程被系统回收后可能直接从本页恢复，此处再注入一次 Key，
        // 保证导航 View 的静态 options 不为空（必须在 View 创建之前）
        KeyManager.applyKey(getApplicationContext(), KeyManager.getEffectiveKey(this));

        // v3.0.1 修复模式混淆：进入本模式前，确保定位适配器单例按本模式重建
        com.xiyin.navi.core.GeoAdapterFix.ensureMode(0);
naviManager = new TencentCarNaviManager(this);
        naviManager.addNaviView(carNaviView);
        naviManager.setInternalTtsEnabled(false);   // 语音交给本机 TTS，避免双播
        naviManager.setNaviCallback(naviCallback);

        CarNaviInfoPanel panel = carNaviView.showNaviInfoPanel();
        panel.setOnNaviInfoListener(this::finish);

        applyNaviViewMode();

        // 先进定位，保证算路完成后立刻有真实位置可用
        AppLocation.get().start(this);
        AppLocation.get().addListener(this);

        searchRoute();
    }

    /** 视角：需求为「完全俯视」，即 2D 地图朝北（SDK 默认是 2.5D 车头朝上） */
    private void applyNaviViewMode() {
        try {
            if (carNaviView == null) {
                return;
            }
            NaviMode mode = AppConst.NAVI_OVERHEAD_VIEW
                    ? NaviMode.MODE_2DMAP_TOWARDS_NORTH
                    : NaviMode.MODE_3DCAR_TOWARDS_UP;
            carNaviView.setNaviMode(mode);
            Log.i(TAG, "导航视角设置为: " + mode);
        } catch (Throwable t) {
            Log.w(TAG, "设置导航视角失败: " + t);
        }
    }

    private void searchRoute() {
        if (!routeSearched.compareAndSet(false, true)) {
            Log.w(TAG, "算路已发起过，忽略重复调用");
            return;
        }
        tipText.setText(R.string.planning);
        CarRouteSearchOptions options = CarRouteSearchOptions.create();
        try {
            naviManager.searchRoute(start, dest, null, options, new TencentRouteSearchCallback() {
                @Override
                public void onRouteSearchFailure(int code, String msg) {
                    Log.e(TAG, "算路失败 code=" + code + " msg=" + msg);
                    tipText.setText(getString(R.string.plan_failed) + code + " " + msg);
                    routeSearched.set(false);   // 允许重试
                }

                @Override
                public void onRouteSearchSuccess(ArrayList<RouteData> route) {
                    Log.i(TAG, "算路成功，路线数=" + (route == null ? 0 : route.size()));
                    startRealNavi();
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "searchRoute 异常: " + t);
            tipText.setText(getString(R.string.plan_failed) + t);
            routeSearched.set(false);
        }
    }

    /** 算路成功后进入官方实时导航；严格只执行一次 */
    private void startRealNavi() {
        if (!naviStarted.compareAndSet(false, true)) {
            Log.w(TAG, "startNavi 已执行过，跳过重复调用（防重复开始导航）");
            return;
        }
        try {
            // 若 SDK 已在导航中则直接返回，避免二次 start
            if (naviManager.isNavigating()) {
                Log.w(TAG, "SDK 已在导航中，跳过 startNavi");
                tipText.setVisibility(TextView.GONE);
                return;
            }
            naviManager.startNavi(0);
            Log.i(TAG, "startNavi 已调用（仅一次）");
            // 导航真正开始后再设一次视角，防止 startNavi 内部把模式重置回默认 2.5D
            applyNaviViewMode();
            if (AppConst.NAVI_OVERHEAD_VIEW) {
                // 俯视模式下车标下移，留出更多前方视野
                carNaviView.setNaviFixingProportion2D(0.5f, 0.75f);
            }
            tipText.setVisibility(TextView.GONE);
        } catch (Throwable t) {
            Log.e(TAG, "startNavi 异常: " + t);
            naviStarted.set(false);
            tipText.setText(getString(R.string.plan_failed) + t);
        }
    }

    /** 真实定位 → 注入官方导航引擎（500ms 粒度，与 SDK 内部定位同源，不冲突） */
    @Override
    public void onFix(LocationFix fix) {
        if (naviManager == null || fix == null) {
            return;
        }
        naviManager.updateLocation(NavUtil.toGpsLocation(fix), fix.status, fix.reason);
    }

    private final TencentNaviCallback naviCallback = new TencentNaviCallback() {
        @Override
        public void onStartNavi() {
            Log.i(TAG, "SDK 回调 onStartNavi");
        }

        @Override
        public void onStopNavi() {
            Log.i(TAG, "SDK 回调 onStopNavi");
        }

        @Override
        public void onOffRoute() {
            Log.w(TAG, "SDK 回调 onOffRoute");
            if (offRouteMonitor != null) {
                offRouteMonitor.onSdkOffRoute();
            }
        }

        @Override
        public void onRecalculateRouteSuccess(int i, ArrayList<RouteData> arrayList) {
            Log.i(TAG, "重新算路成功");
            if (offRouteMonitor != null) {
                offRouteMonitor.onRerouteFinished();
            }
        }

        @Override
        public void onRecalculateRouteSuccessInFence(int i) {
        }

        @Override
        public void onRecalculateRouteFailure(int i, int i1, String s) {
            Log.w(TAG, "重新算路失败 " + i + "/" + i1 + " " + s);
            if (offRouteMonitor != null) {
                offRouteMonitor.onRerouteFinished();
            }
        }

        @Override
        public void onRecalculateRouteStarted(int i) {
            Log.i(TAG, "开始重新算路 " + i);
        }

        @Override
        public void onRecalculateRouteCanceled() {
        }

        @Override
        public void onArrivedDestination() {
            Log.i(TAG, "到达目的地");
        }

        @Override
        public void onPassedWayPoint(int i) {
        }

        @Override
        public void onUpdateRoadType(int i) {
        }

        @Override
        public void onUpdateParallelRoadStatus(ParallelRoadStatus parallelRoadStatus) {
        }

        @Override
        public void onUpdateAttachedLocation(AttachedLocation attachedLocation) {
            // 用「实际位置 vs 绑路位置」的距离判定偏航，超阈值主动触发重算
            if (offRouteMonitor != null) {
                offRouteMonitor.onAttachedLocation(attachedLocation);
            }
        }

        @Override
        public void onFollowRouteClick(String s, ArrayList<LatLng> arrayList) {
        }

        /** 官方导航语音 → 本机 TTS（TtsSpeaker 内部已做同文本去重） */
        @Override
        public int onVoiceBroadcast(NaviTts naviTts) {
            tts.speak(naviTts);
            return 0;
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        if (carNaviView != null) {
            carNaviView.onStart();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLocation.get().addListener(this);
        if (carNaviView != null) {
            carNaviView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (carNaviView != null) {
            carNaviView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (carNaviView != null) {
            carNaviView.onStop();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        try {
            if (naviManager != null && naviManager.isNavigating()) {
                naviManager.stopNavi();
                Log.i(TAG, "退出：stopNavi");
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopNavi 失败: " + t);
        }
        tts.stop();
        tts.release();
        AppLocation.get().removeListener(this);
        AppLocation.get().stop(this);
        if (carNaviView != null) {
            carNaviView.onDestroy();
        }
        super.onDestroy();
    }
}
