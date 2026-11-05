#!/usr/bin/env bash
# 从 tianting-web 同步设计 token 并重新生成 Tokens.gen.kt（FE-UI-IMP-1：唯一来源在 tianting-web）。
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
src="${1:-$root/../tianting-web/src/theme/tokens.json}"
cp "$src" "$root/design/tokens.json"
cd "$root" && ./gradlew -q :app:generateTokens
