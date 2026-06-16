package me.zhengjie.modules.parking.utils;

import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.Base64;

/**
 * Base64字符串转MultipartFile工具类
 */
public class Base64ToMultipartFileConverter {

    /**
     * 将Base64字符串转换为MultipartFile
     * @param imageBase64 Base64编码的图片字符串（可以包含data:image/xxx;base64,前缀）
     * @return MultipartFile对象
     */
    public static MultipartFile convert(String imageBase64) {
        try {
            // 1. 去除Base64前缀（如果有的话）
            String base64Data = imageBase64;
            if (base64Data.contains(",")) {
                base64Data = base64Data.split(",")[1];
            }

            // 2. 解码Base64字符串为字节数组
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);

            // 3. 创建临时文件
            String fileName = "upload_" + System.currentTimeMillis() + ".jpg";
            File tempFile = File.createTempFile(fileName, ".jpg");
            tempFile.deleteOnExit(); // 程序退出时自动删除

            // 4. 写入字节数据到临时文件
            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(imageBytes);
            fos.flush();
            fos.close();

            // 5. 返回MultipartFile对象
            return new CustomMultipartFile(tempFile, fileName, "image/jpeg");

        } catch (Exception e) {
            throw new RuntimeException("Base64转MultipartFile失败", e);
        }
    }

    /**
     * 自定义MultipartFile实现类
     */
    private static class CustomMultipartFile implements MultipartFile {

        private final File file;
        private final String originalFilename;
        private final String contentType;

        public CustomMultipartFile(File file, String originalFilename, String contentType) {
            this.file = file;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return file.length() == 0;
        }

        @Override
        public long getSize() {
            return file.length();
        }

        @Override
        public byte[] getBytes() throws IOException {
            return readFileToBytes(file);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new FileInputStream(file);
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            FileInputStream fis = new FileInputStream(file);
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buffer = new byte[1024];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            fis.close();
            fos.close();
        }

        /**
         * 读取文件为字节数组
         */
        private byte[] readFileToBytes(File file) throws IOException {
            FileInputStream fis = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                bos.write(buffer, 0, len);
            }
            fis.close();
            bos.close();
            return bos.toByteArray();
        }
    }
}