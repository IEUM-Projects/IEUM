package com.ieum.ieum.admin;

import com.ieum.ieum.report.ReportStatus;
import com.ieum.ieum.report.ReportType;
import com.ieum.ieum.store.InfoStatus;
import com.ieum.ieum.store.Store;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 관리자 API가 주고받는 모양 */
public final class AdminDtos {

    private AdminDtos() {
    }

    public static String infoStatusLabel(InfoStatus status) {
        return switch (status) {
            case VERIFIED -> "영업 확인됨";
            case UNVERIFIED -> "영업 정보 미확인";
            case CLOSED -> "폐업";
        };
    }

    /**
     * 입력 정리.
     * keepNewLines=true (처리 메모): 줄바꿈·탭은 두고 다른 제어 문자만 뺀다.
     * keepNewLines=false (한 줄 정보): 줄바꿈도 띄어쓰기로 바꾸고 여러 칸 띄어쓰기는 한 칸으로.
     */
    static String clean(String value, boolean keepNewLines) {
        if (value == null) return null;
        if (keepNewLines) return value.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "").trim();
        return value.replaceAll("[\\n\\r\\t]", " ").replaceAll("\\p{Cc}", "").replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------- 요청

    /**
     * 제보 처리.
     * reportIds: 같은 가게의 '처리 대기' 제보 (여러 건을 한 번에)
     * decision : APPLIED(반영) / REJECTED(반려)
     * note     : 처리 메모. 제보한 사람의 '내 정보'에 그대로 보인다. 반려할 때는 꼭 써야 한다
     * changes  : 반영할 때 함께 고칠 가게 정보 (반려할 때는 보내면 안 됨)
     */
    public record ReviewRequest(
            @NotEmpty(message = "처리할 제보를 골라 주세요.")
            @Size(max = 50, message = "한 번에 50건까지 처리할 수 있어요.")
            List<@NotNull Long> reportIds,
            @NotNull(message = "반영할지 반려할지 골라 주세요.")
            ReportStatus decision,
            @Size(max = 300, message = "처리 메모는 300자까지 쓸 수 있어요.")
            String note,
            @Valid
            StoreChanges changes
    ) {
        public ReviewRequest {
            note = clean(note, true);
            if (note != null && note.isEmpty()) note = null;
            if (reportIds != null) reportIds = reportIds.stream().distinct().toList();
        }
    }

    /**
     * 고칠 가게 정보. null인 칸은 바꾸지 않는다.
     * hours, phone은 빈 글("")을 보내면 정보를 지운다 (예: 전화번호가 없어진 경우).
     */
    public record StoreChanges(
            InfoStatus infoStatus,
            @Size(max = 200, message = "영업시간은 200자까지 쓸 수 있어요.")
            String hours,
            @Size(max = 30, message = "전화번호가 너무 길어요.")
            @Pattern(regexp = "^$|^[0-9][0-9\\- ]{6,28}[0-9]$", message = "전화번호는 숫자와 - 로 써 주세요. (예: 02-123-4567)")
            String phone,
            @Size(min = 5, max = 300, message = "도로명 주소는 5~300자로 써 주세요.")
            String roadAddress
    ) {
        public StoreChanges {
            hours = clean(hours, false);
            phone = clean(phone, false);
            roadAddress = clean(roadAddress, false);
        }

        public boolean isEmpty() {
            return infoStatus == null && hours == null && phone == null && roadAddress == null;
        }
    }

    // ---------------------------------------------------------------- 응답

    public record Counts(long received, long applied, long rejected, long pendingStores) {
    }

    public record StoreBrief(Long id, String name, String category, String roadAddress, String jibunAddress,
                             String phone, String hours, InfoStatus infoStatus, String infoStatusLabel,
                             boolean visible) {
        static StoreBrief from(Store s) {
            return new StoreBrief(s.getId(), s.getName(), s.getCategory(), s.getRoadAddress(), s.getJibunAddress(),
                    s.getPhone(), s.getHours(), s.getInfoStatus(), AdminDtos.infoStatusLabel(s.getInfoStatus()), s.isVisible());
        }
    }

    public record ReportRow(Long id, Long storeId, String storeName, ReportType type, String typeLabel,
                            String content, ReportStatus status, String statusLabel, String reporterNickname,
                            LocalDateTime createdAt, String adminNote, String reviewerNickname,
                            LocalDateTime reviewedAt) {
    }

    /** 처리 대기: 가게 하나와 그 가게의 대기 제보들 */
    public record PendingStore(StoreBrief store, List<ReportRow> reports) {
    }

    public record PendingPage(List<PendingStore> items, int page, boolean hasNext, long totalStores) {
    }

    public record ReviewedPage(List<ReportRow> items, int page, boolean hasNext, long total) {
    }

    public record EditRow(StoreEditField field, String fieldLabel, String before, String after,
                          String adminNickname, LocalDateTime createdAt) {
    }

    /** 가게 하나의 제보 전체와 수정 기록 (처리 창) */
    public record StoreDetail(StoreBrief store, List<ReportRow> reports, List<EditRow> edits) {
    }

    /** 처리 결과. changed: 실제로 바뀐 항목 이름, warning: 처리는 됐지만 알려 둘 점 */
    public record ReviewResult(StoreBrief store, int reviewed, ReportStatus decision, List<String> changed,
                               String warning) {
    }
}
