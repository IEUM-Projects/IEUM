package com.ieum.ieum.admin;

import com.ieum.ieum.admin.AdminDtos.Counts;
import com.ieum.ieum.admin.AdminDtos.EditRow;
import com.ieum.ieum.admin.AdminDtos.PendingPage;
import com.ieum.ieum.admin.AdminDtos.PendingStore;
import com.ieum.ieum.admin.AdminDtos.ReportRow;
import com.ieum.ieum.admin.AdminDtos.ReviewRequest;
import com.ieum.ieum.admin.AdminDtos.ReviewResult;
import com.ieum.ieum.admin.AdminDtos.ReviewedPage;
import com.ieum.ieum.admin.AdminDtos.StoreBrief;
import com.ieum.ieum.admin.AdminDtos.StoreChanges;
import com.ieum.ieum.admin.AdminDtos.StoreDetail;
import com.ieum.ieum.region.GeoService;
import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.region.Region;
import com.ieum.ieum.region.RegionRepository;
import com.ieum.ieum.report.ReportStatus;
import com.ieum.ieum.report.StoreReport;
import com.ieum.ieum.report.StoreReportRepository;
import com.ieum.ieum.store.Store;
import com.ieum.ieum.store.StoreRepository;
import com.ieum.ieum.user.User;
import com.ieum.ieum.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 관리자 제보 처리 (ADM-01)
 *
 * - 처리 대기 제보를 가게별로 묶어 오래 기다린 가게부터 보여 준다.
 * - 같은 가게의 대기 제보 여러 건을 한 번에 반영 또는 반려한다.
 * - 반영할 때 가게 정보(영업 상태, 영업시간, 전화번호, 주소)를 함께 고치고, 고친 내용은 store_edit_logs에 남긴다.
 * - 두 관리자가 같은 가게를 동시에 처리하면: 가게 행과 제보 행을 잠가서 한 명씩 처리하고,
 *   늦은 쪽은 "이미 처리된 제보가 있어요"(409)를 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminReportService {

    public static final int PENDING_PAGE_SIZE = 20;
    public static final int REVIEWED_PAGE_SIZE = 30;
    private static final int STORE_HISTORY_LIMIT = 100;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final StoreReportRepository reportRepository;
    private final StoreRepository storeRepository;
    private final StoreEditLogRepository editLogRepository;
    private final UserRepository userRepository;
    private final RegionRepository regionRepository;
    private final GeoService geo;
    private final com.ieum.ieum.notification.NotificationService notifications;
    private final TransactionTemplate transaction;

    // ---------------------------------------------------------------- 조회

    @Transactional(readOnly = true)
    public Counts counts() {
        Map<ReportStatus, Long> byStatus = reportRepository.countByStatus().stream()
                .collect(Collectors.toMap(row -> (ReportStatus) row[0], row -> (Long) row[1]));
        return new Counts(byStatus.getOrDefault(ReportStatus.RECEIVED, 0L),
                byStatus.getOrDefault(ReportStatus.APPLIED, 0L),
                byStatus.getOrDefault(ReportStatus.REJECTED, 0L),
                reportRepository.countPendingStores());
    }

    /** 처리 대기: 가게별 묶음, 가장 오래 기다린 가게부터 */
    @Transactional(readOnly = true)
    public PendingPage pending(int page) {
        int safePage = Math.max(page, 0);
        List<StoreReportRepository.PendingGroup> groups =
                reportRepository.findPendingGroups(PENDING_PAGE_SIZE + 1, safePage * PENDING_PAGE_SIZE);
        boolean hasNext = groups.size() > PENDING_PAGE_SIZE;
        List<Long> storeIds = groups.stream().limit(PENDING_PAGE_SIZE).map(StoreReportRepository.PendingGroup::getStoreId).toList();
        long total = reportRepository.countPendingStores();
        if (storeIds.isEmpty()) return new PendingPage(List.of(), safePage, false, total);

        Map<Long, Store> stores = byId(storeRepository.findAllById(storeIds), Store::getId);
        List<StoreReport> reports = reportRepository.findByStoreIdInAndStatusOrderByCreatedAtAscIdAsc(storeIds, ReportStatus.RECEIVED);
        Map<Long, String> nicknames = nicknames(reports.stream().map(StoreReport::getUserId));
        Map<Long, List<ReportRow>> rowsByStore = reports.stream().collect(Collectors.groupingBy(StoreReport::getStoreId,
                LinkedHashMap::new, Collectors.mapping(r -> row(r, stores.get(r.getStoreId()), nicknames), Collectors.toList())));

        List<PendingStore> items = storeIds.stream()
                .filter(stores::containsKey)
                .map(id -> new PendingStore(StoreBrief.from(stores.get(id)), rowsByStore.getOrDefault(id, List.of())))
                .toList();
        return new PendingPage(items, safePage, hasNext, total);
    }

    /** 처리 내역 (반영됨 / 반려됨): 최근에 처리한 순 */
    @Transactional(readOnly = true)
    public ReviewedPage reviewed(ReportStatus status, int page) {
        if (status == ReportStatus.RECEIVED) throw new BusinessException(ErrorCode.INVALID_INPUT);
        int safePage = Math.max(page, 0);
        Page<StoreReport> result = reportRepository.findByStatusOrderByReviewedAtDescIdDesc(
                status, PageRequest.of(safePage, REVIEWED_PAGE_SIZE));
        List<StoreReport> reports = result.getContent();
        Map<Long, Store> stores = byId(storeRepository.findAllById(reports.stream().map(StoreReport::getStoreId).distinct().toList()), Store::getId);
        Map<Long, String> nicknames = nicknames(Stream.concat(reports.stream().map(StoreReport::getUserId),
                reports.stream().map(StoreReport::getReviewedBy)));
        return new ReviewedPage(reports.stream().map(r -> row(r, stores.get(r.getStoreId()), nicknames)).toList(),
                safePage, result.hasNext(), result.getTotalElements());
    }

    /** 가게 하나: 지금 정보, 모든 제보(최근 100건), 수정 기록(최근 100건) */
    @Transactional(readOnly = true)
    public StoreDetail store(Long storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        return detail(store);
    }

    private StoreDetail detail(Store store) {
        List<StoreReport> reports = reportRepository.findByStoreIdOrderByCreatedAtDescIdDesc(store.getId(), PageRequest.of(0, STORE_HISTORY_LIMIT));
        List<StoreEditLog> edits = editLogRepository.findByStoreIdOrderByCreatedAtDescIdDesc(store.getId(), PageRequest.of(0, STORE_HISTORY_LIMIT));
        Map<Long, String> nicknames = nicknames(Stream.of(
                reports.stream().map(StoreReport::getUserId),
                reports.stream().map(StoreReport::getReviewedBy),
                edits.stream().map(StoreEditLog::getAdminId)).flatMap(Function.identity()));
        return new StoreDetail(StoreBrief.from(store),
                reports.stream().map(r -> row(r, store, nicknames)).toList(),
                edits.stream().map(e -> new EditRow(e.getField(), e.getField().label(), e.getBeforeValue(),
                        e.getAfterValue(), nicknames.get(e.getAdminId()), e.getCreatedAt())).toList());
    }

    // ---------------------------------------------------------------- 처리

    /**
     * 제보 처리.
     * 주소를 바꾸는 경우 카카오 로컬 API로 좌표를 먼저 찾는다 (바깥 API를 기다리는 동안 DB를 잠그지 않도록 트랜잭션 밖에서).
     */
    public ReviewResult review(User admin, Long storeId, ReviewRequest request) {
        StoreChanges changes = request.changes() == null || request.changes().isEmpty() ? null : request.changes();
        if (request.decision() == ReportStatus.RECEIVED) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("반영 또는 반려를 골라 주세요."));
        }
        if (request.decision() == ReportStatus.REJECTED) {
            if (changes != null) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("반려할 때는 가게 정보를 고칠 수 없어요."));
            if (request.note() == null) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("반려하는 이유를 처리 메모에 써 주세요. 제보한 분께 보여요."));
        }

        Located place = locateIfMoved(storeId, changes);
        return transaction.execute(status -> apply(admin, storeId, request.reportIds(), request.decision(), request.note(), changes, place));
    }

    /**
     * 제보 없이 가게 정보만 고치기.
     * 폐업 처리한 가게는 지도에서 숨겨져 더 제보를 받을 수 없으므로, 잘못 처리했을 때 되돌리는 길이 여기다.
     */
    public ReviewResult edit(User admin, Long storeId, StoreChanges changes) {
        if (changes == null || changes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("고칠 내용을 입력해 주세요."));
        }
        Located place = locateIfMoved(storeId, changes);
        return transaction.execute(status -> apply(admin, storeId, List.of(), null, null, changes, place));
    }

    private Located locateIfMoved(Long storeId, StoreChanges changes) {
        Store current = storeRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        if (changes != null && changes.roadAddress() != null && !changes.roadAddress().equals(current.getRoadAddress())) {
            return locate(changes.roadAddress());
        }
        return null;
    }

    /** decision이 null이면 제보 없이 가게 정보만 고친다 */
    private ReviewResult apply(User admin, Long storeId, List<Long> reportIds, ReportStatus decision, String note,
                               StoreChanges changes, Located place) {
        Store store = storeRepository.findForUpdate(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        List<StoreReport> reports = reportIds.isEmpty() ? List.of() : reportRepository.findAllForUpdate(reportIds);
        if (reports.size() != reportIds.size()
                || reports.stream().anyMatch(r -> !r.getStoreId().equals(storeId))) {
            throw new BusinessException(ErrorCode.NOT_FOUND, List.of("이 가게의 제보가 아닌 것이 있어요. 목록을 새로 불러와 주세요."));
        }
        if (reports.stream().anyMatch(r -> !r.isReceived())) {
            throw new BusinessException(ErrorCode.REPORT_ALREADY_REVIEWED);
        }

        LocalDateTime now = LocalDateTime.now(SEOUL);
        List<String> changed = new ArrayList<>();
        String warning = null;
        if (changes != null) {
            if (changes.infoStatus() != null && changes.infoStatus() != store.getInfoStatus()) {
                record(store, admin, StoreEditField.INFO_STATUS, AdminDtos.infoStatusLabel(store.getInfoStatus()),
                        AdminDtos.infoStatusLabel(changes.infoStatus()), changed);
                store.changeInfoStatus(changes.infoStatus());
            }
            if (changes.hours() != null && !changes.hours().equals(blankToEmpty(store.getHours()))) {
                record(store, admin, StoreEditField.HOURS, store.getHours(), emptyToNull(changes.hours()), changed);
                store.changeHours(emptyToNull(changes.hours()));
            }
            if (changes.phone() != null && !changes.phone().equals(blankToEmpty(store.getPhone()))) {
                record(store, admin, StoreEditField.PHONE, store.getPhone(), emptyToNull(changes.phone()), changed);
                store.changePhone(emptyToNull(changes.phone()));
            }
            if (place != null && !place.address().equals(store.getRoadAddress())) {
                record(store, admin, StoreEditField.ROAD_ADDRESS, store.getRoadAddress(), place.address(), changed);
                store.moveTo(place.address(), place.lat(), place.lng(),
                        place.region().map(Region::getSidoCode).orElse(null), place.region().map(Region::getCode).orElse(null));
                if (place.region().isEmpty()) {
                    warning = "새 주소의 지역 코드를 찾지 못했어요. 지도에는 보이지만 지역 고르기 목록에는 빠져요.";
                }
            }
        }
        reports.forEach(r -> r.review(decision, note, admin.getId(), now));
        // 제보한 회원에게 처리 결과 알림 (NOTI-01)
        if (decision != null) {
            boolean applied = decision == ReportStatus.APPLIED;
            String memo = note == null || note.isBlank() ? "" : " · " + note.strip();
            reports.forEach(r -> notifications.send(r.getUserId(), applied ? "REPORT_APPLIED" : "REPORT_REJECTED",
                    applied ? "제보가 반영됐어요" : "제보를 반려했어요",
                    "‘" + store.getName() + "’ " + r.getType().label() + memo, "/mypage.html#reports"));
        }
        if (decision != ReportStatus.REJECTED && changed.isEmpty() && warning == null) {
            warning = decision == null ? "지금 정보와 같아서 바뀐 것이 없어요."
                    : "가게 정보는 바뀌지 않았어요. 제보만 '반영됨'으로 처리했어요.";
        }
        log.info("관리자 {} 가게 {} 처리: 제보 {}건 {} 변경 {}", admin.getId(), storeId, reports.size(), decision, changed);
        return new ReviewResult(StoreBrief.from(store), reports.size(), decision, changed, warning);
    }

    private void record(Store store, User admin, StoreEditField field, String before, String after, List<String> changed) {
        editLogRepository.save(StoreEditLog.of(store.getId(), admin.getId(), field, before, after));
        changed.add(field.label());
    }

    // ---------------------------------------------------------------- 주소 → 좌표·지역

    private record Located(String address, double lat, double lng, Optional<Region> region) {
    }

    private Located locate(String address) {
        if (!geo.available()) throw new BusinessException(ErrorCode.MAP_SERVICE_UNAVAILABLE);
        try {
            // 브이월드 주소 검색 (DATA-05: 카카오 결과는 저장할 수 없음)
            GeoService.Located point = geo.locate(address)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND));
            Optional<Region> region = Optional.ofNullable(point.sigunguCode()).flatMap(regionRepository::findByCode);
            return new Located(address, point.lat(), point.lng(), region);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {   // 브이월드 연결·키·한도 오류(VWorldException), 잘못된 설정 모두
            log.warn("관리자 주소 확인 실패: {}", e.toString());
            throw new BusinessException(ErrorCode.MAP_SERVICE_UNAVAILABLE);
        }
    }

    // ---------------------------------------------------------------- 도우미

    private ReportRow row(StoreReport r, Store store, Map<Long, String> nicknames) {
        return new ReportRow(r.getId(), r.getStoreId(), store == null ? "알 수 없는 가게" : store.getName(),
                r.getType(), r.getType().label(), r.getContent(), r.getStatus(), r.getStatus().label(),
                nicknames.getOrDefault(r.getUserId(), "알 수 없음"), r.getCreatedAt(), r.getAdminNote(),
                r.getReviewedBy() == null ? null : nicknames.getOrDefault(r.getReviewedBy(), "알 수 없음"),
                r.getReviewedAt());
    }

    private Map<Long, String> nicknames(Stream<Long> ids) {
        Set<Long> unique = ids.filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
        if (unique.isEmpty()) return Map.of();
        return byId(userRepository.findAllById(unique), User::getId).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getNickname()));
    }

    private static <T> Map<Long, T> byId(Collection<T> items, Function<T, Long> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
