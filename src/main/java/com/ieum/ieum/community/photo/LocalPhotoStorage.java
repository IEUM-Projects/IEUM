package com.ieum.ieum.community.photo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 서버 폴더에 사진을 둔다 (COM-02).
 * 폴더: ieum.upload.dir (기본: 사용자 홈의 ieum-uploads/photos). 프로젝트 폴더 밖이라 Git에 올라가지 않는다.
 * 파일이 한 폴더에 너무 많이 쌓이지 않게 이름 앞 두 글자로 하위 폴더를 나눈다: photos/ab/ab12….jpg
 * 이 폴더는 주소로 바로 열리지 않는다. 사진은 PostPhotoController 가 글 상태를 확인한 뒤에만 보낸다.
 */
@Slf4j
@Component
public class LocalPhotoStorage implements PhotoStorage {

    private static final Pattern NAME = Pattern.compile("[0-9a-f]{32}(_t)?\\.jpg");

    private final Path root;

    public LocalPhotoStorage(@Value("${ieum.upload.dir:${user.home}/ieum-uploads/photos}") String dir) throws IOException {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("동네 이야기 사진 폴더: {}", root);
    }

    private Path path(String name) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("사진 이름이 올바르지 않아요: " + name);
        return root.resolve(name.substring(0, 2)).resolve(name);
    }

    @Override
    public void save(String name, byte[] data) throws IOException {
        Path target = path(name);
        Files.createDirectories(target.getParent());
        // 다 쓴 뒤에 이름을 바꿔서, 쓰는 도중의 반쪽 파일이 보이지 않게
        Path tmp = Files.createTempFile(target.getParent(), "up-", ".tmp");
        try {
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public Optional<byte[]> load(String name) throws IOException {
        Path p = path(name);
        return Files.exists(p) ? Optional.of(Files.readAllBytes(p)) : Optional.empty();
    }

    @Override
    public void delete(String name) {
        try {
            Files.deleteIfExists(path(name));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("사진 파일을 지우지 못했어요: {} ({})", name, e.getMessage());
        }
    }
}
