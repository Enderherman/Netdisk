package top.enderherman.netdisk.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

@Service
public class AvatarService {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SIDE = 4096;
    private static final long MAX_PIXELS = 16_000_000L;
    private static final byte[] DEFAULT_AVATAR = defaultAvatar();
    private final AppConfig config;

    public AvatarService(AppConfig config) { this.config = config; }

    public void save(String userId, MultipartFile upload) {
        if (upload == null || upload.isEmpty() || upload.getSize() > MAX_BYTES) {
            throw new BusinessException("请选择不超过 2MB 的 JPG、PNG 或 GIF 图片");
        }
        Path temporary = null;
        try (InputStream input = upload.getInputStream()) {
            byte[] encoded = normalize(input.readNBytes(MAX_BYTES + 1));
            Path target = path(userId);
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "avatar-", ".tmp");
            Files.write(temporary, encoded);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new BusinessException("头像保存失败，请稍后重试");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public byte[] read(String userId) {
        Path target = path(userId);
        if (Files.isRegularFile(target)) {
            try (InputStream input = Files.newInputStream(target)) {
                return normalize(input.readNBytes(MAX_BYTES + 1));
            } catch (IOException | BusinessException ignored) {
                // 旧版本可能留下损坏文件；只返回有效的安全默认图片。
            }
        }
        return DEFAULT_AVATAR.clone();
    }

    private Path path(String userId) {
        if (userId == null || !userId.matches("[A-Za-z0-9]{1,32}")) {
            throw new BusinessException("用户标识不正确");
        }
        return Path.of(config.getProjectFolder()).toAbsolutePath().normalize().resolve("file").resolve("avatar").resolve(userId + ".jpg");
    }

    private byte[] normalize(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new BusinessException("头像不能超过 2MB");
        ImageReader reader = null;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException("头像必须是有效图片");
            reader = readers.next();
            if (!Set.of("jpeg", "jpg", "png", "gif").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                throw new BusinessException("头像仅支持 JPG、PNG 或 GIF");
            }
            reader.setInput(input, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
                throw new BusinessException("头像尺寸不能超过 4096 像素或 1600 万像素");
            }
            BufferedImage source = reader.read(0);
            double scale = Math.min(1.0, 512.0 / Math.max(width, height));
            BufferedImage output = new BufferedImage(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = output.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, output.getWidth(), output.getHeight(), null);
            graphics.dispose();
            return jpeg(output);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof BusinessException business) throw business;
            throw new BusinessException("头像图片损坏或格式不支持");
        } finally {
            if (reader != null) reader.dispose();
        }
    }

    private static byte[] defaultAvatar() {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(240, 240, 240));
        graphics.fillRect(0, 0, 128, 128);
        graphics.setColor(new Color(140, 140, 140));
        graphics.fillOval(44, 25, 40, 40);
        graphics.fillRoundRect(27, 73, 74, 65, 50, 50);
        graphics.dispose();
        return jpeg(image);
    }

    private static byte[] jpeg(BufferedImage image) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "jpg", bytes)) throw new IllegalStateException("JPEG 编码器不可用");
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
