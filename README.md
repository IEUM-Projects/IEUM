# 이음 (IEUM)

**백년가게·전통시장·로컬크리에이터·지역 축제를 잇는 전국 로컬 가이드**

[![Live](https://img.shields.io/badge/site-ieum.me.kr-0f766e)](https://ieum.me.kr)
[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-green)]()
[![MySQL](https://img.shields.io/badge/MySQL-8.4-4479A1)]()

오래된 가게와 시장, 동네 축제처럼 지도 앱에서 잘 보이지 않는 지역의 장소를 한곳에 모아 보여 주는 웹 서비스입니다.
공공데이터를 바탕으로 '내 주변'·'테마로 걷기'·'걷기 코스'를 만들고, 동네 이야기 게시판에서 이용자끼리 후기를 나눕니다.

**운영 URL:** https://ieum.me.kr

<!-- 스크린샷: 홈 / 지도 / 축제 달력 / 걷기 코스 -->

---

## 목차

1. [프로젝트 소개](#1-프로젝트-소개)
2. [주요 기능](#2-주요-기능)
3. [기술 스택](#3-기술-스택)
4. [시스템 아키텍처](#4-시스템-아키텍처)
5. [저장소 구조](#5-저장소-구조)
6. [시작하기](#6-시작하기)
7. [운영 배포](#7-운영-배포)
8. [API 개요](#8-api-개요)
9. [개발 이력](#9-개발-이력)
10. [문서](#10-문서)
11. [트러블슈팅](#11-트러블슈팅)
12. [기여 가이드](#12-기여-가이드)
13. [라이선스 · 팀](#13-라이선스--팀)

---

## 1. 프로젝트 소개

이음(IEUM)은 지역 상생을 위한 로컬 가이드입니다. 중소벤처기업부가 지정한 **백년가게**, 전국 **전통시장**, **로컬크리에이터**, **지역 축제**를 지도와 달력으로 보여 주고, 가까운 곳을 묶어 걷기 코스를 만들어 줍니다.

### 배경 · 목표

| 항목 | 내용 |
|------|------|
| **배경** | 오래된 동네 가게·시장 정보가 여러 공공데이터에 흩어져 있고, 일반 지도 앱에서는 잘 드러나지 않음 |
| **목표** | 공공데이터를 한곳에 모아 '오늘 갈 만한 동네 가게·시장·축제'를 쉽게 찾게 하기 |
| **대상** | 전국, 어르신도 쓰기 쉬운 큰 글씨·큰 버튼 (KRDS 디지털 정부서비스 UI/UX 가이드라인 참고) |
| **원칙** | 공식 API·공공데이터만 사용(크롤링 안 함), 실제 GPS 위치는 서버로 보내지 않음 |

### 주요 성과

- 백년가게 1,511곳 · 전통시장 1,403곳 · 로컬크리에이터 1,339곳 · 시·군·구 234개 데이터 구축
- 관광공사·문화축제 표준데이터로 축제 달력과 주변 관광지 자동 수집
- TMAP 보행자 경로로 실제 길을 따라 그리는 걷기 코스
- 카카오 로그인 + 이메일 회원가입, JWT 인증, 동네 이야기 게시판
- 인허가 데이터로 폐업 의심 가게 감지, 이용자 영업 정보 제보와 관리자 처리
- AWS(EC2 + RDS + S3) + Nginx + Let's Encrypt + Jenkins로 운영 배포

---

## 2. 주요 기능

### 장소 찾기

| 기능 | 설명 |
|------|------|
| 홈 | 바로가기 아이콘, 이번 주 축제(진행 중 + 이번 주 예정), 내 주변 백년가게(업종별 탭), 테마 |
| 지도 (`map.html`) | 카카오맵 위에 백년가게·전통시장·로컬크리에이터 표시, 업종 칩으로 거르기 |
| 내 주변 | 기기 안에서만 현재 위치를 쓰고, 서버에는 가장 가까운 시·군·구만 보냄 |
| 가게 상세 (`store.html`) | 주소, 영업 상태(인허가 확인), 온누리상품권 가맹 여부, 길찾기·카카오맵에서 보기, 영업 정보 제보 |
| 전통시장 (`market.html`) | 장 서는 날(5일장 등), 시설 정보, 시장 이야기(역사·유래), 관련 뉴스 |
| 로컬크리에이터 (`creators.html`) | 지역별·연도별 선정 목록 |
| 통합 검색 (`search.html`) | 가게·시장·축제·공지·동네 이야기 글을 한 번에 검색 |

### 축제 · 여행

| 기능 | 설명 |
|------|------|
| 축제 달력 (`festivals.html`) | 월별 달력, 시·도 + 가까운 순 정렬, 진행 중·예정 표시 |
| 축제 상세 (`festival.html`) | 기간·장소·사진, 길찾기, 전화하기 |
| 테마로 걷기 (`themes.html`) | 노포·5일장·온누리 등 테마별 목록, 걸어서 2km → 10km → 30km 순으로 자동 넓히기 |
| 걷기 코스 (`course.html`, `trip.html`) | 가게·시장·관광지를 골라 코스 만들기, TMAP 실제 도보 경로와 걸리는 시간 |
| 길찾기 | 카카오맵 앱(안드로이드 intent·iOS 스킴) 또는 웹 길찾기로 연결 |

### 동네 이야기 (커뮤니티)

- 말머리(후기·추천·질문·자유) 글쓰기, 사진 4장(촬영 위치 등 정보 삭제), 댓글·댓글 수정
- 별점 없이 '공감'만, 신고 → 관리자 가림·복구
- 금칙어(욕설) 거르기, 닉네임 중복·금칙어 검사

### 회원

- 카카오 로그인 / 이메일 가입(이메일 인증), 비밀번호 찾기·바꾸기
- 이용약관·개인정보 처리방침 동의 기록 (버전이 바뀌면 다시 동의)
- 찜한 곳, 내가 쓴 글·제보, 알림(댓글·제보 처리·가림·추천 확인)
- 회원 탈퇴 시 카카오 연결 끊기, 글은 작성자만 지우고 남김
- 글씨 크기 조절, PWA(홈 화면에 추가·오프라인 안내)

### 관리자

- **요약 화면:** 처리할 제보·신고·폐업 의심 수, 최근 관리자 작업
- **영업 정보 제보 처리**, 가게 정보 수정 기록
- **폐업 감지:** 인허가 데이터와 비교, 일괄 가져오기
- **장소 연결:** 카카오 장소 ID 후보 고르기, 주소→좌표(브이월드) 작업
- **시장 이야기** 작성·공개, 시장 링크 관리
- **동네 이야기 관리**(가림·복구·추천), **회원 관리**(정지·강제 탈퇴·재가입 허용)
- **공지사항** 작성, **관리자 작업 기록**(1년 보관)

---

## 3. 기술 스택

### Frontend

HTML · CSS · JavaScript (프레임워크 없이 정적 파일) · 카카오맵 JavaScript SDK · PWA (Service Worker)

### Backend

Java 21 · Spring Boot 4.1 · Spring Security · OAuth2 Client(카카오) · JWT · Spring Data JPA · Validation

### Database

MySQL 8.4 (운영: AWS RDS, `time_zone` = Asia/Seoul)

### Infrastructure

AWS EC2 (Ubuntu 24.04) · RDS MySQL · S3(사진) · Nginx · Let's Encrypt · systemd · Jenkins

### External API · 공공데이터

| API · 데이터 | 용도 |
|-----|------|
| 한국관광공사 국문 관광정보 서비스 | 축제, 주변 관광지, 시장 소개글 |
| 전국문화축제표준데이터 | 축제 달력 |
| TMAP 보행자 경로안내 | 걷기 코스 도보 경로 |
| 브이월드 지오코더 | 주소 → 좌표 |
| 카카오 로컬 · 로그인 · 지도 | 장소 ID, 카카오 로그인·연결 끊기, 지도 화면·길찾기 |
| NAVER API HUB 뉴스 검색 | 시장·동네 소식 |
| 백년가게 지정리스트, 전국전통시장표준데이터, 전통시장현황(시설), 온누리상품권 가맹점, 로컬크리에이터 리스트, 행정안전부 인허가 데이터 | 기본 장소 데이터, 영업 상태 확인 |

전체 목록과 문서 링크는 'API 목록' 스프레드시트에 따로 정리했습니다.

---

## 4. 시스템 아키텍처

```
사용자 ──https──▶ Nginx (EC2, 443) ──▶ 이음 앱 (127.0.0.1:8080) ──▶ RDS MySQL
                     │                         ├──────────────▶ S3 (사진, IAM 역할)
                     │                         └──────────────▶ 공공데이터·카카오·TMAP·네이버 API
GitHub ──webhook──▶ └─ jenkins.ieum.me.kr ──▶ Jenkins (127.0.0.1:8090) ── 빌드 → deploy.sh
```

- 화면은 Spring Boot가 `static/`의 HTML·JS를 그대로 내려주고, 데이터는 `/api/*`로 받습니다.
- 앱은 127.0.0.1에서만 듣고, 밖에서는 Nginx를 거쳐야 들어옵니다 (`server.forward-headers-strategy: native`).
- 사진 버킷은 공개하지 않습니다. 앱이 글 상태를 확인한 뒤 사진을 보내 줍니다.
- 보안 머리말: CSP(위반은 `/api/csp-report`로 기록), HSTS, Referrer-Policy, Permissions-Policy

### 위치 정책 (LOC-02)

현재 위치(GPS)는 **기기 밖으로 보내지 않습니다.** 브라우저가 가장 가까운 시·군·구를 골라 그 중심 좌표만 서버에 보내고, 받은 목록을 기기 안에서 실제 위치로 다시 정렬합니다.

---

## 5. 저장소 구조

```
IEUM/
├── src/main/java/com/ieum/ieum/
│   ├── store/  market/  festival/  tour/  theme/   # 장소·축제·관광·테마
│   ├── community/  notification/  favorite/        # 동네 이야기·알림·찜
│   ├── auth/  user/  policy/  mail/                # 로그인·회원·약관·메일
│   ├── admin/  report/  license/  place/           # 관리자·제보·인허가·장소 연결
│   ├── onnuri/  news/  search/  notice/  region/
│   └── global/                                     # security, config, error, seo
├── src/main/resources/
│   ├── static/            # HTML · css/ · js/ · images/ · sw.js
│   ├── application.yml    # 공통 설정 (비밀 값 없음)
│   └── application-prod.yml
├── sql/                   # DB 만들기 스크립트 01~32 + README
├── deploy/                # 서버 준비·배포 스크립트, Nginx, systemd, 설정 견본
├── scripts/               # 데이터 변환 스크립트
├── brand/                 # 심볼 마크 원본
├── Jenkinsfile
└── README.md
```

`application-local.yml`(내 PC용 키·비밀번호)은 Git에 올리지 않습니다.

---

## 6. 시작하기

### 사전 요구사항

Java 21 · MySQL 8 · 각 API 키(공공데이터포털, 카카오, TMAP, 브이월드, 네이버)

### DB 만들기

`sql/` 폴더의 파일을 **번호 순서대로** 실행합니다 (`sql/README.md` 참고).

```bash
mysql -u root -p < sql/01_schema_base.sql
mysql -u root -p ieum < sql/02_data_regions.sql
# ... 32_schema_comment_edit.sql 까지
```

좌표·지역 코드가 비어 있는 가게·시장은 서버가 켜질 때 브이월드로 채웁니다.

### 내 PC 설정

`src/main/resources/application-local.yml`을 만들고 아래 항목을 채웁니다. 이름은 `deploy/application-prod.example.yml`과 같습니다.

- DB 접속 정보, `jwt.secret`
- 카카오 로그인(`spring.security.oauth2.client.registration.kakao`), `kakao.rest-api-key`·`javascript-key`·`admin-key`
- `public-data.service-key`, `tmap.app-key`, `vworld.api-key`, `naver.client-id`·`client-secret`
- 메일(이메일 인증·비밀번호 찾기), 운영자 이름·문의처(`ieum.policy`)

카카오 admin 키는 서버에서만 씁니다. 화면(JS)에 넣거나 커밋하지 않습니다.

### 실행 · 빌드

```bash
./gradlew bootRun          # http://localhost:8080 (local 프로필)
./gradlew clean bootJar    # 결과: build/libs/*.jar (plain jar 제외)
```

---

## 7. 운영 배포

자세한 순서(AWS 화면 체크리스트)는 `deploy/README.md`에 있습니다.

### 운영 환경

| 항목 | 값 |
|------|-----|
| URL | https://ieum.me.kr |
| 서버 | AWS EC2 t3.medium (Ubuntu 24.04), 탄력적 IP |
| DB | RDS MySQL 8.4, 파라미터 그룹 `time_zone=Asia/Seoul`, 자동 백업 7일 |
| 사진 | S3 (퍼블릭 차단, EC2 IAM 역할로 접근) |
| 프로세스 | systemd `ieum` (127.0.0.1:8080) |
| 비밀 설정 | `/etc/ieum/application-prod.yml` |
| 로그 | `/var/log/ieum/ieum.log` (20MB × 30개) |
| CI/CD | Jenkins `jenkins.ieum.me.kr` (127.0.0.1:8090) |

### 서버 준비

```bash
scp -i 내키.pem -r deploy ubuntu@<탄력적IP>:~/
ssh -i 내키.pem ubuntu@<탄력적IP>
cd ~/deploy && sudo bash setup-ubuntu.sh
```

`setup-ubuntu.sh`가 시간대·스왑 설정, Java 21·Nginx·certbot·MySQL 클라이언트·Jenkins 설치, `ieum` 서비스 등록까지 합니다. HTTPS 인증서 발급과 Nginx 설정(`deploy/nginx/`)은 `deploy/README.md`의 다음 단계에서 합니다.
비밀 설정은 견본을 복사해 채웁니다.

```bash
sudo cp application-prod.example.yml /etc/ieum/application-prod.yml
sudo nano /etc/ieum/application-prod.yml
```

### 배포 흐름

`main`에 push → GitHub Webhook → Jenkins `빌드`(bootJar) → `배포`(`sudo /opt/ieum/bin/deploy.sh <jar> <빌드번호>`)

- `deploy.sh`는 새 jar로 서버를 다시 켜고, 살아나지 않으면 **바로 앞 버전으로 되돌립니다.**
- Jenkins sudo 권한은 `deploy.sh` 하나만 허용합니다.

### 확인

```bash
systemctl status ieum
sudo tail -f /var/log/ieum/ieum.log
sudo grep "TMAP 보행자 경로" /var/log/ieum/ieum.log | tail -5
sudo grep "CSP 위반" /var/log/ieum/ieum.log | tail -5
```

### 배포 완료 기준

HTTPS 접속 → 카카오 로그인·이메일 가입 → 지도·축제 표시 → 동네 이야기 사진 올리기(S3) → `ieum active`

---

## 8. API 개요

### 인증 · 회원

```
POST   /api/auth/signup | login | refresh | logout
GET    /api/auth/signup-options | nickname-check
POST   /api/auth/email-verification[/confirm]
POST   /api/auth/password-reset[/confirm|/complete]
PUT    /api/auth/password
GET    /oauth2/authorization/kakao            # 카카오 로그인
GET|PATCH|DELETE /api/users/me                # 내 정보·탈퇴
POST   /api/users/me/agreements               # 약관 동의
GET    /api/policy
```

### 장소 · 축제 · 여행

```
GET    /api/stores[/{id}]                     # 백년가게
GET    /api/creators[/regions]                # 로컬크리에이터
GET    /api/markets[/{id}]   /api/markets/{id}/story
GET    /api/festivals | /nearby | /{id}
GET    /api/tour/nearby | /course | /walk     # 주변 관광지, 코스, 도보 경로
GET    /api/themes[/{id}]?radius=auto|2000|10000|30000|all
GET    /api/regions   /api/places/search
GET    /api/search[/{type}]                   # 통합 검색
GET    /api/news   /api/news/market/{id}
GET    /api/notices[/{id}]
GET    /api/config/map                        # 지도 JS 키
```

### 이용자 활동

```
POST   /api/stores/{storeId}/reports          # 영업 정보 제보
GET|PUT|DELETE /api/favorites/{type}/{id}     # 찜
GET    /api/users/me/favorites | posts | reports
GET    /api/notifications[/unread-count]   POST /{id}/read | /read-all
GET|POST /api/posts   GET|PUT|DELETE /api/posts/{id}
POST|DELETE /api/posts/{id}/like
POST   /api/posts/{id}/comments   PUT|DELETE /api/comments/{id}
POST   /api/posts/photos   GET /api/posts/photos/{name}
POST   /api/community/reports
```

### 관리자 (`/api/admin`)

```
GET    /summary | /actions
GET    /reports[/counts]   /stores/{storeId}   POST /stores/{storeId}/review | edit
GET|POST /closures[/summary|/import|/{storeId}]   # 폐업 감지
/places/{id}/candidates|place|no-place|category|address   /places/jobs[/geocode|/link]
/stories[/{marketId}][/publish|/hide]   /market-links
/community[/{type}/{id}/hide|restore]   /community/posts/{id}/recommend
/users[/{id}][/withdraw|/ban]   /users/{id}/suspend
/notices[/{id}]
```

### 기타

```
GET    /robots.txt   /sitemap.xml            # 검색·링크 미리보기
POST   /api/csp-report
```

---

## 9. 개발 이력

| 단계 | 내용 | 상태 |
|------|------|------|
| 1 | 지도 화면 · 카카오 로그인 · JWT 인증 (MAP-01, SYS-01) | ✅ |
| 2 | 디자인 시스템 · 축제 달력 · 영업 정보 제보 · 전통시장 (UI-01, FES-01, STO-01, MKT-01) | ✅ |
| 3 | 홈 새 디자인 · 관리자 제보 처리 · 공지사항 · 통합 검색 (UI-02~03, ADM-01, NOT-01, SRC-01) | ✅ |
| 4 | 테마로 걷기 · 시장 정보 갱신 · 폐업 감지 · 온누리상품권 (THM-01, DATA-01~04) | ✅ |
| 5 | 주변 관광지·코스 · 동네 소식 · 로컬크리에이터 · 시장 이야기 · 카카오 장소 (TOUR-01, NEWS-01, LOC-01, MKT-02, DATA-05) | ✅ |
| 6 | 약관·처리방침 · 동네 이야기 게시판 · 관리자·홈 레이아웃 (POL-01, COM-01, UI-04) | ✅ |
| 7 | 닉네임 검사 · 심볼 마크 · 이메일 인증 · 비밀번호 찾기 · 공유 미리보기 · 오류 화면 (AUTH-01~03, BRAND-01, SEO-01, ERR-01) | ✅ |
| 8 | 찜·내 주변·알림 · 회원 관리 · 사진·금칙어 · 카카오 연결 끊기 · 관리자 요약·작업 기록 · 댓글 고치기 (FAV, ADM-02~03, COM-02~04, SRCH-02, SEO-02) | ✅ |
| 9 | AWS 운영 배포 (EC2 + RDS + S3, Nginx, Let's Encrypt, Jenkins) (DEPLOY-01) | ✅ |
| 10 | TMAP 도보 경로 수정 · 사용자 의견 반영 UX 개선 (TMAP-01, 홈·축제·지도·테마·코스) | ✅ |
| 11 | 방문자 통계 (IP 저장 없이) · CSP enforce 전환 | 🔜 |

---

## 10. 문서

| 문서 | 경로 |
|------|------|
| DB 만들기 순서 | `sql/README.md` |
| 제보·관리자 지정 쿼리 | `sql/report_queries.sql` |
| 배포 준비 (AWS 체크리스트) | `deploy/README.md` |
| 운영 설정 견본 | `deploy/application-prod.example.yml` |
| Nginx 설정 | `deploy/nginx/` |
| S3 IAM 정책 | `deploy/s3-policy.json` |
| 이용약관 · 개인정보 처리방침 | `src/main/resources/static/terms.html`, `privacy.html` |

---

## 11. 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| 502 Bad Gateway | 앱(8080)이 안 켜짐 | `systemctl status ieum`, `journalctl -u ieum -n 80` |
| 가입·글이 하루 전 날짜로 기록 | DB 시간대가 UTC | RDS 파라미터 그룹 `time_zone=Asia/Seoul` 적용 후 재부팅, `SELECT NOW();` 확인 |
| `time_zone` 항목이 안 보임 | 파라미터 그룹 패밀리가 다름 | `mysql8.4` 패밀리로 새로 만들기 |
| 사진 올리기 실패 (S3) | EC2에 IAM 역할 미연결 | 인스턴스 › 보안 › IAM 역할 수정 → `ieum-ec2-role` |
| 카카오 로그인 KOE006 | 리다이렉트 URI 미등록 | 카카오 개발자 콘솔에 `https://ieum.me.kr/login/oauth2/code/kakao` 등록 |
| 지도가 안 뜸 | JS SDK 도메인 미등록 | 카카오 앱 › 플랫폼 › Web에 `https://ieum.me.kr` 등록 |
| 코스가 직선으로만 나옴 | TMAP 요청 거절(400) 또는 한도 | 로그의 `TMAP 보행자 경로 실패: HTTP …` 코드 확인 |
| Jenkins 인증 실패 | GitHub 토큰 미등록·만료 | Jenkins Credentials `github-ieum`에 토큰 다시 등록 |
| 브라우저에 일부 스크립트가 막힘 | CSP | 로그의 `CSP 위반`에서 빠진 출처 확인 후 허용 목록에 추가 |
| RDS 접속 안 됨 | 보안 그룹 | `ieum-db` 인바운드 3306 소스를 `ieum-web` 보안 그룹으로 |

---

## 12. 기여 가이드

- `main`에 직접 커밋하지 않습니다. `main`에 올리면 바로 운영에 배포됩니다.
- `develop`에서 브랜치 분기 → PR → `develop` → `main`
- 브랜치 이름: `feature/<작업번호>-<내용>`, `fix/<작업번호>-<내용>` (예: `feature/THM-01-themes`)
- 이슈 본문은 체크박스(`- [ ]`) 형식, 커밋 메시지 끝에 `Refs #이슈번호`
- `application-local.yml`, `/etc/ieum/application-prod.yml`, API 키·비밀번호는 커밋하지 않습니다.
- 데이터는 공식 API·공공데이터만 씁니다 (크롤링 안 함). DB를 바꾸면 `sql/`에 다음 번호 파일을 더하고 `sql/README.md`에 적습니다.

---

## 13. 라이선스 · 팀

- **라이선스:** 미정
- **데이터 출처:** 공공데이터포털·한국관광공사·소상공인시장진흥공단·행정안전부 등 각 기관의 이용 조건을 따릅니다.
- **팀 / 작성자:** 1인 개발
- **사이트:** https://ieum.me.kr
