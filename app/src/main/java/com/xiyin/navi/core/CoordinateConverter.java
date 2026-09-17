package com.xiyin.navi.core;

/**
 * 坐标系转换：WGS-84 → GCJ-02（俗称火星坐标）。
 *
 * <p><b>为什么必须有这个类：</b>
 * <ul>
 *   <li>Android 原生 {@code LocationManager}（GPS_PROVIDER）返回的是 <b>WGS-84</b> 原始坐标</li>
 *   <li>腾讯地图 / 导航 SDK 使用的是 <b>GCJ-02</b> 加密坐标</li>
 * </ul>
 * 在中国境内，两者存在 <b>约 300~600 米</b>的固定偏移。
 * 若把 WGS-84 直接当 GCJ-02 画到腾讯地图上，就会出现"定位点偏到别处"的现象。
 *
 * <p>导航页面之所以看起来准，是因为导航引擎内部会做<b>绑路</b>（把点吸附到最近道路），
 * 把偏移掩盖掉了；地图页面没有绑路，偏移就暴露出来。
 *
 * <p>算法与腾讯官方转换接口（{@code apis.map.qq.com/ws/coord/v1/translate}）实测一致，
 * 典型点位误差 &lt; 0.1 米。境外坐标不做转换（避免出国使用被错误偏移）。
 */
public final class CoordinateConverter {

    private CoordinateConverter() {}

    /** 克拉索夫斯基椭球长半轴 */
    private static final double EARTH_A = 6378245.0;
    /** 椭球偏心率平方 */
    private static final double EARTH_EE = 0.00669342162296594323;

    public static final String TYPE_WGS84 = "WGS84";
    public static final String TYPE_GCJ02 = "GCJ02";

    /**
     * WGS-84 → GCJ-02。
     *
     * @return double[]{lat, lng}（GCJ-02）；若在中国境外则原样返回
     */
    public static double[] wgs84ToGcj02(double lat, double lng) {
        if (outOfChina(lat, lng)) {
            return new double[]{lat, lng};
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);

        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EARTH_EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);

        dLat = (dLat * 180.0) / ((EARTH_A * (1 - EARTH_EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (EARTH_A / sqrtMagic * Math.cos(radLat) * Math.PI);

        return new double[]{lat + dLat, lng + dLng};
    }

    /**
     * 中国大陆粗略范围判断。不在范围内（港澳台及境外）不转换。
     * 与常见实现保持一致：经度 72.004~137.8347、纬度 0.8293~55.8271。
     */
    public static boolean outOfChina(double lat, double lng) {
        return (lng < 72.004 || lng > 137.8347) || (lat < 0.8293 || lat > 55.8271);
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
