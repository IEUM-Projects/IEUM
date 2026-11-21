package com.ieum.ieum.community;

import com.ieum.ieum.community.photo.PostPhotoService;
import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.global.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * 동네 이야기 사진 (COM-02)
 *   POST /api/posts/photos           사진 한 장 올리기 (본문이 그대로 사진 파일, 10MB까지) → {id, width, height, bytes}
 *   GET  /api/posts/photos/{이름}     사진 보기 (보이는 글의 사진, 또는 서명이 붙은 주소)
 *
 * 올리기는 multipart 대신 파일 자체를 본문으로 받는다. 서버 전체의 업로드 한도(관리자 CSV용 1GB)와 상관없이
 * 여기서 10MB 를 넘는 순간 읽기를 멈출 수 있어서다.
 */
@RestController
@RequiredArgsConstructor
public class PostPhotoController {

    private final CommunityService community;
    private final PostPhotoService photos;

    @PostMapping("/api/posts/photos")
    public ResponseEntity<PostPhotoService.Uploaded> upload(@AuthenticationPrincipal AuthUser viewer, HttpServletRequest request) throws IOException {
        long uid = community.requireWriter(viewer);
        if (request.getContentLengthLong() > PostPhotoService.MAX_BYTES) throw new BusinessException(ErrorCode.PHOTO_TOO_LARGE);
        byte[] data = readCapped(request.getInputStream(), PostPhotoService.MAX_BYTES);
        if (data.length == 0) throw new BusinessException(ErrorCode.PHOTO_INVALID);
        return ResponseEntity.status(HttpStatus.CREATED).body(photos.upload(uid, data));
    }

    @GetMapping("/api/posts/photos/{name}")
    public ResponseEntity<byte[]> photo(@PathVariable String name, @RequestParam(required = false) Long e,
                                        @RequestParam(required = false) String s) {
        return photos.serve(name, e, s)
                .map(sv -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        // 보이는 글: 10분 캐시 (가리면 오래 남지 않게). 서명 주소: 이 브라우저에만 1시간
                        .cacheControl(sv.signed() ? CacheControl.maxAge(Duration.ofHours(1)).cachePrivate()
                                : CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                        .body(sv.data()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build());
    }

    static byte[] readCapped(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new BusinessException(ErrorCode.PHOTO_TOO_LARGE);
        }
        return out.toByteArray();
    }
}
