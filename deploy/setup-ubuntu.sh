#!/bin/bash
# 이음 EC2 처음 준비 (DEPLOY-01) — Ubuntu 24.04 LTS 에서 한 번만 실행
#   sudo bash setup-ubuntu.sh
# 하는 일: 시간대, 스왑, Java 21, Nginx, certbot, MySQL 클라이언트, Jenkins 설치, ieum 사용자·폴더·서비스 등록
# 다시 실행해도 안전하게 만들었어요 (이미 된 것은 건너뜀).
set -euo pipefail
cd "$(dirname "$0")"

echo "== 1. 시간대 Asia/Seoul"
timedatectl set-timezone Asia/Seoul

echo "== 2. 스왑 2GB (빌드할 때 메모리가 모자라지 않게)"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "== 3. 프로그램 설치 (Java 21, Nginx, certbot, MySQL 클라이언트)"
apt-get update
apt-get install -y openjdk-21-jdk-headless nginx certbot mysql-client-core-8.0 curl unzip fontconfig

echo "== 4. Jenkins (공식 저장소, LTS)"
# 2026년 1월부터 Jenkins 저장소 서명 키가 jenkins.io-2026.key 로 바뀌었어요
if [ ! -f /usr/share/keyrings/jenkins-keyring.asc ]; then
  curl -fsSL https://pkg.jenkins.io/debian-stable/jenkins.io-2026.key | tee /usr/share/keyrings/jenkins-keyring.asc > /dev/null
  echo "deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] https://pkg.jenkins.io/debian-stable binary/" > /etc/apt/sources.list.d/jenkins.list
  apt-get update
fi
apt-get install -y jenkins
mkdir -p /etc/systemd/system/jenkins.service.d
install -m 644 jenkins-override.conf /etc/systemd/system/jenkins.service.d/override.conf

echo "== 5. ieum 사용자와 폴더"
id ieum >/dev/null 2>&1 || useradd --system --home /opt/ieum --shell /usr/sbin/nologin ieum
mkdir -p /opt/ieum/releases /opt/ieum/bin /etc/ieum /var/log/ieum /var/www/certbot
chown -R ieum:ieum /opt/ieum/releases /var/log/ieum
chown root:ieum /etc/ieum
chmod 750 /etc/ieum
install -m 755 deploy.sh /opt/ieum/bin/deploy.sh
if [ ! -f /etc/ieum/application-prod.yml ]; then
  install -o root -g ieum -m 640 application-prod.example.yml /etc/ieum/application-prod.yml
  echo "   → /etc/ieum/application-prod.yml 를 만들었어요. 비밀 값을 채워 주세요 (sudo nano /etc/ieum/application-prod.yml)"
fi

echo "== 6. Jenkins 가 배포 스크립트만 관리자 권한으로 실행하게"
echo 'jenkins ALL=(root) NOPASSWD: /opt/ieum/bin/deploy.sh' > /etc/sudoers.d/ieum-deploy
chmod 440 /etc/sudoers.d/ieum-deploy
visudo -cf /etc/sudoers.d/ieum-deploy

echo "== 7. 이음 서비스 등록"
install -m 644 ieum.service /etc/systemd/system/ieum.service
systemctl daemon-reload
systemctl enable ieum
systemctl enable --now jenkins
systemctl restart jenkins

echo "== 8. Nginx (인증서 받기 전 http 설정)"
install -m 644 nginx/ieum-ssl.conf nginx/ieum-proxy.conf /etc/nginx/snippets/
if [ ! -f /etc/letsencrypt/live/ieum.me.kr/fullchain.pem ]; then
  install -m 644 nginx/ieum-http.conf /etc/nginx/sites-available/ieum.conf
else
  install -m 644 nginx/ieum.conf /etc/nginx/sites-available/ieum.conf
fi
ln -sfn /etc/nginx/sites-available/ieum.conf /etc/nginx/sites-enabled/ieum.conf
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx

echo
echo "끝났어요. 다음 순서 (README 의 6~9단계):"
echo "  1) 인증서: sudo certbot certonly --webroot -w /var/www/certbot -d ieum.me.kr -d www.ieum.me.kr -d jenkins.ieum.me.kr"
echo "  2) https 설정: sudo install -m 644 nginx/ieum.conf /etc/nginx/sites-available/ieum.conf && sudo nginx -t && sudo systemctl reload nginx"
echo "  3) Jenkins 첫 비밀번호: sudo cat /var/lib/jenkins/secrets/initialAdminPassword  →  https://jenkins.ieum.me.kr"
