import random
import string
import time
import uuid
from collections import defaultdict
from typing import Dict, List, Optional

import httpx
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
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


# ── Reddit proxy ──────────────────────────────────────────────────────────────
# Android devices on residential/mobile IPs receive empty listings from Reddit.
# This endpoint proxies the request server-side where Reddit responds normally.

_REDDIT_CLIENT_ID     = "KvLG0eQTdPDIf_Buo-gkww"
_REDDIT_CLIENT_SECRET = "BCRKFdWhHJ_Ckifv-guBVixUfQA__w"
_REDDIT_UA            = "android:com.streamcloud.app:v1.0.0 (by /u/streamcloud_app)"

_reddit_token_cache: dict = {"token": None, "expiry": 0.0}


async def _get_reddit_token() -> str:
    import base64 as _b64
    now = time.time()
    if _reddit_token_cache["token"] and _reddit_token_cache["expiry"] > now:
        return _reddit_token_cache["token"]
    creds = _b64.b64encode(
        f"{_REDDIT_CLIENT_ID}:{_REDDIT_CLIENT_SECRET}".encode()
    ).decode()
    async with httpx.AsyncClient(timeout=15.0) as c:
        r = await c.post(
            "https://www.reddit.com/api/v1/access_token",
            content=b"grant_type=client_credentials",
            headers={
                "Authorization": f"Basic {creds}",
                "User-Agent": _REDDIT_UA,
                "Content-Type": "application/x-www-form-urlencoded",
            },
        )
        r.raise_for_status()
    data = r.json()
    _reddit_token_cache["token"]  = data["access_token"]
    _reddit_token_cache["expiry"] = now + data.get("expires_in", 3600) - 60
    return _reddit_token_cache["token"]


@app.get("/reddit/r/{subreddit}/{sort}")
async def reddit_listing(
    subreddit: str,
    sort: str,
    limit: int = 50,
    after: Optional[str] = None,
):
    """Proxy Reddit listing through server IP to avoid residential-IP restrictions."""
    token = await _get_reddit_token()
    url = (
        f"https://oauth.reddit.com/r/{subreddit}/{sort}"
        f"?limit={limit}&raw_json=1&include_over_18=on"
    )
    if after:
        url += f"&after={after}"
    async with httpx.AsyncClient(timeout=20.0) as c:
        r = await c.get(url, headers={
            "Authorization": f"Bearer {token}",
            "User-Agent": _REDDIT_UA,
        })
    if r.status_code == 401:
        _reddit_token_cache["token"] = None  # force refresh next call
    return r.json()


# ── Listen Together ───────────────────────────────────────────────────────────

class _LtMember:
    def __init__(self, member_id: str, name: str, ws: WebSocket, is_host: bool = False):
        self.id = member_id
        self.name = name
        self.ws = ws
        self.is_host = is_host


class _LtRoom:
    def __init__(self, code: str):
        self.code = code
        self.members: List[_LtMember] = []

    def get_host(self) -> Optional[_LtMember]:
        return next((m for m in self.members if m.is_host), None)

    async def broadcast(self, message: dict, exclude_id: Optional[str] = None) -> None:
        for m in list(self.members):
            if m.id != exclude_id:
                try:
                    await m.ws.send_json(message)
                except Exception:
                    pass

    async def send_to(self, member_id: str, message: dict) -> None:
        m = next((x for x in self.members if x.id == member_id), None)
        if m:
            try:
                await m.ws.send_json(message)
            except Exception:
                pass

    def member_list_payload(self, you_id: str) -> dict:
        return {
            "type": "member_list",
            "members": [
                {"id": m.id, "name": m.name, "is_host": m.is_host}
                for m in self.members
            ],
            "you_id": you_id,
            "you_is_host": any(m.id == you_id and m.is_host for m in self.members),
        }


_lt_rooms: Dict[str, _LtRoom] = {}


@app.post("/listen-together/room")
async def lt_create_room():
    while True:
        code = "".join(random.choices(string.ascii_uppercase + string.digits, k=6))
        if code not in _lt_rooms:
            break
    _lt_rooms[code] = _LtRoom(code)
    return {"code": code}


@app.websocket("/listen-together/ws/{room_code}")
async def lt_ws(websocket: WebSocket, room_code: str, name: str = "Friend"):
    await websocket.accept()

    room = _lt_rooms.get(room_code)
    if not room:
        await websocket.send_json({"type": "error", "message": "Room not found"})
        await websocket.close(code=4004)
        return

    member_id = str(uuid.uuid4())
    is_host = len(room.members) == 0
    member = _LtMember(member_id, name[:32], websocket, is_host)
    room.members.append(member)

    # Welcome: send full member list
    await websocket.send_json(room.member_list_payload(member_id))

    # Notify existing members
    await room.broadcast(
        {"type": "member_join", "id": member_id, "name": member.name, "is_host": is_host},
        exclude_id=member_id,
    )

    # Ask host to send sync state to the new guest
    if not is_host:
        host = room.get_host()
        if host:
            await host.ws.send_json({"type": "sync_request", "requester_id": member_id})

    try:
        while True:
            msg = await websocket.receive_json()
            msg_type = msg.get("type", "")

            if msg_type in ("play", "pause", "seek", "track_change") and member.is_host:
                # Broadcast host playback commands to all guests
                await room.broadcast(msg, exclude_id=member_id)

            elif msg_type == "sync_state" and member.is_host:
                # Host responding to a sync_request — forward to the requester or broadcast
                requester_id = msg.get("requester_id")
                payload = {**msg, "server_time_ms": int(time.time() * 1000)}
                if requester_id:
                    await room.send_to(requester_id, payload)
                else:
                    await room.broadcast(payload, exclude_id=member_id)

            elif msg_type == "sync_request" and not member.is_host:
                # Guest requesting sync — forward to host with requester info
                host = room.get_host()
                if host:
                    await host.ws.send_json({**msg, "requester_id": member_id})

    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        if member in room.members:
            room.members.remove(member)

        if not room.members:
            _lt_rooms.pop(room_code, None)
        else:
            if member.is_host and room.members:
                new_host = room.members[0]
                new_host.is_host = True
                await room.broadcast(
                    {
                        "type": "promoted_to_host",
                        "new_host_id": new_host.id,
                    }
                )
                await room.broadcast(room.member_list_payload(new_host.id))
            await room.broadcast(
                {"type": "member_leave", "id": member_id, "name": member.name}
            )
