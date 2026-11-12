package com.ieum.ieum.region;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/regions
 * 시·도별로 묶은 시·군·구 목록과 각 지역의 중심 좌표.
 *
 * 응답 예:
 * [ { "sidoCode": "11", "sidoName": "서울",
 *     "sigungus": [ { "code": "11110", "name": "종로구", "centerLat": 37.57, "centerLng": 126.98, "storeCount": 40 } ] } ]
 */
@RestController
@RequestMapping("/api/regions")
@RequiredArgsConstructor
public class RegionController {

    private final RegionRepository regionRepository;

    public record Sigungu(String code, String name, double centerLat, double centerLng, long storeCount) {
    }

    public record Sido(String sidoCode, String sidoName, List<Sigungu> sigungus) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Sido> regions() {
        // 쿼리 결과(시·군·구 한 줄씩)를 시·도 단위로 묶는다. LinkedHashMap: 넣은 순서(시·도 코드 순) 유지
        Map<String, Sido> bySido = new LinkedHashMap<>();
        for (RegionRepository.RegionRow row : regionRepository.findAllWithCenter()) {
            Sido sido = bySido.computeIfAbsent(row.getSidoCode(),
                    k -> new Sido(row.getSidoCode(), row.getSidoName(), new ArrayList<>()));
            sido.sigungus().add(new Sigungu(row.getCode(), row.getSigunguName(),
                    row.getCenterLat(), row.getCenterLng(), row.getStoreCount()));
        }
        return List.copyOf(bySido.values());
    }
}
