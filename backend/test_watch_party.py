import asyncio
import time
import unittest

from fastapi.testclient import TestClient
from backend import watch_party_server as service


MEDIA = {"tmdb_id": 123, "media_type": "movie", "season": 0, "episode": 0, "title": "Test film"}


class WatchPartyTest(unittest.TestCase):
    def setUp(self):
        service.rooms.clear()
        service.attempts.clear()
        self.client = TestClient(service.app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)
        service.rooms.clear()

    def create(self, **kwargs):
        response = self.client.post("/watch-party/rooms", json={"name": "Host", "media": MEDIA, **kwargs})
        self.assertEqual(response.status_code, 201)
        return response.json()

    def join(self, host, name="Guest"):
        response = self.client.post(f"/watch-party/rooms/{host['room']['code']}/join", json={"name": name})
        self.assertEqual(response.status_code, 200)
        return response.json()

    def auth(self, ws, session):
        ws.send_json({"code": session["room"]["code"], "token": session["token"]})
        return self.until(ws, lambda x: x["type"] == "state")

    def until(self, ws, predicate):
        for _ in range(30):
            message = ws.receive_json()
            if predicate(message):
                return message
        self.fail("Expected room message not received")

    def playback(self, ws, **kwargs):
        ws.send_json({"type": "playback", "media": MEDIA, "position_ms": 45000, "paused": False, "rate": 1.0, **kwargs})

    def test_health_and_landing(self):
        self.assertEqual(self.client.get("/health").json()["protocol"], 1)
        self.assertIn("StreamCloud Watch Party", self.client.get("/").text)

    def test_create_validation_and_invite_normalisation(self):
        self.assertEqual(self.client.post("/watch-party/rooms", json={"name": " "}).status_code, 422)
        host = self.create()
        code = host["room"]["code"]
        self.assertEqual(len(code), 8)
        self.assertNotIn("token", host["room"])
        response = self.client.post(f"/watch-party/rooms/{code[:4].lower()}-{code[4:].lower()}/join", json={"name": "Guest"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(self.client.post("/watch-party/rooms/UNKNOWN1/join", json={"name": "Guest"}).status_code, 404)

    def test_invalid_socket_credentials(self):
        host = self.create()
        for token in ("incorrect", "\u2603"):
            with self.client.websocket_connect("/watch-party/ws") as ws:
                ws.send_json({"code": host["room"]["code"], "token": token})
                self.assertEqual(ws.receive_json()["type"], "removed")
        with self.client.websocket_connect("/watch-party/ws") as ws:
            ws.send_json([])
            self.assertEqual(ws.receive()["code"], 1008)

    def test_host_playback_and_late_join_snapshot(self):
        host = self.create()
        with self.client.websocket_connect("/watch-party/ws") as ws:
            self.auth(ws, host)
            self.playback(ws)
            updated = self.until(ws, lambda x: x.get("seq") == 1)
            self.assertEqual(updated["playback"]["position_ms"], 45000)
            self.assertFalse(updated["playback"]["paused"])
            self.assertEqual(updated["playback"]["member_id"], host["member_id"])
            guest = self.join(host)
            with self.client.websocket_connect("/watch-party/ws") as other:
                snapshot = self.auth(other, guest)
                self.assertEqual(snapshot["playback"], updated["playback"])
                self.assertEqual(len(snapshot["members"]), 2)
                self.assertNotIn(guest["token"], str(snapshot))

    def test_host_only_and_shared_controls(self):
        host = self.create()
        guest = self.join(host)
        with self.client.websocket_connect("/watch-party/ws") as h, self.client.websocket_connect("/watch-party/ws") as g:
            self.auth(h, host)
            self.auth(g, guest)
            self.playback(g)
            self.until(g, lambda x: x["type"] == "error")
            self.assertEqual(service.rooms[host["room"]["code"]].seq, 0)
            h.send_json({"type": "host_only", "enabled": False})
            self.until(g, lambda x: x.get("host_only") is False)
            self.playback(g, position_ms=89000, rate=1.5, paused=True)
            updated = self.until(h, lambda x: x.get("seq") == 1)
            self.assertEqual(updated["playback"]["position_ms"], 89000)
            self.assertEqual(updated["playback"]["rate"], 1.5)
            self.assertTrue(updated["playback"]["paused"])

    def test_wrong_media_and_invalid_positions_cannot_change_room(self):
        host = self.create()
        with self.client.websocket_connect("/watch-party/ws") as ws:
            self.auth(ws, host)
            self.playback(ws, media={**MEDIA, "tmdb_id": 999})
            self.until(ws, lambda x: x["type"] == "error")
            self.playback(ws, position_ms=-1)
            self.playback(ws, rate=500)
            ws.send_json({"type": "ping", "sent_at": 7})
            self.until(ws, lambda x: x["type"] == "pong")
            self.assertEqual(service.rooms[host["room"]["code"]].seq, 0)

    def test_guest_cannot_end_kick_or_change_media(self):
        host = self.create()
        guest = self.join(host)
        with self.client.websocket_connect("/watch-party/ws") as ws:
            self.auth(ws, guest)
            for message in (
                {"type": "end"}, {"type": "kick", "member_id": host["member_id"]},
                {"type": "host_only", "enabled": False},
                {"type": "media", "media": {**MEDIA, "tmdb_id": 456}},
            ):
                ws.send_json(message)
            ws.send_json({"type": "ping", "sent_at": 1})
            self.until(ws, lambda x: x["type"] == "pong")
            room = service.rooms[host["room"]["code"]]
            self.assertTrue(room.host_only)
            self.assertEqual(room.media["tmdb_id"], 123)
            self.assertIn(host["member_id"], room.members)

    def test_kick_revokes_token(self):
        host = self.create()
        guest = self.join(host)
        with self.client.websocket_connect("/watch-party/ws") as h, self.client.websocket_connect("/watch-party/ws") as g:
            self.auth(h, host)
            self.auth(g, guest)
            h.send_json({"type": "kick", "member_id": guest["member_id"]})
            self.until(g, lambda x: x["type"] == "removed")
            with self.client.websocket_connect("/watch-party/ws") as retry:
                retry.send_json({"code": host["room"]["code"], "token": guest["token"]})
                self.assertEqual(retry.receive_json()["type"], "removed")

    def test_host_leaving_promotes_connected_guest(self):
        host = self.create()
        guest = self.join(host)
        with self.client.websocket_connect("/watch-party/ws") as h, self.client.websocket_connect("/watch-party/ws") as g:
            self.auth(h, host)
            self.auth(g, guest)
            h.send_json({"type": "leave"})
            updated = self.until(g, lambda x: x.get("host_id") == guest["member_id"])
            self.assertEqual(len(updated["members"]), 1)
            self.playback(g)
            self.until(g, lambda x: x.get("seq") == 1)

    def test_reconnect_preserves_membership(self):
        host = self.create()
        with self.client.websocket_connect("/watch-party/ws") as ws:
            self.auth(ws, host)
            self.playback(ws)
            self.until(ws, lambda x: x.get("seq") == 1)
        with self.client.websocket_connect("/watch-party/ws") as ws:
            state = self.auth(ws, host)
            self.assertEqual(state["host_id"], host["member_id"])
            self.assertEqual(state["playback"]["position_ms"], 45000)
            self.assertEqual(len(state["members"]), 1)

    def test_host_can_switch_episode_and_end_room(self):
        host = self.create()
        with self.client.websocket_connect("/watch-party/ws") as ws:
            self.auth(ws, host)
            episode = {**MEDIA, "media_type": "tv", "season": 1, "episode": 2}
            ws.send_json({"type": "media", "media": episode})
            state = self.until(ws, lambda x: x.get("seq") == 1)
            self.assertEqual(state["media"], episode)
            self.assertTrue(state["playback"]["paused"])
            self.assertEqual(state["playback"]["member_id"], host["member_id"])
            ws.send_json({"type": "end"})
            self.until(ws, lambda x: x["type"] == "ended")
            self.assertNotIn(host["room"]["code"], service.rooms)

    def test_presence_and_room_limits(self):
        host = self.create()
        with self.client.websocket_connect("/watch-party/ws") as ws:
            self.auth(ws, host)
            ws.send_json({"type": "status", "status": "buffering"})
            state = self.until(ws, lambda x: x["members"][0]["status"] == "buffering")
            self.assertTrue(state["members"][0]["connected"])
        for i in range(11):
            self.join(host, name=str(i))
        response = self.client.post(f"/watch-party/rooms/{host['room']['code']}/join", json={"name": "Extra"})
        self.assertEqual(response.status_code, 409)

    def test_expiry_and_idle_member_cleanup(self):
        host = self.create()
        room = service.rooms[host["room"]["code"]]
        room.members[host["member_id"]].seen = time.monotonic() - 100
        asyncio.run(service.prune())
        self.assertNotIn(room.code, service.rooms)
        host = self.create()
        room = service.rooms[host["room"]["code"]]
        room.created = time.monotonic() - 21601
        asyncio.run(service.prune())
        self.assertNotIn(room.code, service.rooms)

    def test_stream_urls_and_credentials_are_not_retained(self):
        host = self.create(media={**MEDIA, "stream_url": "https://private.example/secret", "headers": {"Authorization": "private"}})
        self.assertNotIn("private.example", str(host["room"]))
        self.assertNotIn("Authorization", str(host["room"]))

    def test_room_creation_is_rate_limited(self):
        for _ in range(10):
            self.create()
        self.assertEqual(self.client.post("/watch-party/rooms", json={"name": "Extra"}).status_code, 429)


if __name__ == "__main__":
    unittest.main()
