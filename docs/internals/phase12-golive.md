# Phase 12 — Documentation + Go-Live Prep: Design Notes & Deep Dive

> Written during the autonomous build. This document is the reviewer-facing
> "why did we do it this way, and what's still broken" companion to the
> Docker/Compose files, README, CHANGELOG, and deployment guide this phase
> produced.

---

## What was built

| Piece | Location | Role |
|---|---|---|
| `docker/Dockerfile` | repo root `docker/` | Multi-stage build: JDK 25 compiles, JRE 25 runs |
| `docker/docker-compose.yml` | same | Real single-node service + labeled-aspirational 3-node profile |
| `README.md` | repo root | Refreshed to the actually-shipped feature set, honest status banner |
| `CHANGELOG.md` | repo root | Keep-a-Changelog history reconstructed from `git log` + phase docs |
| `docs/deployment.md` | `docs/` | Single-node quickstart, JVM tuning, honest multi-node status |
| `mkdocs.yml` | repo root | MkDocs Material config, nav matching the real `docs/` tree |
| This file | `docs/internals/` | Design notes for this phase |

---

## What "go live" means for an open source infra project

There's a gap between "the code works" and "a stranger can adopt it," and
most of that gap is not code — it's the surface area a new user or
contributor has to get through before they trust the project enough to
depend on it:

1. **Can I run it without reading the source?** — a `docker run` one-liner
   or a `git clone && ./gradlew run` that actually works on the first try.
2. **Can I tell what it does and doesn't do yet?** — an accurate README and
   changelog, not marketing copy. KiraDB's own README, before this phase,
   still said "Phase 8 (Semantic Cache) is up next" when semantic cache,
   the dashboard, the SDK, and a benchmark pass had all already shipped
   (verified directly: `docs/internals/phase8-semantic-cache.md`,
   `phase9-dashboard.md`, `phase10-sdk.md`, and `phase11-benchmarks.md` all
   exist with real content, and `kiradb-dashboard/src/pages/*.tsx` and
   `kiradb-client/src/main/java/**` are populated modules, not stubs).
   Stale status is worse than no status — it actively misleads anyone
   deciding whether to depend on the project.
3. **Can I find the thing I need?** — docs that link to each other
   correctly. `docs/index.md` linked to `../CONTRIBUTING.md`, which does not
   exist in this repo (Phase 0's checklist item was never done). That link
   is now removed rather than left dangling; the honest fix is either write
   `CONTRIBUTING.md` or stop pointing at it, and this phase chose the
   latter since writing a real contributing guide is out of scope here.
4. **Discoverability** — the actual "go live" checklist items (Docker Hub
   publish, HN/Reddit/LinkedIn posts, a git tag) are Kiran's to execute, not
   something an agent should do autonomously. See "Kiran must do this" below.

None of this is glamorous work, and all of it is why "feature complete"
open source projects sit unused while worse-engineered projects with good
docs and a working Docker image get adopted.

---

## Why multi-stage Docker builds

The naive Dockerfile is one `FROM eclipse-temurin:25-jdk`, `COPY . .`,
`RUN ./gradlew build`, `ENTRYPOINT [...]`. It works. It also ships:

- The full JDK (`javac`, `jshell`, `jlink`, etc.) — none of which the
  running server process ever calls.
- The Gradle wrapper and its downloaded distribution, plus the entire
  Gradle dependency cache used to *build* the jars.
- Every module's full source tree, including test sources — which in
  KiraDB include an entire JMH benchmark harness
  (`kiradb-benchmark`) that has no business being in a production image.

None of that is theoretical bloat — it's the difference between an image
measured in hundreds of MB of stuff a database process never touches at
runtime, versus a JRE plus the ~15 jars `installDist` actually needs
(Netty, Jackson, Logback/SLF4J, and KiraDB's own module jars — visible
directly under `kiradb-server/build/install/kiradb-server/lib/` after
running `./gradlew :kiradb-server:installDist`). Multi-stage builds let the
`build` stage have the JDK and the whole source tree, then the `runtime`
stage does a single `COPY --from=build` of just the assembled distribution
directory and nothing else — the JDK, Gradle cache, and source tree are
discarded when the build stage's layer isn't in the final image's chain.

The secondary reason is attack surface: a JDK ships a compiler and
scripting engine (`jshell`) that are occasionally useful to an attacker who
has achieved code execution inside a container and wants to compile or
interpret something on the spot. A bare JRE doesn't have those tools.

I used the Gradle `application` plugin's `installDist` task rather than
building a manually-assembled classpath or a fat/shaded jar. `installDist`
already exists because `kiradb-server/build.gradle` applies the
`application` plugin (`mainClass = 'io.kiradb.server.KiraDBServer'`,
`applicationDefaultJvmArgs = ['--enable-preview']`) — it produces a launch
script that already knows the classpath and already bakes in
`--enable-preview`, so the Dockerfile's `ENTRYPOINT` is just
`["/app/bin/kiradb-server"]` with zero hand-maintained JVM flags to keep in
sync with the Gradle config. A shaded/fat jar was considered and rejected:
it would require adding the shadow plugin as a new build dependency for
marginal benefit (one file vs. one directory) and would obscure which jars
are actually on the classpath during debugging.

---

## The honest state of multi-node bootstrap

This is the single most important finding of this phase, and it does not
get softened here:

**`KiraDBServer.main()` boots exactly one standalone node.** I read
`kiradb-server/src/main/java/io/kiradb/server/KiraDBServer.java` in full
before writing any Docker or deployment docs, specifically looking for
`KIRA_NODE_ID`, `KIRA_PEERS`, or any reference to a Raft class. There is
none. The only identity-related input `main()` reads is the
`-Dkiradb.node.id` **system property** (not an env var), and it feeds
exactly one place: `CrdtStore`'s constructor, for CRDT slot ownership on
that single process. It has no bearing on clustering because `main()` never
constructs a Raft node, never binds port 7379, and never contacts a peer.

Meanwhile, `kiradb-raft` is a real, tested implementation — leader
election, `RequestVote`/`AppendEntries`, commit-index advancement, its own
log persistence — proven out via `docs/internals/raft-manual-test.md`,
which runs three Raft nodes **in one JVM process** from a `main()` method in
`kiradb-raft`'s test sources. That demo never touches `KiraDBServer`. The
two pieces — the tested consensus library and the shipped server binary —
have simply never been wired together.

Concretely, what's missing to make `docker-compose.yml`'s aspirational
3-node block real:

1. `KiraDBServer.main()` needs to parse a peer list (an env var read via
   `System.getenv("KIRA_PEERS")`, or a system property, following the
   existing `-Dkiradb.node.id` convention) and construct a `RaftNode` per
   process instead of talking directly to `TieredStorageEngine`.
2. Port 7379 needs an actual listener — today `EXPOSE 7379` in the
   Dockerfile is aspirational plumbing with nothing behind it; the port
   isn't bound anywhere in `KiraDBServer`.
3. Committed Raft log entries need to apply to the same
   `TieredStorageEngine` each node already constructs, replacing today's
   direct-write path (`CommandRouter` calling `storage.put()` straight away)
   with "propose to Raft, apply on commit."
4. `CrdtStore` and the Raft log need a coexistence story — CLAUDE.md's
   design intentionally runs both models side by side ("CRDTs complement
   Raft — they don't replace it"), but `main()` today wires `CrdtStore`
   directly against the same storage engine `RaftNode` would need to own.
   Deciding which writes go through Raft (feature flags? config?) vs. stay
   CRDT-merged (rate limiter counters) is a real design decision, not just
   wiring.

I did not attempt this wiring in this phase — it's a substantial feature
(arguably its own phase), not a docs/packaging task, and CLAUDE.md's Phase 12
task list for this session was explicitly scoped to documentation and
go-live packaging. Attempting a half-wired Raft integration under a
docs-phase budget would be worse than clearly documenting the gap, which is
what `docs/deployment.md` and this file do instead.

---

## What a reviewer should scrutinize

- **Dockerfile non-root user, UID 10001 not 1000.** The first build attempt
  used the conventional UID/GID 1000 and failed: `groupadd: GID '1000'
  already exists` — the `eclipse-temurin:25-jre` base image already
  provisions an `ubuntu` group at GID 1000. Fixed by moving to 10001. This
  was caught by actually running `docker build` (see "What was verified"
  below), not by inspection — a reminder that Dockerfile correctness claims
  should be backed by an actual build, not just a read-through.
- **Base image freshness / CVEs.** `eclipse-temurin:25-jdk` /
  `eclipse-temurin:25-jre` are pulled by floating tag, not a pinned digest.
  This means the image silently picks up upstream patches (good for CVEs,
  bad for reproducibility) — no digest pinning and no vulnerability
  scanning step exists in this repo yet. Flagged below under "How to make
  this better"; not fixed in this phase.
- **`VOLUME ["/data"]` combined with `chown` in the image build.** The
  Dockerfile does `mkdir -p /data && chown -R kiradb:kiradb /app /data`
  *before* declaring `/data` a volume and switching to `USER kiradb`. This
  ordering matters: Docker copies the image's existing directory contents
  (and their ownership) into a freshly-created named volume on first run,
  so the chown must happen in the image layer, not at container-start time,
  or a fresh named volume would come up owned by root and the non-root
  process couldn't write its WAL. This was verified, not assumed — see
  below.
- **`JAVA_OPTS` as the system-property injection mechanism.** The
  Dockerfile sets `ENV JAVA_OPTS="-Dkiradb.data.dir=/data"`. This works
  because the Gradle `application` plugin's generated launch script sources
  `JAVA_OPTS` from the environment (confirmed by reading
  `kiradb-server/build/install/kiradb-server/bin/kiradb-server` — it builds
  its final Java command line from `$DEFAULT_JVM_OPTS $JAVA_OPTS
  $KIRADB_SERVER_OPTS`). A reviewer relying on Gradle's `application` plugin
  elsewhere should know this convention rather than rediscovering it.
- **`docker-compose.yml`'s 3-node block is inert, and says so loudly.**
  Three times over (top-of-file comment, per-service comments, and this
  doc) so nobody copy-pastes it into a production deploy believing it gives
  consistency guarantees it cannot provide.
- **CHANGELOG dates are commit dates, not release dates.** No git tags exist
  in this repo (`git tag` returns nothing), so "v0.5.0 shipped 2026-04-26"
  in the changelog means "the commits implementing that functionality
  landed on that date," not "a release was cut." This is called out
  explicitly at the top of `CHANGELOG.md`.

---

## What was verified vs. what's aspirational

**Actually built and run, not just written:**

- `./gradlew :kiradb-server:installDist -x test` — ran successfully,
  produced `kiradb-server/build/install/kiradb-server/{bin,lib}`.
- `docker build -f docker/Dockerfile -t kiradb/kiradb:test .` — ran to
  completion after the UID fix above. Docker Desktop was not running at the
  start of this phase; it was started and the build was retried rather than
  claiming success from a read-through.
- `docker run` of the built image — confirmed via container logs that the
  HTTP API bound port 8080, confirmed via `docker exec ... whoami` that the
  process runs as `kiradb` (not root), confirmed via `docker exec ... ls -la
  /data` that the WAL file (`kiradb.wal`) is owned by `kiradb:kiradb` and
  present, confirmed via `curl http://localhost:8080/api/overview` that the
  dashboard API returns a JSON overview (and that its `role` field already
  self-reports `"standalone"` — the HTTP layer is already honest about this,
  which is reassuring), and confirmed via a raw RESP3 `SET`/`GET` sent with
  `nc` (no `redis-cli` binary was available in this environment) that
  `+OK` and `$5\r\nworld\r\n` came back correctly over the wire.
- `./gradlew build` — green after all documentation/config changes in this
  phase (Java sources were not touched).

**Documented as aspirational, not tested (because it doesn't exist yet):**

- The 3-node `docker-compose.yml` profile — no Raft wiring exists to test.
- Any claim of "cluster" behavior, failover, or cross-node consistency in a
  containerized deployment.
- MkDocs Material actually deploying to GitHub Pages — `mkdocs.yml` was
  written and its nav double-checked against the real `docs/` file list
  (removing/renaming nothing that doesn't exist), but `mkdocs build`/`mkdocs
  gh-deploy` were not run in this session (no `mkdocs` install requested or
  assumed present; this stays a "Kiran must do this" item below, or a
  follow-up if `mkdocs` and `mkdocs-material` are added as a documented dev
  dependency).

---

## Kiran must do this (not attempted — needs your credentials / public posting)

Per the Phase 12 go-live checklist in `CLAUDE.md`, none of the following
were done, by design:

1. **Publish the Docker image**: `docker build -f docker/Dockerfile -t
   kiradb/kiradb:latest . && docker push kiradb/kiradb:latest` (needs Docker
   Hub credentials / `docker login`).
2. **Publish the Java SDK**: wire a `maven-publish` block into
   `kiradb-client/build.gradle` pointed at GitHub Packages, then `./gradlew
   :kiradb-client:publish` (needs a `GITHUB_TOKEN` with `write:packages`).
3. **Deploy docs to GitHub Pages**: `pip install mkdocs-material && mkdocs
   gh-deploy` from the repo root, once `mkdocs.yml` (added this phase) is
   reviewed (needs push access to the `gh-pages` branch, and GitHub Pages
   enabled in repo settings).
4. **Cut a git tag and GitHub Release**: `git tag v1.0.0 && git push origin
   v1.0.0`, then draft a release from that tag with `CHANGELOG.md`'s v1.0.0
   section as the body.
5. **Public posts**: Hacker News "Show HN," r/java / r/programming /
   r/opensource / r/distributed, LinkedIn, dev.to — all require Kiran's
   accounts and voice; not attempted here even in draft form beyond what
   `README.md` already communicates.
6. **`CONTRIBUTING.md` / `CODE_OF_CONDUCT.md` / branch protection / issue
   labels / Discord** — Phase 0 checklist items that were never completed
   and are unrelated to this phase's docs/packaging scope, but block a
   fully honest go-live checklist. Flagging here rather than silently
   leaving them off the radar.

---

## How to make this better

Concrete, in priority order for anyone picking this up next:

1. **Real `KIRA_NODE_ID` / `KIRA_PEERS` wiring into `KiraDBServer.main()`.**
   This is the gap that matters most — everything else in this list is
   secondary to "does KiraDB actually cluster." See the dedicated section
   above for the concrete steps. Until this exists, every mention of
   "3-node cluster" anywhere in the project's docs or marketing is aspirational.
2. **A dedicated `/health` (liveness) and `/ready` (readiness) endpoint.**
   `/api/overview` is a passable liveness check today (it proves the HTTP
   event loop is alive) but is not a readiness check — it doesn't verify the
   storage engine can still take writes (disk full? WAL write failing?), nor
   does it check whether a node mid-Raft-catchup should be excluded from a
   load balancer's rotation once clustering exists. A real `/health` should
   attempt a trivial storage round-trip (write+read a sentinel key) and
   report `503` on failure rather than always `200`.
3. **Graceful shutdown that drains in-flight connections.** The current
   shutdown hooks close the storage engine and HTTP server but don't stop
   accepting new RESP3 connections first or wait for in-flight commands to
   finish — a `SIGTERM` during a write could see that write fail with a
   channel-closed error rather than completing. A minimal fix: on shutdown,
   stop the boss `EventLoopGroup` from accepting first, wait briefly (with a
   timeout) for the worker group's queued tasks to drain, then close storage.
4. **Structured (JSON) logging.** Logback's default pattern is
   human-readable text, fine for `docker logs` on a laptop, painful for a
   log aggregator (Datadog, ELK, Loki) that wants to parse fields. A
   `logback.xml` with a JSON encoder (e.g. `logstash-logback-encoder`),
   switched on via an env var so local dev keeps the readable format, is a
   small, high-value change.
5. **Image vulnerability scanning in CI.** Nothing in `.github/workflows/`
   scans the built image today. Adding a `trivy` (or `grype`) step to a new
   `docker.yml` workflow — build the image, scan it, fail the workflow on
   HIGH/CRITICAL findings — would catch both KiraDB's own dependency CVEs
   and base-image CVEs before they reach a tag.
6. **Pin base image digests, not floating tags.** `eclipse-temurin:25-jdk`
   and `:25-jre` will silently change underneath the Dockerfile as Temurin
   ships patches. Pinning to a `@sha256:...` digest (refreshed deliberately,
   e.g. monthly via Dependabot's Docker ecosystem support) trades a small
   amount of update friction for reproducible builds.
7. **Byte-based MemCache capacity** (already tracked in `CLAUDE.md`'s Phase
   13 backlog) matters more once this phase's JVM-tuning guidance
   (`docs/deployment.md`) is actually followed in a real deployment — right
   now an operator following that guide is doing back-of-envelope math
   (`max.entries ≈ 0.35 × Xmx / avg_entry_size`) that a byte-based cap would
   make unnecessary.
8. **`CONTRIBUTING.md` and `CODE_OF_CONDUCT.md`** genuinely don't exist.
   Every "go live" checklist that assumes contributors will show up is
   incomplete without them. This is a Phase 0 debt this phase surfaced but
   did not pay down (out of scope), and is worth doing before any public
   posting from the "Kiran must do this" list above.
