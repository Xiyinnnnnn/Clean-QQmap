package com.xiyin.navi;

import android.app.Application;
import android.util.Log;

import com.xiyin.navi.core.KeyManager;

/**
 * 应用入口：在「最早的时机」把腾讯 Key 注入到所有 SDK 通道。
 *
 * <p>v2.9.3 新增。为什么必须放在 Application，而不是某个 Activity：
 * <ul>
 *   <li><b>导航算路鉴权 Key</b>（{@code TencentNavi.Config.navKey}）必须在任何导航
 *       Manager 使用之前设置。否则异步鉴权会把 {@code IsKeyValid=false} 写进缓存，
 *       导致之后所有算路都报 2006 鉴权失败；</li>
 *   <li>腾讯地图/导航 View 要求 Key 在 View 创建之前注入，而 Activity 中
 *       {@code setContentView} 已经创建了 View，注入时机偏晚；</li>
 *   <li>进程被系统回收后可能不经过启动页直接从导航页恢复，写在入口页的判断会失效。</li>
 * </ul>
 */
public class NaviApp extends Application {

    private static final String TAG = "NaviApp";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            String key = KeyManager.getEffectiveKey(this);
            if (key != null && !key.trim().isEmpty()) {
                KeyManager.applyKey(this, key);   // 含地图 / 定位 / 导航鉴权三通道
                Log.i(TAG, "启动时已注入 Key（含导航鉴权通道）");
            } else {
                // 尚未配置 Key：清掉可能残留的脏鉴权缓存，等用户填完后重新注入
                KeyManager.clearNaviAuthCache(this);
                Log.i(TAG, "启动时无 Key，已清鉴权缓存，等待用户配置");
            }
        } catch (Throwable t) {
            Log.w(TAG, "启动注入 Key 失败: " + t);
        }
    }
}
