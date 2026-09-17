package com.xiyin.navi.core;

import android.content.Context;

import com.tencent.tencentmap.mapsdk.maps.MapView;
import com.tencent.tencentmap.mapsdk.maps.SupportMapFragment;
import com.tencent.tencentmap.mapsdk.maps.TencentMapOptions;

/**
 * 带运行时 Key 支持的地图 Fragment。
 *
 * <p>为什么需要子类：{@link SupportMapFragment} 内部创建 MapView 时，
 * 传给 {@code onCreateMapView(Context, TencentMapOptions)} 的 options 是 <b>null</b>，
 * 且它自己不读取任何静态 options，所以无法通过外部设置给它注入 Key。
 * 覆写该方法并传入带 Key 的 options 即可（这是官方留出的 protected 扩展点）。
 *
 * <p>Key 的取用链：{@link KeyManager#getEffectiveKey} → 用户设置优先，Manifest 回退。
 */
public class KeyedMapFragment extends SupportMapFragment {

    @Override
    protected MapView onCreateMapView(Context context, TencentMapOptions options) {
        String key = KeyManager.getEffectiveKey(context);
        TencentMapOptions opts = options != null ? options : new TencentMapOptions();
        if (key != null && !key.isEmpty()) {
            opts.setMapKey(key);
        }
        return new MapView(context, opts);
    }
}
