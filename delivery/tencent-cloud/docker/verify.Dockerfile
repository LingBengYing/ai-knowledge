FROM ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-web:20261028-0062 AS web
RUN node --check scripts/external-server.mjs && node --check scripts/agent-event-stream.mjs && node --check public/wiki-workspace.mjs

FROM ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-agent:20261028-0076 AS agent
RUN --network=none AGENT_SERVICE_TOKEN=synthetic-startup-validation-only-000000000000 /app/.venv/bin/python -c "from fastapi.testclient import TestClient; from knowledge_agent.service import create_app; c=TestClient(create_app()); r=c.get('/health'); assert r.status_code==200; print(r.json())"

FROM ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-ocr:20261028-0078r3 AS ocr
RUN --network=none OCR_API_KEY=synthetic-startup-validation-only python -c "import subprocess,os,time,stat; p=subprocess.Popen(['python','-m','ocr_service']); time.sleep(3); assert p.poll() is None; assert stat.S_ISSOCK(os.stat('/run/knowledge-ocr/page.sock').st_mode); p.terminate(); assert p.wait(timeout=10)==0; assert not os.path.exists('/run/knowledge-ocr/page.sock'); print('ocr_socket_start_stop_passed')"

FROM ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-milvus:2.6.24-0093 AS milvus
RUN echo '26137e03195d7c3b5e067041c1ad8f2747983e44cf3206013d916b0a3379da99  /milvus/bin/milvus' | sha256sum -c - && ldd /milvus/bin/milvus > /tmp/ldd.txt && ! grep 'not found' /tmp/ldd.txt

FROM ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-api:20261028-0094r2 AS api
RUN --network=none set -eu; \
    env RAG_JWT_SECRET=synthetic-startup-validation-only-000000000000 RAG_DATA_DIRECTORY=/tmp/validation java -Xmx384m -jar /app/application.jar > /tmp/startup.log 2>&1 & pid=$!; \
    trap 'kill "$pid" 2>/dev/null || true' EXIT; \
    ready=0; for i in $(seq 1 90); do if curl -fsS http://127.0.0.1:18084/health/live >/tmp/health.json; then ready=1; break; fi; kill -0 "$pid"; sleep 1; done; \
    test "$ready" = 1; cat /tmp/health.json; kill "$pid"; wait "$pid" || test "$?" = 143; trap - EXIT
