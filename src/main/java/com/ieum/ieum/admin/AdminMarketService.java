package com.ieum.ieum.admin;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.market.Market;
import com.ieum.ieum.market.MarketFacility;
import com.ieum.ieum.market.MarketFacility.LinkStatus;
import com.ieum.ieum.market.MarketFacilityRepository;
import com.ieum.ieum.market.MarketRepository;
import com.ieum.ieum.user.User;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 전통시장 시설 정보 연결 확인 (DATA-01)
 *
 * 13_data_market_facilities.sql 이 시장명 + 주소가 같은 줄은 자동으로 잇고(AUTO),
 * 이름만 같은 줄은 관리자가 확인하도록 남긴다(PENDING). 관리자는
 *   LINK   : 이 시장이 맞다 → 잇기 (다른 시장 번호를 직접 넣을 수도 있음)
 *   IGNORE : 맞는 시장이 없다 → 잇지 않음
 *   RESET  : 처리를 되돌려 다시 확인 대기로
 */
@Service
@RequiredArgsConstructor
public class AdminMarketService {

    public static final int PAGE_SIZE = 20;

    private final MarketFacilityRepository facilityRepository;
    private final MarketRepository marketRepository;

    public enum Action { LINK, IGNORE, RESET }

    public record LinkRequest(@NotNull(message = "처리 방법을 골라 주세요.") Action action, Long marketId) {
    }

    public record MarketBrief(Long id, String name, String address) {
        static MarketBrief of(Market m) {
            return m == null ? null : new MarketBrief(m.getId(), m.getName(), m.address());
        }
    }

    public record LinkRow(String code, String name, String address, String region, String status,
                          LocalDate dataDate, LocalDateTime reviewedAt,
                          MarketBrief suggested, MarketBrief linked) {
    }

    public record LinkPage(List<LinkRow> items, int page, boolean hasNext, long total, Map<LinkStatus, Long> counts) {
    }

    @Transactional(readOnly = true)
    public LinkPage list(LinkStatus status, int page) {
        Page<MarketFacility> rows = facilityRepository.findByStatus(status, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
        List<MarketFacility> list = rows.getContent();
        Map<Long, Market> suggested = marketRepository.findAllByIdIn(
                        list.stream().map(MarketFacility::getSuggestedMarketId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Market::getId, Function.identity()));
        Map<String, Market> linked = list.isEmpty() ? Map.of() : marketRepository.findAllByMarketCodeIn(
                        list.stream().map(MarketFacility::getMarketCode).toList())
                .stream().collect(Collectors.toMap(Market::getMarketCode, Function.identity()));
        return new LinkPage(list.stream().map(f -> row(f, suggested.get(f.getSuggestedMarketId()), linked.get(f.getMarketCode()))).toList(),
                rows.getNumber(), rows.hasNext(), rows.getTotalElements(), counts());
    }

    private Map<LinkStatus, Long> counts() {
        Map<LinkStatus, Long> counts = new EnumMap<>(LinkStatus.class);
        for (LinkStatus s : LinkStatus.values()) counts.put(s, 0L);
        for (Object[] r : facilityRepository.countByStatus()) counts.put((LinkStatus) r[0], (Long) r[1]);
        return counts;
    }

    private static LinkRow row(MarketFacility f, Market suggested, Market linked) {
        String region = String.join(" ", java.util.stream.Stream.of(f.getSido(), f.getSigungu()).filter(Objects::nonNull).toList());
        return new LinkRow(f.getMarketCode(), f.getName(), f.address(), region, f.getLinkStatus().name(), f.getDataDate(),
                f.getReviewedAt(), MarketBrief.of(suggested), MarketBrief.of(linked));
    }

    @Transactional
    public LinkRow act(User admin, String code, LinkRequest request) {
        MarketFacility f = facilityRepository.findForUpdate(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.MARKET_LINK_NOT_FOUND));
        Optional<Market> current = marketRepository.findByMarketCodeForUpdate(code);
        Market linked = null;
        switch (request.action()) {
            case LINK -> {
                Long id = request.marketId() != null ? request.marketId() : f.getSuggestedMarketId();
                if (id == null) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("이을 시장 번호를 넣어 주세요."));
                Market target = marketRepository.findForUpdate(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.MARKET_NOT_FOUND));
                if (target.getMarketCode() != null && !target.getMarketCode().equals(code)) {
                    throw new BusinessException(ErrorCode.MARKET_ALREADY_LINKED);
                }
                if (current.isPresent() && current.get() != target) {
                    current.get().unlinkFacility();
                    marketRepository.flush();   // 한 코드는 한 시장에만 (먼저 풀고 잇는다)
                }
                target.linkFacility(code);
                f.review(LinkStatus.LINKED, admin.getId());
                linked = target;
            }
            case IGNORE -> {
                current.ifPresent(Market::unlinkFacility);
                f.review(LinkStatus.IGNORED, admin.getId());
            }
            case RESET -> {
                current.ifPresent(Market::unlinkFacility);
                f.review(LinkStatus.PENDING, admin.getId());
            }
        }
        Market suggested = f.getSuggestedMarketId() == null ? null : marketRepository.findById(f.getSuggestedMarketId()).orElse(null);
        return row(f, suggested, linked);
    }
}
