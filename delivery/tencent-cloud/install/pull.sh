#!/bin/sh
set -eu
# Run docker login ccr.ccs.tencentyun.com first. No password is stored here.
docker pull --platform linux/amd64 ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-api@sha256:3ccc12f550d409b6a27f133036cc112509294d8937e03ec15a6ad3e719f0bc91
docker tag ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-api@sha256:3ccc12f550d409b6a27f133036cc112509294d8937e03ec15a6ad3e719f0bc91 ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-api:20261028-0094r2
docker pull --platform linux/amd64 ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-web@sha256:798fc3e62d1592651de03013f11d254f8670eead379f8ea6b2bf8335c2e3d834
docker tag ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-web@sha256:798fc3e62d1592651de03013f11d254f8670eead379f8ea6b2bf8335c2e3d834 ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-web:20261028-0062
docker pull --platform linux/amd64 ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-agent@sha256:9dd91209b5583bbbb1ebac6bb35fb7e026ba15976db62011eaa555360ead96cd
docker tag ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-agent@sha256:9dd91209b5583bbbb1ebac6bb35fb7e026ba15976db62011eaa555360ead96cd ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-agent:20261028-0076
docker pull --platform linux/amd64 ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-ocr@sha256:abceac4104f2daaa1e28faa6ea3542710943db28464381ae059fbd9b9a7ac38c
docker tag ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-ocr@sha256:abceac4104f2daaa1e28faa6ea3542710943db28464381ae059fbd9b9a7ac38c ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-ocr:20261028-0078r3
docker pull --platform linux/amd64 ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-milvus@sha256:0a29cd782b35595990784d18be18ab470e912f80d8df6899a68ecdef1b2b0aa1
docker tag ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-milvus@sha256:0a29cd782b35595990784d18be18ab470e912f80d8df6899a68ecdef1b2b0aa1 ccr.ccs.tencentyun.com/lingbenying-knowledge/knowledge-milvus:2.6.24-0093
