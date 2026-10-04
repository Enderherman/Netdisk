package top.enderherman.netdisk.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.enderherman.netdisk.annotation.GlobalInterceptor;
import top.enderherman.netdisk.annotation.VerifyParam;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.service.ZipDownloadService;
import java.io.IOException;

@RestController
@RequestMapping("/file")
public class ZipDownloadController extends ABaseController {
    private final ZipDownloadService zipDownloads;
    public ZipDownloadController(ZipDownloadService zipDownloads) { this.zipDownloads = zipDownloads; }

    @PostMapping("/createZipDownloadUrl")
    @GlobalInterceptor(checkParams = true)
    public BaseResponse<?> create(HttpSession session, @VerifyParam(required = true, max = 11000) String fileIds) {
        return getSuccessResponse(zipDownloads.create(getUserInfoFromSession(session).getUserId(), fileIds));
    }

    @GetMapping("/downloadZip/{code}")
    @GlobalInterceptor(checkLogin = false, checkParams = true)
    public void download(@PathVariable("code") @VerifyParam(required = true, max = 100) String code,
                         HttpServletResponse response) throws IOException {
        zipDownloads.download(code, response);
    }
}
