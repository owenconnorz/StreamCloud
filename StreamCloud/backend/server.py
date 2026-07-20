import random
import time
from collections import defaultdict
from typing import Optional

import httpx
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

app = FastAPI(title="StreamCloud Backend")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:128.0) Gecko/20100101 Firefox/128.0",
]

cookie_jar: dict[str, list[str]] = defaultdict(list)


def _domain(url: str) -> str:
    try:
        from urllib.parse import urlparse
        return urlparse(url).hostname or ""
    except Exception:
        return ""


def _get_cookies(domain: str) -> str:
    return "; ".join(cookie_jar.get(domain, []))


def _store_cookies(domain: str, set_cookie_headers: list[str]) -> None:
    if not set_cookie_headers:
        return
    existing = cookie_jar[domain]
    for sc in set_cookie_headers:
        name = sc.split("=")[0].strip()
        existing = [c for c in existing if not c.startswith(name + "=")]
        cookie_val = sc.split(";")[0].strip()
        if cookie_val:
            existing.append(cookie_val)
    cookie_jar[domain] = existing


def _browser_headers(url: str, override: dict) -> dict:
    try:
        from urllib.parse import urlparse
        parsed = urlparse(url)
        origin = f"{parsed.scheme}://{parsed.netloc}"
        referer = origin + "/"
        domain = parsed.hostname or ""
    except Exception:
        origin = referer = domain = ""

    ua = random.choice(USER_AGENTS)
    cookies = _get_cookies(domain)

    headers = {
        "User-Agent": ua,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Cache-Control": "no-cache",
        "Pragma": "no-cache",
        "Sec-Fetch-Dest": "document",
        "Sec-Fetch-Mode": "navigate",
        "Sec-Fetch-Site": "none",
        "Sec-Fetch-User": "?1",
        "Upgrade-Insecure-Requests": "1",
        "Sec-CH-UA": '"Chromium";v="131", "Not_A Brand";v="24"',
        "Sec-CH-UA-Mobile": "?0",
        "Sec-CH-UA-Platform": '"Windows"',
        "Referer": referer,
        "Origin": origin,
    }
    if cookies:
        headers["Cookie"] = cookies

    headers.update({k: v for k, v in override.items() if k.lower() not in ("cookie",)})
    return headers


class ProxyRequest(BaseModel):
    url: str
    method: str = "GET"
    headers: dict = {}
    body: str = ""
    follow_redirects: bool = True


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/nuvio-proxy")
async def nuvio_proxy(req: ProxyRequest):
    domain = _domain(req.url)
    headers = _browser_headers(req.url, req.headers)
    content = req.body.encode("utf-8") if req.body else None

    try:
        async with httpx.AsyncClient(
            follow_redirects=req.follow_redirects,
            timeout=30.0,
        ) as client:
            resp = await client.request(
                method=req.method.upper(),
                url=req.url,
                headers=headers,
                content=content,
            )

        sc_headers = resp.headers.get_list("set-cookie")
        _store_cookies(domain, sc_headers)

        try:
            body_text = resp.text
        except Exception:
            body_text = resp.content.decode("utf-8", errors="replace")

        resp_headers = {}
        for k, v in resp.headers.items():
            resp_headers[k.lower()] = v

        return {
            "ok": resp.is_success,
            "status": resp.status_code,
            "statusText": resp.reason_phrase,
            "url": str(resp.url),
            "body": body_text,
            "headers": resp_headers,
        }

    except httpx.TimeoutException as e:
        return {
            "ok": False,
            "status": 0,
            "statusText": f"Timeout: {e}",
            "url": req.url,
            "body": "",
            "headers": {},
        }
    except Exception as e:
        return {
            "ok": False,
            "status": 0,
            "statusText": str(e),
            "url": req.url,
            "body": "",
            "headers": {},
        }
