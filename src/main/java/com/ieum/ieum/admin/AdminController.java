package com.ieum.ieum.admin;

import com.ieum.ieum.admin.AdminDtos.Counts;
import com.ieum.ieum.admin.AdminDtos.PendingPage;
import com.ieum.ieum.admin.AdminDtos.ReviewRequest;
import com.ieum.ieum.admin.AdminDtos.ReviewResult;
import com.ieum.ieum.admin.AdminDtos.StoreChanges;
import com.ieum.ieum.admin.AdminDtos.StoreDetail;
import com.ieum.ieum.global.security.AuthUser;
import com.ieum.ieum.report.ReportStatus;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 API (ADM-01). 관리자(ADMIN)만 쓸 수 있다.
 *
 * GET  /api/admin/reports/counts                    상태별 제보 수
 * GET  /api/admin/reports?status=RECEIVED&page=0    처리 대기: 가게별 묶음 (오래 기다린 가게부터, 20곳씩)
 * GET  /api/admin/reports?status=APPLIED&page=0     처리 내역: 반영됨 / 반려됨 (최근 처리 순, 30건씩)
 * GET  /api/admin/stores/{id}                       가게 정보 + 제보 전체 + 수정 기록
 * POST /api/admin/stores/{id}/review                제보 처리 (반영·반려, 반영할 때 가게 정보 수정)
 *   {"reportIds":[1,2], "decision":"APPLIED", "note":"폐업 확인했어요. 고마워요!",
 *    "changes":{"infoStatus":"CLOSED", "hours":null, "phone":null, "roadAddress":null}}
 * POST /api/admin/stores/{id}/edit                  제보 없이 가게 정보만 고치기 (예: 잘못 폐업 처리한 가게 되돌리기)
 *   {"infoStatus":"VERIFIED"}
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminGuard guard;
    private final AdminReportService service;
    /** 가게를 숨기거나 옮기면 테마로 걷기 계산을 다시 하게 한다 (트랜잭션이 끝난 뒤) */
    private final ThemeService themeService;

    @GetMapping("/reports/counts")
    public Counts counts(@AuthenticationPrincipal AuthUser authUser) {
        guard.requireAdmin(authUser);
        return service.counts();
    }

    @GetMapping(value = "/reports", params = "!status")
    public PendingPage pendingDefault(@AuthenticationPrincipal AuthUser authUser,
                                      @RequestParam(defaultValue = "0") int page) {
        guard.requireAdmin(authUser);
        return service.pending(page);
    }

    @GetMapping(value = "/reports", params = "status")
    public Object reports(@AuthenticationPrincipal AuthUser authUser, @RequestParam ReportStatus status,
                          @RequestParam(defaultValue = "0") int page) {
        guard.requireAdmin(authUser);
        if (status == ReportStatus.RECEIVED) return service.pending(page);
        return service.reviewed(status, page);
    }

    @GetMapping("/stores/{storeId}")
    public StoreDetail store(@AuthenticationPrincipal AuthUser authUser, @PathVariable Long storeId) {
        guard.requireAdmin(authUser);
        return service.store(storeId);
    }

    @PostMapping("/stores/{storeId}/review")
    public ReviewResult review(@AuthenticationPrincipal AuthUser authUser, @PathVariable Long storeId,
                               @Valid @RequestBody ReviewRequest request) {
        ReviewResult result = service.review(guard.requireAdmin(authUser), storeId, request);
        themeService.refresh();
        return result;
    }

    @PostMapping("/stores/{storeId}/edit")
    public ReviewResult edit(@AuthenticationPrincipal AuthUser authUser, @PathVariable Long storeId,
                             @Valid @RequestBody StoreChanges changes) {
        ReviewResult result = service.edit(guard.requireAdmin(authUser), storeId, changes);
        themeService.refresh();
        return result;
    }
}
