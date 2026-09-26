package com.xiyin.navi.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.tencent.map.navi.data.NaviTts;

import java.util.Locale;

/**
 * 本机 TTS 转发器（android.speech.tts.TextToSpeech）。
 *
 * <p><b>设计原则：官方说什么，就播什么；同一句只播一次，文本变了才播。</b>
 * 官方导航回调 {@code onVoiceBroadcast(NaviTts)} 交给我们的 {@code NaviTts}，
 * 这里把 {@code naviTts.getText()} 交给系统 TTS 朗读。
 *
 * <p><b>去重（文本级）：</b>官方状态机在 GPS 弱信号 / 卡路口时会反复下发<b>同一句</b>
 * 文本，纯转发会把每句都播出来 → 一直重复。因此记录「最近一次朗读的文本」，
 * 相同文本只播一次，文本变化（新指令）才播。
 *
 * <p>为什么是「文本级」而不是旧版三层去重（正在播报丢弃 / 8s 指纹 / id 幂等）：
 * <ul>
 *   <li>「正在播报就丢弃」会在路口连播时吞掉下一句（漏播）——已证伪。</li>
 *   <li>文本级去重只挡「同一句反复下发」，不挡「不同的新指令」，不漏播。</li>
 * </ul>
 *
 * <p>保留的两处「必要处理」（非业务逻辑，是 TextToSpeech 的固有约束）：
 * <ol>
 *   <li>TextToSpeech 初始化是异步的，就绪前把最后一句暂存到 {@link #pending}，
 *       避免丢掉导航开始的第一句。</li>
 *   <li>用 {@link TextToSpeech#QUEUE_FLUSH}：新指令到来时打断旧指令，
 *       保证「永远播最新的一句」（导航语义上最新的指令优先级最高）。</li>
 * </ol>
 * 初始化失败 / 语言不支持时静默降级，绝不影响导航。
 */
public final class TtsSpeaker {

    private static final String TAG = "TtsSpeaker";

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean ready;
    private volatile boolean released;
    /** TTS 未就绪时暂存的最后一句（就绪后补播） */
    private String pending;
    /** 最近一次决定朗读的文本（文本级去重：相同文本只播一次） */
    private String lastSpoken;

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

    /** 官方导航语音事件 → 本机 TTS（文本级去重：同一句只播一次）。 */
    public void speak(NaviTts naviTts) {
        if (naviTts == null) {
            return;
        }
        speak(naviTts.getText());
    }

    /** 转发 + 文本级去重：同一句只播一次，文本变了才播。 */
    public void speak(final String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        if (!ready) {
            // TTS 还没就绪，暂存最后一句，就绪后补播（同文本覆盖无副作用）
            pending = text;
            Log.i(TAG, "TTS 未就绪，暂存: " + text);
            return;
        }
        // 文本级去重：同一句只播一次，文本变化才播
        if (text.equals(lastSpoken)) {
            return;
        }
        lastSpoken = text;
        main.post(() -> {
            try {
                if (tts != null && !released) {
                    // QUEUE_FLUSH：新句打断旧句，保证永远播最新指令
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
                    Log.i(TAG, "[播报] " + text);
                }
            } catch (Throwable t) {
                Log.w(TAG, "speak 失败: " + t);
            }
        });
    }

    public void stop() {
        try {
            if (tts != null) {
                tts.stop();
            }
        } catch (Throwable t) {
            Log.w(TAG, "stop 失败: " + t);
        }
    }

    public void release() {
        released = true;
        ready = false;
        pending = null;
        lastSpoken = null;
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
        Log.i(TAG, "TTS 释放");
    }
}
