package com.xiyin.navi.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import com.tencent.map.navi.data.GpsLocation;

/** 小工具：位置结构转换。 */
public final class NavUtil {

    private NavUtil() {}

    private static final String SDK_KEY = "com.tencent.map.api_key";
    private static final String SDK_KEY_OLD = "TencentMapSDK";

    /**
     * 从 Manifest 的 meta-data 读取腾讯 Key。
     *
     * <p><b>注意：业务代码不要直接调用本方法。</b>
     * 开源版 Key 由使用者运行时输入并保存在 SharedPreferences，Manifest 中为空。
     * 需要 Key 时请统一用 {@link KeyManager#getEffectiveKey(Context)}。
     * 本方法仅作为 KeyManager 的 Manifest 回退实现存在。
     */
    public static String getAuthKey(Context context) {
        if (context == null) {
            return "";
        }
        String key = "";
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            if (info.metaData != null) {
                key = info.metaData.getString(SDK_KEY);
                if (TextUtils.isEmpty(key)) {
                    key = info.metaData.getString(SDK_KEY_OLD);
                }
            }
        } catch (Throwable ignore) {
        }
        return key == null ? "" : key;
    }

    /** 统一定位结果 → 官方导航引擎要求的位置结构。 */
    public static GpsLocation toGpsLocation(LocationFix fix) {
        if (fix == null) {
            return null;
        }
        GpsLocation g = new GpsLocation();
        g.setLatitude(fix.latitude);
        g.setLongitude(fix.longitude);
        g.setAccuracy(fix.accuracy);
        g.setDirection(fix.bearing);
        g.setVelocity(fix.speed);
        g.setAltitude(fix.altitude);
        g.setProvider(fix.provider);
        g.setTime(fix.time);
        return g;
    }
}
