# The Math Behind Semantic Caching

> A semantic cache returns a cached LLM response when a *new* prompt is **semantically similar** to an old one — even if the wording is completely different.
>
> This document explains, at junior-engineer depth, the math that makes that work.

---

## Why this doc exists

If you've never thought hard about "vectors as meaning," semantic caching feels like magic:

```
Query:    "what is the capital of France?"
Cached:   "France's capital city?"        →  HIT, returns "Paris" without calling the LLM.
```

It's not magic. It's three layered ideas, each from a different mathematical tradition:

1. **Embeddings** — turning text into points in a high-dimensional space.
2. **Cosine similarity** — measuring how close two such points are.
3. **Approximate Nearest Neighbour (ANN) search** — finding the closest point fast, when there are millions of points.

Once those three click, semantic caching is just: *embed the query, find the closest cached embedding, return its answer if close enough.* Linear in difficulty. We'll work up to it.

---

## 1. The starting problem: computers don't understand meaning

A computer storing strings sees bytes:

```
"capital of France"      →  [99, 97, 112, 105, 116, 97, 108, 32, 111, 102, ...]
"France's capital city"  →  [70, 114, 97, 110, 99, 101, 39, 115, 32, 99, ...]
```

Byte-level comparison (or even word-level Levenshtein distance) says these are very different strings. They are. But they **mean** the same thing.

Pre-2013, the only way to bridge this gap was hand-engineered features: TF-IDF, n-grams, synonym tables, hand-coded query expansion. None of it scaled to "anything a user might say."

The breakthrough that made semantic caching practical: **learn the features automatically, from billions of sentences.** The output is an *embedding* — a list of numbers that captures meaning.

---

## 2. Vector spaces — a 30-second refresher

A **vector** is just an ordered list of numbers:

```
v = [3, 1, 4]
```

Geometrically, you can think of it as an arrow from the origin to the point `(3, 1, 4)` in 3D space. The dimensionality is the length of the list.

A **vector space** is a set of vectors that share the same dimensionality. Embeddings live in vector spaces of 384, 768, 1536, or 3072 dimensions — depending on the model.

You cannot draw a 1536-dimensional space, but the math works exactly the same way as in 2D or 3D. The intuitions transfer:

- **Each dimension is a feature.** In our 1536-dim space, dimension 47 might (loosely) correspond to "is this about politics?" Dimension 312 to "is this a question?" The model is what decides what each dimension means; we never see the labels.
- **Distance** between two vectors measures how different they are.
- **Angle** between two vectors measures how related their directions are.

For embeddings, **angle matters more than distance.** That'll matter when we get to cosine similarity.

---

## 3. What an embedding model actually does

An embedding model is a neural network — a function `f: text → vector` — that takes a string and outputs a fixed-dimensional vector of floats:

```
f("capital of France")        = [0.012, -0.485, 0.193, ..., 0.041]    (1536 floats for OpenAI ada-002)
f("France's capital city")    = [0.014, -0.481, 0.196, ..., 0.038]
f("how to bake a cake")       = [-0.102, 0.331, -0.054, ..., 0.219]
```

Two important properties:

- **Determinism.** Same input → exactly the same output, every time. The model is a pure function.
- **Continuity in meaning.** Inputs that mean similar things produce numerically similar outputs. *That's the whole point.* The training objective was specifically designed to enforce this.

### How the model is trained (the "why does this work" question)

This is where most explanations hand-wave. Let's not.

The model is trained on a corpus of billions of sentences from the web. The training objective is some form of **contrastive learning**: pairs of texts known to be related (e.g., a sentence and its paraphrase, or a question and its accepted answer) should have *similar* embeddings; unrelated pairs should have *dissimilar* embeddings.

Over millions of training steps, gradient descent shapes the network's weights so that semantic relationships are encoded as geometric relationships in the output vector space. By the end of training, the model has *learned* — without ever being told explicitly — that:

- Synonyms (`big` ↔ `large`) live near each other.
- Translations (`dog` ↔ `chien`, in a multilingual model) live near each other.
- Questions and their answers live near each other.
- Topics cluster.

This is built on the **distributional hypothesis** in linguistics, articulated by J.R. Firth in 1957:

> *"You shall know a word by the company it keeps."*

Words that appear in similar contexts mean similar things. The training data has billions of contexts; the model learns to encode them.

### What the output actually looks like

A real embedding from `text-embedding-3-small` for the string `"hello"` looks like (truncated):

```
[0.0102, -0.0244, 0.0531, -0.0078, 0.0419, -0.0001, -0.0327, 0.0224, ...]
```

1536 floats, each typically between -0.1 and +0.1. The vector is **already normalized**: its magnitude is exactly 1.0 (within float precision). That means it lives on the surface of a 1536-dimensional unit sphere. We'll come back to why this matters.

---

## 4. Geometric intuition: "closeness in vector space"

Embeddings are useful because **semantic similarity = geometric closeness.** Let's make this concrete in 2D, then generalize.

### A toy example in 2D

Imagine an embedding model that outputs 2D vectors. (Real models are 384–3072 dim; 2D is just for visualization.)

```
              y
              ↑
              │
   "France"   ●──────●  "Paris"            (close — related)
              │
              ●  "capital"
              │                            (different topic — far)
              │
              │            ●  "cookie"
              ───────────────────────→ x
              │
              ● "weather"                  (different topic — different region)
```

In a real embedding:

- "France" and "Paris" land near each other because they appear in similar contexts.
- "capital" lands somewhere between them, because it appears with both.
- "cookie" lands far away — different topic.
- "weather" lands far in a different direction.

If we ask: *"what cached prompt is closest to a new query?"*, we want a way to measure "closeness" in this space.

There are two natural candidates: **Euclidean distance** and **cosine similarity**. The choice matters.

---

## 5. Cosine similarity — the actual formula

**Cosine similarity** measures the angle between two vectors. It is defined as:

```
                          A · B
        cosine(A, B) = ───────────────
                       ║A║ × ║B║
```

Where:

- `A · B` is the **dot product** of A and B
- `║A║` is the **magnitude** (Euclidean length) of A

Let's unpack each piece.

### 5.1 The dot product (numerator)

For vectors `A = [a₁, a₂, ..., aₙ]` and `B = [b₁, b₂, ..., bₙ]`, the dot product is:

```
A · B = a₁·b₁ + a₂·b₂ + ... + aₙ·bₙ
```

It's a single number (a *scalar*).

In Java, with `float[]` vectors:

```java
double dot = 0.0;
for (int i = 0; i < a.length; i++) {
    dot += (double) a[i] * (double) b[i];
}
```

(We accumulate in `double` to avoid float rounding errors when summing 1500+ terms.)

### 5.2 The magnitude (denominator)

The magnitude (or **L2 norm**) of A is:

```
║A║ = √(a₁² + a₂² + ... + aₙ²)
```

Geometrically, it's the length of the arrow from origin to the point. In 2D it's the Pythagorean theorem; in N dimensions it's the same idea generalized.

```java
double magA = 0.0;
for (int i = 0; i < a.length; i++) {
    magA += (double) a[i] * (double) a[i];
}
magA = Math.sqrt(magA);
```

### 5.3 The geometric interpretation

The full formula `(A · B) / (║A║ × ║B║)` happens to equal `cos(θ)`, where θ is the angle between A and B in the vector space. This is a fact from linear algebra (proof in any first-year linear-algebra textbook; we'll trust it).

So:

| Angle θ | cos(θ) | Meaning |
|---|---|---|
| 0° | **1.0** | Vectors point in the **same direction** — maximum similarity |
| 90° | **0.0** | Vectors are **perpendicular** — no relationship |
| 180° | **-1.0** | Vectors point in **opposite directions** — anti-correlated |

For embeddings, in practice, you'll see values clustered between ~0.3 (unrelated topics) and ~0.99 (near-identical paraphrases). Negative cosine is rare for English text embeddings because the model never produces vectors that point "opposite" each other in a meaningful way.

### 5.4 Why direction, not magnitude

Notice the formula divides out the magnitudes. **Two vectors pointing the same direction have cosine = 1.0 regardless of length.**

```
A = [1, 0]
B = [3, 0]                 (same direction, but 3x longer)

A · B = 1*3 + 0*0 = 3
║A║ = 1, ║B║ = 3
cosine = 3 / (1 × 3) = 1.0   ← maximum similarity
```

This is the right behavior for embeddings, because **the direction encodes meaning**, not the length. A "longer" embedding doesn't mean "more meaningful" — the model just happened to output a slightly larger vector. We don't want that to affect similarity.

---

## 6. Worked examples — small vectors you can verify by hand

Let's compute cosine similarity for some 3D vectors so you can do the arithmetic yourself.

### Example 1: identical vectors

```
A = [1, 2, 3]
B = [1, 2, 3]

A · B = 1·1 + 2·2 + 3·3 = 1 + 4 + 9 = 14
║A║ = √(1 + 4 + 9) = √14 ≈ 3.742
║B║ = √14 ≈ 3.742

cosine = 14 / (3.742 × 3.742) = 14 / 14 = 1.0
```

Identical vectors → cosine 1.0. ✓

### Example 2: orthogonal vectors

```
A = [1, 0, 0]
B = [0, 1, 0]

A · B = 1·0 + 0·1 + 0·0 = 0
║A║ = 1, ║B║ = 1

cosine = 0 / (1 × 1) = 0.0
```

Perpendicular → cosine 0.0. No relationship.

### Example 3: similar direction, different magnitudes

```
A = [1, 2, 3]
B = [2, 4, 6]      (same direction, 2× longer)

A · B = 2 + 8 + 18 = 28
║A║ = √14 ≈ 3.742
║B║ = √(4 + 16 + 36) = √56 ≈ 7.483

cosine = 28 / (3.742 × 7.483) = 28 / 28 = 1.0
```

Same direction, different magnitude → still cosine 1.0. ✓ This is what makes cosine the right metric for direction-based encodings like embeddings.

### Example 4: realistic-ish embeddings

Let's pretend our 5D embeddings look like this:

```
"capital of France"     A = [ 0.40,  0.50,  0.10, -0.20,  0.70]
"France's capital city" B = [ 0.42,  0.48,  0.12, -0.18,  0.71]   (very similar)
"how to bake a cake"    C = [-0.30,  0.10,  0.60,  0.50, -0.40]   (very different)
```

Compute cosine(A, B):

```
A · B = 0.40·0.42 + 0.50·0.48 + 0.10·0.12 + (-0.20)·(-0.18) + 0.70·0.71
      = 0.168 + 0.240 + 0.012 + 0.036 + 0.497
      = 0.953

║A║² = 0.16 + 0.25 + 0.01 + 0.04 + 0.49 = 0.95;   ║A║ = √0.95 ≈ 0.9747
║B║² = 0.1764 + 0.2304 + 0.0144 + 0.0324 + 0.5041 = 0.9577;  ║B║ ≈ 0.9786

cosine(A, B) = 0.953 / (0.9747 × 0.9786) ≈ 0.953 / 0.9538 ≈ 0.999
```

A and B are nearly identical: cosine **0.999**. Rounded down for safety, an `0.95` threshold would catch this as a cache hit.

Compute cosine(A, C):

```
A · C = 0.40·(-0.30) + 0.50·0.10 + 0.10·0.60 + (-0.20)·0.50 + 0.70·(-0.40)
      = -0.12 + 0.05 + 0.06 - 0.10 - 0.28
      = -0.39

║C║² = 0.09 + 0.01 + 0.36 + 0.25 + 0.16 = 0.87;   ║C║ ≈ 0.9327
cosine(A, C) ≈ -0.39 / (0.9747 × 0.9327) ≈ -0.429
```

A and C are not just unrelated, they're slightly *anti-correlated*. With a threshold of 0.85, this is firmly a miss. ✓

This is exactly what the cache will compute, just at 1536 dimensions instead of 5.

---

## 7. Why cosine, not Euclidean distance?

**Euclidean distance** is the "ruler distance" between two vectors:

```
distance(A, B) = √(Σ (aᵢ - bᵢ)²)
```

It measures how *far apart* the points are. For our pretend embeddings above:

```
distance(A, B) = √((0.40-0.42)² + (0.50-0.48)² + ...) ≈ √0.0066 ≈ 0.081
distance(A, C) = √((0.40+0.30)² + (0.50-0.10)² + ...) ≈ √1.96 ≈ 1.40
```

A is closer to B than to C — same answer cosine gave. So why prefer cosine?

Three reasons:

1. **Embeddings are normalized.** OpenAI, Cohere, Sentence-Transformers all return unit-magnitude vectors (║v║ = 1.0). For unit vectors, cosine and Euclidean produce the *same ranking* — so you can use either, but cosine is bounded `[-1, 1]` regardless of dimension, while Euclidean varies with magnitudes.
2. **Bounded output.** A cosine of 0.92 has a known meaning in any dimension. A Euclidean distance of 0.4 is meaningful only if you know the magnitudes — its scale depends on the embedding norm.
3. **Industry convention.** Every vector DB, every embedding documentation, every paper uses cosine. Following the convention removes a class of "wait, are you talking about cosine or Euclidean" confusion.

**Bottom line:** for normalized embeddings, cosine and Euclidean give equivalent rankings. We'll use cosine because it's bounded and conventional.

---

## 8. Normalization — the optimization that matters

If both vectors have magnitude 1.0, the cosine formula simplifies to **just the dot product**:

```
cosine(A, B) = A · B / (1 × 1) = A · B
```

**Normalize once, compare cheaply forever.** Most embedding APIs return already-normalized vectors. If yours doesn't, you should normalize at write time:

```java
double mag = magnitudeOf(v);
for (int i = 0; i < v.length; i++) {
    v[i] /= mag;       // unit-magnitude
}
```

Now cosine similarity = dot product = a single loop, no magnitude division at query time. For 10,000 cached entries × 1536 dims, that's a 10× speed-up over computing magnitudes on every query.

KiraDB's semantic cache will normalize on write and store the unit vector. Search reduces to dot products.

---

## 9. The curse of dimensionality (and why cosine still works)

In high dimensions, geometry gets weird. Two specific weirdnesses:

### 9.1 All points become roughly equidistant

If you sample random vectors uniformly in 1000 dimensions, **most pairs have a Euclidean distance very close to the same value.** The contrast between "nearest" and "farthest" neighbour shrinks toward zero. This is the *curse of dimensionality*.

This is a real problem for ANN search and is one reason naïve nearest-neighbour algorithms break down at high dimensions.

### 9.2 Why embeddings escape the curse

Embeddings are *not* uniformly random in 1536-dim space. Trained embedding models concentrate semantic content on a low-dimensional **manifold** within the high-dimensional space — different topics live in clearly different regions, similar inputs cluster tightly.

So even though the *space* has 1536 dimensions, the *useful information* lives in a much lower-dimensional surface within it. Cosine similarity remains a strong discriminator on this manifold.

This is also why ANN structures like HNSW work well on embeddings despite the curse: the manifold has structure they can exploit.

---

## 10. The threshold — what's a good cosine cutoff?

If `cosine(query, cached) ≥ THRESHOLD`, return the cached answer. What should THRESHOLD be?

Empirically, here's what different thresholds catch in production:

| Threshold | Catches | Risk |
|---|---|---|
| 0.99 | Near-identical paraphrases only | Misses obvious paraphrases |
| 0.95 | Most paraphrases, same intent | Standard for production caches |
| 0.90 | Loose paraphrases, related topics | Some false hits (incorrect answers returned) |
| 0.85 | Topic-similar but sometimes-wrong-intent | Too loose for most use cases |
| 0.80 | Anything vaguely related | Almost certainly bad |

The sweet spot for most LLM-cache use cases is **0.92–0.95**. KiraDB will let you configure threshold per cache namespace because the right value is workload-dependent:

- **Strict QA cache** (must return the right answer): 0.95+
- **Suggestion / completion cache** (close enough is fine): 0.90
- **Topic-tagging cache** (domain-level, not exact): 0.85

A practical rule: run a sample of your real queries through the cache, manually inspect the matches, and pick the threshold where the false-hit rate drops to acceptable. Don't pick a threshold by feeling.

---

## 11. ANN — making search fast at scale

Naïve search: for each cached vector, compute cosine, return the max. Cost: **O(N × D)** per query, where N is cache size and D is vector dimension.

For N = 10,000 and D = 1536: ~15M float ops, ~5–10 ms on a modern CPU. **Linear scan is fine for cache sizes under ~10k entries.**

For N = 10M and D = 1536: ~15B float ops. That's hundreds of milliseconds per query. Linear scan no longer works.

That's the regime where you need ANN — Approximate Nearest Neighbour algorithms. They trade a small amount of recall (you might miss the absolute closest vector) for a large speed-up (logarithmic instead of linear).

The two big families:

### 11.1 HNSW — Hierarchical Navigable Small World

The state of the art. Builds a multi-layer graph where each layer is a "navigable small world" (every node is reachable in roughly log(N) hops). Search starts at the top sparse layer, descends to the bottom dense layer, finding the nearest neighbour in O(log N) time.

Used by: Weaviate, Qdrant, Milvus, FAISS, pgvector.

**Tradeoff:** complex to implement (multiple parameters: `M`, `efConstruction`, `efSearch`); needs tuning per workload; significant memory overhead beyond the raw vectors.

### 11.2 IVF — Inverted File Index

Cluster the vectors into K coarse groups using k-means. At query time, find the nearest cluster center, then exhaustive-search just within that cluster. Avoids the curse of dimensionality by reducing the search space.

**Tradeoff:** clustering quality determines recall; vectors near cluster boundaries get missed; needs periodic re-clustering as data changes.

### 11.3 LSH — Locality-Sensitive Hashing

Hash functions designed so that "similar" inputs collide. Look up the hash of the query and only compare against vectors with the same hash.

**Tradeoff:** classical for small dimensions, generally beaten by HNSW in modern benchmarks.

### What KiraDB will use

For Phase 8 we ship a **flat (linear) index** behind a `VectorIndex` interface. Reasons:

- Most LLM-cache use cases have ≤10k entries — linear is fast enough.
- Zero external dependencies; matches the README pitch.
- HNSW can be added later behind the same interface (filed in Phase 13 with a "10k entries" trigger).

---

## 12. Numerical pitfalls

A few things that bite people working with embeddings:

### 12.1 Float precision

Cosine similarity from a single computation can return values like `1.0000000003` or `0.99999998` — *slightly* outside the theoretical `[-1, 1]` range due to float rounding when summing 1500+ terms. Robust code clamps the result:

```java
return Math.max(-1.0, Math.min(1.0, cosine));
```

### 12.2 Accumulate dot products in `double`

A `float` has 23 bits of mantissa, ~7 decimal digits of precision. Summing 1536 small floats accumulates error. Always do dot products in `double` and only cast back at the end if you must.

### 12.3 NaN propagation

If a vector contains a NaN (rare but possible if something corrupted the data), the dot product will be NaN, and `Math.max(NaN, ...)` may not behave as you expect. Validate vectors on write.

### 12.4 Don't compare embeddings from different models

Embeddings from `text-embedding-3-small` (1536 dims) and `nomic-embed-text` (768 dims) live in completely different vector spaces. Cosine between them is meaningless. Always tag entries with the embedding model name and refuse cross-model lookups.

---

## 13. Putting it all together — the cache decision flow

```
                  ┌──────────────────────────┐
   client ──────▶ │  SC.GET ns "user query"  │
                  └────────────┬─────────────┘
                               │
                  ┌────────────▼─────────────┐
                  │ embed(query) via         │  one HTTP call to OpenAI/Ollama
                  │ EmbeddingService         │  (~50–500 ms)
                  └────────────┬─────────────┘
                               │
                          q_vec (1536 floats, ║q_vec║ = 1)
                               │
                  ┌────────────▼─────────────┐
                  │ VectorIndex.findClosest( │  for each cached vector v:
                  │   q_vec, threshold)      │     score = q_vec · v   (dot product, no division)
                  │                          │  return entry with max score
                  └────────────┬─────────────┘  (linear scan today; HNSW later)
                               │
                       maxScore  cachedEntry
                               │
                       maxScore ≥ threshold ?
                       /                      \
                     YES                        NO
                      │                          │
            return cachedEntry.response   return nil → caller falls
                                          back to LLM, then SC.SET
```

### Write path

```
SC.SET ns "user query" "llm response" [threshold]
  ↓
embed(query) → q_vec
store { promptText, q_vec, response, ttl, model } under sc:<ns>:<hash>
add q_vec to in-memory index
```

### Why we keep the original prompt text

For debugging. When `SC.GET` returns a hit, we can log "this query matched cached prompt X with similarity 0.94" — operators love this. Without it, you can't tell *why* a particular cache hit fired.

---

## 14. The one-line summary you can give someone in an elevator

> *Semantic caching turns text into 1500-dim vectors using a trained embedding model, then returns a cached answer when a new query's vector is within a configured cosine angle of an old one — typically 0.92 to 0.95.*

Every word in that sentence has a paragraph behind it in this doc. Now you have the paragraphs.

---

## Reading list

- *Word2Vec: Distributed Representations of Words and Phrases* — Mikolov et al., 2013 (the paper that started modern embeddings)
- *Sentence-BERT* — Reimers & Gurevych, 2019 (sentence-level embeddings via BERT)
- *Efficient and robust approximate nearest neighbor search using HNSW graphs* — Malkov & Yashunin, 2018 (the HNSW paper)
- *Curse of dimensionality* — any standard machine-learning textbook (Bishop's PRML chapter 1, or Hastie/Tibshirani/Friedman)
- OpenAI embeddings docs: https://platform.openai.com/docs/guides/embeddings
- The KiraDB unit tests under `kiradb-semantic-cache/src/test/java/...` (once Phase 8 lands) — every test will be a worked example of one property
