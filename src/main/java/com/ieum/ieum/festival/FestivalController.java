package com.ieum.ieum.festival;

import com.ieum.ieum.region.Region;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 축제 API (누구나 조회 가능)
 *
 * GET /api/festivals?from=2026-11-01&to=2026-11-30&sido=11
 *     → 그 기간과 하루라도 겹치는 축제 (시작일 순). sido를 빼면 전국. 기간은 최대 93일
 * GET /api/festivals/{id}
 *     → 축제 상세
 */
@RestController
@RequestMapping("/api/festivals")
@RequiredArgsConstructor
public class FestivalController {

    private static final int MAX_DAYS = 93;

    private final FestivalRepository repository;

    public record Summary(Long id, String name, String place, LocalDate startDate, LocalDate endDate,
                          boolean longTerm, String sidoCode, String regionName,
                          BigDecimal latitude, BigDecimal longitude, String imageUrl) {
    }

    public record Detail(Long id, String name, String place, LocalDate startDate, LocalDate endDate,
                         boolean longTerm, String sidoCode, String regionName,
                         BigDecimal latitude, BigDecimal longitude, String imageUrl, String imageLicense,
                         String description, String organizer, String host, String sponsor,
                         String phone, String homepageUrl, String roadAddress, String jibunAddress,
                         String source, String provider, LocalDate dataDate) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Summary> list(@RequestParam LocalDate from, @RequestParam LocalDate to,
                              @RequestParam(required = false) String sido) {
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "끝 날짜가 시작 날짜보다 빨라요.");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "한 번에 " + MAX_DAYS + "일까지 조회할 수 있어요.");
        }
        if (sido != null && sido.isBlank()) sido = null;
        if (sido != null && !sido.matches("\\d{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지역 코드가 올바르지 않아요.");
        }
        List<Festival> festivals = repository.findOverlapping(from, to, sido);
        Map<String, Region> regions = regionsOf(festivals);
        return festivals.stream().map(f -> new Summary(f.getId(), f.getName(), f.getPlace(), f.getStartDate(),
                f.getEndDate(), f.isLongTerm(), f.getSidoCode(), regionName(f, regions),
                f.getLatitude(), f.getLongitude(), f.getImageUrl())).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public Detail detail(@PathVariable Long id) {
        Festival f = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "축제를 찾을 수 없어요."));
        Map<String, Region> regions = regionsOf(List.of(f));
        return new Detail(f.getId(), f.getName(), f.getPlace(), f.getStartDate(), f.getEndDate(), f.isLongTerm(),
                f.getSidoCode(), regionName(f, regions), f.getLatitude(), f.getLongitude(),
                f.getImageUrl(), f.getImageLicense(), f.getDescription(), f.getOrganizer(), f.getHost(),
                f.getSponsor(), f.getPhone(), f.getHomepageUrl(), f.getRoadAddress(), f.getJibunAddress(),
                f.getSource(), f.getProvider(), f.getDataDate());
    }

    private Map<String, Region> regionsOf(List<Festival> festivals) {
        List<String> codes = festivals.stream().map(Festival::getSigunguCode).filter(Objects::nonNull).distinct().toList();
        if (codes.isEmpty()) return Map.of();
        return repository.findRegions(codes).stream().collect(Collectors.toMap(Region::getCode, Function.identity()));
    }

    /** "서울 중구" 같은 지역 이름. 지역 코드가 없으면 null */
    private static String regionName(Festival f, Map<String, Region> regions) {
        Region r = f.getSigunguCode() == null ? null : regions.get(f.getSigunguCode());
        return r == null ? null : r.getSidoName() + " " + r.getSigunguName();
    }
}
