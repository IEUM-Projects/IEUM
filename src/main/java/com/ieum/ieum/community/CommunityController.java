package com.ieum.ieum.community;

import com.ieum.ieum.community.CommunityDtos.CommentRequest;
import com.ieum.ieum.community.CommunityDtos.LikeResult;
import com.ieum.ieum.community.CommunityDtos.PostDetail;
import com.ieum.ieum.community.CommunityDtos.PostPage;
import com.ieum.ieum.community.CommunityDtos.PostRequest;
import com.ieum.ieum.community.CommunityDtos.PostSummary;
import com.ieum.ieum.community.CommunityDtos.ReportRequest;
import com.ieum.ieum.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 동네 이야기 게시판 (COM-01)
 *
 * 누구나
 *   GET    /api/posts?category=REVIEW&q=&placeType=STORE&placeId=5&page=0   목록 (20개씩)
 *   GET    /api/posts/{id}                    글 + 댓글 (로그인했으면 공감 여부, 내 글 여부)
 * 약관에 동의한 회원
 *   POST   /api/posts                         {"category","title","content","placeType","placeId"}
 *   PUT    /api/posts/{id}                    고치기 (글쓴이)
 *   DELETE /api/posts/{id}                    지우기 (글쓴이, 관리자)
 *   POST   /api/posts/{id}/like · DELETE      공감 / 공감 취소
 *   POST   /api/posts/{id}/comments           {"content"}
 *   DELETE /api/comments/{id}                 댓글 지우기 (글쓴이, 관리자)
 *   POST   /api/community/reports             {"targetType":"POST|COMMENT","targetId","reason","content"}
 *   GET    /api/users/me/posts                내 글
 */
@RestController
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService service;

    @GetMapping("/api/posts")
    public PostPage list(@AuthenticationPrincipal AuthUser viewer, @RequestParam(defaultValue = "") String category,
                         @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String placeType,
                         @RequestParam(required = false) Long placeId, @RequestParam(defaultValue = "0") int page) {
        return service.list(category, q, placeType, placeId, page, viewer == null ? null : viewer.id());
    }

    @GetMapping("/api/posts/{id}")
    public PostDetail detail(@AuthenticationPrincipal AuthUser viewer, @PathVariable long id) {
        return service.detail(id, viewer);
    }

    @PostMapping("/api/posts")
    public ResponseEntity<PostDetail> create(@AuthenticationPrincipal AuthUser viewer, @RequestBody(required = false) PostRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(viewer, req));
    }

    @PutMapping("/api/posts/{id}")
    public PostDetail update(@AuthenticationPrincipal AuthUser viewer, @PathVariable long id, @RequestBody(required = false) PostRequest req) {
        return service.update(viewer, id, req);
    }

    @DeleteMapping("/api/posts/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser viewer, @PathVariable long id) {
        service.delete(viewer, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/posts/{id}/like")
    public LikeResult like(@AuthenticationPrincipal AuthUser viewer, @PathVariable long id) {
        return service.like(viewer, id, true);
    }

    @DeleteMapping("/api/posts/{id}/like")
    public LikeResult unlike(@AuthenticationPrincipal AuthUser viewer, @PathVariable long id) {
        return service.like(viewer, id, false);
    }

    @PostMapping("/api/posts/{id}/comments")
    public ResponseEntity<PostDetail> comment(@AuthenticationPrincipal AuthUser viewer, @PathVariable long id,
                                              @RequestBody(required = false) CommentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.comment(viewer, id, req == null ? null : req.content()));
    }

    @DeleteMapping("/api/comments/{id}")
    public PostDetail deleteComment(@AuthenticationPrincipal AuthUser viewer, @PathVariable long id) {
        return service.deleteComment(viewer, id);
    }

    @PostMapping("/api/community/reports")
    public ResponseEntity<Void> report(@AuthenticationPrincipal AuthUser viewer, @RequestBody(required = false) ReportRequest req) {
        service.report(viewer, req);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/api/users/me/posts")
    public List<PostSummary> mine(@AuthenticationPrincipal AuthUser viewer) {
        return service.mine(viewer);
    }
}
