# 이음 DB 만들기 (MySQL 8)

새 PC나 서버에서 DB를 처음 만들 때 **번호 순서대로** 실행해요.

| 순서 | 파일 | 내용 |
|---|---|---|
| 1 | `01_schema_base.sql` | `ieum` 데이터베이스, 지역(`regions`)·가게(`stores`) 표 |
| 2 | `02_data_regions.sql` | 시·군·구 지역 코드 234개 |
| 3 | `03_data_stores.sql` | 백년가게 1,511곳 (좌표 포함) |
| 4 | `04_schema_auth.sql` | 회원(`users`), 로그인 유지 토큰(`refresh_tokens`) 표 |
| 5 | `05_schema_festival.sql` | 축제(`festivals`) 표. 내용은 서버가 공공데이터 API에서 받아 채움 |
| 6 | `06_schema_report.sql` | 영업 정보 제보(`store_reports`) 표 |
| 7 | `07_schema_markets.sql` | 전통시장(`markets`) 표 |
| 8 | `08_data_markets.sql` | 전통시장 1,403곳 (좌표가 없는 곳과 지역 코드는 서버가 켜질 때 카카오로 채움) |
| 9 | `09_schema_admin.sql` | 제보 처리 기록 칸(`store_reports.reviewed_by/reviewed_at`), 가게 정보 수정 기록(`store_edit_logs`) 표 |
| 10 | `10_schema_notice.sql` | 공지사항(`notices`) 표 |
| 11 | `11_schema_token_family.sql` | 로그인 유지 토큰에 묶음 칸(`refresh_tokens.family_id`) 추가 |
| - | `report_queries.sql` | 제보 확인 쿼리, **관리자 지정·해제**, 수정 기록 보기 (필요할 때 골라서 실행) |

## 실행 방법 (MySQL Workbench)

1. **File → Open SQL Script**로 파일을 엽니다.
   (File → Run SQL Script는 한글 파일에서 cp949 오류가 날 수 있어요.)
2. 번개 모양(⚡) 버튼으로 실행합니다.
3. 01부터 11까지 차례로 반복합니다.

## 다시 실행해도 괜찮아요

모든 파일은 **여러 번 실행해도 기존 데이터를 지우지 않아요.**
- 표가 이미 있으면 그대로 둡니다 (`CREATE TABLE IF NOT EXISTS`).
- 지역·가게가 이미 들어가 있으면 건너뜁니다 (`ON DUPLICATE KEY UPDATE`).
- 전통시장은 같은 시장(시장명+주소)이면 내용만 최신으로 바꾸고, 서버가 채운 좌표·지역은 그대로 둡니다.
  1년에 한 번 새 데이터가 나오면 `08_data_markets.sql`만 새로 만들어 실행하면 돼요.

## 주의

- **표를 지우는 파일은 없어요.** DB를 완전히 처음부터 다시 만들어야 할 때만 Workbench에서 직접
  `DROP DATABASE ieum;`을 실행한 뒤 01부터 다시 실행하세요. 회원·제보·축제 데이터가 모두 사라지니 신중하게!
- 이미 있는 표에 칸을 더할 때도(09처럼) 이미 있으면 건너뛰게 만들어서 여러 번 실행해도 안전해요.
- 새 표가 생기면 `12_...sql`처럼 다음 번호로 추가해요.
- DB 비밀번호 같은 설정은 이 폴더가 아니라 `application-local.yml`에 있어요 (커밋하지 않음).
