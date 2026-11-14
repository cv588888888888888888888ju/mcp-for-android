package com.jxev.ai.client;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手机端 TTS（2026-08-14 新增，配合 ScriptEngine 的 speak 步骤）。
 *
 * 策略（任务书 CODEX_TASK_phone_gateway）：
 * 1. 优先 SiliconFlow API（参数与 ECS tts_service.py 对齐），key 从 assets/tts_key.txt 读；
 * 2. 网络失败 / 无 key / 返回非 WAV → 回退 Android 系统 TTS（中文 locale）；
 * 3. 拿到 wav → 复用 {@link TtsPlayer}（MediaPlayer）播放。
 *
 * 线程：本类内部单线程 executor 执行网络/合成，播放统一切主线程（TtsPlayer 负责）。
 */
public class TtsEngine {
    private static final String TAG = "TtsEngine";

    private static final String SF_ENDPOINT = "https://api.siliconflow.cn/v1/audio/speech";
    private static final String SF_MODEL = "FunAudioLLM/CosyVoice2-0.5B";
    private static final String SF_VOICE = "FunAudioLLM/CosyVoice2-0.5B:alex";
    private static final String ASSET_TTS_KEY = "tts_key.txt";

    private static volatile TtsEngine instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface SpeakCallback {
        void onResult(boolean success, String message);
    }

    public static TtsEngine getInstance() {
        if (instance == null) {
            synchronized (TtsEngine.class) {
                if (instance == null) {
                    instance = new TtsEngine();
                }
            }
        }
        return instance;
    }

    /** 异步 speak：任意线程可调，结果经回调返回（播放已入队即算成功）。 */
    public void speak(Context context, String text, SpeakCallback callback) {
        executor.execute(() -> {
            try {
                String key = readAssetKey(context);
                if (key != null && !key.trim().isEmpty()) {
                    try {
                        byte[] wav = sfGenerate(key.trim(), text);
                        TtsPlayer.getInstance().play(context, wav, (success, msg) -> {
                            if (!success) {
                                Log.w(TAG, "SF wav 播放失败（回退不适用）: " + msg);
                            }
                        });
                        fire(callback, true,
                                "SiliconFlow TTS 已开始播放（wav " + wav.length + "B）");
                        return;
                    } catch (Exception e) {
                        Log.w(TAG, "SiliconFlow TTS 失败，回退系统 TTS: " + e.getMessage());
                    }
                } else {
                    Log.w(TAG, "assets/tts_key.txt 缺失或为空，回退系统 TTS");
                }
                systemTts(context, text, callback);
            } catch (Exception e) {
                Log.e(TAG, "TTS 异常", e);
                fire(callback, false, "TTS 异常: " + e.getMessage());
            }
        });
    }

    // ================== SiliconFlow ==================

    /** 调 SF API 生成 wav（阻塞网络调用，必须在后台线程）。 */
    private static byte[] sfGenerate(String apiKey, String text) throws Exception {
        URL url = new URL(SF_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            JSONObject payload = new JSONObject();
            payload.put("model", SF_MODEL);
            payload.put("input", text);
            payload.put("voice", SF_VOICE);
            payload.put("response_format", "wav");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

            OutputStream os = conn.getOutputStream();
            try {
                os.write(body);
                os.flush();
            } finally {
                try { os.close(); } catch (Exception ignored) {}
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String errBody = "";
                try (InputStream es = conn.getErrorStream()) {
                    if (es != null) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = es.read(buf)) > 0) {
                            bos.write(buf, 0, n);
                        }
                        errBody = bos.toString("UTF-8");
                    }
                }
                throw new RuntimeException("SF TTS HTTP " + code + ": "
                        + (errBody.length() > 300 ? errBody.substring(0, 300) : errBody));
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream is = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            }
            byte[] wav = bos.toByteArray();
            if (wav.length < 4 || wav[0] != 'R' || wav[1] != 'I'
                    || wav[2] != 'F' || wav[3] != 'F') {
                throw new RuntimeException("SF TTS 返回内容不是 WAV（缺 RIFF 头）");
            }
            return wav;
        } finally {
            conn.disconnect();
        }
    }

    // ================== 系统 TTS 回退 ==================

    /** 主线程初始化 TextToSpeech，synthesizeToFile 后交给 TtsPlayer 播放。 */
    private void systemTts(Context context, final String text, final SpeakCallback callback) {
        mainHandler.post(() -> {
            final AtomicBoolean done = new AtomicBoolean(false);
            // 数组 holder 绕过 lambda 的 definite-assignment 限制（回调可能同步触发）
            final TextToSpeech[] ttsHolder = new TextToSpeech[1];
            ttsHolder[0] = new TextToSpeech(context.getApplicationContext(), status -> {
                final TextToSpeech tts = ttsHolder[0];
                if (status != TextToSpeech.SUCCESS) {
                    tts.shutdown();
                    fire(callback, false, "系统 TTS 初始化失败 status=" + status);
                    return;
                }
                int langRes = tts.setLanguage(Locale.CHINESE);
                if (langRes == TextToSpeech.LANG_MISSING_DATA
                        || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                final File outFile = new File(context.getCacheDir(),
                        "tts_system_" + System.currentTimeMillis() + ".wav");
                final String utteranceId = "speak_" + System.currentTimeMillis();
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        if (!done.compareAndSet(false, true)) {
                            return;
                        }
                        TtsPlayer.getInstance().playFile(context,
                                outFile.getAbsolutePath(), (s, m) -> {
                                    if (!s) {
                                        Log.w(TAG, "系统 TTS 音频播放失败: " + m);
                                    }
                                });
                        fire(callback, true, "系统 TTS 已开始播放（文件 "
                                + outFile.getName() + "）");
                        tts.shutdown();
                    }

                    @Override
                    public void onError(String utteranceId) {
                        if (!done.compareAndSet(false, true)) {
                            return;
                        }
                        fire(callback, false, "系统 TTS 合成失败");
                        tts.shutdown();
                    }
                });
                Bundle params = new Bundle();
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                int res = tts.synthesizeToFile(text, params, outFile, utteranceId);
                if (res != TextToSpeech.SUCCESS && done.compareAndSet(false, true)) {
                    fire(callback, false, "系统 TTS synthesizeToFile 失败 res=" + res);
                    tts.shutdown();
                }
            });
        });
    }

    // ================== 工具 ==================

    private static String readAssetKey(Context context) {
        try (InputStream is = context.getAssets().open(ASSET_TTS_KEY)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8").trim();
        } catch (Exception e) {
            Log.w(TAG, "读取 assets/" + ASSET_TTS_KEY + " 失败: " + e.getMessage());
            return null;
        }
    }

    private static void fire(SpeakCallback callback, boolean success, String message) {
        if (callback != null) {
            callback.onResult(success, message);
        }
    }
}
