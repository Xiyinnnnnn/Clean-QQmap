package com.xiyin.navi.core;

/** 全局常量：定位刷新与导航模式。参数集中在此，便于调优。 */
public final class AppConst {

    private AppConst() {}

    // 腾讯地图 Key 的唯一定义处是 AndroidManifest.xml 的 TencentMapSDK meta-data，
    // 腾讯 SDK 只从那里读取；运行时可用 NavUtil.getAuthKey() 取。

    /**
     * GPS 定位采样间隔（毫秒）。v2.6 改回 1 秒。
     *
     * <p>实测权衡：
     * <ul>
     *   <li>1 秒 → 导航跟手、偏航能及时发现，代价是静止时点会有小幅跳动</li>
     *   <li>5 秒 → 点更稳，但开车时位置变化太慢、无法及时发现偏航</li>
     * </ul>
     * 驾驶场景优先实时性，故选 1 秒；静态漂移由 {@code MAX_ACCEPT_ACCURACY_M}
     * 精度过滤 + 偏航判定计数共同抑制。
     */
    public static final int LOCATION_INTERVAL_MS = 1000;

    /** 导航中的采样间隔（毫秒）。驾驶优先实时性，同为 1 秒。 */
    public static final int NAV_LOCATION_INTERVAL_MS = 1000;

    // ==================== 偏航重算（v2.6 新增） ====================

    /**
     * 偏航判定阈值（米）：实际位置与「绑路后位置」的距离超过该值即视为偏离路线。
     *
     * <p>为什么需要自己判定：SDK 的重算入口是 {@code onOffRoute()}，
     * 而当位置由外部 {@code updateLocation()} 注入时，SDK 内部那条自动检测链路
     * 不一定会触发——所以必须由 App 主动检测并调用 {@code onOffRoute()} 触发重算。
     */
    public static final float OFF_ROUTE_THRESHOLD_CAR_M = 45f;
    public static final float OFF_ROUTE_THRESHOLD_RIDE_M = 30f;
    public static final float OFF_ROUTE_THRESHOLD_WALK_M = 25f;

    /** 连续多少次采样都判定偏航才真正触发重算（防止单次漂移误触发）。 */
    public static final int OFF_ROUTE_CONFIRM_COUNT = 3;

    /** 两次重算之间的最小间隔（毫秒），避免反复重算刷屏。 */
    public static final long REROUTE_COOLDOWN_MS = 15000L;

    /**
     * GPS 优先阈值：精度优于该值（米）视为 GPS 级定位。
     * 一旦拿到 GPS 级结果，就不再接受网络定位，避免"网络定位不准"把车标带偏。
     */
    public static final float GPS_LEVEL_ACCURACY_M = 150f;

    /**
     * 导航视角开关。
     * <p>true  = 完全俯视（2D 地图朝北）→ {@code NaviMode.MODE_2DMAP_TOWARDS_NORTH}
     * <br>false = 2.5D 车头朝上（SDK 默认）→ {@code NaviMode.MODE_3DCAR_TOWARDS_UP}
     */
    public static final boolean NAVI_OVERHEAD_VIEW = true;

    /** 精度劣化容忍：新结果比当前结果差超过该值（米）时丢弃。 */
    public static final float ACCURACY_DROP_TOLERANCE_M = 200f;

    /** 模式 */
    public static final int MODE_CAR = 0;
    public static final int MODE_RIDE = 1;
    public static final int MODE_WALK = 2;
    /** 公交 / 地铁换乘方案（v4.1 新增；腾讯 NaviSDK 无公交实时导航入口，走官方换乘规划） */
    public static final int MODE_TRANSIT = 3;

    /** Intent 参数 */
    public static final String EXTRA_MODE = "extra_mode";
    public static final String EXTRA_START_LAT = "extra_start_lat";
    public static final String EXTRA_START_LNG = "extra_start_lng";
    public static final String EXTRA_DEST_LAT = "extra_dest_lat";
    public static final String EXTRA_DEST_LNG = "extra_dest_lng";
    public static final String EXTRA_DEST_NAME = "extra_dest_name";
    /** 公交方案对象（TransitSearch.Plan，Serializable）用于列表→详情页传递 */
    public static final String EXTRA_PLAN = "extra_transit_plan";
}
