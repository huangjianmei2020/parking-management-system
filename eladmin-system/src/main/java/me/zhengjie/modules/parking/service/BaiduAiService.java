package me.zhengjie.modules.parking.service;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Base64;

/**
 * 百度AI车牌识别服务
 */
@Slf4j
@Service
public class BaiduAiService {

    private static final String API_KEY = "CZ51o7G6A1bmzsTgfSWL1AzB";
    private static final String SECRET_KEY = "NZyhvR05Q4ZS6UGGqM8p8q6vvieQ6F4m";
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String LICENSE_PLATE_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/license_plate";

    /**
     * 识别车牌号
     * @param image 上传的图片文件
     * @return 识别的车牌号
     */
    public String recognizePlate(MultipartFile image) {
        try {
            // 1. 校验图片是否为空
            if (image == null || image.isEmpty()) {
                throw new IllegalArgumentException("图片不能为空");
            }

            // 2. 校验图片格式
            String contentType = image.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                throw new IllegalArgumentException("请上传图片文件");
            }

            // 3. 获取access_token
            String accessToken = getAccessToken();

            // 4. 将图片转为Base64
            byte[] imageBytes = image.getBytes();
            String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);

            // 5. 调用百度AI车牌识别API
            String result = httpPost(LICENSE_PLATE_URL, accessToken, imageBase64);

            log.info("百度AI API原始返回结果：{}", result);

            // 6. 解析返回结果
            JSONObject jsonObject = new JSONObject(result);

            // 检查是否有错误
            if (jsonObject.has("error_code")) {
                String errorMsg = jsonObject.getString("error_msg");
                log.error("百度AI车牌识别API返回错误：{}", errorMsg);
                throw new RuntimeException("车牌识别失败：" + errorMsg);
            }

            // 解析车牌号
            JSONObject wordsResult = jsonObject.getJSONObject("words_result");
            String plateNumber = wordsResult.getString("number");

            if (plateNumber == null || plateNumber.isEmpty()) {
                throw new RuntimeException("未能识别出车牌号，请确保图片清晰");
            }

            log.info("AI车牌识别成功：{}", plateNumber);
            return plateNumber;

        } catch (IllegalArgumentException e) {
            log.error("参数校验失败：{}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("AI车牌识别失败", e);
            throw new RuntimeException("车牌识别失败，请手动输入车牌号");
        }
    }

    /**
     * 获取百度AI的access_token
     */
    private String getAccessToken() throws Exception {
        String url = TOKEN_URL + "?grant_type=client_credentials" +
                "&client_id=" + API_KEY +
                "&client_secret=" + SECRET_KEY;

        URL realUrl = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) realUrl.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream()));
        String line;
        StringBuilder response = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        connection.disconnect();

        JSONObject jsonObject = new JSONObject(response.toString());

        // 检查是否有错误
        if (jsonObject.has("error")) {
            String errorMsg = jsonObject.getString("error_description");
            log.error("获取access_token失败：{}", errorMsg);
            throw new RuntimeException("获取access_token失败：" + errorMsg);
        }

        return jsonObject.getString("access_token");
    }

    /**
     * 发送POST请求调用百度AI API
     */
    private String httpPost(String url, String accessToken, String imageBase64) throws Exception {
        String fullUrl = url + "?access_token=" + accessToken;

        URL realUrl = new URL(fullUrl);
        HttpURLConnection connection = (HttpURLConnection) realUrl.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        // 构建请求参数
        String params = "image=" + URLEncoder.encode(imageBase64, "UTF-8");

        // 发送请求
        OutputStream os = connection.getOutputStream();
        os.write(params.getBytes());
        os.flush();
        os.close();

        // 读取响应
        int responseCode = connection.getResponseCode();
        BufferedReader reader;
        if (responseCode == 200) {
            reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()));
        } else {
            reader = new BufferedReader(
                    new InputStreamReader(connection.getErrorStream()));
        }

        String line;
        StringBuilder response = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        connection.disconnect();

        return response.toString();
    }
}