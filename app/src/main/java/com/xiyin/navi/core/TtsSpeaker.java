package com.xiyin.navi.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.tencent.map.navi.data.NaviTts;

import java.util.Locale;

/**
 * 本机 TTS「纯转发器」（android.speech.tts.TextToSpeech）。
 *
 * <p><b>设计原则：官方说什么，就播什么。</b>
 * 官方导航回调 {@code onVoiceBroadcast(NaviTts)} 交给我们的 {@code NaviTts}，
 * 这里只把 {@code naviTts.getText()} 原样交给系统 TTS 朗读，
 * <b>不做去重、不做队列管理、不生成/改写任何文案</b>——
 * 语音该不该说、说几遍，完全交给官方 SDK 的语音状态机决定。
 *
 * <p>为什么砍掉旧版三层去重（正在播报丢弃 / 8s 文本指纹 / TTS id 幂等）：
 * <ul>
 *   <li>去重只能「少播」，不能「多播」，它治不了 GPS 弱信号时官方状态机
 *       反复下发同一句导致的重复，反而会在正常连播时把下一句吞掉（漏播）。</li>
 *   <li>「正在播报就丢弃」在路口连续指令（"前方500米右转"→"前方路口右转"）
 *       场景下，前一句没播完就把后一句丢了，是漏播的直接来源。</li>
 *   <li>砍掉后逻辑只剩「转发」，我们自己这一侧引入 bug 的面降到最小。</li>
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

    /** 官方导航语音事件 → 本机 TTS（纯转发，不加工）。 */
    public void speak(NaviTts naviTts) {
        if (naviTts == null) {
            return;
        }
        speak(naviTts.getText());
    }

    /** 纯转发：官方给什么文本就播什么文本。 */
    public void speak(final String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        if (!ready) {
            // TTS 还没就绪，暂存最后一句，就绪后补播
            pending = text;
            Log.i(TAG, "TTS 未就绪，暂存: " + text);
            return;
        }
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
