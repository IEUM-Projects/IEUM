package com.ieum.ieum.license;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 인허가 종류 설정 (application.yml 의 license-check.kinds)
 *
 *   - id: general_restaurants
 *     label: 일반음식점
 *     code: "101"                       # 관리번호 가운데 업종 번호 (예: 3000000-101-2004-00052)
 *     categories: [음식점, 기타, ""]     # 이 분류의 가게를 이 인허가에서 찾는다 ("" = 분류 없음)
 *
 * 파일을 올리면 관리번호로 어느 종류인지 알아낸다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "license-check")
public class LicenseKinds {

    private List<Kind> kinds = new ArrayList<>();

    @Getter
    @Setter
    public static class Kind {
        private String id;
        private String label;
        private String code;
        private List<String> categories = new ArrayList<>();

        public boolean covers(String category) {
            String c = category == null ? "" : category.trim();
            return categories.stream().map(x -> x == null ? "" : x.trim()).anyMatch(c::equals);
        }
    }

    public Optional<Kind> byCode(String code) {
        return kinds.stream().filter(k -> k.getCode() != null && k.getCode().equals(code)).findFirst();
    }

    public Optional<Kind> byId(String id) {
        return kinds.stream().filter(k -> k.getId().equals(id)).findFirst();
    }

    /** 어느 인허가로도 확인할 수 없는 분류인지 (도소매, 공방 등) */
    public boolean notTarget(String category) {
        return kinds.stream().noneMatch(k -> k.covers(category));
    }
}
