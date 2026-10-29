#!/bin/sh
set -eu
set -a
. /run/config/common.env
set +a
exec node scripts/external-server.mjs
