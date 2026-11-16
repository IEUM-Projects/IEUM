package com.ieum.ieum.admin;

import com.ieum.ieum.admin.AdminClosureService.ActionRequest;
import com.ieum.ieum.admin.AdminClosureService.Row;
import com.ieum.ieum.admin.AdminClosureService.RowPage;
import com.ieum.ieum.admin.AdminClosureService.Summary;
import com.ieum.ieum.global.security.AuthUser;
import com.ieum.ieum.license.LicenseImportService.JobView;
import com.ieum.ieum.license.StoreLicenseCheck.Review;
import com.ieum.ieum.theme.ThemeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 폐업 의심 가게 (관리자, DATA-02)
 *
 * GET  /api/admin/closures/summary                확인 현황 (확인한 가게 수, 결과별 수, 마지막 확인 시각)
 * GET  /api/admin/closures?review=PENDING&page=0  처리 상태별 목록 (20곳씩)
 * POST /api/admin/closures/{storeId}              {"action":"CLOSE"} / {"action":"DISMISS"} / {"action":"RESET"}
 * POST /api/admin/closures/import                 인허가 CSV 파일 올리기 (multipart, files 여러 개) → 따로 돌며 확인
 *                                                 진행 상황은 summary 의 job
 */
@RestController
@RequestMapping("/api/admin/closures")
@RequiredArgsConstructor
public class AdminClosureController {

    private final AdminGuard guard;
    private final AdminClosureService service;
    private final ThemeService themeService;

    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal AuthUser authUser) {
        guard.requireAdmin(authUser);
        return service.summary();
    }

    @GetMapping
    public RowPage list(@AuthenticationPrincipal AuthUser authUser,
                        @RequestParam(defaultValue = "PENDING") Review review,
                        @RequestParam(defaultValue = "0") int page) {
        guard.requireAdmin(authUser);
        return service.list(review, page);
    }

    @PostMapping("/{storeId}")
    public Row act(@AuthenticationPrincipal AuthUser authUser, @PathVariable Long storeId,
                   @Valid @RequestBody ActionRequest request) {
        Row row = service.act(guard.requireAdmin(authUser), storeId, request);
        themeService.refresh();   // 폐업 처리한 가게는 테마로 걷기에서도 빠지게
        return row;
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobView importFiles(@AuthenticationPrincipal AuthUser authUser,
                               @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        guard.requireAdmin(authUser);
        return service.importFiles(files);
    }
}
