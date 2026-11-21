package com.ieum.ieum.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 이용약관·개인정보 처리방침 설정 (POL-01)
 *
 * application.yml
 *   ieum.policy.version        약관·방침 판. 내용을 바꾸면 1 올린다 → 회원은 다음 로그인 때 다시 동의
 *   ieum.policy.effective-date 시행일 (화면에 표시)
 * application-local.yml (운영자 정보, 공개 화면에 그대로 나온다)
 *   ieum.policy.operator-name  운영자(개인정보 보호책임자) 이름
 *   ieum.policy.contact-email  문의·개인정보 요청을 받을 이메일
 *   ieum.policy.hosting        개인정보 처리를 맡긴 곳 (서버·DB를 빌린 곳, 예: "네이버클라우드 - 서버·DB 운영"). 없으면 비움
 */
@Component
public class Policy {

    private final int version;
    private final String effectiveDate;
    private final String operatorName;
    private final String contactEmail;
    private final String hosting;

    public Policy(@Value("${ieum.policy.version:1}") int version,
                  @Value("${ieum.policy.effective-date:}") String effectiveDate,
                  @Value("${ieum.policy.operator-name:}") String operatorName,
                  @Value("${ieum.policy.contact-email:}") String contactEmail,
                  @Value("${ieum.policy.hosting:}") String hosting) {
        this.version = Math.max(1, version);
        this.effectiveDate = effectiveDate == null ? "" : effectiveDate.strip();
        this.operatorName = operatorName == null ? "" : operatorName.strip();
        this.contactEmail = contactEmail == null ? "" : contactEmail.strip();
        this.hosting = hosting == null ? "" : hosting.strip();
    }

    public int version() {
        return version;
    }

    public String effectiveDate() {
        return effectiveDate;
    }

    public String operatorName() {
        return operatorName;
    }

    public String contactEmail() {
        return contactEmail;
    }

    public String hosting() {
        return hosting;
    }

    /** 운영자 이름과 문의 이메일을 넣었는지 (배포 전에 꼭 넣어야 함) */
    public boolean configured() {
        return !operatorName.isBlank() && !contactEmail.isBlank();
    }
}
