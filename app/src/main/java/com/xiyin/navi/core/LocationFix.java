package com.xiyin.navi.core;

/**
 * 统一定位结果（值对象）。
 *
 * <p><b>关键设计：同时携带两套坐标</b>
 * <ul>
 *   <li>{@link #latitude}/{@link #longitude} —— <b>GCJ-02</b>，给腾讯地图与导航 SDK 用</li>
 *   <li>{@link #wgsLatitude}/{@link #wgsLongitude} —— <b>WGS-84</b> 原始值，便于对照与排查</li>
 * </ul>
 * Android 原生 GPS 返回的是 WGS-84，而腾讯产品用的是 GCJ-02，两者在国内相差 300~600 米。
 * 在构造时就统一转换，下游拿到的 latitude/longitude 一定是可直接上图/上导航的 GCJ-02，
 * 避免各处各自转换导致遗漏。
 */
public final class LocationFix {

    /** 腾讯 SDK 的 status 常量值（TencentLocationListener.STATUS_GPS_AVAILABLE） */
    public static final int STATUS_OK = 3;

    /** GCJ-02 坐标（腾讯地图/导航使用） */
    public final double latitude;
    public final double longitude;

    /** WGS-84 原始坐标（仅用于对照诊断） */
    public final double wgsLatitude;
    public final double wgsLongitude;

    public final double altitude;
    public final float accuracy;
    public final float bearing;
    public final float speed;
    public final long time;
    public final String provider;
    public final int status;
    public final String reason;
    public final String coordinateType;

    private LocationFix(double gcjLat, double gcjLng, double wgsLat, double wgsLng,
                        double altitude, float accuracy, float bearing, float speed,
                        long time, String provider, int status, String reason,
                        String coordinateType) {
        this.latitude = gcjLat;
        this.longitude = gcjLng;
        this.wgsLatitude = wgsLat;
        this.wgsLongitude = wgsLng;
        this.altitude = altitude;
        this.accuracy = accuracy;
        this.bearing = bearing;
        this.speed = speed;
        this.time = time;
        this.provider = provider;
        this.status = status;
        this.reason = reason;
        this.coordinateType = coordinateType;
    }

    /** 用 WGS-84 原始坐标构造（原生 GPS/网络定位走这里），内部自动转成 GCJ-02。 */
    public static LocationFix fromWgs84(double wgsLat, double wgsLng, double altitude,
                                        float accuracy, float bearing, float speed,
                                        long time, String provider, int status, String reason) {
        double[] gcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLng);
        return new LocationFix(gcj[0], gcj[1], wgsLat, wgsLng,
                altitude, accuracy, bearing, speed, time, provider, status, reason,
                CoordinateConverter.TYPE_WGS84);
    }

    /** 用已经是 GCJ-02 的坐标构造（如腾讯融合定位、或已转换过的数据）。 */
    public static LocationFix fromGcj02(double gcjLat, double gcjLng, double altitude,
                                        float accuracy, float bearing, float speed,
                                        long time, String provider, int status, String reason) {
        return new LocationFix(gcjLat, gcjLng, gcjLat, gcjLng,
                altitude, accuracy, bearing, speed, time, provider, status, reason,
                CoordinateConverter.TYPE_GCJ02);
    }

    public boolean isValid() {
        // 0,0 视为无效（大西洋坐标）
        return !(Math.abs(latitude) < 1e-6 && Math.abs(longitude) < 1e-6);
    }

    /** 原始 WGS-84 与转换后 GCJ-02 之间的偏移距离（米），便于诊断。 */
    public double offsetMeters() {
        double dLat = (latitude - wgsLatitude) * 111320.0;
        double dLng = (longitude - wgsLongitude) * 111320.0
                * Math.cos(Math.toRadians(latitude));
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.CHINA,
                "GCJ02 %.6f,%.6f (WGS84 %.6f,%.6f 偏移%.0fm) acc=%.0fm brg=%.0f src=%s[%s]",
                latitude, longitude, wgsLatitude, wgsLongitude, offsetMeters(),
                accuracy, bearing, provider, coordinateType);
    }
}
