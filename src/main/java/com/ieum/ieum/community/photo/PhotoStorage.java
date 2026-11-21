package com.ieum.ieum.community.photo;

import java.io.IOException;
import java.util.Optional;

/**
 * 사진 파일 저장소 (COM-02).
 * 지금은 서버 폴더({@link LocalPhotoStorage})에 둔다. 배포 서버를 정한 뒤 S3·R2 같은 곳으로 옮기려면
 * 이 인터페이스를 구현한 클래스를 하나 더 만들고 그쪽을 빈으로 쓰면 된다. 나머지 코드는 이름(name)만 안다.
 */
public interface PhotoStorage {

    /** name 예: "ab12…ef.jpg", "ab12…ef_t.jpg" */
    void save(String name, byte[] data) throws IOException;

    Optional<byte[]> load(String name) throws IOException;

    /** 없으면 조용히 넘어간다 */
    void delete(String name);
}
