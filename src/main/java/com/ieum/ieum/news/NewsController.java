package com.ieum.ieum.news;

import com.ieum.ieum.news.NewsService.Result;
import com.ieum.ieum.news.NewsService.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 동네 소식 (NEWS-01). 누구나 조회.
 *
 * GET /api/news?topic=market|store|local&size=10   주제별 최신 기사 (최대 30)
 * GET /api/news/market/{id}?size=5                 그 시장 이름이 들어간 최신 기사 (최대 10)
 * 기사 본문은 가져오지 않는다 (제목·출처 도메인·날짜·원문 링크).
 */
@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    static final String SOURCE = "네이버 뉴스 검색";

    private final NewsService newsService;

    public record NewsResponse(String topic, String label, String query, java.util.List<NewsService.NewsItem> items,
                               boolean available, String source) {
    }

    @GetMapping
    public NewsResponse topic(@RequestParam(defaultValue = "market") String topic, @RequestParam(defaultValue = "10") int size) {
        Topic t = Topic.of(topic);
        Result r = newsService.topic(t, Math.clamp(size, 1, 30));
        return new NewsResponse(t.key, t.label, r.query(), r.items(), r.available(), SOURCE);
    }

    @GetMapping("/market/{id}")
    public NewsResponse market(@PathVariable long id, @RequestParam(defaultValue = "5") int size) {
        Result r = newsService.market(id, Math.clamp(size, 1, 10));
        return new NewsResponse("market-" + id, null, r.query(), r.items(), r.available(), SOURCE);
    }
}
