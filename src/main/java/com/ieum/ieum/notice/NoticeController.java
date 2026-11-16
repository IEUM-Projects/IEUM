package com.ieum.ieum.notice;

import com.ieum.ieum.notice.NoticeDtos.NoticeDetail;
import com.ieum.ieum.notice.NoticeDtos.NoticePage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공지사항 (누구나)
 * GET /api/notices?page=0&size=10   보이는 공지, 고정 먼저 최신 순 (size 1~20)
 * GET /api/notices/{id}             공지 하나 (숨긴 공지는 404)
 */
@RestController
@RequestMapping("/api/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService service;

    @GetMapping
    public NoticePage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public NoticeDetail get(@PathVariable Long id) {
        return service.get(id);
    }
}
