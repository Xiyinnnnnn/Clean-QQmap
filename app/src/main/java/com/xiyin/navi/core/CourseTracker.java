package com.xiyin.navi.core;

/**
 * 移动方向推算器（Course Over Ground）：不依赖任何设备姿态传感器，
 * 只用<b>定位点之间的实际位移</b>推算"人实际朝哪个方向在走"。
 *
 * <p>为什么不用 {@link OrientationSensor}（旋转矢量/磁力计+陀螺仪）：
 * 传感器给的是"手机朝向"，既受磁干扰乱飘（地库/车内/金属建筑），
 * 又与"用户实际行进方向"不一致（手机放兜里、横握、看地图转身）。
 * 位移方向则天然等于真实移动方向，且不受磁环境影响。
 *
 * <p>算法（分三层，逐层降噪）：
 * <ol>
 *   <li><b>锚点累积</b>：以上一个"确认点"为锚，新点离锚点位移超过阈值才认作一次有效移动，
 *       得到一段位移向量。阈值随定位精度自适应（精度越差、要求位移越大），
 *       避免把 GPS 抖动当成移动。</li>
 *   <li><b>向量平滑</b>：把各段位移的单位向量做指数加权平均（sin/cos 分量分别平滑），
 *       加权系数随该段位移长度自适应（越长越可信、权重越高），天然免疫 0/360° 环绕。</li>
 *   <li><b>静止保持</b>：长时间没有有效位移（站在原地）或速度极低时，
 *       保持上一次的方向不变——既不乱转，也不强行回北。</li>
 * </ol>
 *
 * <p>输出：相对正北的方位角（0=北，90=东，顺时针，0~360），
 * 语义与地图 Marker 的 rotation 一致；从未获得有效方向时返回 {@link Float#NaN}。
 */
public final class CourseTracker {

    /** 一次有效移动的最小位移（米），实际阈值取 max(本值, 定位精度×精度系数) */
    private static final float BASE_MIN_DISPLACEMENT_M = 4f;
    /** 精度系数：阈值 = 精度 × 本系数（精度越差，要求位移越大，滤掉抖动） */
    private static final float ACCURACY_FACTOR = 0.5f;
    /** 单次位移封顶（米）：超过视为跳变/漂移，不作为方向依据 */
    private static final float MAX_SEGMENT_M = 400f;
    /** 平滑参考长度（米）：位移越长，越接近"完全采信新方向" */
    private static final float SMOOTH_REF_M = 12f;
    /** 平滑系数下/上限（越小越平滑、越大越跟手） */
    private static final float ALPHA_MIN = 0.18f;
    private static final float ALPHA_MAX = 0.70f;
    /** 判定"仍在移动"的速度下限（m/s）；低于此且无新位移则视为静止 */
    private static final float MOVING_SPEED_MS = 0.7f;
    /** 距上次有效位移超过该毫秒数则视为已静止（保持方向不再更新） */
    private static final long STALE_MS = 12000L;
    /** 锚点最大存活时间：超时无更新则重设锚点（避免拿很久以前的点算方向） */
    private static final long ANCHOR_MAX_AGE_MS = 30000L;

    // 地球每度纬度对应的米数（经度再乘 cos(lat)）
    private static final double METERS_PER_DEG_LAT = 111320.0;

    private boolean hasAnchor;
    private double anchorLat;
    private double anchorLng;
    private long anchorTime;

    private boolean hasCourse;
    private double smoothEast;   // 平滑后的单位向量东向分量 × 累计权重
    private double smoothNorth;  // 平滑后的单位向量北向分量 × 累计权重
    private float courseDeg = Float.NaN;
    private long lastMoveTime;
    private int segmentCount;

    /** 喂入一次定位结果；只在发生有效位移时才更新内部方向。 */
    public void update(LocationFix fix) {
        if (fix == null || !fix.isValid()) {
            return;
        }
        long now = fix.time > 0 ? fix.time : System.currentTimeMillis();

        if (!hasAnchor || now - anchorTime > ANCHOR_MAX_AGE_MS) {
            setAnchor(fix.latitude, fix.longitude, now);
            return;
        }

        double dNorth = (fix.latitude - anchorLat) * METERS_PER_DEG_LAT;
        double dEast = (fix.longitude - anchorLng) * METERS_PER_DEG_LAT
                * Math.cos(Math.toRadians(fix.latitude));
        double distance = Math.hypot(dEast, dNorth);

        // 阈值：基础值 与 精度自适应值 取大
        float minDisp = BASE_MIN_DISPLACEMENT_M;
        if (fix.accuracy > 0) {
            minDisp = Math.max(minDisp, fix.accuracy * ACCURACY_FACTOR);
        }

        if (distance < minDisp) {
            // 位移不足 → 不更新方向；若长时间没动则保持既有方向（不乱转）
            return;
        }
        if (distance > MAX_SEGMENT_M) {
            // 跳变/漂移，抛弃该段，仅把锚点移到当前位置
            setAnchor(fix.latitude, fix.longitude, now);
            return;
        }

        // 有效位移段 → 计算方向并平滑
        double eastUnit = dEast / distance;
        double northUnit = dNorth / distance;
        double segBearing = Math.toDegrees(Math.atan2(eastUnit, northUnit)); // 0=北, 顺时针
        double alpha = clamp((float) (distance / (distance + SMOOTH_REF_M)), ALPHA_MIN, ALPHA_MAX);

        if (!hasCourse) {
            smoothEast = eastUnit;
            smoothNorth = northUnit;
            hasCourse = true;
        } else {
            smoothEast = smoothEast * (1 - alpha) + eastUnit * alpha;
            smoothNorth = smoothNorth * (1 - alpha) + northUnit * alpha;
        }
        double smoothedBearing = Math.toDegrees(Math.atan2(smoothEast, smoothNorth));
        courseDeg = (float) normalize360(smoothedBearing);
        segmentCount++;
        lastMoveTime = now;

        setAnchor(fix.latitude, fix.longitude, now);
    }

    private void setAnchor(double lat, double lng, long time) {
        anchorLat = lat;
        anchorLng = lng;
        anchorTime = time;
        hasAnchor = true;
    }

    private static double normalize360(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 当前可用的移动方向（0~360，0=北，顺时针）。
     *
     * @return 从未获得有效方向时为 {@link Float#NaN}；调用方可回退到 GPS 航向或保持不动
     */
    public float getCourse() {
        return hasCourse ? courseDeg : Float.NaN;
    }

    public boolean hasCourse() {
        return hasCourse;
    }

    /** 重置（换页/重新定位时调用），下次从零重建方向。 */
    public void reset() {
        hasAnchor = false;
        hasCourse = false;
        smoothEast = 0;
        smoothNorth = 0;
        courseDeg = Float.NaN;
        lastMoveTime = 0;
        segmentCount = 0;
    }

    /** 供状态栏显示的简短描述。 */
    public String describe() {
        if (!hasCourse) {
            return "移动方向(计算中)";
        }
        long age = lastMoveTime == 0 ? -1 : System.currentTimeMillis() - lastMoveTime;
        boolean still = age < 0 || age > STALE_MS;
        return String.format(java.util.Locale.CHINA, "移动方向 %d°%s n=%d",
                (int) courseDeg, still ? "(静止保持)" : "", segmentCount);
    }
}
