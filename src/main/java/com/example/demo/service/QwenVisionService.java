package com.example.demo.service;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 千问视觉模型：识别屏幕上海克斯 3 选 1 区域。
 *
 * 流程（一键）：模拟 F12 截图（leagAid 落地 PNG）→ 按坐标裁剪 3 条区域
 *   → 送 qwen-vl-max 识别 → 返回海克斯名列表
 */
@Service
public class QwenVisionService {

    private final String apiKey;
    private final String model;
    private final String screenshotDir;

    // 海克斯名字条坐标（来自 leagAid，1920x1080，已实测有效）
    private static final int[] HEX_LEFT_X = {487, 847, 1200};
    private static final int HEX_Y = 433, HEX_W = 240, HEX_H = 30;

    public QwenVisionService(@Value("${qwen.api-key}") String apiKey,
                             @Value("${qwen.vl-model:qwen-vl-max}") String model,
                             @Value("${app.screenshot-dir:C:\\WeGameApps\\英雄联盟\\Game\\Screenshots}") String screenshotDir) {
        this.apiKey = apiKey;
        this.model = model;
        this.screenshotDir = screenshotDir;
    }

    /** 一键识别：模拟 F12 截图 → 裁剪 → 千问视觉 → 海克斯名列表 */
    public List<String> recognizeHexOptions() {
        System.out.println("[识别] recognizeHexOptions 被调用");
        Path screenshot = captureF12();
        if (screenshot == null) {
            System.out.println("[识别] 截图失败，返回空");
            return List.of();
        }
        System.out.println("[识别] 截图成功: " + screenshot.getFileName());
        return recognizeFromImage(screenshot);
    }

    /** 模拟 F12 触发游戏内截图，返回最新 PNG 路径 */
    private Path captureF12() {
        try {
            System.out.println("[截图] 目录=" + screenshotDir);
            File dir = new File(screenshotDir);
            if (!dir.isDirectory()) { System.out.println("[截图] 目录不存在"); return null; }
            // 记录截图前所有 PNG 的最后修改时间上限
            long beforeMax = 0;
            File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
            if (files == null) { System.out.println("[截图] 无 PNG 文件"); return null; }
            for (File f : files) {
                beforeMax = Math.max(beforeMax, f.lastModified());
            }
            System.out.println("[截图] 触发前最大时间戳=" + beforeMax + " 文件数=" + files.length);

            Robot robot;
            try {
                robot = new Robot();
            } catch (Exception e) {
                System.out.println("[截图] new Robot() 失败: " + e);
                return null;
            }
            robot.keyPress(KeyEvent.VK_F12);
            Thread.sleep(100);
            robot.keyRelease(KeyEvent.VK_F12);
            System.out.println("[截图] 已模拟 F12");
            // 等新截图落地（最长 3s）：找 lastModified > beforeMax 的文件
            for (int i = 0; i < 30; i++) {
                Thread.sleep(100);
                File[] after = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
                if (after != null) {
                    for (File f : after) {
                        if (f.lastModified() > beforeMax) {
                            return f.toPath();
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从截图裁剪 3 条海克斯区域，拼一张图送视觉模型识别 */
    private List<String> recognizeFromImage(Path screenshot) {
        try {
            BufferedImage full = ImageIO.read(Files.newInputStream(screenshot));
            if (full == null) return List.of();

            // 适配分辨率：坐标基于 1920x1080，按实际图缩放
            double scale = full.getWidth() / 1920.0;
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int x = (int) (HEX_LEFT_X[i] * scale);
                int y = (int) (HEX_Y * scale);
                int w = (int) (HEX_W * scale);
                int h = (int) (HEX_H * scale);
                x = Math.max(0, Math.min(x, full.getWidth() - 1));
                y = Math.max(0, Math.min(y, full.getHeight() - 1));
                w = Math.min(w, full.getWidth() - x);
                h = Math.min(h, full.getHeight() - y);
                BufferedImage crop = full.getSubimage(x, y, w, h);
                items.add(Map.of("image", "data:image/png;base64," + toBase64(crop)));
            }
            items.add(Map.of("text",
                    "你是英雄联盟海克斯强化识别器。图中有3个海克斯强化选项，从左到右。严格按以下格式输出，只输出3行：\n" +
                    "1. 海克斯名A\n2. 海克斯名B\n3. 海克斯名C\n不要输出任何其他内容、解释或标点。"));

            return callVision(items);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 调 qwen-vl 视觉模型，返回识别文本列表 */
    private List<String> callVision(List<Map<String, Object>> content) {
        try {
            Constants.apiKey = apiKey;
            MultiModalMessage message = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(content)
                    .build();
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .model(model)
                    .message(message)
                    .build();
            MultiModalConversation conv = new MultiModalConversation();
            var result = conv.call(param);
            var contentList = result.getOutput().getChoices().get(0).getMessage().getContent();
            List<String> names = new ArrayList<>();
            if (contentList != null) {
                for (Object o : contentList) {
                    if (o instanceof Map<?, ?> m && m.get("text") != null) {
                        String text = String.valueOf(m.get("text"));
                        for (String line : text.lines().toList()) {
                            String clean = cleanName(line);
                            if (!clean.isEmpty() && names.size() < 3) names.add(clean);
                        }
                    }
                }
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 清洗识别文本：去序号、去标点、去说明，只留海克斯名 */
    private String cleanName(String line) {
        String s = line.trim();
        // 去开头序号：1. / 1、/ 1: / (1) / 一. 等
        int k = 0;
        while (k < s.length() && (Character.isDigit(s.charAt(k)) || "一二三四五六七八九十".indexOf(s.charAt(k)) >= 0)) k++;
        if (k > 0 && k < s.length() && ".、:：)）".indexOf(s.charAt(k)) >= 0) {
            k++;
            while (k < s.length() && Character.isWhitespace(s.charAt(k))) k++;
            s = s.substring(k);
        }
        // 去结尾的句号/顿号/冒号
        int e = s.length();
        while (e > 0 && "。，、；：,.;:".indexOf(s.charAt(e - 1)) >= 0) e--;
        s = s.substring(0, e);
        // 去常见废话前缀：海克斯/强化/选项 | 第X个 | ：/是 | 空白
        for (String p : new String[]{"海克斯", "强化", "选项"}) {
            if (s.startsWith(p)) { s = s.substring(p.length()).trim(); break; }
        }
        if (s.startsWith("第")) {
            int j = 1;
            while (j < s.length() && (Character.isDigit(s.charAt(j)) || "一二三".indexOf(s.charAt(j)) >= 0)) j++;
            if (j < s.length() && s.charAt(j) == '个') s = s.substring(j + 1).trim();
        }
        while (!s.isEmpty() && (s.charAt(0) == '：' || s.charAt(0) == ':' || s.charAt(0) == '是')) s = s.substring(1).trim();
        return s.trim();
    }

    private String toBase64(BufferedImage img) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }
}
