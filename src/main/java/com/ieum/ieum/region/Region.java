package com.ieum.ieum.region;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * regions 테이블 (시·군·구 지역 코드).
 * 테이블은 schema.sql로 만들었고, ddl-auto: validate가 이 클래스와 모양이 맞는지 검사한다.
 */
@Entity
@Table(name = "regions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Region {

    @Id
    @Column(columnDefinition = "char(5)")
    private String code;

    @Column(name = "sido_code", nullable = false, columnDefinition = "char(2)")
    private String sidoCode;

    @Column(name = "sido_name", nullable = false, length = 20)
    private String sidoName;

    @Column(name = "sigungu_name", nullable = false, length = 40)
    private String sigunguName;
}
