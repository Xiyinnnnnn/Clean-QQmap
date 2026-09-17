package com.xiyin.navi;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory;
import com.tencent.tencentmap.mapsdk.maps.SupportMapFragment;
import com.tencent.tencentmap.mapsdk.maps.TencentMap;
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptor;
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory;
import com.tencent.tencentmap.mapsdk.maps.model.LatLng;
import com.tencent.tencentmap.mapsdk.maps.model.Marker;
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions;
import com.xiyin.navi.core.AppConst;
import com.xiyin.navi.core.AppLocation;
import com.xiyin.navi.core.LocationFix;
import com.xiyin.navi.core.OrientationSensor;
import com.xiyin.navi.core.KeyManager;
import com.xiyin.navi.core.PlaceSearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主界面 v2.1
 *
 * <p>流程：打开应用 → 自动 GPS 定位并居中（起点=当前位置，箭头图标带朝向）
 * → 顶部搜索框输入 + 回车/点搜索按钮 → 点结果选为终点 → 选导航方式 → 实时导航。
 */
public class MainActivity extends AppCompatActivity
        implements AppLocation.Listener, AppLocation.StatusListener, OrientationSensor.Listener {

    private static final String TAG = "MyNaviMain";
    private static final int REQ_PERM = 100;

    private TencentMap tencentMap;
    private EditText searchInput;
    private View resultScroll;
    private LinearLayout resultContainer;
    private TextView txtStart;
    private TextView txtDest;
    private TextView txtStatus;

    private Marker myMarker;
    private Marker destMarker;

    /** 设备朝向传感器：让箭头随身体方位旋转（GPS 的 bearing 静止时为 0，不能用于此） */
    private final OrientationSensor orientationSensor = new OrientationSensor();
    /** 传感器给出的方位角；-1 表示尚不可用 */
    private volatile float sensorAzimuth = -1f;
    /** 最近一次 GPS 速度（m/s），用于判断该用 GPS 航向还是设备朝向 */
    private volatile float lastSpeed;

    /** 起点恒为当前位置（真实 GPS） */
    private volatile LocationFix startFix;
    private PlaceSearch.Place destPlace;

    private final PlaceSearch placeSearch = new PlaceSearch();
    private final List<PlaceSearch.Place> results = new ArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean firstCenterDone;
    private boolean searching;

    /** 是否刚去导航：回来时清掉终点并刷新回当前定位 */
    private volatile boolean wentToNavi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Key 守卫：无有效 Key 时先去配置页（开源场景使用者需自填）
        if (KeyManager.getEffectiveKey(this).isEmpty()) {
            // 无 Key：进入配置页（自动启动场景，不加 force 标记）
            startActivity(new Intent(this, KeySetupActivity.class));
            finish();
            return;
        }
        // 进程重启/直接进入本页时，重新注入 Key（必须在任何 NaviView 创建之前）
        KeyManager.applyKey(getApplicationContext(), KeyManager.getEffectiveKey(this));
        setContentView(R.layout.activity_main);

        searchInput = findViewById(R.id.search_input);
        resultScroll = findViewById(R.id.result_scroll);
        resultContainer = findViewById(R.id.result_container);
        txtStart = findViewById(R.id.txt_start);
        txtDest = findViewById(R.id.txt_dest);
        txtStatus = findViewById(R.id.txt_status);

        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map_frag);
        if (mapFragment != null) {
            tencentMap = mapFragment.getMap();
        }
        if (tencentMap != null) {
            tencentMap.getUiSettings().setCompassEnabled(true);
            tencentMap.getUiSettings().setMyLocationButtonEnabled(false);
            tencentMap.getUiSettings().setZoomControlsEnabled(false);
        }

        // 回车（IME 搜索键）
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_NULL) {
                doSearch();
                return true;
            }
            return false;
        });
        // 兜底：实体回车键
        searchInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                doSearch();
                return true;
            }
            return false;
        });
        // 搜索按钮
        findViewById(R.id.btn_search).setOnClickListener(v -> doSearch());

        findViewById(R.id.btn_navi).setOnClickListener(v -> chooseNaviMode());

        // 长按底部状态行 → 更换 Key（复用鉴权页）
        txtStatus.setOnLongClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.key_change)
                    .setMessage(getString(R.string.key_current_prefix)
                            + maskKey(KeyManager.getEffectiveKey(this)))
                    .setPositiveButton(R.string.key_change, (d, w) -> {
                        Intent i = new Intent(this, KeySetupActivity.class);
                        i.putExtra(KeySetupActivity.EXTRA_REASON, getString(R.string.key_change));
                        // 用户主动更换：必须带 force，否则会被"已有 Key 就跳过"逻辑弹回
                        i.putExtra(KeySetupActivity.EXTRA_FORCE_SETUP, true);
                        startActivity(i);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });

        requestPermissionsIfNeeded();

        txtStart.setText(R.string.start_default);
        txtDest.setText(R.string.dest_default);
        updateStatus();
    }

    // ==================== 搜索 ====================

    private void doSearch() {
        final String keyword = searchInput.getText() == null
                ? "" : searchInput.getText().toString().trim();
        Log.i(TAG, "触发搜索: [" + keyword + "]");
        if (keyword.length() == 0) {
            Toast.makeText(this, R.string.need_keyword, Toast.LENGTH_SHORT).show();
            return;
        }
        if (searching) {
            Log.w(TAG, "上一次搜索未结束，忽略");
            return;
        }
        searching = true;
        hideKeyboard();
        txtStatus.setText(R.string.searching);
        Toast.makeText(this, "搜索中…", Toast.LENGTH_SHORT).show();

        placeSearch.search(this, keyword, new PlaceSearch.Callback() {
            @Override
            public void onResult(List<PlaceSearch.Place> places) {
                searching = false;
                Log.i(TAG, "搜索返回 " + places.size() + " 条");
                showResults(places);
            }

            @Override
            public void onError(String message) {
                searching = false;
                Log.e(TAG, "搜索失败: " + message);
                resultScroll.setVisibility(View.GONE);
                txtStatus.setText(getString(R.string.search_failed) + message);
                Toast.makeText(MainActivity.this,
                        getString(R.string.search_failed) + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 把结果填充进容器（固定高度 ScrollView，避免 ListView 的测量坑） */
    private void showResults(List<PlaceSearch.Place> places) {
        results.clear();
        results.addAll(places);
        resultContainer.removeAllViews();
        if (results.isEmpty()) {
            resultScroll.setVisibility(View.GONE);
            txtStatus.setText(R.string.no_result);
            Toast.makeText(this, R.string.no_result, Toast.LENGTH_SHORT).show();
            return;
        }
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < results.size(); i++) {
            final int index = i;
            PlaceSearch.Place p = results.get(i);
            View row = inflater.inflate(R.layout.search_item, resultContainer, false);
            ((TextView) row.findViewById(R.id.item_title))
                    .setText(p.title == null ? "" : p.title);
            ((TextView) row.findViewById(R.id.item_address))
                    .setText(p.address == null ? "" : p.address);
            row.setOnClickListener(v -> onPlaceSelected(index));
            resultContainer.addView(row);
        }
        resultScroll.setVisibility(View.VISIBLE);
        txtStatus.setText("找到 " + results.size() + " 个地点，点击选择为终点");
    }

    // ==================== 选终点 ====================

    private void onPlaceSelected(int index) {
        if (index < 0 || index >= results.size()) {
            return;
        }
        PlaceSearch.Place p = results.get(index);
        destPlace = p;
        Log.i(TAG, "选中终点: " + p.title + " " + p.lat + "," + p.lng);

        if (tencentMap == null) {
            Toast.makeText(this, "地图未就绪，请稍后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        LatLng ll = new LatLng(p.lat, p.lng);
        if (destMarker != null) {
            destMarker.setPosition(ll);
        } else {
            BitmapDescriptor icon = null;
            try {
                icon = BitmapDescriptorFactory.fromAsset("navi_marker_end.png");
            } catch (Throwable t) {
                Log.w(TAG, "终点图标加载失败: " + t);
            }
            MarkerOptions options = new MarkerOptions(ll).anchor(0.5f, 1f);
            if (icon != null) {
                options.icon(icon);
            }
            destMarker = tencentMap.addMarker(options);
        }

        resultScroll.setVisibility(View.GONE);
        hideKeyboard();
        txtDest.setText("终点：" + p.title);
        txtStatus.setText("已选终点，正在选择导航方式…");
        tencentMap.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 15f));

        // 流程：点击搜索结果 → 直接弹出选择导航方式
        main.postDelayed(this::chooseNaviMode, 300);
    }

    // ==================== 定位 ====================

    @Override
    public void onFix(LocationFix fix) {
        if (fix == null || tencentMap == null) {
            return;
        }
        startFix = fix;
        LatLng ll = new LatLng(fix.latitude, fix.longitude);

        // 箭头图标（带朝向）
        if (myMarker == null) {
            BitmapDescriptor icon = null;
            try {
                icon = BitmapDescriptorFactory.fromAsset("my_arrow.png");
            } catch (Throwable t) {
                Log.w(TAG, "箭头图标加载失败: " + t);
            }
            // anchor 必须为几何中心，否则旋转时会绕偏移点转而不是绕箭头本身转
            MarkerOptions options = new MarkerOptions(ll).anchor(0.5f, 0.5f);
            if (icon != null) {
                options.icon(icon);
            }
            myMarker = tencentMap.addMarker(options);
        } else {
            myMarker.setPosition(ll);
        }
        lastSpeed = fix.speed;
        myMarker.setRotation(resolveBearing(fix));

        if (!firstCenterDone) {
            firstCenterDone = true;
            tencentMap.moveCamera(CameraUpdateFactory.newLatLngZoom(ll, 17f));
            Log.i(TAG, "首次定位居中: " + fix);
        }

        txtStart.setText(String.format(Locale.CHINA,
                "起点（当前位置）：%.5f, %.5f  ±%.0fm  卫星%d  %s",
                fix.latitude, fix.longitude, fix.accuracy,
                AppLocation.get().getSatellites(), fix.provider));
        Log.i(TAG, "定位已上图（GCJ02）: " + fix);
        updateStatus();
    }

    /**
     * 朝向解析（混合策略）：
     * <ul>
     *   <li>在移动（速度 > 1.5m/s）：用 GPS 航向，方向更贴合实际行进</li>
     *   <li>静止或传感器可用：用设备朝向，身体转身箭头就跟着转</li>
     * </ul>
     * 这样站着不动也能正确指示朝向，正是之前"箭头不转"的根因。
     */
    private float resolveBearing(LocationFix fix) {
        boolean moving = fix.speed > 1.5f;
        // 移动中且 GPS 确实给出了有效航向（非 0）时，用 GPS 航向更贴合行进方向
        if (moving && fix.bearing > 0.5f) {
            return fix.bearing;
        }
        // 静止、或 GPS 未给出航向（系统此时恒返回 0）→ 用设备朝向
        if (sensorAzimuth >= 0f) {
            return sensorAzimuth;
        }
        return fix.bearing;
    }

    /** 设备朝向变化 → 立即旋转箭头（不必等 GPS 回调，转动手腕就能看到箭头跟转） */
    @Override
    public void onOrientation(float azimuthDegrees) {
        sensorAzimuth = azimuthDegrees;
        if (myMarker != null) {
            boolean moving = lastSpeed > 1.5f;
            if (!moving) {
                myMarker.setRotation(azimuthDegrees);
            }
        }
    }

    @Override
    public void onStatus(String message) {
        Log.i(TAG, "定位状态: " + message);
        if (txtStatus != null) {
            txtStatus.setText(message);
        }
    }

    private void updateStatus() {
        AppLocation loc = AppLocation.get();
        long age = loc.getAgeMs();
        String ageText = age < 0 ? "未定位" : (age / 1000) + "s前";
        String bearingSrc = orientationSensor.isAvailable()
                ? (lastSpeed > 1.5f ? "GPS航向" : "设备朝向") : "GPS航向(无传感器)";
        txtStatus.setText(String.format(Locale.CHINA,
                "定位：%s ｜ 有效 %d 次 ｜ 丢弃 %d ｜ 卫星 %d ｜ 间隔 %ds ｜ 朝向 %s",
                ageText, loc.getFixCount(), loc.getRejectedCount(),
                loc.getSatellites(), AppConst.LOCATION_INTERVAL_MS / 1000, bearingSrc));
    }

    // ==================== 选择导航方式 ====================

    private void chooseNaviMode() {
        if (destPlace == null) {
            Toast.makeText(this, R.string.need_dest, Toast.LENGTH_SHORT).show();
            return;
        }
        if (startFix == null || !startFix.isValid()) {
            Toast.makeText(this, R.string.need_start, Toast.LENGTH_LONG).show();
            txtStatus.setText(R.string.gps_check);
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.choose_navi)
                .setItems(new CharSequence[]{
                        getString(R.string.navi_car),
                        getString(R.string.navi_ride),
                        getString(R.string.navi_walk)
                }, (dialog, which) -> {
                    int mode = which == 0 ? AppConst.MODE_CAR
                            : (which == 1 ? AppConst.MODE_RIDE : AppConst.MODE_WALK);
                    startNavi(mode);
                })
                .show();
    }

    private void startNavi(int mode) {
        Class<?> target = mode == AppConst.MODE_CAR ? CarNaviActivity.class
                : (mode == AppConst.MODE_RIDE ? RideNaviActivity.class : WalkNaviActivity.class);
        Intent intent = new Intent(this, target);
        intent.putExtra(AppConst.EXTRA_MODE, mode);
        intent.putExtra(AppConst.EXTRA_START_LAT, startFix.latitude);
        intent.putExtra(AppConst.EXTRA_START_LNG, startFix.longitude);
        intent.putExtra(AppConst.EXTRA_DEST_LAT, destPlace.lat);
        intent.putExtra(AppConst.EXTRA_DEST_LNG, destPlace.lng);
        intent.putExtra(AppConst.EXTRA_DEST_NAME,
                destPlace.title == null ? "" : destPlace.title);
        wentToNavi = true;
        startActivity(intent);
    }

    // ==================== 杂项 ====================

    /** Key 脱敏显示，便于确认当前使用的是哪一个 */
    private String maskKey(String key) {
        if (key == null || key.length() < 10) {
            return "（未设置）";
        }
        return key.substring(0, 5) + "-****-****-****-" + key.substring(key.length() - 5);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Throwable ignore) {
        }
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        String[] perms = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        boolean need = false;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                need = true;
                break;
            }
        }
        if (need) {
            requestPermissions(perms, REQ_PERM);
        } else {
            startLocation();
        }
    }

    private void startLocation() {
        AppLocation.get().start(this);
        AppLocation.get().addListener(this);
        AppLocation.get().addStatusListener(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            boolean locOk = false;
            for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
                if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    locOk = true;
                }
            }
            if (locOk) {
                Log.i(TAG, "定位权限已授予，启动定位");
                AppLocation.get().stop(this);
                startLocation();
            } else {
                txtStatus.setText("未授予定位权限，无法导航");
                Toast.makeText(this, "需要定位权限才能导航", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLocation();
        // 方向传感器：只在页面可见时开启，省电
        orientationSensor.addListener(this);
        orientationSensor.start(this);

        // 从导航页返回 → 清掉之前的终点，并把视野刷新回当前定位
        if (wentToNavi) {
            wentToNavi = false;
            resetToCurrentLocation();
        }

        LocationFix last = AppLocation.get().getLastFix();
        if (last != null) {
            onFix(last);
        }
        main.removeCallbacks(statusTick);
        main.postDelayed(statusTick, 2000);
    }

    /**
     * 回到主界面时的复位：清除终点与终点标记，地图重新居中到当前位置，
     * 起点继续跟随真实定位。避免退出导航后还停在上一次搜索的终点。
     */
    private void resetToCurrentLocation() {
        // v3.0 修复：从导航页返回时「只把视野拉回当前定位」，不再清空终点。
        // 旧实现会清掉 destPlace / 终点标记 / 搜索框，导致退出驾车后想改选
        // 骑行或步行时必须回首页重新搜索终点，用户感知为「被强制弹回首页」。
        // 保留终点后，退出任意导航页都能直接再点「导航」切换模式。
        if (resultScroll != null) {
            resultScroll.setVisibility(View.GONE);
        }
        if (searchInput != null) {
            searchInput.setText("");
        }

        // 允许下一次定位重新做一次"首次居中"
        firstCenterDone = false;
        LocationFix last = AppLocation.get().getLastFix();
        if (last != null && tencentMap != null) {
            LatLng ll = new LatLng(last.latitude, last.longitude);
            tencentMap.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 17f));
            firstCenterDone = true;
            txtStart.setText(String.format(Locale.CHINA,
                    "起点（当前位置）：%.5f, %.5f  ±%.0fm  卫星%d  %s",
                    last.latitude, last.longitude, last.accuracy,
                    AppLocation.get().getSatellites(), last.provider));
            Log.i(TAG, "复位后重新上图（GCJ02）: " + last);
        } else {
            txtStart.setText(R.string.start_default);
            txtStatus.setText("正在重新定位…");
        }
    }

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            main.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onPause() {
        main.removeCallbacks(statusTick);
        orientationSensor.removeListener(this);
        orientationSensor.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(statusTick);
        AppLocation.get().removeListener(this);
        AppLocation.get().removeStatusListener(this);
        AppLocation.get().stop(this);
        super.onDestroy();
    }
}
