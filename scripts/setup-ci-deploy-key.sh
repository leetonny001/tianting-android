#!/usr/bin/env bash
# 给 tianting-android 的 CI 配置一把只读部署密钥，用来检出私有的 tianting-sdk-kotlin（由仓库所有者本人运行）。
# 私钥只经过管道设成 Actions secret TIANTING_SDK_KOTLIN_DEPLOY_KEY，不打印、不留在磁盘上。
set -euo pipefail
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
ssh-keygen -q -t ed25519 -N '' -C "tianting-android-ci-sdk-kotlin" -f "$dir/key"
gh repo deploy-key add "$dir/key.pub" -R leetonny001/tianting-sdk-kotlin -t "tianting-android-ci"
gh secret set TIANTING_SDK_KOTLIN_DEPLOY_KEY -R leetonny001/tianting-android < "$dir/key"
echo "完成：已为 tianting-sdk-kotlin 添加只读部署密钥，并设置 tianting-android 的 secret；本地密钥文件已删除。"
