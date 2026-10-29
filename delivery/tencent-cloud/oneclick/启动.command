#!/bin/sh
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
./start.sh
code=$?
printf "\n按回车关闭窗口……"
read -r unused
exit "$code"
