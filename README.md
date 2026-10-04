# Vectors deep dive — article code

The article [**Vectors Everywhere!**](article.md) and the code it runs: embedding the
630,000-document DBpedia-14 corpus into a persistent
[Vectors](https://github.com/integrallis/vectors) collection, then serving the same bytes from local
disk and from Cloudflare R2 object storage.

Every file is a single JBang script that declares its own dependencies in its header. There is no
build file, nothing to compile, and no project to import.

```bash
git clone https://github.com/integrallis/vectors-deep-dive.git
cd vectors-deep-dive
jbang dbpedia.java
```

Run the commands from the repository root. `dbpedia.java` creates `corpus/` there and `ingest.java`
reads the Parquet files back out of it, so the working directory is what ties the steps together.

## What you need

- **Java 25** and **JBang**. The article's walkthrough used Temurin 25.0.3 and JBang 0.141.0.
- **Vectors 0.1.27** from Maven Central — JBang fetches it on first run.
- The Panama Vector API and native access, enabled per script through `//RUNTIME_OPTIONS
  --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED`. Scripts that open a collection fail closed without them.
- `OPENAI_API_KEY`, for the two scripts that call an embedding API: `ingest.java`, and `query.java` when given new text to embed.
- `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, for the object-storage half. Use the
  Access Key ID and Secret Access Key that a Cloudflare R2 API token generates; the separate Token
  value is not used. Which permission you need depends on whether you let a script create the
  bucket — see below.

Two paths are reused throughout:

```bash
export COLLECTION="$HOME/vectors/dbpedia-14-3small-512"
export REMOTE="r2://vectors-deep-dive/dbpedia-14-3small-512"
```

`r2://BUCKET/PREFIX` is this example's own URI convention, resolved by `CollectionLocation.java`. It
is not a Vectors scheme.

## The scripts

| Script | What it does | Needs |
| --- | --- | --- |
| [`dbpedia.java`](dbpedia.java) | Downloads both DBpedia-14 Parquet files into `corpus/` and prints the row counts, read with the Hardwood Java Parquet reader. | — |
| [`ingest.java`](ingest.java) | Embeds all 630,000 documents into a persistent collection: `text-embedding-3-small` at 512 dimensions, cosine, HNSW. | `OPENAI_API_KEY` |
| [`verify.java`](verify.java) | The two questions the ingest exists to answer: are the stored vectors unit length, and is the on-disk size what the row count implies. | — |
| [`inspect.java`](inspect.java) | Reads `CURRENT`, describes that generation from `manifest.bin`, and maps a single vector **without opening the collection**. | — |
| [`sample.java`](sample.java) | A deterministic 200-per-category view (seed 42, 2,800 rows) for Studio, reusing embeddings already stored — it calls no embedding API. | — |
| [`bucket.java`](bucket.java) | Creates the R2 bucket. Idempotent: a bucket you already own is reported, not an error. | R2 keys |
| [`r2.java`](r2.java) | Publishes a closed local snapshot to a new R2 prefix, or lists that prefix and prints the published generation. No embedding calls. | R2 keys |
| [`query.java`](query.java) | Embeds a query **once**, writes its exact float32 values to a `.f32` file, then reuses that file for every later search. | `OPENAI_API_KEY` only when embedding new text |
| [`CollectionLocation.java`](CollectionLocation.java) | Location handling shared through `//SOURCES`. Not run directly. | — |

## Walkthrough

```bash
# corpus
jbang dbpedia.java

# local collection: long, and sized for a 20 GiB Java heap plus native memory and page cache
jbang ingest.java
jbang verify.java
jbang inspect.java "$COLLECTION"

# save each query vector once, then search locally
jbang query.java "$COLLECTION" queries/painter.f32 "a nineteenth century French painter"
jbang query.java "$COLLECTION" queries/painter.f32

# publish the snapshot and search the same bytes from object storage
jbang bucket.java vectors-deep-dive          # or create the bucket by hand: see below
jbang r2.java publish "$COLLECTION" "$REMOTE"
jbang r2.java list "$REMOTE"
jbang query.java "$REMOTE" queries/painter.f32

# a small Studio view built from the stored embeddings
jbang sample.java "$COLLECTION" "$HOME/vectors/dbpedia-14-view"
```

### Creating the bucket without an Admin token

`bucket.java` is the only script that needs **Admin Read & Write**, because creating a bucket is an account-level operation. To avoid issuing a token that can create and delete buckets, create the bucket yourself and skip that script:

1. In the Cloudflare dashboard, open **R2** and create a bucket named `vectors-deep-dive`.
2. Create an R2 API token with **Object Read & Write**, scoped to that bucket.
3. Export the three variables as above and start at `r2.java`.

Nothing else in the walkthrough changes. `r2.java` and `query.java` perform only object operations against the named bucket — list a prefix, get a key, put a key, stream a file — so object-scoped credentials cover the rest of the walkthrough. The bucket name is the host part of `$REMOTE`; change it there if you use a different name.

The live suite in [`tests/`](tests/README.md) is the exception: it exercises bucket creation and deletion, so it needs Admin Read & Write, and with object-scoped credentials its bucket-creation test fails explicitly rather than being skipped.

Saving the query vector first keeps the local and R2 comparison on one input: both searches score the same float32 query against the same stored bytes, so any difference in results comes from the engine rather than the embedding. `query.java` also rejects a collection whose stored recipe is not `openai/text-embedding-3-small` at 512 dimensions.

## Cost and scale

`ingest.java` makes 630,000 embedding calls against a paid API and writes a multi-gigabyte collection; it is the only script that costs money. Everything after it — `verify`, `inspect`, `sample`, `r2`, and `query` with a saved `.f32` — reuses what the ingest stored.

The dataset is [DBpedia-14](https://huggingface.co/datasets/fancyzhx/dbpedia_14), CC-BY-SA-3.0:
560,000 training and 70,000 test rows, 45,000 in each of fourteen categories, with `label`, `title`
and `content` fields.

Timings these scripts print are not benchmarks; they include network calls.

## Also in this repository

- [`tests/`](tests/README.md) — the live R2 integration suite. It creates and removes its own
  buckets, and fails on missing credentials rather than skipping silently.
- [`media/`](media/index.html) — the six clips the article embeds, each an H.264 MP4 with a PNG poster, plus the gallery page. The asciinema casts, Playwright source captures and saved query vectors are not included.
