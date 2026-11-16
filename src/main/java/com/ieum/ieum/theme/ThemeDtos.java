package com.ieum.ieum.theme;

import java.util.List;

/** 테마로 걷기 API가 돌려주는 모양 (THM-01) */
public final class ThemeDtos {

    private ThemeDtos() {
    }

    /**
     * 테마 하나의 요약.
     * total: 전국 수, near: 기준 위치 30km 안 수 (위치를 안 보냈으면 null),
     * today: 오늘 장이 서는 곳 수 (5일장 테마만, 나머지는 null)
     */
    public record ThemeSummary(String id, String title, String description, String unit,
                               long total, Long near, Long today) {
    }

    /** distanceM: 가게 목록 안에서는 시장(또는 골목 가운데)에서의 거리 */
    public record StoreLite(Long id, String name, String category, Long distanceM) {
    }

    /** 백년가게 골목: 150m 안쪽으로 이어진 백년가게 3곳 이상 */
    public record AlleyItem(String key, String label, String regionName, double centerLat, double centerLng,
                            int spanM, Long distanceM, List<StoreLite> stores) {
    }

    public record MarketLite(Long id, String name, String regionName, String roadAddress, String marketDays,
                             boolean permanent, Long distanceM, boolean fairToday) {
    }

    /** 시장 안 백년가게: 시장에서 300m 안의 백년가게 */
    public record MarketStoresItem(MarketLite market, List<StoreLite> stores) {
    }

    public record ThemePage<T>(ThemeSummary theme, List<T> items, int page, boolean hasNext, long total) {
    }
}
