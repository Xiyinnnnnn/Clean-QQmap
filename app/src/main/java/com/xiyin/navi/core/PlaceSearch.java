package com.xiyin.navi.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 地点搜索：调用腾讯地图官方 WebService 输入提示接口
 * （与官方导航 Demo 的 LocationSearchActivity 用的是同一个官方接口与同一个 Key）。
 * 只做"搜索"，路线规划/导航仍完全由官方导航 SDK 负责。
 */
public final class PlaceSearch {

    private static final String TAG = "PlaceSearch";
    private static final String URL_BASE = "https://apis.map.qq.com/ws/place/v1/suggestion/?";

    public static final class Place {
        public double lat;
        public double lng;
        public String title;
        public String address;
    }

    public interface Callback {
        void onResult(List<Place> places);

        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    public void search(Context context, String keyword, Callback callback) {
        if (keyword == null || keyword.trim().length() == 0) {
            main.post(() -> callback.onResult(new ArrayList<>()));
            return;
        }
        final Context app = context.getApplicationContext();
        final String key = KeyManager.getEffectiveKey(app);
        if (key == null || key.isEmpty()) {
            // 直接拦下，避免发出空 key 请求（服务端会回 status=311，用户看不懂）
            main.post(() -> callback.onError("尚未配置腾讯 Key，请到首页长按底部状态行填写"));
            return;
        }
        new Thread(() -> {
            List<Place> result = new ArrayList<>();
            String error = null;
            HttpURLConnection conn = null;
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(URL_BASE)
                        .append("page_index=1&page_size=20")
                        // 必须走 KeyManager：开源版 Key 由使用者运行时输入，
                        // Manifest 里是空的，直接读 meta-data 会拿到空串 → status=311
                        .append("&key=").append(key)
                        .append("&keyword=").append(URLEncoder.encode(keyword, "UTF-8"));
                conn = (HttpURLConnection) new URL(sb.toString()).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code != 200) {
                    // 读错误流，把服务端真实原因带出来，而不是只报 HTTP 码
                    String detail = readStream(conn.getErrorStream());
                    if (detail.length() > 200) {
                        detail = detail.substring(0, 200);
                    }
                    error = "HTTP " + code + (detail.isEmpty() ? "" : " " + detail);
                    Log.e(TAG, "搜索 HTTP 失败: " + error + " url=" + sb);
                } else {
                    InputStream is = conn.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                    reader.close();
                    String raw = body.toString();
                    SearchResult sr;
                    try {
                        sr = gson.fromJson(raw, SearchResult.class);
                    } catch (Throwable t) {
                        sr = null;
                        Log.e(TAG, "解析异常 raw=" + (raw.length() > 300 ? raw.substring(0, 300) : raw));
                    }
                    if (sr == null) {
                        error = "返回内容无法解析";
                    } else if (sr.status != 0) {
                        // 把常见错误码翻译成可操作的中文提示
                        error = explainStatus(sr.status, sr.message);
                    } else if (sr.data != null) {
                        for (Item it : sr.data) {
                            if (it == null || it.location == null) {
                                continue;
                            }
                            Place p = new Place();
                            p.lat = it.location.lat;
                            p.lng = it.location.lng;
                            p.title = it.title;
                            p.address = it.address;
                            result.add(p);
                        }
                    }
                }
            } catch (Throwable t) {
                error = t.toString();
                Log.e(TAG, "搜索失败: " + t);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final String err = error;
            main.post(() -> {
                if (err != null && result.isEmpty()) {
                    callback.onError(err);
                } else {
                    callback.onResult(result);
                }
            });
        }).start();
    }

    /** 把腾讯 WebService 状态码翻译成用户能照着做的提示。 */
    private static String explainStatus(int status, String rawMessage) {
        String detail = rawMessage == null ? "" : rawMessage;
        switch (status) {
            case 311:
                return "Key 格式错误或未配置（请到首页长按底部状态行更换 Key）";
            case 190:
                return "Key 无效（请检查是否复制完整、是否已绑定本机签名 SHA1）";
            case 110:
            case 111:
                return "该 Key 未开通「WebServiceAPI」服务："
                        + "请到 lbs.qq.com 控制台为 Key 勾选 WebServiceAPI（地图与导航不受影响）";
            case 121:
                return "Key 今日调用量已达上限，请明天再试或更换 Key";
            case 120:
                return "Key 配额不足";
            default:
                return "接口返回 status=" + status + (detail.isEmpty() ? "" : " " + detail);
        }
    }

    private static String readStream(InputStream is) {
        if (is == null) {
            return "";
        }
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    // ==================== 接口结构 ====================

    private static class SearchResult {
        int status;
        String message;
        int count;
        List<Item> data;
    }

    private static class Item {
        String title;
        String address;
        Loc location;
    }

    private static class Loc {
        double lat;
        double lng;
    }
}
