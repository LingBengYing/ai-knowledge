#!/bin/sh
set -eu
set -a
. /run/config/common.env
set +a
exec java -Xms128m -Xmx1024m -XX:+ExitOnOutOfMemoryError -XX:-CreateCoredumpOnCrash -Duser.home=/tmp -jar /app/application.jar --rag.knowledge-agent.enabled=true --rag.knowledge-agent.base-url=http://127.0.0.1:18112 --rag.knowledge-agent.service-token="$AGENT_SERVICE_TOKEN"
