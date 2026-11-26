#!/usr/bin/env bash
# Pulls main and rebuilds. The GitHub deploy key is pinned to this script
# (forced command), so a leaked key can do nothing but redeploy main.
set -euo pipefail
SRC=/opt/aria-src
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch main https://github.com/jssantogit/Aria.git "$SRC"
fi
if [ "$(git -C "$SRC" remote get-url origin)" != "https://github.com/jssantogit/Aria.git" ]; then
  echo "Refusing to deploy from a repository other than jssantogit/Aria" >&2
  exit 1
fi
git -C "$SRC" fetch --depth 1 origin main
git -C "$SRC" reset --hard FETCH_HEAD
echo "deploying $(git -C "$SRC" log -1 --format='%h %s')"
DOMAIN="$(cat /etc/aria-domain)" bash "$SRC/backend/deploy/setup.sh"
