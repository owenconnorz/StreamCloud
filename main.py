"""Single-instance entrypoint for the StreamCloud Watch Party room service."""
import os

import uvicorn


if __name__ == "__main__":
    uvicorn.run(
        "backend.watch_party_server:app",
        host="0.0.0.0",
        port=int(os.environ.get("PORT", "8000")),
        workers=1,
    )
