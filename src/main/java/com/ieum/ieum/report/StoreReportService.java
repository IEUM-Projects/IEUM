package com.ieum.ieum.report;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.store.StoreRepository;
import com.ieum.ieum.user.UserRepository;
import com.ieum.ieum.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 영업 정보 제보 (STO-01)
 *
 * 악용 방지
 * - 같은 회원이 같은 가게에 같은 종류는 하루 한 번 (DB 고유 키로도 한 번 더 막음)
 * - 한 회원이 하루에 보낼 수 있는 제보는 최대 DAILY_LIMIT건
 * - 제보가 쌓여도 가게를 자동으로 숨기지 않는다 (장난 제보로 멀쩡한 가게가 사라지지 않게)
 */
@Service
@RequiredArgsConstructor
public class StoreReportService {

    public static final int DAILY_LIMIT = 20;
    private static final int MY_REPORTS_LIMIT = 50;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final StoreReportRepository reportRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;

    @Transactional
    public StoreReport create(Long userId, Long storeId, ReportType type, String content) {
        userRepository.findById(userId)
                .filter(u -> u.getStatus() != UserStatus.WITHDRAWN)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        storeRepository.findByIdAndVisibleTrue(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        LocalDate today = LocalDate.now(SEOUL);
        if (reportRepository.existsByUserIdAndStoreIdAndTypeAndReportDate(userId, storeId, type, today)) {
            throw new BusinessException(ErrorCode.DUPLICATE_REPORT);
        }
        if (reportRepository.countByUserIdAndReportDate(userId, today) >= DAILY_LIMIT) {
            throw new BusinessException(ErrorCode.TOO_MANY_REPORTS);
        }
        return reportRepository.save(StoreReport.create(storeId, userId, type, content, today));
    }

    public record MyReport(StoreReport report, String storeName) {
    }

    /** 내 제보 (최근 50건) */
    @Transactional(readOnly = true)
    public List<MyReport> myReports(Long userId) {
        List<StoreReport> reports = reportRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, MY_REPORTS_LIMIT));
        if (reports.isEmpty()) return List.of();
        Map<Long, String> names = reportRepository.findStoreNames(reports.stream().map(StoreReport::getStoreId).distinct().toList())
                .stream().collect(Collectors.toMap(row -> (Long) row[0], row -> (String) row[1]));
        return reports.stream().map(r -> new MyReport(r, names.getOrDefault(r.getStoreId(), "알 수 없는 가게"))).toList();
    }
}
