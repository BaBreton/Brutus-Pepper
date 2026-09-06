#!/usr/bin/env bash
[[ ${SCENARIO:-} != timeout ]] || exit 7
printf '{"status":"ok"}\n'
