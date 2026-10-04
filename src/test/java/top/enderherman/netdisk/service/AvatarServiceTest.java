package top.enderherman.netdisk.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class AvatarServiceTest {
    @TempDir Path storage;

    @Test
    void missingAvatarReturnsValidDefaultWithoutCreatingFiles() throws Exception {
        byte[] bytes = service().read("member");
        assertEquals(128, ImageIO.read(new ByteArrayInputStream(bytes)).getWidth());
        assertEquals(0xff, Byte.toUnsignedInt(bytes[0]));
        assertFalse(Files.exists(storage.resolve("file")));
    }

    @Test
    void imageIsReencodedAndTrailingContentIsDiscarded() throws Exception {
        byte[] png = png(32, 24);
        byte[] tail = "<script>alert('unexpected')</script>".getBytes(StandardCharsets.UTF_8);
        byte[] polyglot = Arrays.copyOf(png, png.length + tail.length);
        System.arraycopy(tail, 0, polyglot, png.length, tail.length);
        service().save("member", new MockMultipartFile("avatar", "fake.html", "text/html", polyglot));
        byte[] saved = Files.readAllBytes(target());
        assertEquals(0xff, Byte.toUnsignedInt(saved[0]));
        assertEquals(0xd8, Byte.toUnsignedInt(saved[1]));
        assertEquals(32, ImageIO.read(new ByteArrayInputStream(saved)).getWidth());
        assertFalse(new String(saved, StandardCharsets.ISO_8859_1).contains("<script>"));
    }

    @Test
    void largeValidImageIsResized() throws Exception {
        service().save("member", upload(png(1024, 256)));
        BufferedImage decoded = ImageIO.read(target().toFile());
        assertEquals(512, decoded.getWidth());
        assertEquals(128, decoded.getHeight());
    }

    @Test
    void malformedAndOversizedUploadsCannotReplaceExistingAvatar() throws Exception {
        service().save("member", upload(png(16, 16)));
        byte[] original = Files.readAllBytes(target());
        assertThrows(BusinessException.class, () -> service().save("member", upload("<html/>".getBytes())));
        assertThrows(BusinessException.class, () -> service().save("member", upload(new byte[2 * 1024 * 1024 + 1])));
        assertThrows(BusinessException.class, () -> service().save("member", null));
        assertArrayEquals(original, Files.readAllBytes(target()));
    }

    @Test
    void enormousDeclaredDimensionsAreRejectedBeforeDecompression() throws Exception {
        byte[] bytes = png(1, 1);
        ByteBuffer.wrap(bytes).putInt(16, 50_000).putInt(20, 50_000);
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
        assertThrows(BusinessException.class, () -> service().save("member", upload(bytes)));
        assertFalse(Files.exists(target()));
    }

    @Test
    void corruptLegacyAvatarFallsBackAndIdentifiersCannotEscapeFolder() throws Exception {
        Files.createDirectories(target().getParent());
        Files.writeString(target(), "<html/>");
        assertNotNull(ImageIO.read(new ByteArrayInputStream(service().read("member"))));
        assertThrows(BusinessException.class, () -> service().read("../outside"));
        assertThrows(BusinessException.class, () -> service().save("../outside", upload(png(1, 1))));
    }

    private Path target() { return storage.resolve("file/avatar/member.jpg"); }
    private AvatarService service() {
        AppConfig config = new AppConfig();
        config.setProjectFolder(storage.toString());
        return new AvatarService(config);
    }
    private MockMultipartFile upload(byte[] bytes) { return new MockMultipartFile("avatar", "avatar.png", "image/png", bytes); }
    public static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }
}
