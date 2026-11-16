package com.ieum.ieum.notice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 공지 API가 주고받는 모양 */
public final class NoticeDtos {

    private NoticeDtos() {
    }

    /**
     * 공지 쓰기·고치기 (관리자).
     * 제목: 한 줄 (줄바꿈·연속 공백은 띄어쓰기 하나로), 내용: 줄바꿈 유지, 보이지 않는 제어 문자는 뺀다.
     */
    public record NoticeRequest(
            @NotBlank(message = "제목을 입력해 주세요.")
            @Size(min = 2, max = 100, message = "제목은 2~100자로 써 주세요.")
            String title,
            @NotBlank(message = "내용을 입력해 주세요.")
            @Size(max = 5000, message = "내용은 5,000자까지 쓸 수 있어요.")
            String content,
            Boolean pinned,
            Boolean visible
    ) {
        public NoticeRequest {
            if (title != null) {
                title = title.replaceAll("[\\n\\r\\t]", " ").replaceAll("\\p{Cc}", "").replaceAll("\\s+", " ").trim();
            }
            if (content != null) {
                content = content.replace("\r\n", "\n").replaceAll("[\\p{Cc}&&[^\\n\\t]]", "").strip();
            }
        }

        public boolean isPinned() {
            return Boolean.TRUE.equals(pinned);
        }

        /** 보내지 않으면 공개 */
        public boolean isVisible() {
            return visible == null || visible;
        }
    }

    /** 목록 한 줄 */
    public record NoticeRow(Long id, String title, boolean pinned, boolean visible, LocalDateTime createdAt,
                            LocalDateTime updatedAt, String authorNickname) {
    }

    public record NoticePage(List<NoticeRow> items, int page, int size, boolean hasNext, long total, int totalPages) {
    }

    /** 공지 하나. edited: 쓴 뒤 고친 적이 있으면 true (화면에 '수정됨' 표시) */
    public record NoticeDetail(Long id, String title, String content, boolean pinned, boolean visible,
                               LocalDateTime createdAt, LocalDateTime updatedAt, boolean edited) {
    }
}
