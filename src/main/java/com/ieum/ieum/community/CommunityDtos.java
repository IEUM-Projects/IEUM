package com.ieum.ieum.community;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 동네 이야기 게시판 요청·응답 모양 (COM-01) */
public final class CommunityDtos {

    private CommunityDtos() {
    }

    public static final Map<String, String> CATEGORIES = Map.of(
            "REVIEW", "후기", "RECOMMEND", "추천", "QUESTION", "질문", "FREE", "자유");
    public static final Map<String, String> RECOMMEND_STATUS = Map.of(
            "PENDING", "확인 전", "ADDED", "지도에 추가했어요", "EXISTS", "이미 있는 곳이에요", "HOLD", "보류");
    public static final Map<String, String> REPORT_REASONS = Map.of(
            "SPAM", "광고·도배", "ABUSE", "욕설·비방", "PRIVACY", "개인정보 노출", "FALSE", "사실과 다른 내용", "ETC", "기타");

    /** 작성자. 회원 번호는 내보내지 않는다. 탈퇴한 회원은 이름 대신 '탈퇴한 회원' */
    public record Author(String nickname, boolean withdrawn, boolean mine) {
    }

    /** 이야기를 붙인 곳 (가게·시장·축제) */
    public record Place(String type, long id, String name, String url) {
    }

    public record PostSummary(long id, String category, String categoryLabel, String title, String excerpt,
                              Author author, Place place, int likeCount, int commentCount,
                              LocalDateTime createdAt, boolean edited, String status,
                              String recommendStatus, String recommendStatusLabel,
                              String thumbnail, int photoCount) {
    }

    public record PostPage(List<PostSummary> items, int page, boolean hasNext, long total) {
    }

    /** content 가 null 이면 가려지거나 지워진 댓글 (state 로 구분) */
    public record CommentView(long id, String content, String state, Author author, LocalDateTime createdAt) {
    }

    public record PostDetail(long id, String category, String categoryLabel, String title, String content,
                             Author author, Place place, int likeCount, int commentCount,
                             LocalDateTime createdAt, LocalDateTime editedAt, String status, String hiddenReason,
                             String recommendStatus, String recommendStatusLabel, String recommendNote,
                             boolean likedByMe, boolean canEdit, List<CommentView> comments,
                             List<com.ieum.ieum.community.photo.PostPhotoService.PhotoView> photos) {
    }

    /** photos: 붙일 사진 번호와 설명, 순서대로 (COM-02). 고칠 때 null 이면 사진은 그대로 */
    public record PostRequest(String category, String title, String content, String placeType, Long placeId,
                              List<com.ieum.ieum.community.photo.PostPhotoService.PhotoRef> photos) {
    }

    public record CommentRequest(String content) {
    }

    public record ReportRequest(String targetType, Long targetId, String reason, String content) {
    }

    public record LikeResult(boolean liked, int likeCount) {
    }
}
