package com.ieum.ieum.auth.oauth;

import java.util.Map;

/**
 * 카카오 사용자 정보(/v2/user/me 응답)에서 필요한 값만 꺼낸다.
 *
 * 응답 예:
 * {
 *   "id": 1234567890,
 *   "properties":    { "nickname": "병선" },
 *   "kakao_account": { "profile": { "nickname": "병선" } }
 * }
 * 닉네임은 동의 설정에 따라 둘 중 한 곳에만 올 수 있어서 둘 다 확인한다.
 */
public record KakaoUserInfo(String id, String nickname) {

    public static KakaoUserInfo from(Map<String, Object> attributes) {
        Object id = attributes.get("id");
        if (id == null) {
            throw new IllegalArgumentException("카카오 응답에 회원 번호(id)가 없어요.");
        }

        String nickname = null;
        if (attributes.get("kakao_account") instanceof Map<?, ?> account
                && account.get("profile") instanceof Map<?, ?> profile
                && profile.get("nickname") instanceof String n) {
            nickname = n;
        }
        if (nickname == null && attributes.get("properties") instanceof Map<?, ?> properties
                && properties.get("nickname") instanceof String n) {
            nickname = n;
        }
        return new KakaoUserInfo(String.valueOf(id), nickname);
    }
}
