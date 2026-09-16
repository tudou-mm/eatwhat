package com.eatwhat.controller;

import com.eatwhat.auth.AuthContext;
import com.eatwhat.common.BizException;
import com.eatwhat.common.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传。
 *
 * 之前 media 是前端直接填 URL（`https://picsum.photos/...`），
 * 商户真发菜的时候没法传自己的照片，这一环必须补上。
 *
 * 四道校验，缺一不可：
 * 1. **扩展名白名单** —— 不能只看 Content-Type，那是客户端说了算的。
 *    放开的话有人上传 .html/.jsp 到静态目录，就是一个存储型 XSS。
 * 2. **魔数（文件头）校验** —— 扩展名和 Content-Type 都能被客户端随便改，
 *    只有字节骗不了人。曾经 `.png` 改名 + `image/png` 就能塞任意内容进去。
 * 3. **尺寸上限** —— 图片 5MB、视频 20MB（20MB 是冻结规则里的原文）。
 * 4. **落盘名由服务端生成**（UUID + 白名单后缀），原始文件名一律不用，
 *    防止 `../../etc/passwd` 这类路径穿越。
 *
 * 需要 admin 或 merchant 身份（见 AuthInterceptor）。
 */
@RestController
@RequestMapping("/api")
public class UploadController {

    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "mov", "m4v", "webm");

    // ---- 魔数签名 ----
    private static final byte[] SIG_PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] SIG_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] SIG_GIF = {'G', 'I', 'F', '8'};
    private static final byte[] SIG_RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] SIG_WEBP = {'W', 'E', 'B', 'P'};
    private static final byte[] SIG_EBML = {(byte) 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
    private static final byte[] SIG_FTYP = {'f', 't', 'y', 'p'};

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final String uploadDir;
    private final String publicPath;
    private final long maxImageMb;
    private final long maxVideoMb;

    public UploadController(@Value("${eatwhat.upload.dir:./uploads}") String uploadDir,
                            @Value("${eatwhat.upload.publicPath:/uploads}") String publicPath,
                            @Value("${eatwhat.upload.maxImageMb:5}") long maxImageMb,
                            @Value("${eatwhat.upload.maxVideoMb:20}") long maxVideoMb) {
        this.uploadDir = uploadDir;
        this.publicPath = publicPath;
        this.maxImageMb = maxImageMb;
        this.maxVideoMb = maxVideoMb;
    }

    /** multipart/form-data，字段名固定 file */
    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                         HttpServletRequest req) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "没有收到文件");
        }

        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extOf(original);
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);

        // ---- 1. 判定类型：Content-Type 与扩展名都可疑时直接拒 ----
        boolean looksImage = contentType.startsWith("image/") || IMAGE_EXT.contains(ext);
        boolean looksVideo = contentType.startsWith("video/") || VIDEO_EXT.contains(ext);
        if (!looksImage && !looksVideo) {
            throw new BizException(400, "只支持图片或视频，收到：" + (contentType.isEmpty() ? original : contentType));
        }
        if (looksImage && looksVideo) {
            // 例如 content-type 说 image/png 但后缀是 .mp4 —— 这种矛盾数据不要猜，直接拒
            throw new BizException(400, "文件类型前后矛盾（" + contentType + " vs ." + ext + "）");
        }
        String kind = looksImage ? "image" : "video";
        Set<String> allowed = looksImage ? IMAGE_EXT : VIDEO_EXT;
        if (!allowed.contains(ext)) {
            throw new BizException(400, "不支持的格式 ." + ext + "，允许：" + allowed);
        }

        // ---- 2. 尺寸上限（视频 20MB 是冻结规则） ----
        long limitMb = looksImage ? maxImageMb : maxVideoMb;
        long limitBytes = limitMb * 1024 * 1024;
        if (file.getSize() > limitBytes) {
            throw new BizException(400, String.format("文件太大：%.1fMB，上限 %dMB",
                    file.getSize() / 1024.0 / 1024.0, limitMb));
        }

        // ---- 3. 真实内容校验（魔数）----
        // 扩展名和 Content-Type 都是客户端填的，改个名字就能绕过去。
        // 静态目录是直接吐给浏览器的，不校验内容等于留了一个存储型 XSS 的口子。
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(16);
        }
        String sniffed = sniffKind(head);
        if (sniffed == null) {
            throw new BizException(400, "文件内容不是可识别的图片或视频，后缀可能是伪造的");
        }
        if (!sniffed.equals(kind)) {
            throw new BizException(400, "文件内容与后缀不符：." + ext + " 属于 " + kind
                    + "，但内容看起来是 " + sniffed);
        }

        // ---- 4. 落盘 ----
        String day = LocalDate.now(CN).format(DAY);
        Path base = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path target = base.resolve(day);
        Files.createDirectories(target);

        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dest = target.resolve(filename);
        file.transferTo(dest.toFile());

        String relative = publicPath + "/" + day + "/" + filename;
        // 返回**绝对地址**：前端跑在 5173、后端在 8080，相对路径在页面里是取不到的
        String origin = req.getScheme() + "://" + req.getServerName() + ":" + req.getServerPort();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", origin + relative);
        m.put("relative", relative);
        m.put("name", original);
        m.put("kind", kind);
        m.put("size", file.getSize());
        m.put("sizeText", String.format("%.1fMB", file.getSize() / 1024.0 / 1024.0));
        m.put("uploader", AuthContext.get() == null ? null : AuthContext.get().subject());
        return R.ok(m);
    }

    /**
     * 按文件头识别真实类型，认不出来返回 null。
     * 只认「这个后缀本该是什么」，不猜 —— 猜就会给伪造留缝。
     */
    private static String sniffKind(byte[] h) {
        if (h == null || h.length < 4) return null;
        if (startsWith(h, SIG_PNG) || startsWith(h, SIG_JPEG) || startsWith(h, SIG_GIF)) {
            return "image";
        }
        // WebP: RIFF....WEBP
        if (startsWith(h, SIG_RIFF) && matchAt(h, 8, SIG_WEBP)) return "image";
        // WebM / Matroska: EBML
        if (startsWith(h, SIG_EBML)) return "video";
        // mp4 / mov / m4v：前 4 字节是 box size，第 5-8 字节是 'ftyp'
        if (matchAt(h, 4, SIG_FTYP)) return "video";
        return null;
    }

    private static boolean startsWith(byte[] head, byte[] sig) {
        return matchAt(head, 0, sig);
    }

    private static boolean matchAt(byte[] head, int offset, byte[] sig) {
        if (head.length < offset + sig.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if (head[offset + i] != sig[i]) return false;
        }
        return true;
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
