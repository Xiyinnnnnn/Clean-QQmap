package com.xiyin.navi.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.tencent.map.geolocation.TencentLocationManagerOptions;
import com.tencent.tencentmap.mapsdk.maps.TencentMapOptions;

/**
 * 腾讯 Key 的运行时管理（v2.9 新增，为开源发布设计）。
 *
 * <p><b>为什么需要它：</b>腾讯地图 SDK 默认从 AndroidManifest 的
 * {@code TencentMapSDK} meta-data 读 Key。若把个人 Key 打包进开源项目，
 * 所有人都能用你的额度。改为运行时由使用者自己填入。
 *
 * <p><b>官方运行时注入入口（已反编译确认，非 hack）：</b>
 * <ul>
 *   <li>地图：{@link TencentMapOptions#setMapKey(String)}</li>
 *   <li>导航：{@code CarNaviView/RideNaviView/WalkNaviView.setTencentMapOptions(opts)}（静态）</li>
 *   <li>定位：{@link TencentLocationManagerOptions#setKey(String)}</li>
 * </ul>
 * 地图 SDK 内部 {@code t.a()} 的逻辑是：
 * <pre>
 *   if (options.getMapKey() 不为空) 用它;   // ← 运行时优先
 *   else 从 Manifest 的 TencentMapSDK 读;   // ← 回退
 * </pre>
 * 因此运行时注入的 Key 会覆盖 Manifest。
 *
 * <p>本类还提供 Manifests 回退：若 Manifest 中已配置 Key（开发者自用场景），
 * 使用者无需再输入。
 */
public final class KeyManager {

    private static final String TAG = "KeyManager";
    private static final String PREF_NAME = "navi_key";
    private static final String PREF_KEY = "tencent_map_key";

    private KeyManager() {}

    /** 读取用户已保存的 Key；未设置返回空串。 */
    public static String getSavedKey(Context context) {
        if (context == null) {
            return "";
        }
        try {
            SharedPreferences sp = context.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String k = sp.getString(PREF_KEY, "");
            return k == null ? "" : k.trim();
        } catch (Throwable t) {
            Log.w(TAG, "读取已存 Key 失败: " + t);
            return "";
        }
    }

    /** 保存用户输入的 Key。 */
    public static void saveKey(Context context, String key) {
        if (context == null) {
            return;
        }
        try {
            context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_KEY, key == null ? "" : key.trim()).apply();
            Log.i(TAG, "已保存 Key（长度 " + (key == null ? 0 : key.trim().length()) + "）");
        } catch (Throwable t) {
            Log.w(TAG, "保存 Key 失败: " + t);
        }
    }

    /**
     * 取当前生效的 Key：优先用户设置，其次 Manifest（开发者自用）。
     */
    public static String getEffectiveKey(Context context) {
        String saved = getSavedKey(context);
        if (!saved.isEmpty()) {
            return saved;
        }
        return NavUtil.getAuthKey(context);
    }

    /**
     * 把 Key 注入到腾讯 SDK。
     *
     * <p>三个入口都要设：地图（含导航 View 内的地图）与定位各自独立读取。
     * 建议在 Application.onCreate() 尽早调用——必须在第一个 MapView / NaviView 创建之前。
     */
    public static void applyKey(Context context, String key) {
        final String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            return;
        }
        // 1) 三个导航 View：反编译确认它们各自持有「独立」的静态 options 字段，
        //    并非共享，所以必须逐个设置
        try {
            TencentMapOptions carOpts = new TencentMapOptions().setMapKey(k);
            com.tencent.map.navi.car.CarNaviView.setTencentMapOptions(carOpts);
            Log.i(TAG, "已注入 驾车 导航 Key");
        } catch (Throwable t) {
            Log.w(TAG, "注入驾车 Key 失败: " + t);
        }
        try {
            TencentMapOptions rideOpts = new TencentMapOptions().setMapKey(k);
            com.tencent.map.navi.ride.RideNaviView.setTencentMapOptions(rideOpts);
            Log.i(TAG, "已注入 骑行 导航 Key");
        } catch (Throwable t) {
            Log.w(TAG, "注入骑行 Key 失败: " + t);
        }
        try {
            TencentMapOptions walkOpts = new TencentMapOptions().setMapKey(k);
            com.tencent.map.navi.walk.WalkNaviView.setTencentMapOptions(walkOpts);
            Log.i(TAG, "已注入 步行 导航 Key");
        } catch (Throwable t) {
            Log.w(TAG, "注入步行 Key 失败: " + t);
        }
        // 2) 定位
        try {
            boolean ok = TencentLocationManagerOptions.setKey(k);
            Log.i(TAG, "已注入定位 Key, 结果=" + ok);
        } catch (Throwable t) {
            Log.w(TAG, "注入定位 Key 失败: " + t);
        }
        // 3) ★导航算路鉴权★（v2.9.3 修复）
        //    NaviSDK 的算路鉴权走「独立通道」TencentNavi.Config.navKey，
        //    既不是 TencentMapOptions.setMapKey，也与 Manifest 只是回退关系，两者不互通。
        //    漏设的后果：首次算路因鉴权缓存缺省值(true)侥幸通过，随后异步鉴权把
        //    IsKeyValid 缓存写成 false，导致「退出导航后再进入」必失败(2006 鉴权失败)。
        //    所以这里必须：先清脏缓存，再注入 navKey（顺序不可反）。
        clearNaviAuthCache(context);
        applyNaviKey(context, k);
    }

    /**
     * 注入导航引擎鉴权 Key（腾讯 NaviSDK 的官方运行时入口，已反编译确认）。
     *
     * <p>取值优先级（{@code a.a.a.h.n.a(Context):String} 内部）：
     * <pre>
     *   1. TencentNavi 的静态 Config.navKey   ← 本方法设置，运行时优先
     *   2. ApplicationInfo.metaData["com.tencent.map.api_key"]
     *   3. ApplicationInfo.metaData["TencentMapSDK"]   ← 仅作为回退
     * </pre>
     * 公开 API 为 {@code TencentNavi.init(Context, Config)} + {@code Config#setNavKey(String)}。
     */
    public static void applyNaviKey(Context context, String key) {
        final String k = key == null ? "" : key.trim();
        if (context == null || k.isEmpty()) {
            return;
        }
        try {
            com.tencent.map.navi.TencentNavi.Config cfg =
                    new com.tencent.map.navi.TencentNavi.Config();
            cfg.setNavKey(k);
            cfg.setDeviceId(com.tencent.map.navi.TencentNavi.getDeviceId(context));
            com.tencent.map.navi.TencentNavi.init(context.getApplicationContext(), cfg);
            Log.i(TAG, "已注入导航引擎鉴权 Key");
        } catch (Throwable t) {
            Log.w(TAG, "注入导航引擎鉴权 Key 失败: " + t);
        }
    }

    /**
     * 清除 NaviSDK 的鉴权结果缓存。
     *
     * <p>NaviSDK 把鉴权结果写入 {@code SharedPreferences("navi_key_data")} 的
     * {@code IsKeyValid} / {@code KeyValid_cacheExpire}，且 {@code IsKeyValid} 缺省为 true。
     * 一旦某次鉴权失败（例如 Key 尚未注入），脏缓存会让之后**所有**算路直接失败，
     * 直到缓存自然过期。因此在注入 Key 的同时必须清掉它，强制重新鉴权。
     */
    public static void clearNaviAuthCache(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.getApplicationContext()
                    .getSharedPreferences("navi_key_data", Context.MODE_PRIVATE)
                    .edit()
                    .remove("IsKeyValid")
                    .remove("KeyValid_cacheExpire")
                    .apply();
            Log.i(TAG, "已清除导航鉴权缓存");
        } catch (Throwable t) {
            Log.w(TAG, "清除导航鉴权缓存失败: " + t);
        }
    }

    /**
     * 做一次轻量网络校验：调用腾讯 WebService 确认 Key 可用。
     * 只验证「Key 本身是否有效」，不做签名绑定校验（绑定仍需在控制台配置 SHA1）。
     *
     * @param onResult (是否通过, 提示文案)
     */
    public static void verifyKey(final Context context, final String key,
                                 final VerifyCallback onResult) {
        final android.os.Handler main =
                new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            String message;
            boolean ok = false;
            java.net.HttpURLConnection conn = null;
            try {
                String url = "https://apis.map.qq.com/ws/geocoder/v1/?address="
                        + java.net.URLEncoder.encode("北京市", "UTF-8")
                        + "&key=" + java.net.URLEncoder.encode(key, "UTF-8");
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    message = "网络异常（HTTP " + code + "）";
                } else {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    VerifyResp resp = gson.fromJson(sb.toString(), VerifyResp.class);
                    if (resp == null) {
                        message = "返回内容无法解析";
                    } else if (resp.status == 0) {
                        ok = true;
                        message = "Key 校验通过";
                    } else if (resp.status == 190 || resp.status == 311) {
                        // 190=无效的key, 311=key格式错误 → 明确无效，必须拦下
                        ok = false;
                        message = "Key 无效（" + resp.status + "）："
                                + (resp.message == null ? "" : resp.message);
                    } else if (resp.status == 121) {
                        // 121=当日调用量达上限：Key 本身是有效的，只是额度用完，放行
                        ok = true;
                        message = "Key 有效，但今日调用量已达上限（不影响地图与导航）";
                    } else if (resp.status == 110 || resp.status == 111) {
                        // 未开通该 WebService 服务：Key 有效，但需在控制台勾选服务
                        ok = true;
                        message = "Key 有效，但未开通「WebServiceAPI」服务："
                                + "地点搜索将不可用，地图与导航不受影响";
                    } else {
                        ok = true;
                        message = "Key 已保存（校验返回 " + resp.status + " "
                                + (resp.message == null ? "" : resp.message) + "）";
                    }
                }
            } catch (Throwable t) {
                message = "校验失败：" + t;
                Log.e(TAG, "verifyKey 异常: " + t);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final boolean fok = ok;
            final String fmsg = message;
            main.post(() -> onResult.onResult(fok, fmsg));
        }).start();
    }

    public interface VerifyCallback {
        void onResult(boolean ok, String message);
    }

    private static class VerifyResp {
        int status;
        String message;
    }
}
