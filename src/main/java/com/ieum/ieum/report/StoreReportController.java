package com.ieum.ieum.report;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.global.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 영업 정보 제보 API (로그인 필요)
 *
 * POST /api/stores/{id}/reports   제보 보내기   {"type":"CLOSED","content":"지난달에 문을 닫았어요."}
 * GET  /api/users/me/reports      내 제보 목록 (최근 50건)
 */
@RestController
@RequiredArgsConstructor
public class StoreReportController {

    private final StoreReportService reportService;

    public record CreateRequest(
            @NotNull(message = "어떤 정보가 다른지 골라 주세요.")
            ReportType type,
            @Size(max = 500, message = "자세한 내용은 500자까지 쓸 수 있어요.")
            String content
    ) {
        public CreateRequest {
            // 앞뒤 공백을 지우고, 줄바꿈 말고 보이지 않는 제어 문자는 뺀다. 빈 글은 null
            if (content != null) {
                content = content.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "").trim();
                if (content.isEmpty()) content = null;
            }
        }
    }

    public record Created(Long id, Long storeId, ReportType type, String typeLabel,
                          ReportStatus status, String statusLabel) {
    }

    public record MyReport(Long id, Long storeId, String storeName, ReportType type, String typeLabel,
                           String content, ReportStatus status, String statusLabel, String adminNote,
                           LocalDateTime createdAt) {
    }

    @PostMapping("/api/stores/{storeId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public Created create(@AuthenticationPrincipal AuthUser authUser, @PathVariable Long storeId,
                          @Valid @RequestBody CreateRequest request) {
        StoreReport report;
        try {
            report = reportService.create(authUser.id(), storeId, request.type(), request.content());
        } catch (DataIntegrityViolationException e) {
            // 거의 동시에 두 번 눌러서 앞의 검사를 둘 다 통과한 경우: DB 고유 키가 막아 준다
            throw new BusinessException(ErrorCode.DUPLICATE_REPORT);
        }
        return new Created(report.getId(), report.getStoreId(), report.getType(), report.getType().label(),
                report.getStatus(), report.getStatus().label());
    }

    @GetMapping("/api/users/me/reports")
    public List<MyReport> myReports(@AuthenticationPrincipal AuthUser authUser) {
        return reportService.myReports(authUser.id()).stream().map(m -> {
            StoreReport r = m.report();
            return new MyReport(r.getId(), r.getStoreId(), m.storeName(), r.getType(), r.getType().label(),
                    r.getContent(), r.getStatus(), r.getStatus().label(), r.getAdminNote(), r.getCreatedAt());
        }).toList();
    }
}
