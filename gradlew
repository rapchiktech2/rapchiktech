#!/usr/bin/env sh
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
exec gradle "$@"
