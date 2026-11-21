# 이음 DB 만들기 (MySQL 8)

새 PC나 서버에서 DB를 처음 만들 때 **번호 순서대로** 실행해요.

| 순서 | 파일 | 내용 |
|---|---|---|
| 1 | `01_schema_base.sql` | `ieum` 데이터베이스, 지역(`regions`)·가게(`stores`) 표 |
| 2 | `02_data_regions.sql` | 시·군·구 지역 코드 234개 |
| 3 | `03_data_stores.sql` | 백년가게 1,511곳 (공공 지정리스트의 업체명·주소 + 카카오맵 장소 ID. 좌표·지역은 서버가 켜질 때 브이월드로 채움) |
| 4 | `04_schema_auth.sql` | 회원(`users`), 로그인 유지 토큰(`refresh_tokens`) 표 |
| 5 | `05_schema_festival.sql` | 축제(`festivals`) 표. 내용은 서버가 공공데이터 API에서 받아 채움 |
| 6 | `06_schema_report.sql` | 영업 정보 제보(`store_reports`) 표 |
| 7 | `07_schema_markets.sql` | 전통시장(`markets`) 표 |
| 8 | `08_data_markets.sql` | 전통시장 1,403곳 (좌표가 없는 곳과 지역 코드는 서버가 켜질 때 브이월드·주소로 채움) |
| 9 | `09_schema_admin.sql` | 제보 처리 기록 칸(`store_reports.reviewed_by/reviewed_at`), 가게 정보 수정 기록(`store_edit_logs`) 표 |
| 10 | `10_schema_notice.sql` | 공지사항(`notices`) 표 |
| 11 | `11_schema_token_family.sql` | 로그인 유지 토큰에 묶음 칸(`refresh_tokens.family_id`) 추가 |
| 12 | `12_schema_market_facility.sql` | 전통시장 시장코드 칸(`markets.market_code`), 시설 정보(`market_facilities`) 표 |
| 13 | `13_data_market_facilities.sql` | 전통시장 시설 정보 1,403곳 (소상공인시장진흥공단 전통시장현황, 2026-08-04 기준). 시장명+주소가 같은 1,376곳은 자동 연결 |
| 14 | `14_schema_store_license.sql` | 백년가게 인허가 영업상태 확인 결과(`store_license_checks`) 표 |
| 15 | `15_schema_onnuri.sql` | 온누리상품권 가맹 정보 표(`store_onnuri`, `market_onnuri`) |
| 16 | `16_data_onnuri.sql` | 온누리상품권 가맹 정보 (전국 가맹점 현황 2026-07-31 기준): 백년가게 583곳, 전통시장 968곳 |
| 17 | `17_schema_token_cleanup.sql` | 로그인 유지 토큰 정리용 색인(`refresh_tokens.expires_at`) |
| 18 | `18_schema_tour.sql` | 주변 관광지 저장(`tour_spots`), 지역별 불러온 기록(`tour_area_fetches`) |
| 19 | `19_schema_creator.sql` | 로컬크리에이터 연도별 선정 내용(`creator_selections`) 표, 지역별 보기 칸(`stores.area_sido`, `area_sigungu`) |
| 20 | `20_data_creators.sql` | 로컬크리에이터 1,339곳 (가게 표에 `type=CREATOR`로, 지역은 주소에서 뽑고, 좌표는 서버가 켜질 때 브이월드로 채움) |
| 21 | `21_schema_market_story.sql` | 시장 이야기(`market_stories`), 관광공사 시장 소개글 저장(`market_tour_info`) |
| 22 | `22_data_market_stories.sql` | 대표 시장 15곳 이야기 초안 (출처 포함, 관리자가 확인 후 공개) |
| 23 | `23_kakao_cleanup.sql` | 카카오 로컬 API 이용 조건 맞추기: 카카오에서 온 좌표·주소·전화번호·분류를 한 번만 지우고(장소 ID는 남김), 분류·영업 확인을 인허가 결과로 채움. 지운 기록은 `ieum_data_fixes` 표 |
| 24 | `24_schema_policy.sql` | 회원의 이용약관·개인정보 처리방침 동의 기록 칸 (`users.agreed_policy_version`, `agreed_at`). 실행 전에 가입한 회원은 다음 로그인 때 동의 화면이 나와요 |
| 25 | `25_schema_community.sql` | 동네 이야기 게시판 표 4개: 글(`posts`), 댓글(`post_comments`), 공감(`post_likes`), 신고(`community_reports`). 다른 표는 건드리지 않아요 |
| 26 | `26_schema_email_verification.sql` | 가입 이메일 인증 표(`email_verifications`)와 `users.email_verified_at` 칸. 이미 가입한 이메일 회원은 가입한 때로 '인증됨' 표시 |
| 27 | `27_schema_favorites.sql` | 찜한 곳 표(`favorites`): 회원이 찜한 가게·시장. 회원 줄을 지우면 함께 지워짐 |
| 28 | `28_schema_notifications.sql` | 알림 표(`notifications`): 댓글·제보 처리·가림·추천 확인 알림. 90일 보관, 회원 줄을 지우면 함께 지워짐 |
| 29 | `29_schema_banned_identities.sql` | 재가입 막기 표(`banned_identities`): 관리자가 강제 탈퇴시킨 회원의 이메일·카카오 회원번호를 HMAC 값으로만 보관. '재가입 허용' 때 지움 |
| 30 | `30_schema_post_photos.sql` | 동네 이야기 사진 표(`post_photos`): 글에 붙인 사진의 파일 이름·크기·설명·순서. 파일은 서버 폴더(`ieum.upload.dir`)에 있음 |
| 31 | `31_schema_admin_actions.sql` | 관리자 작업 기록 표(`admin_actions`): 관리자 API로 바꾼 일을 한 줄씩. 1년 보관 |
| - | `report_queries.sql` | 제보 확인 쿼리, **관리자 지정·해제**, 수정 기록 보기 (필요할 때 골라서 실행) |

## 실행 방법 (MySQL Workbench)

1. **File → Open SQL Script**로 파일을 엽니다.
   (File → Run SQL Script는 한글 파일에서 cp949 오류가 날 수 있어요.)
2. 번개 모양(⚡) 버튼으로 실행합니다.
3. 01부터 31까지 차례로 반복합니다.

## 다시 실행해도 괜찮아요

모든 파일은 **여러 번 실행해도 기존 데이터를 지우지 않아요.**
- 표가 이미 있으면 그대로 둡니다 (`CREATE TABLE IF NOT EXISTS`).
- 지역·가게가 이미 들어가 있으면 건너뜁니다 (`ON DUPLICATE KEY UPDATE`).
- 전통시장은 같은 시장(시장명+주소)이면 내용만 최신으로 바꾸고, 서버가 채운 좌표·지역은 그대로 둡니다.
  1년에 한 번 새 데이터가 나오면 `08_data_markets.sql`만 새로 만들어 실행하면 돼요.
- 시장 시설 정보(13)도 1년에 한 번 새 CSV로 다시 만들어 실행해요. 시설 값만 최신으로 바뀌고,
  관리자가 '시장 연결 확인'에서 처리한 연결은 그대로 둡니다.
- 온누리상품권(16)도 1년에 한 번 새 가맹점 현황 CSV로 다시 만들어 실행해요. 새 파일에 없는 옛 줄은 지워져요.

## 주의

- **표를 지우는 파일은 없어요.** DB를 완전히 처음부터 다시 만들어야 할 때만 Workbench에서 직접
  `DROP DATABASE ieum;`을 실행한 뒤 01부터 다시 실행하세요. 회원·제보·축제 데이터가 모두 사라지니 신중하게!
- 이미 있는 표에 칸을 더할 때도(09처럼) 이미 있으면 건너뛰게 만들어서 여러 번 실행해도 안전해요.
- 23번은 지우기를 `ieum_data_fixes` 표에 기록해 두고 **한 번만** 해요. 다시 실행해도 서버가 새로 찾은 좌표는 지우지 않아요.
- 새 표가 생기면 `32_...sql`처럼 다음 번호로 추가해요.
- 25번 표들은 `users`를 참조해요. 시험 등으로 회원 줄을 직접 지울 때는 그 회원의 글·댓글·공감·신고를 먼저 지워야 해요 (탈퇴는 회원 줄을 지우지 않아서 상관없어요).
- DB 비밀번호 같은 설정은 이 폴더가 아니라 `application-local.yml`에 있어요 (커밋하지 않음).
