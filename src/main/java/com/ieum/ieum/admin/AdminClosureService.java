package com.ieum.ieum.admin;

import com.ieum.ieum.admin.AdminDtos.StoreChanges;
import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.license.LicenseImportService;
import com.ieum.ieum.license.LicenseKinds;
import com.ieum.ieum.license.StoreLicenseCheck;
import com.ieum.ieum.license.StoreLicenseCheck.Result;
import com.ieum.ieum.license.StoreLicenseCheck.Review;
import com.ieum.ieum.license.StoreLicenseCheckRepository;
import com.ieum.ieum.store.InfoStatus;
import com.ieum.ieum.store.Store;
import com.ieum.ieum.store.StoreRepository;
import com.ieum.ieum.user.User;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 폐업 의심 가게 확인 (관리자, DATA-02)
 *   CLOSE   : 폐업 처리 (가게를 '폐업'으로 바꿔 지도에서 숨김, 수정 기록 남음)
 *   DISMISS : 영업 중 맞음 (같은 인허가가 같은 상태로 다시 나와도 다시 올리지 않음)
 *   RESET   : 처리 되돌리기 → 확인 대기 (폐업 처리한 가게를 다시 열려면 제보 처리 화면에서 직접 수정)
 */
@Service
@RequiredArgsConstructor
public class AdminClosureService {

    public static final int PAGE_SIZE = 20;
    public static final int MAX_FILES = 40;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final StoreLicenseCheckRepository checkRepository;
    private final StoreRepository storeRepository;
    private final AdminReportService reportService;
    private final LicenseImportService importService;
    private final LicenseKinds kinds;

    public enum Action { CLOSE, DISMISS, RESET }

    public record ActionRequest(@NotNull(message = "처리 방법을 골라 주세요.") Action action) {
    }

    public record Row(Long storeId, String storeName, String category, String roadAddress, String infoStatus,
                      String result, String licenseKind, String licenseName, String licenseAddress, String licenseStatus,
                      LocalDate closedOn, String matchNote, LocalDateTime checkedAt, String review, LocalDateTime reviewedAt) {
    }

    public record RowPage(List<Row> items, int page, boolean hasNext, long total, Map<Review, Long> counts) {
    }

    public record Summary(long targetStores, long checkedStores, Map<Result, Long> results, Map<Review, Long> reviews,
                          LocalDateTime lastCheckedAt, List<String> kinds, LicenseImportService.JobView job) {
    }

    @Transactional(readOnly = true)
    public RowPage list(Review review, int page) {
        Page<StoreLicenseCheck> rows = checkRepository.findByReview(review, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
        Map<Long, Store> stores = storeRepository.findAllById(rows.getContent().stream().map(StoreLicenseCheck::getStoreId).toList())
                .stream().collect(Collectors.toMap(Store::getId, Function.identity()));
        return new RowPage(rows.getContent().stream().map(c -> row(c, stores.get(c.getStoreId()))).toList(),
                rows.getNumber(), rows.hasNext(), rows.getTotalElements(), reviewCounts());
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        Map<Result, Long> results = new EnumMap<>(Result.class);
        for (Result r : Result.values()) results.put(r, 0L);
        for (Object[] o : checkRepository.countByResult()) results.put((Result) o[0], (Long) o[1]);
        long checked = results.values().stream().mapToLong(Long::longValue).sum();
        return new Summary(importService.countTargets(), checked, results, reviewCounts(), checkRepository.lastCheckedAt(),
                kinds.getKinds().stream().map(LicenseKinds.Kind::getLabel).toList(), importService.current());
    }

    private Map<Review, Long> reviewCounts() {
        Map<Review, Long> counts = new EnumMap<>(Review.class);
        for (Review r : Review.values()) counts.put(r, 0L);
        for (Object[] o : checkRepository.countByReview()) counts.put((Review) o[0], (Long) o[1]);
        return counts;
    }

    private Row row(StoreLicenseCheck c, Store s) {
        String kindLabel = kinds.getKinds().stream().filter(k -> k.getId().equals(c.getLicenseKind()))
                .map(LicenseKinds.Kind::getLabel).findFirst().orElse(c.getLicenseKind());
        return new Row(c.getStoreId(), s == null ? null : s.getName(), s == null ? null : s.getCategory(),
                s == null ? null : s.getRoadAddress(), s == null ? null : s.getInfoStatus().name(),
                c.getResult().name(), kindLabel, c.getLicenseName(), c.getLicenseAddress(), c.getLicenseStatus(),
                c.getClosedOn(), c.getMatchNote(), c.getCheckedAt(), c.getReview().name(), c.getReviewedAt());
    }

    @Transactional
    public Row act(User admin, Long storeId, ActionRequest request) {
        StoreLicenseCheck c = checkRepository.findForUpdate(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLOSURE_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(SEOUL);
        switch (request.action()) {
            case CLOSE -> {
                if (c.getReview() != Review.PENDING) throw new BusinessException(ErrorCode.CLOSURE_ALREADY_REVIEWED);
                // 제보 처리 화면의 '직접 수정'과 같은 길로 폐업 처리 (가게 잠금, 수정 기록). 같은 트랜잭션 안에서 실행됨
                reportService.edit(admin, storeId, new StoreChanges(InfoStatus.CLOSED, null, null, null));
                c.review(Review.CLOSED, admin.getId(), now);
            }
            case DISMISS -> {
                if (c.getReview() != Review.PENDING) throw new BusinessException(ErrorCode.CLOSURE_ALREADY_REVIEWED);
                c.review(Review.DISMISSED, admin.getId(), now);
            }
            case RESET -> {
                if (c.getReview() != Review.CLOSED && c.getReview() != Review.DISMISSED) {
                    throw new BusinessException(ErrorCode.CLOSURE_ALREADY_REVIEWED);
                }
                c.review(Review.PENDING, admin.getId(), now);
            }
        }
        return row(c, storeRepository.findById(storeId).orElse(null));
    }

    /** 인허가 파일 올리기 → 따로 돌면서 확인 (진행 상황은 summary.job) */
    public LicenseImportService.JobView importFiles(List<MultipartFile> files) {
        List<MultipartFile> real = files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (real.isEmpty()) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("인허가 CSV 파일을 골라 주세요."));
        if (real.size() > MAX_FILES) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("파일은 한 번에 " + MAX_FILES + "개까지 올릴 수 있어요."));
        LicenseImportService.JobView job = importService.start(real);
        if (job == null) throw new BusinessException(ErrorCode.LICENSE_CHECK_RUNNING);
        return job;
    }
}
