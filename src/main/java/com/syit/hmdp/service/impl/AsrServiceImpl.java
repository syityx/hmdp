package com.syit.hmdp.service.impl;

import com.syit.hmdp.service.IAsrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AsrServiceImpl implements IAsrService {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String asrModel;

    public AsrServiceImpl(@Value("${spring.ai.openai.api-key}") String apiKey,
                           @Value("${siliconflow.asr.model}") String asrModel) {
        this.restTemplate = new RestTemplate();
        this.apiKey = apiKey;
        this.asrModel = asrModel;
    }

    @Override
    public String transcribe(File audioFile) {
        try {
            String boundary = "----" + UUID.randomUUID().toString().replace("-", "");
            byte[] body = buildMultipartBody(boundary, audioFile, asrModel);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary));
            headers.setContentLength(body.length);

            log.info("调用 SenseVoice ASR, file={}, size={}", audioFile.getName(), audioFile.length());
            Map<String, Object> resp = restTemplate.exchange(
                    "https://api.siliconflow.cn/v1/audio/transcriptions",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class).getBody();

            String text = (String) resp.get("text");
            log.info("ASR 转录完成, textLength={}", text != null ? text.length() : 0);
            return text;
        } catch (IOException e) {
            throw new RuntimeException("ASR 调用失败", e);
        }
    }

    private byte[] buildMultipartBody(String boundary, File audioFile, String model) throws IOException {
        byte[] fileBytes = Files.readAllBytes(audioFile.toPath());
        String filename = audioFile.getName();

        StringBuilder sb = new StringBuilder();
        // file part
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"\r\n");
        sb.append("Content-Type: audio/mpeg\r\n\r\n");
        byte[] fileHeader = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // model part
        String modelPart = "\r\n--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
                model + "\r\n--" + boundary + "--\r\n";
        byte[] modelBytes = modelPart.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] result = new byte[fileHeader.length + fileBytes.length + modelBytes.length];
        System.arraycopy(fileHeader, 0, result, 0, fileHeader.length);
        System.arraycopy(fileBytes, 0, result, fileHeader.length, fileBytes.length);
        System.arraycopy(modelBytes, 0, result, fileHeader.length + fileBytes.length, modelBytes.length);
        return result;
    }
}
