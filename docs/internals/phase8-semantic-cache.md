# Phase 8 — Semantic Cache: Design Notes & Deep Dive

> Written during the autonomous build (2026-07-05). Math background lives in
> [semantic-cache-math.md](semantic-cache-math.md) — read that first if
> embeddings/cosine/ANN aren't already comfortable.

---

## What was built

| Piece | Location | Role |
|---|---|---|
| `EmbeddingProvider` | `kiradb-semantic-cache/.../embedding/EmbeddingProvider.java` | text → L2-normalized `float[]`. The pluggability seam. |
| `LexicalEmbeddingProvider` | same package | **Default.** Feature-hashed bag of word unigrams + bigrams + char trigrams, FNV-1a, 512-dim. Zero deps, deterministic. |
| `OllamaEmbeddingProvider` | same package | Real neural embeddings via local Ollama HTTP API (`nomic-embed-text` default). |
| `VectorIndex` / `SearchResult` | `.../index/` | similarity search seam: `add / remove / search(query, k)`. |
| `FlatCosineIndex` | `.../index/` | Exact brute-force dot product over `ConcurrentHashMap`. O(n·d) per query. |
| `SemanticCacheStore` | `.../SemanticCacheStore.java` | The orchestrator: embed → persist → index; lookup with threshold + TTL + lazy expiry; stats. |
| `SemanticCacheHandler` | `kiradb-server/.../handlers/` | `SC.SET / SC.GET / SC.DEL / SC.STATS` RESP3 commands. |
| Server wiring | `KiraDBServer.registerSemanticCacheCommands` | provider chosen by `-Dkiradb.sc.provider=lexical\|ollama`. |

Request flow:

```
SC.GET "capital of france?" [THRESHOLD 0.6]
   │
   ▼
SemanticCacheHandler ── parse args, default threshold from store
   │
   ▼
SemanticCacheStore.get(prompt, threshold)
   │  provider.embed(prompt)              ← ~µs lexical, ~ms Ollama
   │  index.search(vector, k=5)           ← O(n·512) dot products
   │  for each candidate best-first:
   │     score < threshold → MISS (early break — ordering guarantees it)
   │     storage lost it   → drop dangling vector, next
   │     expired           → lazy delete, next
   │     else              → HIT, count tokens saved
   ▼
BulkString(response) or RespNull
```

---

## The design decisions, and why

### 1. Embedding provider: lexical default, Ollama opt-in (not OpenAI)

The plan offered OpenAI API vs Ollama. We shipped **neither as the default** —
here's the reasoning a principal engineer applies:

| Option | Quality | Latency | Cost | Boot dependency | Deterministic tests |
|---|---|---|---|---|---|
| OpenAI API | best | 50–200 ms + network | $ per call, needs API key | internet + key | no (remote model can change) |
| Ollama local | very good | 5–50 ms local | free | ollama daemon + model pull | no (model file versioned, but heavy in CI) |
| Lexical hashing | word-overlap only | ~10 µs | free | none | **yes** |

A database that fails to boot because an embedding sidecar is down is a bad
database. The **default must be self-contained**; the quality upgrade must be a
config flag, not a code change. `-Dkiradb.sc.provider=ollama` swaps in the real
thing. Adding an `OpenAiEmbeddingProvider` later is ~50 lines against the same
interface.

**The honesty cost:** the lexical embedder measures word overlap, not meaning.
"car" vs "automobile" scores ≈ 0. Its Javadoc says so explicitly. The
integration test's paraphrase corpus deliberately uses word-overlap paraphrases
so it isn't pretending to test synonymy it can't deliver.

### 2. Why vectors must be L2-normalized (the interface contract)

Cosine similarity is `dot(a,b) / (|a|·|b|)`. If every vector has length 1, the
denominators vanish and **cosine = plain dot product** — one multiply-add loop,
no square roots at query time. The contract lives in `EmbeddingProvider`'s
Javadoc and every provider enforces it at construction of the vector, not at
use. This is the classic "make illegal states unrepresentable at the seam"
move: the index never has to wonder whether normalization happened.

Pitfall a reviewer should check: a *future* provider that forgets to normalize
will silently return wrong similarities (scores > 1 or scaled down). A
defensive `assert |v| ≈ 1` in `FlatCosineIndex.add` is a reasonable hardening
item.

### 3. Flat exact index, not HNSW, not Weaviate

Brute force over n vectors of dim 512 costs n·512 multiply-adds. At 10k
entries that's ~5M FLOPs ≈ well under a millisecond on any modern core. The
crossover where graph indexes (HNSW) earn their complexity is ~50k–100k
entries. A semantic cache for LLM prompts rarely holds more than tens of
thousands of live entries (they have TTLs; stale answers are poison).

Weaviate as a sidecar was rejected for the same reason as OpenAI-as-default:
**operational dependency for a case we don't have yet.** The `VectorIndex`
interface is the escape hatch — an `HnswIndex` or `WeaviateIndex` is a drop-in.

### 4. Threshold semantics: default 0.85, per-query override

`SC.GET prompt THRESHOLD 0.6` overrides the store default (0.85, configurable
via `-Dkiradb.sc.threshold`). Two thresholds exist conceptually:

- **0.85+** is calibrated for *neural* embedders, where true paraphrases land
  0.90–0.97 and unrelated text lands < 0.75.
- The *lexical* embedder spreads paraphrases lower (dropping "what is the"
  genuinely removes features), so deployments using it should configure ~0.55–0.65.

This is why the milestone integration test passes `THRESHOLD 0.55` explicitly —
that's not test-gaming, it's what a lexical deployment would configure. The
plan's "per cache namespace threshold (0.80–0.99)" is **not implemented**
(there are no namespaces yet) — see *How to make this better*.

### 5. TTL inside the payload + injectable clock, not storage-engine TTL

The storage engine has its own TTL machinery, but the cache stores expiry
*inside* the serialized entry and checks it against an injectable
`LongSupplier clock`. Why:

- **Testability.** TTL tests advance a fake clock instead of `Thread.sleep` —
  deterministic and instant. Sleeping tests are flaky tests.
- **Single source of truth.** The index (which the storage engine knows nothing
  about) must also skip expired entries; the expiry has to live where the cache
  logic can see it.
- **Lazy deletion + top-k=5.** Expired entries are removed on the lookup that
  discovers them. Searching top-5 (not top-1) means an expired best match
  doesn't hide a live second-best. The alternative — a background sweeper —
  is more machinery for marginal gain at cache scale (a sweeper becomes worth
  it when expired-but-unqueried entries hold real memory; see below).

### 6. Persistence format and restart recovery

Entries are stored under `sc:entry:<hex sha-256(prompt)>` as a hand-rolled
`DataOutputStream` binary: prompt, response, expiresAtMillis, embedding floats.
On construction the store scans the `sc:entry:` prefix and rebuilds the
in-memory index — **no re-embedding at boot**, so restart works even if Ollama
is down, and boot cost is one prefix scan.

Why SHA-256 of the prompt as the id: stable across restarts, collision-safe,
and makes `SC.DEL prompt` an O(1) exact-key operation (delete is *exact*, not
semantic — deleting "the closest match" would be a footgun).

Why hand-rolled binary and not JSON/Jackson: the embedding is 512 floats;
JSON would balloon it ~4× and add a dependency to the hot path for zero
benefit. The format is documented in the class Javadoc — that's the contract.

**Reviewer scrutiny point:** the format has no version byte. Changing the
layout (or the embedder — see next) breaks old entries. A version prefix +
`provider.id()` stamp per entry is cheap insurance; listed below.

### 7. Stable hashing (FNV-1a, not String.hashCode)

Persisted vectors must mean the same thing forever. `String.hashCode()` is
*currently* stable by spec, but tying on-disk data to a JDK implementation
detail is the kind of dependency that bites years later. FNV-1a is 6 lines we
own. Same reasoning as the deterministic `FlagBucketing` SHA-256 in Phase 7.

---

## Pitfalls hit during the build

- **Jedis 5 RESP3 maps**: `SC.STATS` returns a RESP3 map (`%`); Jedis parses it
  into `List<KeyValue>`, not a flat alternating list. The integration test now
  handles both shapes.
- **Threshold miscalibration**: the milestone test initially ran at the 0.85
  default and scored well under 80% — correct behavior for a lexical embedder,
  wrong expectation in the test. Fixed by making the test configure the
  threshold a lexical deployment would use (see §4).

---

## What a reviewer should scrutinize

1. **Unbounded growth.** Nothing evicts un-expired entries. A workload that
   `SC.SET`s forever grows the index (heap: 512 floats × n = 2 KB/entry) and
   storage without limit. This is the biggest real gap.
2. **Embedder/entry mismatch.** Entries embedded with `lexical` are searched
   with whatever provider the server booted with. Switch `lexical → ollama`
   (different dimension!) and the rebuilt index holds 512-dim vectors while
   queries produce 768-dim ones — at best an exception, at worst nonsense
   scores. Entries should be stamped with `provider.id()` and mismatches
   purged or re-embedded at boot.
3. **`set` is not atomic** across storage.put + index.add. A crash between the
   two is healed at next boot (rebuild scans storage — storage is the source
   of truth), but a concurrent `get` in that window can see storage-yes/index-no
   (harmless miss) — worth knowing, not worth fixing.
4. **Stats aren't persisted** — counters reset on restart, and
   `estimatedTokensSaved` uses the crude length/4 heuristic. Fine for Phase 8;
   the dashboard (Phase 9) reads them live.

## How to make this better

Ranked by value-per-effort:

1. **Capacity bound + eviction** (the unbounded-growth gap): `maxEntries`
   config; evict LRU-by-last-hit or lowest-hit-count. The cache already tracks
   hits per lookup — per-entry hit metadata is a small addition.
2. **Entry versioning + provider stamp**: one format-version byte + the
   `provider.id()` string per entry; on rebuild, skip/flag entries whose
   provider ≠ current. Prevents the dimension-mismatch failure class outright.
3. **Namespaces with per-namespace thresholds** (from the original plan):
   `SC.SET ns prompt response`; key becomes `sc:entry:<ns>:<sha>`; one index
   per namespace. Different apps genuinely need different strictness.
4. **HNSW index** behind `VectorIndex` when entry counts justify it
   (>~50k). Pure-Java HNSW is a great learning exercise: layered skip-list
   intuition applied to graphs.
5. **Negative caching**: cache "no similar entry existed" for hot misses to
   skip repeated embed+search on prompt storms. Needs careful invalidation on
   writes.
6. **Batch embedding API** on `EmbeddingProvider` for bulk warm-up loads
   (Ollama and OpenAI both support batching; n round-trips → 1).
7. **Persist stats** under `sc:stats` so `SC.STATS` survives restart, and
   replace length/4 with a real tokenizer count when a tokenizer dependency is
   justified.
