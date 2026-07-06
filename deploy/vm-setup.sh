#!/usr/bin/env bash
# 경로 A(Oracle Always Free VM) 부트스트랩 — Ubuntu 22.04/24.04 기준.
# VM에 SSH 접속 후 실행. Docker/Compose 설치 → 레포 clone → .env.prod 준비까지 자동화한다.
# (실제 시크릿 입력·compose 기동·Caddy는 마지막에 사람이 확인하며 진행 — 스크립트가 안내 출력)
#
#   실행:  curl -fsSL https://raw.githubusercontent.com/JungWanSEO/SideProjectWeb/dev/deploy/vm-setup.sh | bash
#     또는:  git clone 후  bash deploy/vm-setup.sh
set -euo pipefail

REPO_URL="https://github.com/JungWanSEO/SideProjectWeb.git"
REPO_DIR="${HOME}/SideProjectWeb"
BRANCH="dev"

echo "==> 1) 패키지 인덱스 갱신 + 기본 도구"
sudo apt-get update -y
sudo apt-get install -y ca-certificates curl git ufw

echo "==> 2) Docker Engine + Compose 플러그인 설치 (공식 저장소)"
if ! command -v docker >/dev/null 2>&1; then
  sudo install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  sudo chmod a+r /etc/apt/keyrings/docker.gpg
  echo \
    "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo apt-get update -y
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
else
  echo "    docker 이미 설치됨 — 건너뜀"
fi

echo "==> 3) 현재 사용자를 docker 그룹에 추가(sudo 없이 docker 실행). 재로그인 후 적용."
sudo usermod -aG docker "$USER" || true

echo "==> 4) 방화벽(ufw) — SSH/HTTP/HTTPS 만 오픈 (앱 8080은 외부 비노출: Caddy가 프록시)"
sudo ufw allow OpenSSH || true
sudo ufw allow 80/tcp || true
sudo ufw allow 443/tcp || true
sudo ufw --force enable || true
# ⚠️ Oracle Cloud는 ufw 외에 콘솔의 '보안 목록/NSG'에서도 80/443/22 인그레스를 열어야 한다.

echo "==> 5) 레포 clone (또는 갱신)"
if [ -d "${REPO_DIR}/.git" ]; then
  git -C "${REPO_DIR}" pull --ff-only origin "${BRANCH}"
else
  git clone --branch "${BRANCH}" "${REPO_URL}" "${REPO_DIR}"
fi

echo "==> 6) .env.prod 준비 (템플릿 복사 — 아직 값은 비어 있음)"
cd "${REPO_DIR}/backend"
if [ ! -f .env.prod ]; then
  cp .env.prod.example .env.prod
  echo "    backend/.env.prod 생성됨 — 다음 단계에서 값을 채운다."
else
  echo "    backend/.env.prod 이미 존재 — 덮어쓰지 않음."
fi

cat <<'NEXT'

────────────────────────────────────────────────────────────
✅ 부트스트랩 완료. 남은 수동 단계:

  1) docker 그룹 적용을 위해 한 번 재로그인:   exit  후 다시 SSH 접속
  2) 시크릿 채우기:                              nano ~/SideProjectWeb/backend/.env.prod
        - MYSQL_PASSWORD / MYSQL_ROOT_PASSWORD (강한 랜덤)
        - JWT_SECRET            (openssl rand -base64 48)
        - APP_CORS_ALLOWED_ORIGINS / APP_OAUTH2_REDIRECT  (FE 도메인 — Vercel 배포 후 확정)
  3) 기동:   cd ~/SideProjectWeb/backend
             docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
     첫 기동 시 Flyway가 V1~최신 마이그레이션을 새 MySQL에 적용한다.
  4) 로그 확인:   docker logs -f commerce-api      (Started ... in N seconds 확인)
  5) HTTPS:   Caddy 설치 후 deploy/Caddyfile 의 도메인을 실제 값으로 바꿔 /etc/caddy/Caddyfile 로.
              (설치: sudo apt-get install -y caddy)
────────────────────────────────────────────────────────────
NEXT
