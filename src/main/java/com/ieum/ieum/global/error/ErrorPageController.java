package com.ieum.ieum.global.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 오류 화면 (ERR-01). 스프링 기본 오류 화면(영어 Whitelabel) 대신 쓴다.
 *
 * - /api/… 요청  : 화면 대신 늘 JSON {code, message, details} (브라우저 주소창으로 열어도)
 * - 그 밖의 요청 : 상태 코드에 맞는 우리 오류 화면 (404 없는 주소, 5xx 서버 오류, 그 밖의 4xx)
 *
 * 화면 틀은 src/main/resources/error-pages/error.html (static 밖에 두어 주소로 바로 열리지 않게).
 * 요청 주소 같은 사용자 입력은 화면에 넣지 않는다 (스크립트 끼워 넣기 방지).
 */
@Controller
public class ErrorPageController implements ErrorController {

    private final String template;

    public ErrorPageController() {
        try {
            this.template = new ClassPathResource("error-pages/error.html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("오류 화면 틀(error-pages/error.html)을 읽지 못했어요.", e);
        }
    }

    @RequestMapping("/error")
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = code instanceof Integer i ? i : response.getStatus();
        if (status < 400) status = 404;   // 누가 /error 를 직접 열었을 때
        Object uriAttr = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String uri = uriAttr != null ? uriAttr.toString() : request.getRequestURI();

        response.resetBuffer();
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        byte[] body;
        if (isApi(uri, request.getContextPath())) {
            response.setContentType("application/json;charset=UTF-8");
            body = apiError(status).toJson().getBytes(StandardCharsets.UTF_8);
        } else {
            response.setContentType("text/html;charset=UTF-8");
            body = page(status).getBytes(StandardCharsets.UTF_8);
        }
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    static boolean isApi(String uri, String contextPath) {
        return uri != null && uri.startsWith(contextPath + "/api/");
    }

    static ErrorResponse apiError(int status) {
        if (status == 404) return ErrorResponse.of(ErrorCode.NOT_FOUND);
        if (status >= 500) return ErrorResponse.of(ErrorCode.INTERNAL_ERROR);
        if (status == 401) return ErrorResponse.of(ErrorCode.UNAUTHORIZED);
        return new ErrorResponse("ERROR", "요청을 처리할 수 없어요. 주소나 보낸 내용을 확인해 주세요.", java.util.List.of());
    }

    private String page(int status) {
        String title;
        String message;
        String actions;
        String search = "";
        if (status == 404) {
            title = "페이지를 찾을 수 없어요";
            message = "주소가 바뀌었거나 없어진 페이지예요. 주소를 다시 확인하거나, 아래에서 찾아보세요.";
            actions = link("/", "홈으로", true) + link("/map.html", "지도에서 찾기", false);
            search = """
                    <form class="error-search" action="/search.html" method="get" role="search">
                      <label for="q">가게·시장·축제 검색</label>
                      <div class="search-row">
                        <input id="q" name="q" type="search" maxlength="50" placeholder="예: 망원시장, 칼국수">
                        <button type="submit" class="button-secondary">검색</button>
                      </div>
                    </form>""";
        } else if (status >= 500) {
            title = "잠시 문제가 생겼어요";
            message = "서버에서 요청을 처리하지 못했어요. 잠시 뒤에 다시 시도해 주세요. 계속 안 되면 공지사항을 확인해 주세요.";
            actions = link("", "다시 시도", true) + link("/", "홈으로", false);
        } else {
            title = "요청을 처리할 수 없어요";
            message = "주소나 보낸 내용이 올바르지 않아요. 홈에서 다시 시작해 주세요.";
            actions = link("/", "홈으로", true) + link("/map.html", "지도에서 찾기", false);
        }
        return template.replace("{{TITLE}}", title)
                .replace("{{CODE_LABEL}}", "오류 코드 " + status)
                .replace("{{MESSAGE}}", message)
                .replace("{{ACTIONS}}", actions)
                .replace("{{SEARCH}}", search);
    }

    /** href="" 는 지금 주소를 다시 연다 (다시 시도) */
    private static String link(String href, String text, boolean primary) {
        return "<a class=\"" + (primary ? "button-primary" : "button-secondary") + "\" href=\"" + href + "\">" + text + "</a>";
    }
}
