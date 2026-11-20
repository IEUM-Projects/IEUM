package com.ieum.ieum.policy;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/policy - 약관·방침 화면에 넣을 판, 시행일, 운영자 정보 (누구나)
 */
@RestController
@RequiredArgsConstructor
public class PolicyController {

    private final Policy policy;

    public record PolicyInfo(int version, String effectiveDate, String operatorName, String contactEmail, String hosting, boolean configured) {
    }

    @GetMapping("/api/policy")
    public PolicyInfo info() {
        return new PolicyInfo(policy.version(), policy.effectiveDate(), policy.operatorName(), policy.contactEmail(),
                policy.hosting(), policy.configured());
    }
}
