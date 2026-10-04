# Pulse

A live incident console: **Ktor** WebSocket server in Kotlin, **Next.js** front
end, no database.

```bash
docker compose up --build
```

Open <http://localhost:3000> — then open it again in a second window. Raise an
incident in one and watch it appear in the other, with the viewer count on both.

Android and iOS clients live in
[pulse-mobile](https://github.com/rizwan-dev/pulse-mobile): one Kotlin
Multiplatform module implementing the same resume-by-sequence protocol, with a
shared Compose Multiplatform UI.

---

## Why this exists

Realtime is easy to demo and hard to get right. A tutorial WebSocket app
broadcasts a message and calls it done. The problems that actually bite are the
ones that only appear once a connection is unreliable and there is more than one
client:

- What happens to a client whose connection drops for thirty seconds?
- What happens to everyone else when one client stops reading?
- How does a client know it has fallen behind, rather than quietly showing a
  stale screen?

Pulse answers those three, and most of the code is about them.

## Resuming after a drop

Every change carries a monotonically increasing `seq`. The client remembers the
last one it applied, and reconnects with it:

```
ws://localhost:9191/ws?since=42
```

The server replays exactly what was missed. There is no full re-fetch, no
"refresh the page", and nothing silently lost.

Verified end to end, not just asserted in a unit test — a client connects,
disconnects, misses two events raised while it was away, reconnects with its
cursor and receives precisely the gap:

```
snapshot on connect: 0 incidents, seq 0
live push received: seq 1 "Pushed while connected"
presence broadcast: 1 viewer(s)
replayed after reconnect: ["Pushed while connected","Missed one","Missed two"]
```

**Sequence numbers, not timestamps.** Ordering by wall clock breaks when two
events land in the same millisecond or the clock steps. A counter gives a total
order and an unambiguous cursor.

**A resume has three answers, not two.** The cursor is inside the buffer, or
older than it, or *ahead of the server's own sequence* — which is what a client
holds after the server restarts. The third case went unnoticed until the mobile
client hit it: `replayAfter` matched nothing and returned an empty list,
indistinguishable from "you are up to date", so the client sat on a board the
server no longer had and kept asking to resume from a cursor the server would
not reach for several more events, silently missing every one of them. A restart
is not a lag — the client missed nothing, the server lost everything — so it now
gets a plain snapshot rather than a "you fell behind by −7 events" message.

**The replay buffer is bounded.** History is capped at 500 messages. A client
gone longer than that is told it *lagged* and handed a fresh snapshot, rather
than being given a partial history it would mistake for a complete one. An
unbounded buffer is a memory leak with a friendly name.

## One slow client must not stall everyone

The broadcast is a `MutableSharedFlow` with `extraBufferCapacity = 256` and
`onBufferOverflow = DROP_OLDEST`.

A consumer that stops reading therefore loses messages instead of blocking the
producer. That is the right trade here, and it is only safe *because* of the
sequence numbers: a client that drops messages can detect the gap and ask for a
replay. Suspending the producer to wait for one slow socket is how a single bad
client takes down a broadcast for everybody.

## Three states on the client, not two

`lib/types.ts` mirrors the Kotlin sealed interface as a discriminated union, so
a `switch` with no `default` fails to compile the day the server grows a new
message type — which is exactly when you want to find out.

The connection itself is modelled as `connecting | live | reconnecting |
offline`, with capped exponential backoff on reconnect (500ms doubling to a
30-second ceiling). A tight reconnect loop is a self-inflicted denial of
service against your own API.

`useIncidentFeed` deliberately does **not** optimistically update local state
after a POST. The change comes back over the socket like everyone else's, so
there is one path into the UI and the screen cannot disagree with the server.

## Three bugs this build found

Written down because they are the realistic ones, and all three passed a casual
read:

**1. The connecting client never saw its own viewer count.** Presence was
emitted to the shared flow before that socket started collecting it, and with
`replay = 0` it was simply gone. A lone operator would have read "0 operators
watching" forever. Fixed by sending presence directly to the new socket and
broadcasting to the others.

**2. The leaving-viewer broadcast never ran.** It was `launch { … }` inside a
`finally` block — but a socket's coroutine scope is already cancelled by the
time that runs, so the coroutine was silently dropped. Fixed with a
non-suspending `tryEmit` that needs no live scope.

**3. `/health` returned a 500.** It responded with
`mapOf("status" to "UP", "viewers" to n)`, and kotlinx.serialization has no
serializer for a `Map` with mixed value types. Docker's `HEALTHCHECK` would
never have passed, so compose would never have started the web container.

## API

| | |
| --- | --- |
| `GET /health` | status and current viewer count |
| `GET /api/incidents` | snapshot plus the current `seq` |
| `POST /api/incidents` | raise one; `{ title, severity, source }` |
| `POST /api/incidents/{id}/ack` | acknowledge; `{ by }` — idempotent, first operator wins |
| `WS /ws?since=<seq>` | the live feed, resuming from `seq` |

Acknowledging is idempotent on purpose: two operators clicking at the same
moment is normal, not an error. The first wins and the second is told the
current state rather than shown a failure it cannot act on.

## Tests

```bash
cd pulse && ./gradlew test
cd web && npm run typecheck && npm run build
```

11 tests. Unit tests on sequencing, idempotent acknowledgement and the bounded
replay buffer; integration tests over a real WebSocket through the real routing
and serialisation, including the reconnect-and-replay path.

## Layout

```
pulse/   Ktor 3.1, Kotlin 2.1, coroutines, kotlinx.serialization
web/     Next.js 16, React 19, TypeScript (strict)
```

There is no database. The board is in memory deliberately — this repository is
about the realtime layer, and a persistence tier would be noise around the part
worth reading.

## Running without Docker

```bash
cd pulse && ./gradlew run          # :9191 — not 8080, which almost
                                   # everything else already wants
cd web && npm install && npm run dev   # :3000
```

---

Built by [Rizwanul Haque](https://github.com/rizwan-dev). More reference
implementations at [RizTech Academy](https://riztechacademy.com).
