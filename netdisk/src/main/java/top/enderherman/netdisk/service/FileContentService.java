package top.enderherman.netdisk.service;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

/** 已授权内容的安全、定长流式输出，支持音视频和下载续传。 */
@Service
public class FileContentService {
    @Resource private AppConfig appConfig;

    public Path resolve(String relative) {
        try {
            if (relative == null || relative.isBlank() || relative.contains("\\") || relative.contains(":")) fail();
            Path root = Path.of(appConfig.getProjectFolder(), "file").toAbsolutePath().normalize();
            Path input = Path.of(relative);
            Path path = root.resolve(input).normalize();
            if (input.isAbsolute() || path.equals(root) || !path.startsWith(root)
                    || !Files.isRegularFile(path) || !path.toRealPath().startsWith(root.toRealPath())) fail();
            return path;
        } catch (IOException | InvalidPathException ex) {
            throw new BusinessException(ResponseCodeEnum.CODE_404);
        }
    }

    public void requireUsable(FileInfo file) {
        if (file == null || !Integer.valueOf(2).equals(file.getDelFlag())
                || !Integer.valueOf(2).equals(file.getStatus()) || !Integer.valueOf(0).equals(file.getFolderType())) fail();
    }

    public void send(HttpServletRequest request, HttpServletResponse response, FileInfo file, boolean attachment) {
        requireUsable(file);
        sendPath(request, response, file.getFilePath(), file.getFileName(), attachment);
    }

    public void sendPath(HttpServletRequest request, HttpServletResponse response, String relative, String name, boolean attachment) {
        Path path = resolve(relative);
        try (RandomAccessFile data = new RandomAccessFile(path.toFile(), "r")) {
            long size = data.length();
            // 显示名可以更改扩展名，但上传时确定的存储后缀与原始内容不变。
            String extension = relative.contains(".") ? relative.substring(relative.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            String type = switch (extension) {
                case "jpg", "jpeg" -> "image/jpeg";
                case "png" -> "image/png";
                case "gif" -> "image/gif";
                case "webp" -> "image/webp";
                case "avif" -> "image/avif";
                case "pdf" -> "application/pdf";
                case "mp4", "m4v" -> "video/mp4";
                case "webm" -> "video/webm";
                case "mp3" -> "audio/mpeg";
                case "wav" -> "audio/wav";
                case "ogg" -> "audio/ogg";
                case "m4a" -> "audio/mp4";
                case "m3u8" -> "application/vnd.apple.mpegurl";
                case "ts" -> "video/mp2t";
                case "txt", "md", "csv", "json", "xml", "log", "java", "js", "css", "py", "yml", "yaml" -> "text/plain; charset=UTF-8";
                default -> "application/octet-stream";
            };
            response.setContentType(type);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Cache-Control", "private, no-store");
            response.setHeader("Accept-Ranges", "bytes");
            long modified = Files.getLastModifiedTime(path).toMillis();
            String etag = "\"" + Long.toHexString(size) + "-" + Long.toHexString(modified) + "\"";
            response.setHeader("ETag", etag);
            response.setDateHeader("Last-Modified", modified);
            String filename = URLEncoder.encode(name == null ? "download" : name, StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader("Content-Disposition", ((attachment || type.equals("application/octet-stream")) ? "attachment" : "inline")
                    + "; filename=\"download\"; filename*=UTF-8''" + filename);
            long start = 0, end = size - 1;
            String range = request == null || !"GET".equalsIgnoreCase(request.getMethod()) ? null : request.getHeader("Range");
            if (range != null && request.getHeader("If-Range") != null) {
                String condition = request.getHeader("If-Range");
                boolean matches = etag.equals(condition);
                if (!matches && !condition.startsWith("\"") && !condition.startsWith("W/")) {
                    try { matches = request.getDateHeader("If-Range") >= (modified / 1000) * 1000; }
                    catch (IllegalArgumentException ignored) { /* 不匹配时返回整个当前内容。 */ }
                }
                if (!matches) range = null;
            }
            if (range != null) {
                try {
                    if (size == 0 || !range.matches("bytes=[0-9]*-[0-9]*")) throw new IllegalArgumentException();
                    String[] values = range.substring(6).split("-", -1);
                    if (values[0].isEmpty()) {
                        long suffix = Long.parseLong(values[1]);
                        if (suffix <= 0) throw new IllegalArgumentException();
                        start = Math.max(0, size - suffix);
                    } else {
                        start = Long.parseLong(values[0]);
                        if (!values[1].isEmpty()) end = Math.min(end, Long.parseLong(values[1]));
                    }
                    if (start >= size || start > end) throw new IllegalArgumentException();
                } catch (IllegalArgumentException ex) {
                    response.setStatus(416);
                    response.setHeader("Content-Range", "bytes */" + size);
                    response.setContentLengthLong(0);
                    return;
                }
                response.setStatus(206);
                response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }
            long remaining = size == 0 ? 0 : end - start + 1;
            response.setContentLengthLong(remaining);
            if (request != null && "HEAD".equalsIgnoreCase(request.getMethod())) return;
            data.seek(start);
            byte[] buffer = new byte[64 * 1024];
            var output = response.getOutputStream();
            while (remaining > 0) {
                int length = data.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (length < 0) throw new IOException("文件在传输期间被截断");
                output.write(buffer, 0, length);
                remaining -= length;
            }
        } catch (IOException ex) {
            if (!response.isCommitted()) throw new BusinessException(ResponseCodeEnum.CODE_500);
        }
    }

    private void fail() { throw new BusinessException(ResponseCodeEnum.CODE_404); }
}
