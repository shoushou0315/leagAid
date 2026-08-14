package com.example.demo.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 闃块噷浜戠櫨鐐?embedding锛坬wen text-embedding-v4锛? *
 * OpenAI 鍏煎绔偣锛歅OST {baseUrl}/embeddings
 * 杩斿洖 data[].embedding锛?024 缁达級銆? */
public class QwenEmbeddingModel implements EmbeddingModel {

    private final String apiKey;
    private final String modelName;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public QwenEmbeddingModel(String apiKey, String modelName, String baseUrl) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public Response<List<Embedding>> embedAll(List<dev.langchain4j.data.segment.TextSegment> segments) {
        List<String> texts = segments.stream()
                .map(dev.langchain4j.data.segment.TextSegment::text)
                .toList();
        return embedTexts(texts);
    }

    private Response<List<Embedding>> embedTexts(List<String> texts) {
        try {
            String body = mapper.createObjectNode()
                    .put("model", modelName)
                    .putPOJO("input", texts)
                    .toString();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());

            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                throw new RuntimeException("Qwen embedding failed: " + response.body());
            }

            List<Embedding> embeddings = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode emb = item.path("embedding");
                float[] floats = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    floats[i] = (float) emb.get(i).asDouble();
                }
                embeddings.add(Embedding.from(floats));
            }
            return Response.from(embeddings);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
