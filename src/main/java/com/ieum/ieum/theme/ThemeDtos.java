package com.ieum.ieum.theme;

import java.util.List;

/** 테마로 걷기 API가 돌려주는 모양 (THM-01) */
public final class ThemeDtos {

    private ThemeDtos() {
    }

    /**
     * 테마 하나의 요약.
     * total: 전국 수,
     * near / nearRadius: 기준 위치에서 2km → 10km → 30km 순으로 넓혀 처음 있는 반경과 그 안의 수 (THM-02).
     *                    30km 안에도 없으면 near=0, nearRadius=30000. 위치를 안 보냈으면 둘 다 null
     * today: 오늘 장이 서는 곳 수 (5일장 테마만, 나머지는 null)
     */
    public record ThemeSummary(String id, String title, String description, String unit,
                               long total, Long near, Integer nearRadius, Long today) {
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

    /**
     * radius: 이 목록이 거른 반경(m). null 이면 전국 (위치가 없거나 '전국'을 고름)
     * expanded: 'auto' 로 물었는데 2km 안에 없어서 넓혔는지 (화면이 '2km 안에는 없어서…'라고 알림)
     */
    public record ThemePage<T>(ThemeSummary theme, List<T> items, int page, boolean hasNext, long total,
                               Integer radius, boolean expanded) {
    }
}
