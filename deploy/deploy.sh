#!/bin/bash
# 이음 배포 스크립트 (DEPLOY-01)
# 사용: sudo /opt/ieum/bin/deploy.sh <빌드한 jar 경로> <빌드 번호>
#   1. jar 를 /opt/ieum/releases/ieum-<번호>.jar 로 복사
#   2. /opt/ieum/current.jar 가 새 파일을 가리키게 바꾸고 서버 다시 켜기
#   3. 2분 안에 /api/regions 가 200 으로 답하는지 확인 (DB 까지 붙었는지)
#   4. 안 되면 바로 앞 버전으로 되돌리고 실패로 끝냄
#   5. 옛 jar 는 최근 5개만 남김
set -euo pipefail

JAR="${1:?jar 경로를 주세요}"
BUILD="${2:-manual-$(date +%Y%m%d%H%M%S)}"
BASE=/opt/ieum
REL="$BASE/releases"
CUR="$BASE/current.jar"
HEALTH="http://127.0.0.1:8080/api/regions"

[ -f "$JAR" ] || { echo "jar 가 없어요: $JAR"; exit 1; }
mkdir -p "$REL"
TARGET="$REL/ieum-$BUILD.jar"
install -o ieum -g ieum -m 640 "$JAR" "$TARGET"

PREV=""
if [ -L "$CUR" ]; then PREV="$(readlink -f "$CUR")"; fi

ln -sfn "$TARGET" "$CUR"
echo "서버를 다시 켜요: $(basename "$TARGET")"
systemctl restart ieum

ok=0
for i in $(seq 60); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "$HEALTH" || true)"
  if [ "$code" = "200" ]; then ok=1; break; fi
  sleep 2
done

if [ "$ok" != "1" ]; then
  echo "새 버전이 2분 안에 살아나지 않았어요. 서버 로그:"
  journalctl -u ieum -n 60 --no-pager || true
  if [ -n "$PREV" ] && [ -f "$PREV" ]; then
    echo "앞 버전으로 되돌려요: $(basename "$PREV")"
    ln -sfn "$PREV" "$CUR"
    systemctl restart ieum
  fi
  exit 1
fi

echo "배포 성공: $(basename "$TARGET")"
# 최근 5개만 남기기 (지금 쓰는 것은 지우지 않음)
ls -1t "$REL"/ieum-*.jar 2>/dev/null | tail -n +6 | while read -r old; do
  [ "$old" = "$(readlink -f "$CUR")" ] || rm -f "$old"
done
