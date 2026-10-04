# Deep Dive in the Java Vectors Library

Is the Vectors library a Vector Database? The answer is it depends on you\! The TLDR of it is that you can have vector collections in your JVM. Why would you want to do that? Vectors can be made if any unstructured data and collections can get quite big, the secret sauce is a property called semantic similarity. Which leads us to semantic search which allows us to find similar vectors to an input vector. The closer you are to the vectors you are searching over the fastest your searches will be\!

## Vectors and Embeddings

So what is the relationship between a Vector and an Embedding? A Vector is just an ordered set of numbers… A typical embedding is a vector that was created using a model. Take any unstructured data that matches the model input, e.g. It can be vectorized using a model and you get a vector. The dimensions of the vector, e.g. how many numbers we pack in there make it a multi-dimensional vector. Again, an image, a text snippet, a word, will all have many models that can turn them into vectors. The model extracts the “meaning” of the input and maps it to this n-dimensional space. The image below shows the flat embeddings created from an RGB 32x32 image using a CNN Vision model, in this particular model you could get a 768 dim vector or a 500 dim from the “Hidden Units”. Notice that this CNN is a classifier with 2 Outputs or Classes, we just don’t use that\!

![Flat embeddings taken from a CNN vision model](https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/001_cnn.png)

## Searching

Once you have a collection of vectors, what good are they for? Well, you can, given an input vector, find the top-K similar vectors. If you have a collection of vectors created from sentences from some documents, given a new query, or sentence, you can use the same model used to create the documents to create the “input” vector (the blue dot).

![Finding the three nearest neighbours of a query vector](https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/002_knn.png)  
The idea is to find the K-nearest neighbours (in this example K=3), which will represent the K most similar elements to our input. This can be useful for a straight up similarity search, give this text find the similar text. Or in a RAG scenario, where you have a query to ask an LLM and you do a similarity search to add related info to the context. We will see a few scenarios next.

### Java Vectors API

Why are we doing this on the Java VM? Because the data is already here. Your documents, your rows, your PDFs, the code that decides what a user is allowed to see, all of it is already running inside a JVM. Sending it somewhere else to be compared, and waiting for an answer to come back, is a choice, and until recently it was a choice we had to make. What changed is that the JVM got good at the one thing vector search actually does.

The Java Vector API and the vectors in "vector database" have nothing to do with each other. Our kind of vector is a list of numbers that carries meaning. The JDK's Vector API is about SIMD (Single Instruction, Multiple Data) persuading the CPU to do arithmetic on several numbers at once. It's a happy accident of naming that we need the second one to go fast at the first.

Here is where a vector search spends its entire life. Comparing two 768-dimension vectors is 768 multiplications and 768 additions:

`float dot = 0f;`  
`for (int i = 0; i < a.length; i++) {`  
  `dot += a[i] * b[i];`  
`}`

That loop is correct, and it leaves most of your processor sitting idle. A modern CPU has registers that hold eight floats side by side and instructions that multiply all eight in one step. Written the way above, you are using one lane out of eight. For a single comparison, who cares. For a search over 100,000 vectors, that loop body runs 76.8 million times, now you care.

The Java Vector API (jdk.incubator.vector) lets you ask for the wide lanes explicitly.  
Same answer, a fraction of the trips around the loop. But couldn't HotSpot have vectorized the first loop by itself? Sometimes, and that is precisely the problem. Auto-vectorization is best-effort and silent. You cannot tell by reading the source whether you got it, and an innocent refactor can quietly take it away. The Vector API turns it into something you asked for and can point at.

This is why Vectors requires `--add-modules jdk.incubator.vector` at compile time and at run time, and why that is not an optional tuning flag. The SIMD distance kernels are not an optimization bolted onto the library; they are the library.

At its core, a vector database boils down to a distance kernel, an index, and a structured file format. With Java 25 providing these capabilities natively, avoiding external network round-trips to isolated services becomes the real game changer.

### The value of locality

Locality means one thing here: the vectors and the code comparing them live in the same process, so reaching a vector is a memory read rather than a conversation.  
A 768-dimension embedding of 32-bit floats is 768 × 4 \= 3,072 bytes. Call it 3 KB. So:

| vectors | raw size |
| :---- | :---- |
| `1,000` | `~3 MB`  |
| `100,000`  | `~300 MB` |
| `1,000,000` | `~3 GB` |

That is the raw vectors; an index such as HNSW adds its own links on top, and text and metadata add theirs. But look at the middle row. A corpus that most applications would describe as "large" fits comfortably in the RAM of the machine you are already paying for.

Now look at what a query does when the collection lives in another service. Serialize the query vector into bytes. Write it to a socket. Wait. The other side deserializes it, searches, and serializes the results. Read them back. Deserialize. Then do all of that again for the next query. The arithmetic in the middle (the part we just made fast) is frequently not the expensive part of that sequence.

When the collection lives in your process, you hand the search a `float[]`. There is no copy, no socket, no wire format, and no second process that has to be up for your feature to work.

How much is that worth in milliseconds? It depends on your corpus, your hardware and your network, and I would rather measure it on your machine than quote my hardware numbers at you. That is exactly what we will do later in this series, with the benchmark in the open. On top of that you also get the ability to debug your vectors\!

## Your first Vectors Collection: DBPedia-14

We will use the popular DBpedia-14 ontology classification dataset built by Zhang, Zhao and LeCum in 2015, who needed a large, clean corpus to benchmark text classifiers. Its data points come from DBpedia 2014, a structured database extracted from Wikipedia: they picked fourteen categories that do not overlap: companies, artists, athletes, animals, plants, films, albums and so on. Every row is one entity: its name, the opening paragraph of its Wikipedia article, and which of the fourteen categories it belongs to. It is typically used to train and score topic classifiers, and more recently as a standard corpus for testing vector search, because the documents are short, there are a lot of them, and every one comes with a correct answer attached. We will create an embedding for each data point using OpenAI's text-embedding-3-small model, asking it for vectors of 512 dimensions rather than its native 1,536, which keeps the whole collection small enough to carry around.

## Download the corpus and store it locally

Let's give those ideas some data. Every script in this article lives in the companion repository, [integrallis/vectors-deep-dive](https://github.com/integrallis/vectors-deep-dive). Clone it and run the commands from its root:

```bash
git clone https://github.com/integrallis/vectors-deep-dive.git
cd vectors-deep-dive
```

You need Java 25 and [JBang](https://www.jbang.dev/documentation/guide/installation.html). JBang reads the dependency declarations at the top of each Java file, resolves the jars, and runs it. There is no application project to assemble first. The working directory matters: `dbpedia.java` writes the corpus into `corpus/` beside the scripts, and later steps read it back from there.

The download is one command:

```bash
jbang dbpedia.java
```

[dbpedia.java](https://github.com/integrallis/vectors-deep-dive/blob/main/dbpedia.java) downloads the two Parquet files into `corpus/`, then reads them using the Java Hardwood reader and prints the row counts. The [DBpedia-14 dataset](https://huggingface.co/datasets/fancyzhx/dbpedia_14) contains 560,000 training rows and 70,000 test rows: **630,000 documents**, with 45,000 in each of fourteen categories. The fields are `label`, `title`, and `content`; the labels in this Parquet version are numbered 0 through 13. The dataset card records the CC-BY-SA-3.0 license.

```text
corpus/
  dbpedia_14-train.parquet
  dbpedia_14-test.parquet
```

Reading the Parquet files in Java is a row cursor over typed accessors:

```java
try (ParquetFileReader parquet = ParquetFileReader.open(InputFile.of(file));
     RowReader reader = parquet.rowReader()) {
  while (reader.hasNext()) {
    reader.next();
    perCategory[(int) reader.getLong("label")]++;
    characters += reader.getString("title").length()
        + reader.getString("content").length();
    rows++;
  }
}
```

We will put both splits in our searchable corpus. We are building a search collection here, so we are not using that split to train and evaluate a classifier. A later retrieval benchmark needs its own held-out queries and relevance judgments.

<video controls playsinline preload="metadata" width="960" style="max-width:100%" poster="https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/01-download.png">
  <source src="https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/01-download.mp4" type="video/mp4">
</video>

[Your corpus: both Parquet files downloaded and 630,000 rows counted](https://github.com/integrallis/vectors-deep-dive/blob/main/media/01-download.mp4).

## Turn the corpus into a persistent collection

The text going into the embedding model is deliberately simple:

```java
String text = row.title() + ". " + row.content().strip();
```

Keep that exact string with the vector. When a search result comes back, we want to read what the model saw. The category and title go into metadata, where they can help us inspect results without becoming hidden instructions to the model.

Set your OpenAI API key in the terminal, then run the [complete ingest script](https://github.com/integrallis/vectors-deep-dive/blob/main/ingest.java):

```bash
export OPENAI_API_KEY="your-api-key"
jbang ingest.java
```

This is the paid step: it sends the corpus text to the embeddings API. The script reports the input-token count returned by the provider. It requests `text-embedding-3-small` with `dimensions(512)`; the `dimensions` parameter is how OpenAI supports shorter embeddings. Use the same model and dimension for queries. [OpenAI's embedding guide](https://developers.openai.com/api/docs/guides/embeddings) describes that parameter and token-based billing.

The script pins its dependencies:

```java
//JAVA 25
//DEPS com.integrallis:vectors:0.1.27
//DEPS dev.hardwood:hardwood-core:1.0.0.Final
//DEPS org.xerial.snappy:snappy-java:1.1.10.8
//DEPS dev.langchain4j:langchain4j-open-ai:1.21.0
//RUNTIME_OPTIONS --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx20g
```

This full-corpus ingest is sized for a machine with room for a 20 GiB Java heap plus native memory and the OS page cache. The final vector file is much smaller than that heap ceiling; building the collection also needs the graph, documents, and batches in flight.

The collection setup is the part to carry into your own application:

```java
Path storage = Path.of(System.getProperty("user.home"))
    .resolve("vectors/dbpedia-14-3small-512");

try (var collection = VectorCollection.builder()
    .dimension(512)
    .metric(SimilarityFunction.COSINE)
    .indexType(IndexType.HNSW)
    .storagePath(storage.toAbsolutePath())
    .embeddingRecipe(recipe)
    .autoCommitThreshold(100_000)
    .backgroundCompaction(Duration.ofMinutes(1))
    .build()) {
  // Embed batches, then add their documents in file order.
  // The complete script supplies the reader, model, recipe and batching loop.
  collection.addAll(documents);
  collection.commit();
}
```

That excerpt shows the configuration; `ingest.java` is the runnable program. It embeds batches of 100, overlaps up to 32 embedding requests, and hands completed batches to one writer in file order. Provider rate limits determine how much concurrency your account can sustain. The script uses IDs `dbpedia-0` through `dbpedia-629999` and stores `category`, `categoryId`, and `title` as metadata.

Why distinguish an embedding batch from a commit? An embedding batch is a network request. A commit publishes a searchable generation of the collection. Committing after every 100-document API response would repeatedly write a growing collection. Here, automatic commits happen at 100,000 staged documents, and the explicit final `commit()` publishes the tail. Staged documents become searchable when their generation is committed.

The script can resume past the number of rows already committed. That assumes the same two source files, the same file order, and a collection this script alone has populated. Keep those inputs unchanged and let the ingest finish before starting Studio or another process against this directory.

The embedding recipe records the model, dimension, similarity function and text preparation. It is stored with the collection and anchored by a hash in the manifest. For a hosted model this is declared provenance: the recipe's date is not a provider guarantee of immutable weights. It still gives us a concrete way to catch an accidental change of model or dimension when reopening the data.

For the remaining commands, give the collection a short shell name:

```bash
export COLLECTION="$HOME/vectors/dbpedia-14-3small-512"
```

## Ask it something

A new query goes through the same embedding model. Then the library gets a `float[]` and a request for the five nearest documents:

```java
var model = OpenAiEmbeddingModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("text-embedding-3-small")
    .dimensions(512)
    .build();

float[] queryVector = model.embed("a nineteenth century French painter")
    .content().vector();

var result = collection.search(
    SearchRequest.builder(queryVector, 5).searchListSize(100).build());

for (var hit : result.hits()) {
  System.out.println(hit.id() + " " + hit.score() + " "
      + hit.document().metadata().get("title"));
}
```

HNSW is an approximate nearest-neighbor index. Here `5` is the number of results we want, while `searchListSize(100)` controls the breadth of the graph search. The returned COSINE score is `(1 + cosine) / 2`; higher means more similar, and it is not a probability that the answer is correct.

The runnable [query.java](https://github.com/integrallis/vectors-deep-dive/blob/main/query.java) adds an important convenience: it saves the query embedding. We will reuse those exact float32 values when we move the collection to R2.

It rejects a collection whose stored recipe is not the model it is querying with, and writes the vector as raw little-endian float32 with `CREATE_NEW`, so an existing file is never overwritten.

```bash
jbang query.java "$COLLECTION" queries/painter.f32 "a nineteenth century French painter" > queries-local-painter.tsv
jbang query.java "$COLLECTION" queries/railway.f32 "a company that operates passenger trains" > queries-local-railway.tsv
jbang query.java "$COLLECTION" queries/plant.f32 "a flowering plant native to South America" > queries-local-plant.tsv
```

The recipe check and the saved vector:

```java
var recipe = collection.config().recipe().orElseThrow();
if (!recipe.modelId().equals("openai/text-embedding-3-small")
    || recipe.dimension() != 512 || recipe.queryPrefix().isPresent()) {
  throw new IllegalArgumentException("Query model does not match the stored recipe");
}

var bytes = ByteBuffer.allocate(512 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
for (float value : vector) bytes.putFloat(value);
Files.write(file, bytes.array(), StandardOpenOption.CREATE_NEW);
```

Each command prints five hits with the ID, score, category and title. Open the TSV files and judge the results by their text and titles. A category such as `Artist` is useful supporting information, but a matching category alone does not establish that a result answers the query.

After that first run, omit the text to reuse the saved embedding:

```bash
jbang query.java "$COLLECTION" queries/painter.f32
```

This invocation needs no embedding API call. The TSV also includes the raw score bits, which will let us compare local and restored results without rounding away a difference. The script checks the collection's recipe before selecting the query model. Query and R2 commands share [CollectionLocation.java](https://github.com/integrallis/vectors-deep-dive/blob/main/CollectionLocation.java), so keep the scripts together in the repository root. The same command therefore accepts a path or an `r2://` URI: a local collection opens from disk, while a published one reads the remote `CURRENT`, confirms the manifest it names is present, and builds the same `VectorCollection` with the object store behind it (condensed):

```java
if (!location.startsWith("r2://")) {
  return builder(Path.of(location).toAbsolutePath()).build();   // local directory
}

S3StorageBackend remote = backend(URI.create(location));        // endpoint + bucket + keys
long published = readLong(remote.get(prefix + "CURRENT"));
var manifest = Manifest.fromBytes(
    remote.get(prefix + FileFormat.generationDirName(published) + "/manifest.bin"));
if (manifest.generationNumber() != published) {
  throw new IllegalStateException("Remote CURRENT disagrees with the published manifest");
}

return builder(localCache).embeddingRecipe(recipe)
    .objectStore(remote, prefix)                                // same API, remote bytes
    .build();
```

## What did we put on disk?

Let's inspect the files without loading the whole collection into Java objects:

```bash
jbang inspect.java "$COLLECTION"
```

[inspect.java](https://github.com/integrallis/vectors-deep-dive/blob/main/inspect.java) reads `CURRENT`, opens that generation's manifest, prints the file sizes, and memory-maps the vectors to read the first eight components of one row. It follows the committed pointer rather than guessing that the highest numbered directory is complete.

```text
~/vectors/dbpedia-14-3small-512/
  recipe.json
  CURRENT
  gen-00000000000000NN/
    manifest.bin
    vectors.bin
    idmap.bin
    metadata.bin
    graph.bin
```

| File | What it holds |
|---|---|
| `CURRENT` | An eight-byte little-endian generation number. |
| `manifest.bin` | The generation's dimension, metric, index type, row count, file lengths, checksums and recipe hash. Format 5 uses 200 bytes. |
| `vectors.bin` | Float32 rows, with each row padded to a 64-byte boundary. |
| `idmap.bin` | The mapping between document IDs and internal ordinals. |
| `metadata.bin` | Stored document text and metadata. |
| `graph.bin` | The HNSW graph that directs the search. |
| `recipe.json` | The embedding recipe, at the collection root. |

No collection is opened to do it. The manifest supplies the row count and dimension, and
`MemorySegmentVectors` maps the file and hands back a slice of one row:

```java
long current = GenerationDirectory.readCurrent(root);
Path generation = root.resolve(FileFormat.generationDirName(current));
Manifest manifest = Manifest.readFrom(generation.resolve("manifest.bin"));

try (var arena = Arena.ofConfined()) {
  var vectors = MemorySegmentVectors.open(generation.resolve("vectors.bin"),
      Math.toIntExact(manifest.liveCount() + manifest.tombstoneCount()),
      manifest.dimension(), arena);
  var row = vectors.vectorSlice(0);
  for (int i = 0; i < 8; i++) {
    System.out.printf("%+.6f ", row.getAtIndex(ValueLayout.JAVA_FLOAT, i));
  }
}
```

At 512 dimensions, a vector occupies `512 × 4 = 2,048` bytes, already a multiple of 64. For this append-only collection, 630,000 rows therefore occupy **1,290,240,000 bytes** in `vectors.bin`, about 1.20 GiB. That size is both the arithmetic and what the existing article collection's file reports. Other dimensions can require padding, and deleted rows can remain physically present until compaction.

[verify.java](https://github.com/integrallis/vectors-deep-dive/blob/main/verify.java) checks that arithmetic against the file, reporting `Files.size(vectors.bin) / (rows × dimension × 4)` so stride padding appears as a ratio. It samples a thousand vectors and computes each L2 norm, since the recipe records these embeddings as normalized, and it asks 2,000 documents for their nearest neighbour other than themselves, reporting how often that neighbour shares the same DBpedia category. The last figure checks the embeddings and index together against the dataset's own labels; nothing was tuned for it.

Memory mapping lets the JVM address these local file bytes through `MemorySegment`. The operating system brings pages into memory as they are needed. It does not make disk access free or imply that the entire collection is resident in RAM. A cold first query and a warm repeated query can have different costs.

<video controls playsinline preload="metadata" width="960" style="max-width:100%" poster="https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/02-local.png">
  <source src="https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/02-local.mp4" type="video/mp4">
</video>

[Your collection on disk, queried across 630,000 documents](https://github.com/integrallis/vectors-deep-dive/blob/main/media/02-local.mp4).

## Open the collection in Vectors Studio

Now let's look at documents instead of file sizes. Studio is a separate application in the Vectors repository. Its initial PCA projection needs BLAS/LAPACK. The macOS system libraries are enough. On Debian or Ubuntu, install the native libraries:

```bash
# Debian / Ubuntu
sudo apt-get install -y libopenblas-dev libarpack2-dev
```

Use Java 25 for Gradle as well as JBang. Pin the Studio checkout so its UI matches this walkthrough:

```bash
git clone https://github.com/integrallis/vectors.git vectors-studio-source
git -C vectors-studio-source checkout ef8d79fbd6de1d7ff49e083d306091327e660bb0
./vectors-studio-source/gradlew -p vectors-studio-source :vectors-studio-web:run --args="--connection embedded:$HOME/vectors"
```

This revision includes the [projection fixes](https://github.com/integrallis/vectors/pull/90): stale browser events no longer replace the active result, completed or failed jobs can be replayed to late subscribers, and t-SNE honors the seed and avoids premature convergence after early exaggeration.

Open **http://localhost:8288**. The connection points at the **parent directory** containing collections. Studio discovers `dbpedia-14-3small-512` beneath it.

1. Open that collection and check its document count, dimension and metric.
2. Browse the document table. Its page-size controls offer 10, 25, 50 or 100 rows.
3. Click a document ID to inspect its text and metadata. The title and category should agree with the text we embedded.

<video controls playsinline preload="metadata" width="960" style="max-width:100%" poster="https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/04-studio-browse.png">
  <source src="https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/04-studio-browse.mp4" type="video/mp4">
</video>

[Your collection in Vectors Studio](https://github.com/integrallis/vectors-deep-dive/blob/main/media/04-studio-browse.mp4).

Stop Studio with Ctrl-C before the next command. Keep these steps sequential: finish the ingest, inspect the local collection, then restore into a separate cache. This avoids one process replacing files while another has them mapped.

## Make a t-SNE view

A two-dimensional projection lets us inspect neighborhoods in the 512-dimensional embeddings. It does not replace the original vectors: search still uses those 512 dimensions.

For the current Studio UI, the Projector submits the entire selected collection. Instead of feeding it all 630,000 rows, make a **2,800-document view: 200 from each category**. [sample.java](https://github.com/integrallis/vectors-deep-dive/blob/main/sample.java) uses a fixed random seed, fetches existing documents by ID, and copies their vectors, text and metadata into a new persistent collection. It makes no embedding requests.

```bash
jbang sample.java "$COLLECTION" "$HOME/vectors/dbpedia-14-view"
./vectors-studio-source/gradlew -p vectors-studio-source :vectors-studio-web:run --args="--connection embedded:$HOME/vectors"
```

The seeded selection and the copy:

```java
var random = new SplittableRandom(42);
while (selected < 2800) {
  Document document = source.get("dbpedia-" + random.nextInt(source.size()));
  var rows = groups.computeIfAbsent(categoryOf(document), k -> new ArrayList<>());
  if (rows.size() < 200) { rows.add(document); selected++; }
}

try (var view = CollectionLocation.builder(target)
    .embeddingRecipe(source.config().recipe().orElseThrow()).build()) {
  for (var rows : groups.values()) view.addAll(rows);
  view.commit();
}
```

<video controls playsinline preload="metadata" width="960" style="max-width:100%" poster="https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/03-sample.png">
  <source src="https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/03-sample.mp4" type="video/mp4">
</video>

[Your 2,800-document view, built from the embeddings you already paid for](https://github.com/integrallis/vectors-deep-dive/blob/main/media/03-sample.mp4).

Open **dbpedia-14-view**, then **Visualize**. Choose **2D** and **t-SNE**. Use these controls:

| Control | Value |
|---|---:|
| Perplexity | 30 |
| Learning rate | 200 |
| Max iterations | 500 |
| Random seed | 42 |
| Sphereize data | Unchecked |

Changing a projection control automatically submits a new job. Wait for the status line to report **done** before exploring the result. Hover over points to read the stored text, then investigate documents in interesting neighborhoods. Try a different seed or perplexity and see which local groupings persist.

t-SNE is useful for looking at local relationships. Distances between separate islands, their apparent sizes, and their arrangement on the screen are not direct measurements of semantic distance. A visually pleasing plot is also not a recall test for HNSW. The projection uses the embeddings; it does not measure whether the graph finds the right neighbors.

<video controls playsinline preload="metadata" width="960" style="max-width:100%" poster="https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/05-studio-tsne.png">
  <source src="https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/05-studio-tsne.mp4" type="video/mp4">
</video>

[Your collection, dimensionality-reduced with t-SNE](https://github.com/integrallis/vectors-deep-dive/blob/main/media/05-studio-tsne.mp4).

Those settings include learning rate **200**. The adapter lets the optimization finish its requested iteration budget. It reports an error if the output still collapses to a point; an empty-looking cloud is not treated as a successful visualization.

Stop Studio before continuing. The full collection is still in its original directory; the small view is just another collection alongside it.

## Give the collection a home in R2

The local files already survive a JVM restart. Now we want a durable copy that another machine can open. Cloudflare R2 supplies an S3-compatible object store. The Java process will download the committed files into a local cache and search those files using mmap.

There are three names to keep straight:

| Name | Example | Purpose |
|---|---|---|
| S3 API endpoint | `https://ACCOUNT_ID.r2.cloudflarestorage.com` | The Cloudflare account's storage API. |
| Bucket | `vectors-deep-dive` | The container for objects. |
| Collection prefix | `dbpedia-14-3small-512/` | The namespace for one collection inside the bucket. |

### Create the bucket from Java

Enable R2 in your Cloudflare account. In **R2 → Overview → Manage API Tokens**, choose **Create Account API token** with **Admin Read & Write** permission. This allows the Java script to create the bucket; you do not need to create one beforehand. If you would rather not issue a token that can create and delete buckets, skip to [Or create the bucket in the dashboard](#or-create-the-bucket-in-the-dashboard) and make it by hand instead. Copy the S3 API endpoint, **Access Key ID** and **Secret Access Key** from the confirmation page. The scripts use that key pair; the separate **Token value** field is not used. Use the displayed endpoint, including its jurisdiction suffix if needed. [Cloudflare documents the credentials and permissions here](https://developers.cloudflare.com/r2/api/tokens/).

Set them in the terminal running JBang:

```bash
export R2_ENDPOINT="https://YOUR_ACCOUNT_ID.r2.cloudflarestorage.com"
export R2_ACCESS_KEY_ID="your-setup-access-key-id"
export R2_SECRET_ACCESS_KEY="your-setup-secret-access-key"
jbang bucket.java vectors-deep-dive
```

[bucket.java](https://github.com/integrallis/vectors-deep-dive/blob/main/bucket.java) uses the Java S3 SDK to make the bucket:

```java
try (var s3 = S3Client.builder()
    .endpointOverride(URI.create(System.getenv("R2_ENDPOINT")))
    .region(Region.of("auto"))
    .forcePathStyle(true)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create(
            System.getenv("R2_ACCESS_KEY_ID"),
            System.getenv("R2_SECRET_ACCESS_KEY"))))
    .build()) {
  s3.createBucket(CreateBucketRequest.builder().bucket("vectors-deep-dive").build());
}
```

R2 uses `auto` as the S3 region and supports `CreateBucket`. [Its S3 compatibility reference](https://developers.cloudflare.com/r2/api/s3/api/) lists the supported operations.

For the remaining upload and query commands, create an **Object Read & Write** token scoped to this bucket and replace `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` with that token's S3 credentials. Retire the setup token when bucket creation is complete. These are the S3 access key and secret, not a Cloudflare bearer token. The bucket can remain private; the Java client authenticates directly.

### Or create the bucket in the dashboard

Creating a bucket is an account-level operation, and the only reason this walkthrough asks for an Admin token. Creating it yourself avoids that, and nothing later in the article changes:

1. In **R2 → Overview**, choose **Create bucket** and name it `vectors-deep-dive`.
2. In **R2 → Overview → Manage API Tokens**, create an **Object Read & Write** token scoped to that
   bucket, and use its **Access Key ID** and **Secret Access Key** for the three exported variables.
3. Skip `bucket.java` and start at `r2.java`.

[r2.java](https://github.com/integrallis/vectors-deep-dive/blob/main/r2.java) and [query.java](https://github.com/integrallis/vectors-deep-dive/blob/main/query.java) perform only object operations against the bucket named in `$REMOTE`: list a prefix, get a key, put a key, stream a file. An object-scoped token therefore covers the rest of this article. If you use a different bucket name, change the host part of `$REMOTE` to match.

### Publish the collection we already built

We have already paid for the embeddings and built the graph. Move the committed snapshot as it stands:

```bash
export REMOTE="r2://vectors-deep-dive/dbpedia-14-3small-512"
jbang r2.java publish "$COLLECTION" "$REMOTE"
```

The `r2://` URI is a convention used by these example scripts. `R2_ENDPOINT` supplies the actual HTTPS service address; the URI supplies the bucket and prefix.

[r2.java](https://github.com/integrallis/vectors-deep-dive/blob/main/r2.java) requires an empty destination prefix. It streams the files from the generation named by local `CURRENT`, copies the root `recipe.json`, and writes remote `CURRENT` **last**. It prints success only after that pointer has been written and read back. Keep the local collection closed during this upload. If an upload is interrupted, no completed snapshot is advertised; use a new prefix for a fresh attempt.

This first upload preserves the existing graph and vectors, so there is no re-embedding or index rebuild. It also makes a detail of this library version explicit: generation shipping handles files inside the generation directory; the root recipe sidecar needs to travel with our snapshot too.

### Open it as an R2-backed collection

The library configuration adds an object-store backend and a local cache path:

```java
try (var backend = S3StorageBackend.create(
    URI.create(System.getenv("R2_ENDPOINT")),
    "vectors-deep-dive", "auto",
    System.getenv("R2_ACCESS_KEY_ID"),
    System.getenv("R2_SECRET_ACCESS_KEY"));
    var collection = VectorCollection.builder()
        .dimension(512)
        .metric(SimilarityFunction.COSINE)
        .indexType(IndexType.HNSW)
        .embeddingRecipe(recipe)
        .storagePath(cache.toAbsolutePath())
        .objectStore(backend, "dbpedia-14-3small-512/")
        .build()) {
  // The same collection.search(...) API works here.
}
```

The shared location helper supplies `recipe` from the uploaded sidecar and chooses a cache under `$HOME/vectors-r2-cache/`. It validates the remote `CURRENT` pointer and manifest before opening, checks that the restored generation matches, and validates the recipe against that manifest. The local source collection stays where it was.

Opening this configuration hydrates the committed generation from R2. Queries then execute in the JVM against the local files. In this version, hydration downloads complete generation files; budget for that startup transfer and memory use. It does not issue an R2 request for each vector comparison.

For future writes, `objectStore(...)` registers generation shipping after local commits. Shipping is asynchronous: a successful local `commit()` alone does not prove the remote upload has completed. Our initial snapshot command waits for its uploads and publishes `CURRENT` explicitly. This walkthrough then uses the R2-backed collection for reads, with no concurrent writer to that prefix.

## Look in the bucket, then ask the same questions

List the objects with Java:

```bash
jbang r2.java list "$REMOTE"
```

You should see the familiar structure, now expressed as object keys:

```text
dbpedia-14-3small-512/recipe.json
dbpedia-14-3small-512/CURRENT
dbpedia-14-3small-512/gen-00000000000000NN/manifest.bin
dbpedia-14-3small-512/gen-00000000000000NN/vectors.bin
dbpedia-14-3small-512/gen-00000000000000NN/idmap.bin
dbpedia-14-3small-512/gen-00000000000000NN/metadata.bin
dbpedia-14-3small-512/gen-00000000000000NN/graph.bin
```

The Cloudflare dashboard's bucket browser shows these same objects. The slashes are key prefixes rather than filesystem directories. `CURRENT` still identifies the published generation.

Now change the collection location and reuse each saved query vector:

```bash
jbang query.java "$REMOTE" queries/painter.f32 > queries-r2-painter.tsv
jbang query.java "$REMOTE" queries/railway.f32 > queries-r2-railway.tsv
jbang query.java "$REMOTE" queries/plant.f32 > queries-r2-plant.tsv
```

These calls make no embedding requests. Each reports the document count, generation and recipe hash to the terminal, and writes the result table to its TSV file. On the same Java runtime and machine, compare the results directly:

```bash
diff -u queries-local-painter.tsv queries-r2-painter.tsv
diff -u queries-local-railway.tsv queries-r2-railway.tsv
diff -u queries-local-plant.tsv queries-r2-plant.tsv
```

Empty diffs establish that these queries returned the same IDs, ordering and score bits from the copied snapshot. They are a concrete check of this move, rather than a claim about every possible query. Startup time includes R2 transfer and local file preparation, so keep it separate from search timing.

Once the query process has closed, point Studio at the cache's parent directory:

```bash
./vectors-studio-source/gradlew -p vectors-studio-source :vectors-studio-web:run --args="--connection embedded:$HOME/vectors-r2-cache"
```

The query script prints the cache directory's name. Select that collection in Studio and inspect the same IDs, text and metadata. Studio is opening the restored local snapshot here. The durable copy lives in R2; the arithmetic still happens beside your Java application.

The clip below publishes the **2,800-document view** rather than the full collection: it creates a temporary bucket, lists the snapshot, restores it through the R2 URI, compares the saved query's results byte for byte, and deletes that bucket. The commands above use the full collection; the storage and query path is the same.

<video controls playsinline preload="metadata" width="960" style="max-width:100%" poster="https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/06-r2.png">
  <source src="https://raw.githubusercontent.com/integrallis/vectors-deep-dive/main/media/06-r2.mp4" type="video/mp4">
</video>

[Your collection published to R2 and queried back](https://github.com/integrallis/vectors-deep-dive/blob/main/media/06-r2.mp4).

The companion [live R2 test suite](https://github.com/integrallis/vectors-deep-dive/blob/main/tests/README.md) runs these Java entry points against a small synthetic collection. It checks snapshot bytes, repeated restores, query equivalence, invalid snapshots, and multipart uploads, then removes its test data. Its runner executes the suite twice to check repeatability:

```bash
jbang tests/liveR2.java /path/to/your/.env
```

The complete suite needs R2 Admin Read & Write credentials for its bucket creation and deletion test. The test README describes the accepted variables and generated JUnit reports.

On October 4, 2026, the complete live R2 suite passed **13 tests twice: 26 passed, no failures or skips**, and a DBpedia-view round trip returned identical local and restored query output. These checks establish snapshot and query equivalence for the tested data; they do not measure retrieval recall or cloud query throughput.

That is the path from a corpus to something useful: embed the text once, persist both the vectors and the material they describe, search it with ordinary Java, inspect it visually, and carry the same committed collection to another machine through object storage.

