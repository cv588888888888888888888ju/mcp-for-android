package com.jxev.ai.client;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * TTS 播放器（2026-08-14 新增，配合 Bridge tts_speak 下发）。
 * 要点：
 * - MediaPlayer 必须在主线程（Looper）创建/播放 → 统一用 mainHandler post；
 * - 播放中收到新音频 → 先停止/释放旧的再播新的；
 * - 播放完成/失败通过回调上报（Bridge 端响应在入队播放前已返回 success，
 *   播放结果以 logcat + 回调为准）。
 */
public class TtsPlayer {
    private static final String TAG = "TtsPlayer";
    private static final String CACHE_FILE = "tts_play.wav";

    private static volatile TtsPlayer sInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MediaPlayer currentPlayer;

    public interface PlaybackCallback {
        void onPlaybackResult(boolean success, String message);
    }

    public static TtsPlayer getInstance() {
        if (sInstance == null) {
            synchronized (TtsPlayer.class) {
                if (sInstance == null) {
                    sInstance = new TtsPlayer();
                }
            }
        }
        return sInstance;
    }

    /** 写 wav 到缓存文件后切主线程播放；写入失败立即回调 false。 */
    public void play(Context context, byte[] wavData, PlaybackCallback callback) {
        final File cacheFile = new File(context.getCacheDir(), CACHE_FILE);
        try {
            FileOutputStream fos = new FileOutputStream(cacheFile);
            try {
                fos.write(wavData);
            } finally {
                fos.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "写入缓存 wav 失败", e);
            if (callback != null) {
                callback.onPlaybackResult(false, "写入 wav 缓存失败: " + e.getMessage());
            }
            return;
        }
        final String path = cacheFile.getAbsolutePath();
        mainHandler.post(() -> playOnMainThread(path, callback));
    }

    /**
     * 直接播放本地音频文件（系统 TTS synthesizeToFile 产物可能不是 RIFF wav，
     * MediaPlayer 可自适应格式），切主线程播放。
     */
    public void playFile(Context context, String path, PlaybackCallback callback) {
        mainHandler.post(() -> playOnMainThread(path, callback));
    }

    private void playOnMainThread(String path, PlaybackCallback callback) {
        stopCurrent();
        final MediaPlayer player = new MediaPlayer();
        currentPlayer = player;
        try {
            player.setDataSource(path);
            player.setOnCompletionListener(mp -> {
                releasePlayer(mp);
                Log.i(TAG, "TTS 播放完成: " + path);
                if (callback != null) {
                    callback.onPlaybackResult(true, "TTS 播放完成");
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer 错误 what=" + what + " extra=" + extra);
                releasePlayer(mp);
                if (callback != null) {
                    callback.onPlaybackResult(false,
                            "TTS 播放失败 what=" + what + " extra=" + extra);
                }
                return true;
            });
            player.prepare();
            player.start();
            Log.i(TAG, "TTS 开始播放: " + path);
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "TTS 播放异常", e);
            releasePlayer(player);
            if (callback != null) {
                callback.onPlaybackResult(false, "TTS 播放异常: " + e.getMessage());
            }
        }
    }

    /** 停止并释放当前播放器（新 tts_play 到来时先停旧）。 */
    private void stopCurrent() {
        if (currentPlayer != null) {
            try {
                currentPlayer.stop();
            } catch (IllegalStateException ignored) {
                // 未 start 时 stop 会抛异常，忽略
            }
            releasePlayer(currentPlayer);
            currentPlayer = null;
        }
    }

    private void releasePlayer(MediaPlayer mp) {
        try {
            mp.reset();
        } catch (IllegalStateException ignored) {
        }
        try {
            mp.release();
        } catch (IllegalStateException ignored) {
        }
        if (currentPlayer == mp) {
            currentPlayer = null;
        }
    }
}
