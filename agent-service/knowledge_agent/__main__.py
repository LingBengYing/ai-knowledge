import logging
import os

import uvicorn

from .service import create_app


def main():
    try:
        app = create_app()
        port = int(os.environ.get("AGENT_SERVICE_PORT", "18112"))
        if not 1024 <= port <= 65535:
            raise ValueError()
    except (ValueError, KeyError):
        raise SystemExit("agent_configuration_invalid") from None
    logging.getLogger("httpx").setLevel(logging.CRITICAL)
    uvicorn.run(app, host="127.0.0.1", port=port, access_log=False, log_level="warning")


if __name__ == "__main__":
    main()
