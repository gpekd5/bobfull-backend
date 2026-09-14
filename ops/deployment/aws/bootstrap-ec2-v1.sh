#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -eq 0 ]; then
  echo "Run this script as the EC2 login user, not as root." >&2
  exit 1
fi

install_with_dnf() {
  sudo dnf update -y
  sudo dnf install -y docker awscli
}

install_with_apt() {
  sudo apt-get update
  sudo apt-get install -y docker.io awscli
}

if ! command -v docker >/dev/null 2>&1 || ! command -v aws >/dev/null 2>&1; then
  if command -v dnf >/dev/null 2>&1; then
    install_with_dnf
  elif command -v apt-get >/dev/null 2>&1; then
    install_with_apt
  else
    echo "Unsupported Linux distribution. Install Docker and AWS CLI manually." >&2
    exit 1
  fi
fi

sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"

docker --version
aws --version

cat <<'MESSAGE'
EC2 bootstrap completed.
Log out and log back in once so the docker group membership is applied.
MESSAGE
