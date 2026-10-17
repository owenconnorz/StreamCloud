# StreamCloud Watch Party

An independent room service and native Android TV/mobile client. It does not
connect to Nuvio's party server and cannot join Nuvio room codes.

## Run

Install the root Python dependencies from `pyproject.toml` / `uv.lock`, then run:

```sh
python main.py
```

The default port is 8000. `/health` identifies protocol version 1. The server's
root page gives setup instructions. Run with **one worker on one always-on
instance**, for example a Reserved VM deployment. Do not put multiple replicas
or autoscaling instances behind this in-memory implementation: each would have
different rooms. Do not replace an existing production service without checking
its other routes and clients. This entrypoint serves Watch Party only; the old
`backend/server.py` proxy and music service is unchanged.

Publish the service with public HTTPS access. In StreamCloud, open **Settings →
Watch Party → Server and display name** and enter that published HTTPS address.
All participants must use the same server. Private development preview URLs
are not suitable for installed APKs.

No production deployment is performed by adding this feature.

## Use

- Create a room in settings or from the **Watch Party** button in the native film
  player. Share the eight-character code and the server address.
- Guests join in settings and use **Open film / choose source** to select their
  own authorised stream. The selected film/episode must match. Different edits
  of a film are not guaranteed to share a timeline.
- Host-only controls are on by default. The host can enable shared controls,
  remove a person, leave and hand hosting to another connected participant, or
  end the party. Removal revokes the person's current session; it is not an
  account ban, and anyone who still knows the code can join again.
- Play/pause, seeks, speed, periodic playhead updates and buffering/ready status
  are sent over a token-authenticated WebSocket. Guests correct large drift using
  a server-clock estimate. A disconnected client retries its existing session.
- The native Media3 player is supported. Embedded web players, external players
  and cast receivers are not Watch Party playback endpoints.

## Security and lifecycle

Codes are cryptographically generated invitations. Treat them as private.
Member tokens are high-entropy, held only in the Android process, returned only
to the joining member and sent in the initial encrypted WebSocket message, never
in URL query strings or participant snapshots. The server enforces host
permissions; it does not trust UI controls or a submitted member ID.

Only TMDB identity, episode identity, title, display name, presence and playback
state are shared. Source URLs, provider cookies/headers and video content are
not transmitted. The protocol does not bypass content-provider access rules.
Anonymous display names are not verified account identities.

Limits: 12 participants, 128 rooms, six-hour room lifetime, 90-second inactive
session expiry, bounded WebSocket messages and request/message rate limits.
Rooms and sessions are deliberately ephemeral: restarting the server or
Android app requires creating/joining again. Room state is not stored in the
existing sync database. Durable rooms and multi-instance hosting would need a
shared room store and broadcast system.

## Verify

```sh
python -m unittest backend.test_watch_party -v
cd StreamCloud
./gradlew compileTvReleaseKotlin compileMobileReleaseKotlin
```

The Android sync projection tests live in `PartySyncPolicyTest`. Full device
playback, D-pad focus and real-world network latency still require a two-device
acceptance run; compiler and protocol tests do not verify the rendered UI.
