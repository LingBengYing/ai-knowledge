#!/bin/sh
set -eu
set -a
. /run/config/common.env
set +a
exec milvus run standalone
