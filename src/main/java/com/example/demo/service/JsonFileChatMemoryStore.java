package com.example.demo.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 鍩轰簬 JSON 鏂囦欢鐨勬寔涔呭寲璁板繂锛堟寜 sessionId 涓€涓枃浠讹紝璺ㄩ噸鍚繚瀛橈級
 *
 * 浣跨敤 LangChain4j 瀹樻柟 ChatMessageSerializer 瀹屾暣搴忓垪鍖栵紝
 * 淇濈暀鎵€鏈夋秷鎭被鍨嬶紙鍚?tool_calls / 宸ュ叿缁撴灉锛夛紝
 * 瑙ｅ喅"宸ュ叿娑堟伅琚檷绾т负鏂囨湰瀵艰嚧姝诲惊鐜?鐨勫巻鍙?bug銆? */
public class JsonFileChatMemoryStore implements ChatMemoryStore {

    private final Path dir;

    public JsonFileChatMemoryStore(Path dir) {
        this.dir = dir;
        dir.toFile().mkdirs();
    }

    private File file(Object memoryId) {
        return dir.resolve(memoryId + ".json").toFile();
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        File f = file(memoryId);
        if (!f.exists()) return List.of();
        try {
            String json = Files.readString(f.toPath());
            if (json.isBlank()) return List.of();
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (IOException e) {
            return List.of();
        } catch (Exception e) {
            // 鍙嶅簭鍒楀寲澶辫触锛堟棫鏍煎紡锛夛紝娓呯┖閬垮厤宕╂簝
            System.out.println(">>> [璁板繂] " + memoryId + " 鍙嶅簭鍒楀寲澶辫触: " + e.getMessage());
            return List.of();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        try {
            String json = ChatMessageSerializer.messagesToJson(messages);
            Files.writeString(file(memoryId).toPath(), json);
        } catch (IOException e) {
            System.out.println(">>> [璁板繂] " + memoryId + " 淇濆瓨澶辫触: " + e.getMessage());
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        file(memoryId).delete();
    }
}
