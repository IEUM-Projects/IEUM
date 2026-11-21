# DEPLOY-01 배포 준비: AWS (EC2 + RDS + S3) · Nginx + Let's Encrypt · Jenkins

> COM-04·SEO-02를 적용한 뒤에 덮어써 주세요.
> 정한 구성: **EC2 한 대**(이음 앱 + Jenkins + Nginx) + **RDS MySQL** + **S3**(동네 이야기 사진), 도메인 **ieum.me.kr**

```
사용자 ──https──▶ Nginx (EC2, 443) ──▶ 이음 앱 (127.0.0.1:8080) ──▶ RDS MySQL
                     │                         └──────────────▶ S3 (사진, IAM 역할로)
GitHub ──webhook──▶ └─ jenkins.ieum.me.kr ──▶ Jenkins (127.0.0.1:8090) ── 빌드 → deploy.sh
```

이 묶음은 두 부분이에요.
1. **코드 변경**: 덮어쓰고 지금처럼 GitHub에 올리면 돼요.
2. **서버 준비 파일과 순서**(`deploy/` 폴더, `Jenkinsfile`): AWS 화면에서 직접 하실 일이에요. 아래 순서대로 체크하며 진행해 주세요. 막히면 그 단계 번호와 화면·오류를 알려 주세요.

---

## 1부. 코드 변경

### 1-1. S3 사진 저장소 (`S3PhotoStorage`)
- `ieum.upload.storage: s3`이면 사진을 S3에, `local`(기본)이면 지금처럼 서버 폴더에 둬요.
  - 내 컴퓨터는 그대로 `local`이고, 운영(`prod`)만 `s3`예요.
- 버킷은 **공개하지 않아요.** 사진은 지금처럼 앱이 글 상태를 확인한 뒤 보내 줘요. 가린 글 사진 보호도 그대로예요.
- **열쇠를 어디에도 적지 않아요.** EC2에 붙인 **IAM 역할**의 임시 열쇠를 앱이 알아서 받아 쓰고, 만료 5분 전에 새로 받아요.
- 저장 위치: `s3://버킷/photos/ab/ab12….jpg`
- **AWS SDK를 더하지 않았어요.** 제 작업 환경에서 Maven 저장소에 접속할 수 없어서 SDK를 받아 시험할 수 없었어요.
  - 그래서 필요한 세 가지(넣기·꺼내기·지우기)만 AWS 서명 방식(Signature V4)으로 직접 요청해요.
  - 서명이 맞는지는 **AWS 공식 파이썬 라이브러리(botocore)가 계산한 값과 비교**해 확인했어요. `build.gradle`은 바꿀 필요 없어요.
  - 진짜 S3에는 배포 뒤 **9단계 확인**에서 처음 붙어요.

### 1-2. Nginx 뒤에서 https 알아보기
- `server.forward-headers-strategy: native`
  - Nginx가 붙여 주는 `X-Forwarded-Proto: https`를 믿어요.
  - 그래야 Secure 쿠키, **카카오 로그인 돌아올 주소**(`https://ieum.me.kr/login/oauth2/code/kakao`), HSTS, 미리보기 주소가 모두 https로 맞춰져요.
- 이 머리말은 같은 서버(127.0.0.1)·사설망에서 온 것만 믿어요. 밖에서 꾸며 보내도 소용없어요.

### 1-3. 보안 머리말 (`SecurityHeadersFilter`, `CspReportController`)
- **CSP**: 화면이 불러올 수 있는 스크립트·글꼴·그림 출처를 정해요. 누가 글에 스크립트를 끼워 넣어도 돌지 않게 하는 장치예요.
  - **처음엔 `report-only`**: 막지는 않고, 막혔을 것만 `/api/csp-report`로 받아 로그에 `CSP 위반`으로 남겨요.
  - 카카오맵·관광 사진처럼 운영에서만 나오는 주소가 빠졌는지 한 주쯤 로그를 본 뒤 **`enforce`로 바꿔요** (10단계).
  - 시험: 실제로 막는(`enforce`) 모드에서 우리 화면 17곳(지도·가게·동네 이야기·사진 올리기·내 정보 등)에 위반이 없었어요. 카카오맵은 가짜 스크립트라 진짜 카카오맵은 로그로 확인해야 해요.
- `Referrer-Policy`, `Permissions-Policy`(위치는 이음 화면만)를 더했어요. nosniff·X-Frame-Options·HSTS는 스프링 시큐리티가 이미 붙여요.

### 1-4. 운영 프로필 `application-prod.yml` (jar 안, 비밀 값 없음)
- 앱이 **127.0.0.1:8080에서만** 들어요. 밖에서는 Nginx를 거쳐야만 들어와요.
- 사이트 주소 `https://ieum.me.kr`. 전에 기억해 두라고 하셨던 `ieum.site.base-url`이 여기 들어갔어요.
- 그 밖에 이메일 인증 켜기, 메일 `smtp`, 사진 `s3`, 로그 파일 `/var/log/ieum/ieum.log`(20MB씩 30개), 끌 때 처리 중인 요청 마치기가 들어 있어요.
- **비밀 값**(DB, 카카오·JWT·공공데이터 키, 메일 비밀번호, 버킷 이름)은 서버의 `/etc/ieum/application-prod.yml`에만 둬요. 견본은 `deploy/application-prod.example.yml`이에요.

### 덮어쓸 파일

| 구분 | 파일 |
|---|---|
| 새 서버 | `community/photo/S3PhotoStorage.java`, `global/config/SecurityHeadersFilter.java`, `global/config/CspReportController.java` |
| 바뀐 서버 | `community/photo/LocalPhotoStorage.java`(local일 때만), `global/config/SecurityConfig.java`(`/api/csp-report` 허용) |
| 설정 | `application.yml`(forward-headers, `upload.storage`, `security.csp`), **새** `application-prod.yml` |
| 새 배포 파일 | `Jenkinsfile`(저장소 맨 위), `deploy/` 폴더 전체 |

---

## 2부. 서버 준비 순서

> AWS 콘솔 오른쪽 위 지역을 **서울(ap-northeast-2)**로 두고 시작해요.
> 비밀번호·키는 저에게 보내지 않으셔도 돼요.

### 1단계. S3 버킷
- [ ] S3 › 버킷 만들기
  - 이름 예: `ieum-photos-<아무 글자>` (전 세계에서 하나뿐이어야 해요)
  - 지역 서울
  - **'모든 퍼블릭 액세스 차단' 켜 둔 채로**
  - 버전 관리 **끔**: 켜 두면 지운 사진이 옛 버전으로 남아서 처리방침('바로 지움')과 어긋나요
  - 기본 암호화(SSE-S3) 그대로

### 2단계. IAM 역할 (EC2가 S3에 쓰는 권한)
- [ ] IAM › 정책 생성 › JSON에 `deploy/s3-policy.json`을 붙여 넣고 `<S3-버킷-이름>`을 바꿔요. 이름 예: `ieum-s3-photos`
- [ ] IAM › 역할 생성 › 'AWS 서비스 - EC2' › 위 정책 연결. 이름 예: `ieum-ec2-role`

### 3단계. EC2
- [ ] EC2 › 인스턴스 시작
  - **Ubuntu Server 24.04 LTS**
  - 유형 **t3.medium**: 메모리 4GB. 앱 + Jenkins 빌드를 함께 돌리려면 이 정도가 필요해요
  - 저장소 30GB gp3
  - 키 페어를 새로 만들어 `.pem` 파일을 안전한 곳에 보관해요
  - 고급 세부 정보 › IAM 인스턴스 프로파일: `ieum-ec2-role`
  - 메타데이터 버전: **V2만**(기본값)
- [ ] 보안 그룹(예: `ieum-web`) 인바운드
  - SSH 22: **내 IP만**
  - HTTP 80: 모두
  - HTTPS 443: 모두
- [ ] **탄력적 IP**를 하나 받아 이 인스턴스에 연결해요. 서버를 껐다 켜도 주소가 바뀌지 않게요.

### 4단계. RDS MySQL
- [ ] RDS › 파라미터 그룹 생성 (mysql8.4), 이름 예: `ieum-mysql84`
  - `time_zone` = **Asia/Seoul**: 날짜 비교가 어긋나지 않게요 (전에 기억해 두기로 한 것)
  - `character_set_server` = utf8mb4
- [ ] RDS › 데이터베이스 생성
  - MySQL **8.4**, 템플릿은 '프리 티어' 또는 '개발/테스트'
  - 인스턴스 db.t4g.micro (나중에 키울 수 있어요)
  - 마스터 사용자·비밀번호를 정해 두세요
  - 퍼블릭 액세스 **아니요**
  - 새 보안 그룹(예: `ieum-db`)
  - 추가 구성: 초기 DB 이름 비움, 파라미터 그룹 `ieum-mysql84`, **자동 백업 7일**, **삭제 방지 켜기**, 스토리지 암호화 켜기
- [ ] `ieum-db` 보안 그룹 인바운드: MySQL 3306, 소스 = **`ieum-web` 보안 그룹**. EC2에서만 들어와요.

### 5단계. 도메인 연결 (도메인을 산 곳의 DNS 관리 화면)
- [ ] A 레코드 세 개를 모두 **탄력적 IP**로
  - `ieum.me.kr` (`@`)
  - `www.ieum.me.kr`
  - `jenkins.ieum.me.kr`
- [ ] 몇 분~몇 시간 뒤 내 컴퓨터에서 `nslookup ieum.me.kr` → 탄력적 IP가 나오면 돼요.

### 6단계. 서버에 프로그램 깔기
- [ ] 내 컴퓨터에서 `deploy` 폴더를 서버로 보내요 (Windows PowerShell):
  ```
  scp -i 내키.pem -r deploy ubuntu@<탄력적IP>:~/
  ```
- [ ] 서버에 접속해서 실행해요:
  ```
  ssh -i 내키.pem ubuntu@<탄력적IP>
  cd ~/deploy && sudo bash setup-ubuntu.sh
  ```
  - 하는 일: 시간대 서울, 스왑 2GB, Java 21, Nginx, certbot, MySQL 클라이언트, Jenkins 설치, `ieum` 사용자·폴더, 서비스 등록, http용 Nginx 설정
- [ ] **https 인증서** (5단계 DNS가 연결된 뒤에):
  ```
  sudo certbot certonly --webroot -w /var/www/certbot -d ieum.me.kr -d www.ieum.me.kr -d jenkins.ieum.me.kr
  sudo install -m 644 ~/deploy/nginx/ieum.conf /etc/nginx/sites-available/ieum.conf
  sudo nginx -t && sudo systemctl reload nginx
  ```
  - 인증서는 자동으로 갱신돼요. 갱신된 인증서를 Nginx가 읽게 한 번만 더 해 주세요:
    `echo 'systemctl reload nginx' | sudo tee /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh && sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh`

### 7단계. DB 채우기
- [ ] 서버에서 RDS에 접속해 이음 DB와 앱 전용 계정을 만들어요:
  ```
  mysql -h <RDS-엔드포인트> -u <마스터사용자> -p
  CREATE DATABASE IF NOT EXISTS ieum CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  CREATE USER 'ieum_app'@'%' IDENTIFIED BY '<새 비밀번호>';
  GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES ON ieum.* TO 'ieum_app'@'%';
  ```
- [ ] 내 컴퓨터의 저장소 `sql` 폴더를 서버로 보내고(`scp -i 내키.pem -r sql ubuntu@<IP>:~/`), **`sql/README.md`의 순서대로 01부터 32까지** 실행해요:
  ```
  for f in ~/sql/[0-3][0-9]_*.sql; do echo "$f"; mysql -h <RDS-엔드포인트> -u <마스터사용자> -p<비밀번호> < "$f" || break; done
  ```
  - 축제는 서버를 켤 때 자동으로 받아요 (`on-startup: empty`).
  - 폐업 의심(인허가 CSV)은 배포 뒤 관리자 화면에서 다시 올려요.
- [ ] 관리자 계정: 운영 사이트에서 가입한 뒤 `UPDATE ieum.users SET role='ADMIN' WHERE email='<내 메일>';`

### 8단계. 운영 비밀 설정
- [ ] `sudo nano /etc/ieum/application-prod.yml`에서 `<...>`를 모두 채워요 (견본에 설명이 있어요).
  - DB: 7단계의 `ieum_app` 계정
  - **`jwt.secret`은 운영용으로 새로** 만들어요: `openssl rand -base64 48`
  - 메일, 카카오(어드민 키 포함), 공공데이터·TMAP·네이버·브이월드 키, S3 버킷 이름
- [ ] 파일 권한 확인: `ls -l /etc/ieum/application-prod.yml` → `-rw-r----- root ieum`
- [ ] **카카오 Developers**
  - 플랫폼 › Web에 `https://ieum.me.kr`, `https://www.ieum.me.kr`를 더해요. 지도 JavaScript 키도 이 도메인에서 써요.
  - 카카오 로그인 › Redirect URI에 `https://ieum.me.kr/login/oauth2/code/kakao`를 더해요.
- [ ] **브이월드**: 인증키의 서비스 URL에 `ieum.me.kr`

### 9단계. Jenkins와 첫 배포
- [ ] `sudo cat /var/lib/jenkins/secrets/initialAdminPassword` → `https://jenkins.ieum.me.kr`에서 입력
  - '추천 플러그인 설치'를 고르고, 관리자 계정은 **긴 비밀번호**로 만들어요
- [ ] Jenkins 관리 › Plugins에서 **GitHub** 플러그인이 깔렸는지 확인해요 (보통 추천에 들어 있어요)
- [ ] GitHub 저장소 읽기 권한
  - 비공개 저장소라면 GitHub › Settings › Developer settings › Fine-grained token: 이 저장소만, Contents **읽기**
  - Jenkins › Credentials에 'Username with password'(사용자 = GitHub 아이디, 비밀번호 = 토큰)로 넣어요
- [ ] 새 Item › **Pipeline**, 이름 `ieum`
  - Build Triggers: 'GitHub hook trigger for GITScm polling' 체크
  - Pipeline: 'Pipeline script from SCM' › Git › 저장소 주소와 위 Credentials › 브랜치 `*/main` › Script Path `Jenkinsfile`
- [ ] GitHub 저장소 › Settings › Webhooks › Add
  - URL `https://jenkins.ieum.me.kr/github-webhook/`
  - Content type `application/json`
  - Just the push event
- [ ] **이 묶음을 develop → main에 올리면** 자동으로 빌드·배포돼요. 처음 한 번은 Jenkins에서 '지금 빌드'를 눌러도 돼요.
  - `deploy.sh`가 새 jar로 바꿔 켜고 2분 안에 `/api/regions`가 답하는지 봐요. **안 되면 앞 버전으로 되돌리고** 빌드를 실패로 표시해요.
  - 서버 로그: `journalctl -u ieum -f`, `tail -f /var/log/ieum/ieum.log`

### 배포 뒤 확인
- [ ] `https://ieum.me.kr` 자물쇠, `http://`·`www.`로 열면 `https://ieum.me.kr`로 바뀌는지
- [ ] 지도·가게·축제가 보이는지 (카카오맵 키 도메인)
- [ ] 이메일 가입(인증 메일 도착), **카카오 로그인**
- [ ] 동네 이야기에 사진 올리기 → S3 콘솔의 `photos/` 아래에 파일 두 개(큰 사진·작은 사진)가 생기는지
- [ ] 관리자 화면, 요약
- [ ] 카카오톡으로 가게 링크 보내기 → 이름·설명 미리보기 (SEO-02)
- [ ] 네이버 서치어드바이저·Google Search Console에 `https://ieum.me.kr`과 `sitemap.xml` 등록 (SEO-01)

### 10단계. 한 주쯤 뒤: CSP 켜기
- [ ] `grep "CSP 위반" /var/log/ieum/ieum.log | sort | uniq -c | sort -rn | head`
  - 막힐 뻔한 주소가 이음에 필요한 것(예: 카카오맵의 새 주소)이면 저에게 알려 주세요. `SecurityHeadersFilter`에 더할게요.
- [ ] 문제없으면 `/etc/ieum/application-prod.yml`에 `ieum.security.csp: enforce`를 넣고 `sudo systemctl restart ieum`

---

## 알아 두기

- **약관 판**: 지금 `ieum.policy.version: 2`, `effective-date: 2026-12-01`이에요.
  - 실제로 공개하는 날이 다르면 `application.yml`(또는 서버 설정)의 시행일을 그날로 바꿔 주세요.
  - 아직 회원이 없으니 판 번호는 그대로 둬도 돼요.
- **개인정보 처리방침**: 서버 위탁 업체로 `hosting`에 'Amazon Web Services (서울 리전…)'을 넣게 해 두었어요. 처리방침 내용이 법적으로 맞는지는 운영 전에 한 번 따로 확인해 주세요. 저는 법률 전문가가 아니에요.
- **비용**: EC2 t3.medium, RDS, 탄력적 IP(연결 안 할 때), S3, 데이터 전송이 매달 나와요. 정확한 금액은 AWS 요금 계산기로 확인해 주세요.
- **관광 사진**: 관광공사 사진 주소가 `http://`예요. https 화면에서는 브라우저가 https로 바꿔 불러요 (`enforce` 때는 CSP가 직접 바꿔요). 배포 뒤 관광지 사진이 보이는지 한 번 봐 주세요.
- 1~32 SQL과 데이터 파일은 그대로예요. 이번에 SQL은 없어요 (다음 번호 33).

## 시험 (제 작업 환경)

- **배포 시험 30개 통과** (`deploytest`)
  - **S3**
    - 넣기 → `photos/ab/…`에 두 장, 모든 요청의 서명이 botocore와 같음, 역할의 세션 토큰
    - 자격 증명 한 번 받아 다시 씀, 만료 5분 안이면 새로 받음, 역할 열쇠가 바뀌어도 이어짐
    - 앱을 거쳐 사진 보기, 고치며 뺀 사진·지운 글의 S3 파일 삭제, 서버 폴더에는 아무것도 안 씀
    - S3가 거절하면 500(내용 숨김)·DB 줄 없음·로그
  - **보안 머리말**
    - report-only CSP, Referrer/Permissions/nosniff/X-Frame-Options
    - CSP 보고 받기(옛·새 형식, 주소의 `?` 뒤는 버림), 큰 본문 무시
  - **https 인식**: `X-Forwarded-Proto: https` → https 주소·HSTS, 그냥 http는 그대로, 카카오 돌아올 주소 `https://ieum.me.kr/...`
  - **enforce**: 화면 17곳(사진 올리기 미리보기 포함) 위반 없음, upgrade-insecure-requests는 https일 때만
  - **prod 프로필**: 켜짐, 미리보기 주소 `https://ieum.me.kr`, 127.0.0.1에서만 들음, 로그 파일
- **Nginx 설정 시험 12개 통과** (`nginxtest.sh`)
  - 이 컨테이너에 Nginx 1.24(Ubuntu 24.04와 같은 판)를 깔고, 시험용 인증서로 **실제 `ieum.conf`**를 돌렸어요. 이 환경에 IPv6가 없어 `listen [::]` 줄만 뺐어요.
  - http→https, www→대표 주소
  - 앱이 `https://ieum.me.kr`로 알아봄, HSTS 한 번, CSP 통과, JS gzip
  - 카카오 redirect_uri, 로그인 쿠키 Secure·HttpOnly
  - 13MB 사진은 Nginx가 막고 9MB는 앱까지, 관리자 CSV 30MB는 앱까지, Jenkins 주소 연결
  - 시험하다 고친 것
    - `proxy_read_timeout`이 두 번 들어가 Nginx가 안 켜지던 것
    - JS가 `text/javascript`라 gzip이 안 되던 것
- **deploy.sh 시험**: 가짜 systemctl로 첫 배포, 망가진 jar → 2분 대기 후 **앞 버전으로 되돌림**(앱 200), 6번 더 배포 → 최근 5개만 남음, 파일 주인 `ieum`·권한 640
- `ieum.service`는 `systemd-analyze verify` 통과
- `setup-ubuntu.sh`는 문법 검사와 패키지 이름(Ubuntu 24.04 저장소) 확인까지 했어요. 실제 EC2에서는 아직 돌려 보지 못했어요.
- **전체 회귀 60개: 모두 통과.** 카카오 로그인 시험(27개)은 Secure 쿠키를 끄고 따로 돌려 통과했어요.
