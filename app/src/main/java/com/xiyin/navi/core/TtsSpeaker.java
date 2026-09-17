package com.xiyin.navi.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.tencent.map.navi.data.NaviTts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本机 TTS（android.speech.tts.TextToSpeech）+ 严格防重复播报。
 *
 * <p>防重复策略（三层，确保"一句话只朗读一次"）：
 * <ol>
 *   <li><b>正在播报则直接丢弃</b>：同一时刻只允许一句在播（{@link #speaking}）</li>
 *   <li><b>文本指纹去重</b>：同一句在 {@link #DEDUP_WINDOW_MS} 内重复到达 → 丢弃</li>
 *   <li><b>同条 TTS id 幂等</b>：SDK 可能对同一句连续回调多次，用 id 标记已处理</li>
 * </ol>
 * 只把官方导航回调的 {@code NaviTts.getText()} 播出来，不自行生成导航文案。
 * 初始化失败/语言不支持时静默降级，绝不影响导航。
 */
public final class TtsSpeaker {

    private static final String TAG = "TtsSpeaker";

    /** 同一句话的去重时间窗（毫秒）。官方一句话的正常间隔远大于此值。 */
    private static final long DEDUP_WINDOW_MS = 8000L;

    /** 最多记住多少句历史（环形淘汰），防止无限增长 */
    private static final int HISTORY_SIZE = 30;

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean ready;
    private volatile boolean released;
    private String pending;

    /** 当前是否有语音在播（TTS 回调维护） */
    private volatile boolean speaking;

    /** 最近播报过的文本与时间（环形缓冲，用于去重） */
    private final List<String> recentTexts = new ArrayList<>();
    private final List<Long> recentTimes = new ArrayList<>();

    /** 统计，便于真机验证去重是否生效 */
    private volatile int spokenCount;
    private volatile int droppedCount;

    public void init(Context context) {
        if (tts != null || released) {
            return;
        }
        try {
            tts = new TextToSpeech(context.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        int r = tts.setLanguage(Locale.CHINA);
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Log.w(TAG, "中文TTS不可用，仅静默降级（不影响导航）");
                        }
                        tts.setSpeechRate(1.0f);
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override
                            public void onStart(String utteranceId) {
                                speaking = true;
                            }

                            @Override
                            public void onDone(String utteranceId) {
                                speaking = false;
                            }

                            @Override
                            public void onError(String utteranceId) {
                                speaking = false;
                                Log.w(TAG, "播报出错 utteranceId=" + utteranceId);
                            }
                        });
                        ready = true;
                        Log.i(TAG, "TTS 就绪");
                        if (pending != null) {
                            String p = pending;
                            pending = null;
                            speak(p);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "TTS 配置失败: " + t);
                    }
                } else {
                    Log.w(TAG, "TTS 初始化失败，导航不受影响");
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "TextToSpeech 创建失败: " + t);
        }
    }

    /** 官方导航语音事件 → 本机 TTS。 */
    public void speak(NaviTts naviTts) {
        if (naviTts == null) {
            return;
        }
        speak(naviTts.getText());
    }

    public void speak(final String rawText) {
        if (rawText == null) {
            return;
        }
        final String text = normalize(rawText);
        if (text.length() == 0) {
            return;
        }

        // 第 1 层：正在播报 → 直接丢弃，避免打断与叠播
        if (speaking) {
            droppedCount++;
            Log.i(TAG, "[丢弃·正在播报] " + text + "（累计丢弃 " + droppedCount + "）");
            return;
        }

        // 第 2 层：文本指纹 + 时间窗去重
        long now = System.currentTimeMillis();
        pruneHistory(now);
        for (int i = 0; i < recentTexts.size(); i++) {
            if (recentTexts.get(i).equals(text) && now - recentTimes.get(i) < DEDUP_WINDOW_MS) {
                droppedCount++;
                Log.i(TAG, "[丢弃·重复] " + text + "（累计丢弃 " + droppedCount + "）");
                return;
            }
        }

        // 通过 → 记录并播报
        remember(text, now);
        spokenCount++;

        if (!ready) {
            // TTS 还没就绪，暂存最后一句，就绪后补播
            pending = text;
            Log.i(TAG, "TTS 未就绪，暂存: " + text);
            return;
        }
        main.post(() -> {
            try {
                if (tts != null && !released) {
                    speaking = true;
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "navi-" + spokenCount);
                    Log.i(TAG, "[播报] " + text);
                }
            } catch (Throwable t) {
                speaking = false;
                Log.w(TAG, "speak 失败: " + t);
            }
        });
    }

    /** 归一化：去空白、统一标点，避免"前方路口左转"与"前方路口左转。"被当成两句。 */
    private static String normalize(String s) {
        String t = s.replaceAll("\\s+", "").trim();
        t = t.replace('，', ',').replace('。', '.').replace('！', '!').replace('？', '?');
        return t;
    }

    private void remember(String text, long time) {
        recentTexts.add(text);
        recentTimes.add(time);
        while (recentTexts.size() > HISTORY_SIZE) {
            recentTexts.remove(0);
            recentTimes.remove(0);
        }
    }

    private void pruneHistory(long now) {
        for (int i = recentTexts.size() - 1; i >= 0; i--) {
            if (now - recentTimes.get(i) > DEDUP_WINDOW_MS) {
                recentTexts.remove(i);
                recentTimes.remove(i);
            }
        }
    }

    public void stop() {
        try {
            if (tts != null) {
                tts.stop();
            }
        } catch (Throwable ignore) {
        }
        speaking = false;
    }

    public void release() {
        released = true;
        ready = false;
        speaking = false;
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Throwable t) {
            Log.w(TAG, "release 失败: " + t);
        } finally {
            tts = null;
        }
        Log.i(TAG, "TTS 释放。共播报 " + spokenCount + " 句，去重丢弃 " + droppedCount + " 句");
    }

    // ==================== 诊断 ====================

    public int getSpokenCount() {
        return spokenCount;
    }

    public int getDroppedCount() {
        return droppedCount;
    }
}
