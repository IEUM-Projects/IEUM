package com.ieum.ieum.search;

import java.time.LocalDate;
import java.util.List;

/** 검색 API가 돌려주는 모양 */
public final class SearchDtos {

    private SearchDtos() {
    }

    /** distanceM: 기준 위치(lat, lng)를 보냈을 때만 값이 있다 */
    public record StoreHit(Long id, String type, String name, String category, String roadAddress, String infoStatus, Long distanceM) {
    }

    /** goods: 주요 상품 (검색어가 상품에서 찾아졌을 때 보여 주려고, 80자까지) */
    public record MarketHit(Long id, String name, String marketType, boolean permanent, String marketDays,
                            String roadAddress, String goods, Long distanceM) {
    }

    public record FestivalHit(Long id, String name, String place, LocalDate startDate, LocalDate endDate,
                              String regionName, Long distanceM) {
    }

    /**
     * 동네 이야기 글 (SRCH-02). snippet: 내용에서 검색어가 처음 나온 곳 앞뒤 (내용에서 찾았을 때), 아니면 앞부분.
     * author: 탈퇴한 회원은 '탈퇴한 회원'. thumbnail: 첫 사진 작은 주소 (없으면 null)
     */
    public record PostHit(Long id, String category, String categoryLabel, String title, String snippet, String author,
                          String placeName, java.time.LocalDateTime createdAt, int likeCount, int commentCount, String thumbnail) {
    }

    /** 지역: 누르면 그 지역 지도로 */
    public record RegionHit(String code, String label, double centerLat, double centerLng, long storeCount) {
    }

    public record Group<T>(List<T> items, long total) {
    }

    /** 한 번에 모두: 종류마다 앞의 5개와 전체 수 */
    public record SearchResult(String query, List<String> words, List<RegionHit> regions,
                               Group<StoreHit> stores, Group<MarketHit> markets, Group<FestivalHit> festivals,
                               Group<PostHit> posts) {
    }

    /** 종류 하나 더 보기 (20개씩) */
    public record PageResult<T>(List<T> items, int page, boolean hasNext, long total) {
    }
}
