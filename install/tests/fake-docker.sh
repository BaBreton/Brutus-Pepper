#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$PEPPER_TEST_LOG"
case "$1" in
  info)
    [[ ${SCENARIO:-} != no-socket ]] || exit 1
    printf 'linux/aarch64\n' ;;
  context) printf '%s\n' "${TEST_ENDPOINT:-unix:///var/run/docker.sock}" ;;
  inspect)
    case "$*" in
      *com.docker.compose.project*) printf '%s\n' "${TEST_PROJECT:-brain}" ;;
      *Health*) printf 'healthy\n' ;;
      *) exit 1 ;;
    esac ;;
  compose)
    case "$*" in
      *version*) [[ ${SCENARIO:-} != no-compose ]] ;;
      *' build brain') [[ ${SCENARIO:-} != build-failed ]] ;;
      *'up '*) [[ ${SCENARIO:-} != up-failed ]] ;;
      *exec*) printf 'SECRET-SENTINEL\n'; exit 91 ;;
      *) exit 0 ;;
    esac ;;
  *) exit 1 ;;
esac
