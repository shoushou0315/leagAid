package com.example.demo.ai;

import com.example.demo.service.QwenVisionService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 屏幕海克斯识别工具：AI 需要看当前屏幕三个海克斯选项时调用。
 */
@Component
public class HexRecognizeTool {

    private final QwenVisionService visionService;

    public HexRecognizeTool(QwenVisionService visionService) {
        this.visionService = visionService;
    }

    @Tool("获取当前屏幕上三个海克斯强化选项的名称。用户问'选哪个海克斯/选什么强化/再看一下选哪个/重新识别'等需要看屏幕海克斯的问题时，先调用本工具")
    public String recognizeHex(@P("无需参数") String unused) {
        try {
            List<String> options = visionService.recognizeHexOptions();
            return options.isEmpty()
                    ? "未能识别屏幕海克斯，请确认海克斯选择界面可见"
                    : "屏幕上海克斯：" + String.join(" / ", options);
        } catch (Exception e) {
            // 降级：截图/视觉 API 失败时不抛异常，提示用户重试
            return "海克斯识别失败（" + e.getMessage() + "），请确认游戏窗口可见后重试。";
        }
    }
}
