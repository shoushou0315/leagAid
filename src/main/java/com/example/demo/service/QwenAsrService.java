package com.example.demo.service;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.utils.Constants;
import com.example.demo.mapper.HeroMapper;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式语音识别（qwen-audio-3.0-asr-flash-streaming，DashScope WebSocket）
 *
 * 输入：PCM 16kHz 单声道 16bit 的 base64
 * 输出：识别文字（流式中间结果 + 最终结果）
 *
 * 全量热词：英雄官方名 + 海克斯 + 装备名（提升游戏术语识别准确率）
 */
@Service
public class QwenAsrService {

    private final String apiKey;
    private final String model;
    private final String wsUrl;
    private final Map<String, Integer> vocabulary;

    public QwenAsrService(@org.springframework.beans.factory.annotation.Value("${qwen.api-key}") String apiKey,
                          @org.springframework.beans.factory.annotation.Value("${qwen.asr-model:qwen-audio-3.0-asr-flash-streaming}") String model,
                          @org.springframework.beans.factory.annotation.Value("${qwen.asr-url:wss://dashscope.aliyuncs.com/api-ws/v1/inference}") String wsUrl,
                          HeroMapper heroMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.wsUrl = wsUrl;
        this.vocabulary = buildVocabulary(heroMapper);
        System.out.println(">>> [ASR] 加载热词 " + vocabulary.size() + " 个");
    }

    /** 从数据库加载全量热词：英雄官方名 + 海克斯 + 装备 */
    private Map<String, Integer> buildVocabulary(HeroMapper heroMapper) {
        Map<String, Integer> vocab = new HashMap<>();
        try {
            for (String name : heroMapper.findAllHeroNames()) {
                if (name != null && !name.isEmpty()) vocab.put(name.trim(), 5);   // 英雄官方名（提莫）权重高
            }
            for (String name : heroMapper.findAllAugmentNames()) {
                if (name != null && !name.isEmpty()) vocab.put(name.trim(), 4);
            }
            for (String name : heroMapper.findAllItemNames()) {
                if (name != null && !name.isEmpty()) vocab.put(name.trim(), 3);   // 装备权重低
            }
        } catch (Exception e) {
            System.out.println(">>> [ASR] 热词加载失败: " + e.getMessage());
        }
        return vocab;
    }

    /**
     * 识别一段 PCM 音频（base64），返回最终文字。
     * 中间结果通过 callback 流式回调（可为 null）。
     */
    public String recognize(String audioBase64, java.util.function.Consumer<String> onIntermediate) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> finalText = new AtomicReference<>("");

        Constants.baseWebsocketApiUrl = wsUrl;

        RecognitionParam param = RecognitionParam.builder()
                .model(model)
                .apiKey(apiKey)
                .format("pcm")
                .sampleRate(16000)
                .parameter("vocabulary", vocabulary)   // 即时热词
                .build();

        Recognition recognizer = new Recognition();
        ResultCallback<RecognitionResult> callback = new ResultCallback<RecognitionResult>() {
            @Override
            public void onEvent(RecognitionResult result) {
                if (result.isSentenceEnd()) {
                    finalText.set(result.getSentence().getText());
                } else if (onIntermediate != null) {
                    // 流式中间结果（前端实时显示）
                    String t = result.getSentence().getText();
                    if (t != null && !t.isEmpty()) onIntermediate.accept(t);
                }
            }
            @Override
            public void onComplete() {
                latch.countDown();
            }
            @Override
            public void onError(Exception e) {
                System.out.println(">>> [ASR] 识别错误: " + e.getMessage());
                latch.countDown();
            }
        };

        try {
            recognizer.call(param, callback);
            // 流式发送 PCM
            byte[] pcm = Base64.getDecoder().decode(audioBase64);
            for (int i = 0; i < pcm.length; i += 3200) {
                int end = Math.min(i + 3200, pcm.length);
                recognizer.sendAudioFrame(ByteBuffer.wrap(pcm, i, end - i));
                Thread.sleep(100);
            }
            recognizer.stop();
            latch.await(30, TimeUnit.SECONDS);
        } finally {
            try { recognizer.getDuplexApi().close(1000, "bye"); } catch (Exception ignored) { }
        }
        System.out.println(">>> [ASR] 识别结果: " + finalText.get());
        return finalText.get();
    }

    /** 兼容旧调用：只返回最终文字 */
    public String recognize(String audioBase64) throws Exception {
        return recognize(audioBase64, null);
    }

    /**
     * 建立流式识别会话（真流式：逐帧喂音频）。
     * 返回一个 AsrStream 对象，handler 调用 sendFrame() 喂 PCM，end() 结束。
     * 中间/最终结果通过回调返回。
     */
    public AsrStream startStreaming(java.util.function.Consumer<String> onIntermediate,
                                    java.util.function.Consumer<String> onFinal,
                                    java.util.function.Consumer<Exception> onError) throws Exception {
        Constants.baseWebsocketApiUrl = wsUrl;

        RecognitionParam param = RecognitionParam.builder()
                .model(model)
                .apiKey(apiKey)
                .format("pcm")
                .sampleRate(16000)
                .parameter("vocabulary", vocabulary)
                .build();

        Recognition recognizer = new Recognition();
        ResultCallback<RecognitionResult> callback = new ResultCallback<RecognitionResult>() {
            @Override
            public void onEvent(RecognitionResult result) {
                if (result.isSentenceEnd()) {
                    String t = result.getSentence().getText();
                    if (onFinal != null) onFinal.accept(t == null ? "" : t);
                } else if (onIntermediate != null) {
                    String t = result.getSentence().getText();
                    if (t != null && !t.isEmpty()) onIntermediate.accept(t);
                }
            }
            @Override
            public void onComplete() {
                System.out.println(">>> [ASR] 流式识别完成");
            }
            @Override
            public void onError(Exception e) {
                System.out.println(">>> [ASR] 流式识别错误: " + e.getMessage());
                if (onError != null) onError.accept(e);
            }
        };
        recognizer.call(param, callback);

        return new AsrStream(recognizer);
    }

    /** 流式识别会话：逐帧喂音频，end 结束 */
    public static class AsrStream {
        private final Recognition recognizer;
        private volatile boolean ended = false;

        AsrStream(Recognition recognizer) {
            this.recognizer = recognizer;
        }

        /** 喂一帧 PCM 音频 */
        public void sendFrame(byte[] pcm) {
            if (ended) return;
            try {
                recognizer.sendAudioFrame(ByteBuffer.wrap(pcm));
            } catch (Exception e) {
                System.out.println(">>> [ASR] 喂帧失败: " + e.getMessage());
            }
        }

        /** 结束识别 */
        public void end() {
            if (ended) return;
            ended = true;
            // stop() 阻塞等回调，用独立线程避免卡住调用方
            new Thread(() -> {
                try {
                    recognizer.stop();
                } catch (Exception e) {
                    System.out.println(">>> [ASR] stop失败: " + e.getMessage());
                }
                try {
                    recognizer.getDuplexApi().close(1000, "bye");
                } catch (Exception ignored) { }
            }, "asr-end").start();
        }
    }
}
