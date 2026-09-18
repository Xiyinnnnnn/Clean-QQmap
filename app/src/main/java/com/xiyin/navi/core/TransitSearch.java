package com.xiyin.navi.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 公共交通（公交 / 地铁）换乘方案查询（v4.1 新增）。
 *
 * <p>调用腾讯地图官方 WebService：<code>https://apis.map.qq.com/ws/direction/v1/transit/</code>
 * ——接入方式与 {@link PlaceSearch} 完全一致（同一个 Key、同一种直连）。
 *
 * <p><b>为什么是「换乘方案」而不是「实时导航」：</b>腾讯 NaviSDK（5.3.4.0）只提供
 * 驾车 / 骑行 / 步行三种实时导航，反编译确认其内部<b>没有</b>公交的高层导航入口；
 * 官方对公交/地铁的能力就是本接口的「换乘方案规划」。所以本类即公交场景下的
 * 「腾讯官方导航能力」入口，与三模式定位一致。
 *
 * <p>返回结果保留了官方给出的<b>逐段细节</b>：步行指引、上/下车站与出口、
 * 途经站点列表、线路运营时间，供 {@code TransitDetailActivity} 做站点导航展示。
 */
public final class TransitSearch {

    private static final String TAG = "TransitSearch";
    private static final String URL_BASE = "https://apis.map.qq.com/ws/direction/v1/transit/?";

    /** 一条完整的换乘方案。 */
    public static final class Plan implements Serializable {
        public int distanceM;
        public int durationMin;
        /** 票价（分）；< 0 表示官方未给出。 */
        public int priceCent;
        /** 线路摘要，如「地铁9号线 → 地铁1号线八通线」。 */
        public String summary = "";
        /** 按顺序的乘车/步行分段。 */
        public List<Seg> segments = new ArrayList<>();
    }

    /** 方案中的一段：要么是步行，要么是乘坐某条线路。 */
    public static final class Seg implements Serializable {
        /** true=乘车段，false=步行段。 */
        public boolean transit;

        // ---- 步行段 ----
        public int walkDistanceM;
        public int walkDurationMin;
        public String walkDirection = "";
        /** 逐条步行指引合并成的文本，如「朝东行进58米到达终点」。 */
        public String walkDesc = "";

        // ---- 乘车段 ----
        /** SUBWAY（地铁）/ BUS（公交）。 */
        public String vehicle = "";
        /** 线路名，如「地铁1号线八通线」。 */
        public String lineTitle = "";
        /** 乘坐站数（含上车站到下车站的区间）。 */
        public int stationCount;
        public int lineDistanceM;
        public int lineDurationMin;
        /** 首末班时间，如 05:09 / 22:47。 */
        public String startTime = "";
        public String endTime = "";
        /** 上车站、出入口、下车站。 */
        public String getOnName = "";
        public String getOnExit = "";
        public String getOffName = "";
        /** 站点序列：上车站 → 途经站 → 下车站。 */
        public List<String> stationSeq = new ArrayList<>();
    }

    public interface Callback {
        void onResult(List<Plan> plans);

        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    /**
     * 查询起终点之间的公交/地铁换乘方案。
     * 坐标须为 GCJ-02（本 App 的定位与搜索结果均已是 GCJ-02）。
     */
    public void plan(Context context, double startLat, double startLng,
                     double destLat, double destLng, Callback callback) {
        final Context app = context.getApplicationContext();
        final String key = KeyManager.getEffectiveKey(app);
        if (key == null || key.isEmpty()) {
            main.post(() -> callback.onError("尚未配置腾讯 Key，请到首页长按底部状态行填写"));
            return;
        }
        new Thread(() -> {
            List<Plan> plans = new ArrayList<>();
            String error = null;
            HttpURLConnection conn = null;
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(URL_BASE)
                        .append("from=").append(startLat).append(",").append(startLng)
                        .append("&to=").append(destLat).append(",").append(destLng)
                        .append("&key=").append(key);
                conn = (HttpURLConnection) new URL(sb.toString()).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code != 200) {
                    String detail = readStream(conn.getErrorStream());
                    if (detail.length() > 200) {
                        detail = detail.substring(0, 200);
                    }
                    error = "HTTP " + code + (detail.isEmpty() ? "" : " " + detail);
                    Log.e(TAG, "换乘 HTTP 失败: " + error + " url=" + sb);
                } else {
                    String raw = readStream(conn.getInputStream());
                    TransitResp resp;
                    try {
                        resp = gson.fromJson(raw, TransitResp.class);
                    } catch (Throwable t) {
                        resp = null;
                        Log.e(TAG, "解析异常 raw=" + (raw.length() > 300 ? raw.substring(0, 300) : raw));
                    }
                    if (resp == null) {
                        error = "返回内容无法解析";
                    } else if (resp.status != 0) {
                        error = explainStatus(resp.status, resp.message);
                    } else if (resp.result != null && resp.result.routes != null) {
                        for (Route r : resp.result.routes) {
                            if (r != null) {
                                plans.add(convert(r));
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                error = t.toString();
                Log.e(TAG, "换乘查询失败: " + t);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final String err = error;
            main.post(() -> {
                if (err != null && plans.isEmpty()) {
                    callback.onError(err);
                } else {
                    callback.onResult(plans);
                }
            });
        }).start();
    }

    private static Plan convert(Route r) {
        Plan p = new Plan();
        p.distanceM = r.distance;
        p.durationMin = r.duration;
        p.priceCent = r.price;
        StringBuilder summary = new StringBuilder();
        if (r.steps != null) {
            for (Step s : r.steps) {
                if (s == null || s.mode == null) {
                    continue;
                }
                if ("WALKING".equals(s.mode)) {
                    Seg seg = new Seg();
                    seg.transit = false;
                    seg.walkDistanceM = s.distance;
                    seg.walkDurationMin = s.duration;
                    seg.walkDirection = s.direction == null ? "" : s.direction;
                    seg.walkDesc = buildWalkDesc(s.steps);
                    if (seg.walkDistanceM > 0) {
                        p.segments.add(seg);
                    }
                } else if (s.lines != null) {
                    for (Line ln : s.lines) {
                        if (ln == null) {
                            continue;
                        }
                        Seg seg = buildRideSeg(ln);
                        p.segments.add(seg);
                        if (summary.length() > 0) {
                            summary.append(" → ");
                        }
                        summary.append(seg.lineTitle);
                    }
                }
            }
        }
        p.summary = summary.length() == 0 ? "步行" : summary.toString();
        return p;
    }

    private static Seg buildRideSeg(Line ln) {
        Seg seg = new Seg();
        seg.transit = true;
        seg.vehicle = ln.vehicle == null ? "" : ln.vehicle;
        seg.lineTitle = ln.title == null ? "" : ln.title;
        seg.stationCount = ln.stationCount;
        seg.lineDistanceM = ln.distance;
        seg.lineDurationMin = ln.duration;
        seg.startTime = ln.startTime == null ? "" : ln.startTime;
        seg.endTime = ln.endTime == null ? "" : ln.endTime;
        if (ln.geton != null) {
            seg.getOnName = ln.geton.title == null ? "" : ln.geton.title;
            if (ln.geton.exit != null) {
                seg.getOnExit = ln.geton.exit.title == null ? "" : ln.geton.exit.title;
            }
        }
        if (ln.getoff != null) {
            seg.getOffName = ln.getoff.title == null ? "" : ln.getoff.title;
        }
        // 站点序列 = 上车站 + 途经站（官方 stations 一般不包含上车站）
        if (!seg.getOnName.isEmpty()) {
            seg.stationSeq.add(seg.getOnName);
        }
        if (ln.stations != null) {
            for (Station st : ln.stations) {
                if (st != null && st.title != null) {
                    seg.stationSeq.add(st.title);
                }
            }
        }
        // 下车站若未出现在序列末尾则补上，保证「上车…途经…下车」完整
        if (!seg.getOffName.isEmpty()
                && (seg.stationSeq.isEmpty()
                || !seg.stationSeq.get(seg.stationSeq.size() - 1).equals(seg.getOffName))) {
            seg.stationSeq.add(seg.getOffName);
        }
        return seg;
    }

    /** 把官方逐条步行指引（steps.instruction）合并为一句可读文本。 */
    private static String buildWalkDesc(List<WalkStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (WalkStep w : steps) {
            if (w == null || w.instruction == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(w.instruction);
        }
        return sb.toString();
    }

    private static String readStream(InputStream is) {
        if (is == null) {
            return "";
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            reader.close();
            return body.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 与 PlaceSearch 相同的错误码翻译，便于使用者排查。 */
    private static String explainStatus(int status, String rawMessage) {
        switch (status) {
            case 311:
                return "Key 格式错误或未配置（请到首页长按底部状态行更换 Key）";
            case 190:
                return "Key 无效（请检查是否复制完整、是否已绑定本机签名 SHA1）";
            case 110:
            case 111:
                return "该 Key 未开通「WebServiceAPI」服务或开启了签名校验："
                        + "请到 lbs.qq.com 控制台为 Key 勾选 WebServiceAPI / 关闭签名校验";
            case 121:
                return "Key 今日调用量已达上限，请明天再试或更换 Key";
            case 120:
                return "Key 配额不足";
            default:
                return "接口返回 status=" + status + (rawMessage == null ? "" : " " + rawMessage);
        }
    }

    // ==================== Gson 映射（仅取所需字段） ====================

    static final class TransitResp {
        int status;
        String message;
        TransitResult result;
    }

    static final class TransitResult {
        List<Route> routes;
    }

    static final class Route {
        int distance;
        int duration;
        int price;
        List<Step> steps;
    }

    static final class Step {
        String mode;
        int distance;
        int duration;
        String direction;
        List<WalkStep> steps;
        List<Line> lines;
    }

    static final class WalkStep {
        String instruction;
        @SerializedName("dir_desc")
        String dirDesc;
        int distance;
        @SerializedName("road_name")
        String roadName;
    }

    static final class Line {
        String vehicle;
        String title;
        @SerializedName("station_count")
        int stationCount;
        int distance;
        int duration;
        @SerializedName("start_time")
        String startTime;
        @SerializedName("end_time")
        String endTime;
        GetOn geton;
        GetOff getoff;
        List<Station> stations;
    }

    static final class GetOn {
        String title;
        Exit exit;
    }

    static final class Exit {
        String title;
    }

    static final class GetOff {
        String title;
    }

    static final class Station {
        String title;
    }
}
