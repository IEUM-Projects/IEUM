package com.ieum.ieum.store;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * 가게 관련 비즈니스 로직.
 * @RequiredArgsConstructor: final 필드를 받는 생성자를 Lombok이 만들어 주고,
 * Spring이 그 생성자로 StoreRepository를 넣어 준다 (의존성 주입).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    static final int DEFAULT_RADIUS = 1000;   // 기획안: 기본 반경 1km
    static final int MAX_RADIUS = 5000;
    static final int MAX_RESULTS = 200;

    private final StoreRepository storeRepository;
    private final com.ieum.ieum.onnuri.StoreOnnuriRepository onnuriRepository;
    private final NamedParameterJdbcTemplate jdbc;

    public List<StoreResponses.Summary> findNearby(double lat, double lng, Integer radius, List<String> categories, StoreType type) {
        if (lat < 33 || lat > 39 || lng < 124 || lng > 132) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat, lng가 대한민국 범위를 벗어났어요.");
        }
        int r = radius == null ? DEFAULT_RADIUS : Math.clamp(radius, 100, MAX_RADIUS);

        boolean all = categories == null || categories.isEmpty();
        // IN () 은 빈 목록을 받을 수 없어서, 전체 조회일 때는 의미 없는 값 하나를 넣어 둔다
        List<String> filter = all ? List.of("") : categories;

        return storeRepository.findNearby(lat, lng, r, all, filter, type == null ? "" : type.name(), MAX_RESULTS).stream()
                .map(StoreResponses.Summary::from)
                .toList();
    }

    public StoreResponses.Detail findDetail(Long id) {
        Store store = storeRepository.findByIdAndVisibleTrue(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "가게를 찾을 수 없어요."));
        // 로컬크리에이터: 연도별 선정 내용 (LOC-01)
        List<StoreResponses.Selection> selections = store.getType() != StoreType.CREATOR ? List.of() : jdbc.query("""
                SELECT selected_year, item, fields, organizer, data_date FROM creator_selections
                WHERE store_id = :id ORDER BY selected_year DESC
                """, new MapSqlParameterSource("id", id),
                (rs, i) -> new StoreResponses.Selection(rs.getInt("selected_year"), rs.getString("item"),
                        rs.getString("fields") == null ? List.of() : List.of(rs.getString("fields").split(",")),
                        rs.getString("organizer"), rs.getObject("data_date", LocalDate.class)));
        return StoreResponses.Detail.from(store, onnuriRepository.findById(id).orElse(null), selections);
    }
}
