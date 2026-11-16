package com.ieum.ieum.notice;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.notice.NoticeDtos.NoticeDetail;
import com.ieum.ieum.notice.NoticeDtos.NoticePage;
import com.ieum.ieum.notice.NoticeDtos.NoticeRequest;
import com.ieum.ieum.notice.NoticeDtos.NoticeRow;
import com.ieum.ieum.user.User;
import com.ieum.ieum.user.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 공지사항 (NOT-01)
 * - 누구나: 보이는 공지만, 고정 먼저 최신 순
 * - 관리자: 숨긴 공지까지 보고, 쓰기·고치기(고정·숨김 포함). 지우기는 없다 (숨김으로 내림)
 * - 고정은 보이는 공지 중 3개까지 (목록 맨 위가 고정 공지로만 가득 차지 않도록)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeService {

    public static final int MAX_PINNED = 3;
    public static final int MAX_PAGE_SIZE = 20;

    private final NoticeRepository repository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    // ---------------------------------------------------------------- 누구나

    @Transactional(readOnly = true)
    public NoticePage list(int page, int size) {
        return page(repository.findPublic(PageRequest.of(Math.max(page, 0), clamp(size))), false);
    }

    @Transactional(readOnly = true)
    public NoticeDetail get(Long id) {
        return detail(repository.findByIdAndVisibleTrue(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTICE_NOT_FOUND)));
    }

    // ---------------------------------------------------------------- 관리자

    @Transactional(readOnly = true)
    public NoticePage listForAdmin(int page, int size) {
        return page(repository.findAllForAdmin(PageRequest.of(Math.max(page, 0), clamp(size))), true);
    }

    @Transactional(readOnly = true)
    public NoticeDetail getForAdmin(Long id) {
        return detail(find(id));
    }

    @Transactional
    public NoticeDetail write(User admin, NoticeRequest request) {
        checkPinLimit(request, null);
        Notice notice = repository.save(Notice.write(request.title(), request.content(),
                request.isPinned(), request.isVisible(), admin.getId()));
        reload(notice);
        log.info("관리자 {} 공지 {} 작성 (고정 {}, 공개 {})", admin.getId(), notice.getId(), notice.isPinned(), notice.isVisible());
        return detail(notice);
    }

    @Transactional
    public NoticeDetail edit(User admin, Long id, NoticeRequest request) {
        Notice notice = find(id);
        checkPinLimit(request, id);
        notice.edit(request.title(), request.content(), request.isPinned(), request.isVisible(), admin.getId());
        reload(notice);
        log.info("관리자 {} 공지 {} 수정 (고정 {}, 공개 {})", admin.getId(), id, notice.isPinned(), notice.isVisible());
        return detail(notice);
    }

    // ---------------------------------------------------------------- 도우미

    private void checkPinLimit(NoticeRequest request, Long editingId) {
        if (request.isPinned() && request.isVisible() && repository.countPinned(editingId) >= MAX_PINNED) {
            throw new BusinessException(ErrorCode.TOO_MANY_PINNED);
        }
    }

    private Notice find(Long id) {
        return repository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOTICE_NOT_FOUND));
    }

    private NoticePage page(Page<Notice> result, boolean withAuthor) {
        Map<Long, String> nicknames = withAuthor ? nicknames(result.getContent().stream().map(Notice::getAuthorId)) : Map.of();
        return new NoticePage(result.getContent().stream()
                .map(n -> new NoticeRow(n.getId(), n.getTitle(), n.isPinned(), n.isVisible(), n.getCreatedAt(),
                        n.getUpdatedAt(), withAuthor ? nicknames.getOrDefault(n.getAuthorId(), "알 수 없음") : null))
                .toList(),
                result.getNumber(), result.getSize(), result.hasNext(), result.getTotalElements(), result.getTotalPages());
    }

    /** 시각(created_at, updated_at)은 DB가 채우므로, 저장한 뒤 DB에서 다시 읽는다 */
    private void reload(Notice notice) {
        repository.flush();
        entityManager.refresh(notice);
    }

    private NoticeDetail detail(Notice n) {
        return new NoticeDetail(n.getId(), n.getTitle(), n.getContent(), n.isPinned(), n.isVisible(),
                n.getCreatedAt(), n.getUpdatedAt(), n.getUpdatedBy() != null);
    }

    private Map<Long, String> nicknames(Stream<Long> ids) {
        var unique = ids.filter(Objects::nonNull).distinct().toList();
        if (unique.isEmpty()) return Map.of();
        return userRepository.findAllById(unique).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname, (a, b) -> a));
    }

    private static int clamp(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
