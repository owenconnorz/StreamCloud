"""StreamCloud-only Watch Party service. Run one worker; rooms are ephemeral."""
import asyncio
import json
import secrets
import time
from collections import deque
from contextlib import asynccontextmanager
from dataclasses import dataclass, field

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field
from typing import Literal


def now_ms():
    return int(time.time() * 1000)


class Media(BaseModel):
    tmdb_id: int = Field(gt=0)
    media_type: Literal["movie", "tv"]
    season: int = Field(default=0, ge=0, le=999)
    episode: int = Field(default=0, ge=0, le=9999)
    title: str = Field(max_length=250)


class Identity(BaseModel):
    name: str = Field(min_length=1, max_length=40)


class Create(Identity):
    media: Media | None = None


@dataclass
class Member:
    id: str
    name: str
    token: str
    seen: float = field(default_factory=time.monotonic)
    socket: WebSocket | None = None
    status: str = "browsing"


@dataclass
class Room:
    code: str
    host: str
    members: dict[str, Member]
    media: dict | None
    created: float = field(default_factory=time.monotonic)
    host_only: bool = True
    seq: int = 0
    playback: dict = field(default_factory=lambda: {
        "position_ms": 0, "paused": True, "rate": 1.0, "at": now_ms(),
    })

    def snapshot(self):
        return {
            "type": "state", "code": self.code, "host_id": self.host,
            "host_only": self.host_only, "media": self.media, "seq": self.seq,
            "playback": self.playback, "server_time": now_ms(), "max_members": 12,
            "members": [
                {"id": m.id, "name": m.name, "connected": m.socket is not None, "status": m.status}
                for m in self.members.values()
            ],
        }


rooms: dict[str, Room] = {}
attempts: dict[str, deque] = {}


def throttle(key: str, limit=30):
    now = time.monotonic()
    bucket = attempts.setdefault(key, deque())
    while bucket and bucket[0] < now - 60:
        bucket.popleft()
    if len(bucket) >= limit:
        raise HTTPException(429, "Too many attempts. Try again in a minute.")
    bucket.append(now)


async def broadcast(room: Room, payload=None):
    data = payload or room.snapshot()
    sockets = [m.socket for m in list(room.members.values()) if m.socket]
    # One slow participant must not prevent everyone else receiving playback.
    async def send(ws):
        try:
            await asyncio.wait_for(ws.send_json(data), 2)
        except (Exception, asyncio.CancelledError):
            return
    await asyncio.gather(*(send(ws) for ws in sockets))


async def close_socket(ws, code=1000):
    try:
        await ws.close(code)
    except Exception:
        pass


async def remove_member(room: Room, member_id: str):
    member = room.members.pop(member_id, None)
    if member and member.socket:
        await close_socket(member.socket)
    if not room.members:
        rooms.pop(room.code, None)
        return
    if room.host == member_id:
        room.host = next(
            (m.id for m in room.members.values() if m.socket),
            next(iter(room.members)),
        )
    await broadcast(room)


async def prune():
    for room in list(rooms.values()):
        if time.monotonic() - room.created > 21600:
            rooms.pop(room.code, None)
            await broadcast(room, {"type": "ended", "message": "This room has expired."})
            for member in list(room.members.values()):
                if member.socket:
                    await close_socket(member.socket)
        else:
            for member in list(room.members.values()):
                if time.monotonic() - member.seen > 90:
                    await remove_member(room, member.id)
    for key, values in list(attempts.items()):
        if not values or values[-1] < time.monotonic() - 60:
            attempts.pop(key, None)


async def expire():
    while True:
        await asyncio.sleep(15)
        await prune()


@asynccontextmanager
async def lifespan(_app):
    task = asyncio.create_task(expire())
    yield
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


app = FastAPI(title="StreamCloud Watch Party", lifespan=lifespan)


@app.get("/", response_class=HTMLResponse)
async def index():
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width">
    <title>StreamCloud Watch Party</title></head><body style="background:#111318;color:#eee;
    font-family:system-ui;padding:8vw;max-width:720px"><h1>StreamCloud Watch Party</h1>
    <p>The room service is online.</p><p>In StreamCloud, open Settings → Watch Party and
    enter this site's HTTPS address as the server. Create a room or enter a friend's code.</p>
    <p>Everyone uses their own authorised film source. Only playback controls and film
    metadata are shared—not video files, source URLs or provider credentials.</p>
    <p>Rooms are private invitations, limited to 12 people, and expire after six hours.
    Restarting this service closes all rooms.</p></body></html>"""


@app.get("/health")
async def health():
    return {"status": "ok", "service": "streamcloud-watch-party", "protocol": 1}


def admit(room: Room, identity: Identity):
    name = identity.name.strip()
    if not name:
        raise HTTPException(422, "Enter a display name.")
    member = Member(secrets.token_hex(12), name, secrets.token_urlsafe(32))
    room.members[member.id] = member
    return {"token": member.token, "member_id": member.id, "room": room.snapshot()}


@app.post("/watch-party/rooms", status_code=201)
async def create(body: Create, request: Request):
    throttle("create:" + (request.client.host if request.client else "unknown"), 10)
    if len(rooms) >= 128:
        raise HTTPException(503, "The party service is full. Try again later.")
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    code = "".join(secrets.choice(alphabet) for _ in range(8))
    while code in rooms:
        code = "".join(secrets.choice(alphabet) for _ in range(8))
    room = Room(code, "", {}, body.media.model_dump() if body.media else None)
    result = admit(room, body)
    room.host = result["member_id"]
    rooms[code] = room
    result["room"] = room.snapshot()
    return result


@app.post("/watch-party/rooms/{code}/join")
async def join(code: str, body: Identity, request: Request):
    throttle("join:" + (request.client.host if request.client else "unknown"))
    room = rooms.get(code.upper().replace("-", "").replace(" ", ""))
    if not room:
        raise HTTPException(404, "No active room has that code.")
    if len(room.members) >= 12:
        raise HTTPException(409, "This room is full.")
    result = admit(room, body)
    await broadcast(room)
    return result


async def handle(room: Room, member: Member, msg: dict):
    kind = msg.get("type")
    member.seen = time.monotonic()
    host = member.id == room.host
    if kind == "ping":
        await member.socket.send_json({
            "type": "pong", "sent_at": msg.get("sent_at"), "server_time": now_ms(),
        })
        return
    if kind == "status":
        status = msg.get("status")
        if status in ("browsing", "loading", "buffering", "ready", "behind"):
            if member.status != status:
                member.status = status
                await broadcast(room)
        return
    if kind == "leave":
        await remove_member(room, member.id)
        return
    if kind == "end" and host:
        rooms.pop(room.code, None)
        await broadcast(room, {"type": "ended", "message": "The host ended this party."})
        for person in list(room.members.values()):
            if person.socket:
                await close_socket(person.socket)
        return
    if kind == "kick" and host:
        target = room.members.get(str(msg.get("member_id")))
        if target and target.id != member.id:
            if target.socket:
                await target.socket.send_json({"type": "removed", "message": "The host removed you."})
            await remove_member(room, target.id)
        return
    if kind == "host_only" and host and isinstance(msg.get("enabled"), bool):
        room.host_only = msg["enabled"]
        await broadcast(room)
        return
    if kind == "media" and host:
        try:
            media = Media.model_validate(msg.get("media"))
        except ValueError:
            await member.socket.send_json({"type": "error", "message": "Invalid film selection."})
            return
        if room.media != media.model_dump():
            room.media = media.model_dump()
            room.playback = {
                "position_ms": 0, "paused": True, "rate": 1.0,
                "at": now_ms(), "member_id": member.id,
            }
            room.seq += 1
            await broadcast(room)
        return
    if kind == "playback":
        if room.host_only and not host:
            await member.socket.send_json({"type": "error", "message": "Only the host can control playback."})
            return
        if not room.media or msg.get("media") != room.media:
            await member.socket.send_json({"type": "error", "message": "Open the party's selected film first."})
            return
        position, paused, rate = msg.get("position_ms"), msg.get("paused"), msg.get("rate", 1.0)
        if type(position) is not int or not 0 <= position <= 604800000 or type(paused) is not bool:
            return
        if type(rate) not in (int, float) or not 0.25 <= rate <= 3:
            return
        room.seq += 1
        room.playback = {
            "position_ms": position, "paused": paused, "rate": rate,
            "at": now_ms(), "member_id": member.id,
        }
        await broadcast(room)


@app.websocket("/watch-party/ws")
async def socket(ws: WebSocket):
    await ws.accept()
    room = member = None
    try:
        raw = await asyncio.wait_for(ws.receive_text(), 8)
        if len(raw) > 1024:
            await ws.close(1008)
            return
        auth = json.loads(raw)
        if not isinstance(auth, dict):
            await ws.close(1008)
            return
        room = rooms.get(str(auth.get("code", "")).upper())
        token = str(auth.get("token", ""))
        if room and token.isascii():
            member = next((m for m in room.members.values() if secrets.compare_digest(m.token, token)), None)
        if not member:
            await ws.send_json({"type": "removed", "message": "Room access expired. Join again."})
            await ws.close(1008)
            return
        previous = member.socket
        member.socket = ws
        member.seen = time.monotonic()
        if previous:
            await previous.close(1000)
        await broadcast(room)
        recent = deque()
        while rooms.get(room.code) is room and member.id in room.members:
            raw = await ws.receive_text()
            if len(raw) > 8192:
                await ws.close(1009)
                break
            current = time.monotonic()
            while recent and recent[0] < current - 10:
                recent.popleft()
            if len(recent) >= 80:
                await ws.close(1008)
                break
            recent.append(current)
            msg = json.loads(raw)
            if not isinstance(msg, dict):
                await ws.close(1008)
                break
            await handle(room, member, msg)
    except WebSocketDisconnect:
        pass
    except (ValueError, asyncio.TimeoutError):
        await close_socket(ws, 1008)
    finally:
        if member and member.socket is ws:
            member.socket = None
            member.status = "browsing"
            if room and rooms.get(room.code) is room:
                await broadcast(room)
