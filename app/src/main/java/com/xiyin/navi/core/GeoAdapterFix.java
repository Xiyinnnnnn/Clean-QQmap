package com.xiyin.navi.core;

import android.util.Log;

/**
 * 修复腾讯 NaviSDK「跨导航模式复用定位适配器单例」导致的模式混淆（v3.0.1）。
 *
 * <p><b>根因（jadx 反编译实证）：</b>
 * <pre>
 *   FusionGeoLocationAdapter 是进程级静态单例：
 *     private static volatile FusionGeoLocationAdapter a;
 *     public static FusionGeoLocationAdapter getInstance(Context ctx, int mode) {
 *         if (a == null) { a = new FusionGeoLocationAdapter(ctx, mode); }  // ← mode 只在首次生效
 *         return a;                                                        // ← 之后忽略 mode
 *     }
 *   而模式的唯一应用点是构造期的 a.java#d(): f2201a.setRouteMode(this.f255a);
 *   且全 SDK 没有任何地方调用 destroyAdapter()。
 * </pre>
 *
 * <p>三个 Manager 传入的模式不同：
 * <pre>
 *   驾车 TencentCarNaviManager  → getInstance(ctx)     → mode=0
 *   步行 TencentWalkNaviManager → getInstance(ctx, 1)  → mode=1
 *   骑行 TencentRideNaviManager → getInstance(ctx, 2)  → mode=2
 * </pre>
 * 因此「先驾车、再骑行/步行」时，后两者仍带着 <b>驾车(0)</b> 的 routeMode 运行 → 行为异常。
 *
 * <p><b>修法：</b>进入导航页、创建 Manager 之前，若当前模式与上次登记的不同，
 * 先 {@code destroyAdapter()} 置空单例，让 {@code getInstance(ctx, mode)} 按新模式重建。
 * 命名空间类可见性已确认：{@code FusionGeoLocationAdapter} 为 public，{@code destroyAdapter()} 为 public static。
 */
public final class GeoAdapterFix {

    private static final String TAG = "GeoAdapterFix";

    /** 上一次登记的模式；-1 表示本进程尚未登记过 */
    private static volatile int currentMode = -1;

    private GeoAdapterFix() {}

    /**
     * 确保定位适配器单例以指定模式创建。模式变化时先销毁旧单例。
     *
     * @param mode 0=驾车 1=步行 2=骑行（与 SDK 内部一致）
     */
    public static synchronized void ensureMode(int mode) {
        if (currentMode == mode) {
            Log.i(TAG, "定位适配器模式未变(" + mode + ")，复用单例");
            return;
        }
        try {
            com.tencent.map.location.core.FusionGeoLocationAdapter.destroyAdapter();
            Log.w(TAG, "模式切换 " + currentMode + " → " + mode + "，已销毁旧定位适配器单例");
        } catch (Throwable t) {
            Log.w(TAG, "销毁定位适配器单例失败: " + t);
        }
        currentMode = mode;
    }
}
