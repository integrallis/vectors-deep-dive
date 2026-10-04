///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.integrallis:vectors:0.1.27
//DEPS dev.hardwood:hardwood-core:1.0.0.Final
//DEPS org.xerial.snappy:snappy-java:1.1.10.8
//DEPS dev.langchain4j:langchain4j-open-ai:1.21.0
//RUNTIME_OPTIONS --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx20g

import com.integrallis.vectors.core.Document;
import com.integrallis.vectors.core.EmbeddingRecipe;
import com.integrallis.vectors.core.MetadataValue;
import com.integrallis.vectors.core.SimilarityFunction;
import com.integrallis.vectors.db.IndexType;
import com.integrallis.vectors.db.VectorCollection;
import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModelName;
import dev.langchain4j.model.output.Response;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Embeds all 630,000 DBpedia-14 documents into a persistent Vectors collection. */
public class ingest {

  static final int DIMENSION = 512;
  static final int BATCH = 100;              // documents per embedding request
  /**
   * Documents per generation on disk.
   *
   * <p>Coarse on purpose. A commit publishes a generation holding every live document, so its cost
   * grows with the collection, not with the batch. Committing every 10,000 over 630,000 documents
   * means 63 commits against an ever-larger collection, and the measured ingest fell from 1,020 to
   * 168 documents per second as it went. The only thing a finer cadence buys is a shorter replay
   * after a crash.
   */
  static final int COMMIT_EVERY = 100_000;

  /**
   * Concurrent embedding requests. Measured against the API: 1 thread gives 81 docs/s, 8 gives 615,
   * 32 gives 873, and 64 is slower at 784 — the provider, not the client, is the limit.
   */
  static final int EMBED_THREADS = 32;

  /**
   * Batches allowed in flight, so 630,000 embeddings are never all resident at once.
   *
   * <p>Deep enough to cover a commit. Embedding and committing run on different threads, but the
   * queue between them is what stops the embedder idling while a commit writes: at 873 documents per
   * second, 512 batches of 100 is about a minute of work buffered. 100 vectors at 512 dimensions is
   * 200 KB, so even full the queue holds about 100 MB.
   */
  static final int IN_FLIGHT = 512;

  static final Path STORAGE = Path.of(System.getProperty("user.home"))
      .resolve("vectors/dbpedia-14-3small-512");

  static final Path[] FILES = {
    Path.of("corpus/dbpedia_14-train.parquet"),
    Path.of("corpus/dbpedia_14-test.parquet")
  };

  static final String[] CATEGORIES = {
    "Company", "EducationalInstitution", "Artist", "Athlete", "OfficeHolder",
    "MeanOfTransportation", "Building", "NaturalPlace", "Village", "Animal",
    "Plant", "Album", "Film", "WrittenWork"
  };

  public static void main(String[] args) throws Exception {

    var model = OpenAiEmbeddingModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName(OpenAiEmbeddingModelName.TEXT_EMBEDDING_3_SMALL)
        .dimensions(DIMENSION)
        .maxSegmentsPerBatch(BATCH)
        .build();

    // Declared before anything is embedded. Every field here changes the vectors.
    var recipe = new EmbeddingRecipe(
        "openai/text-embedding-3-small", "2024-01-25",
        Optional.empty(),                      // no weights digest: DECLARED, not ATTESTED
        DIMENSION, SimilarityFunction.COSINE,
        true,                                  // normalized -- checked at the end of the run
        EmbeddingRecipe.Pooling.NONE,
        Optional.empty(), Optional.empty(),    // no document or query prefix
        8191, EmbeddingRecipe.Truncation.HEAD,
        Map.of("join", "title + \". \" + content",
               "source", "fancyzhx/dbpedia_14 train+test, file order",
               "rows", "630000"));

    try (VectorCollection collection = VectorCollection.builder()
        .dimension(DIMENSION)
        .metric(SimilarityFunction.COSINE)
        .indexType(IndexType.HNSW)
        .storagePath(STORAGE.toAbsolutePath())
        .embeddingRecipe(recipe)
        .autoCommitThreshold(COMMIT_EVERY)     // addAll commits for us at this staging size
        .backgroundCompaction(Duration.ofMinutes(1))   // reclaim retired generations as we go
        .build()) {

      long alreadyDone = collection.size();    // resume: skip what a previous run committed
      System.out.printf("collection at %s holds %,d documents%n", STORAGE, alreadyDone);

      long row = 0;
      long embedded = 0;
      long tokens = 0;
      long start = System.nanoTime();
      List<Row> batch = new ArrayList<>(BATCH);

      // Embedding is I/O and committing is CPU and disk, so they run on different threads with a
      // queue between them. Writes stay in file order on the single writer thread, which is what keeps
      // ids positional and makes the resume above correct. Without this the embedder stopped dead
      // during every commit: at 600,000 documents a commit outlasts anything a shallow queue holds.
      BlockingQueue<Embedded> toWrite = new ArrayBlockingQueue<>(IN_FLIGHT);
      AtomicReference<Throwable> writerFailure = new AtomicReference<>();
      AtomicLong writtenTokens = new AtomicLong();
      AtomicLong writtenDocs = new AtomicLong();
      Embedded sentinel = new Embedded(List.of(), List.of(), List.of(), 0);

      Thread writer = Thread.ofPlatform().name("ingest-writer").start(() -> {
        try {
          while (true) {
            Embedded done = toWrite.take();
            if (done == sentinel) {
              return;
            }
            writtenTokens.addAndGet(write(collection, done));
            long n = writtenDocs.addAndGet(done.rows().size());
            if (n % 50_000 == 0) {
              report(n, writtenTokens.get(), start);
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (Throwable t) {
          writerFailure.set(t);
        }
      });

      Deque<Future<Embedded>> inFlight = new ArrayDeque<>();
      try (ExecutorService pool = Executors.newFixedThreadPool(EMBED_THREADS)) {

        for (Path file : FILES) {
          try (ParquetFileReader parquet = ParquetFileReader.open(InputFile.of(file));
              RowReader reader = parquet.rowReader()) {
            while (reader.hasNext()) {
              reader.next();
              long index = row++;
              if (index < alreadyDone) {
                continue;                      // file order is deterministic, so this is safe
              }
              batch.add(new Row(
                  index,
                  (int) reader.getLong("label"),
                  reader.getString("title"),
                  reader.getString("content").strip()));

              if (batch.size() == BATCH) {
                inFlight.addLast(pool.submit(embedTask(model, List.copyOf(batch))));
                batch.clear();
                drain(inFlight, toWrite, EMBED_THREADS * 2, writerFailure);
              }
            }
          }
        }
        if (!batch.isEmpty()) {
          inFlight.addLast(pool.submit(embedTask(model, List.copyOf(batch))));
        }
        drain(inFlight, toWrite, 0, writerFailure);
      }
      toWrite.put(sentinel);
      writer.join();
      if (writerFailure.get() != null) {
        throw new IllegalStateException("the writer thread failed", writerFailure.get());
      }
      collection.commit();                     // the tail below the auto-commit threshold
      tokens = writtenTokens.get();
      embedded = writtenDocs.get();
      report(embedded, tokens, start);

      System.out.printf("%n%,d documents, generation %d, %,d input tokens%n",
          collection.size(), collection.generationNumber(), tokens);
    }
  }

  /**
   * Hands finished batches to the writer, in order, until at most {@code keep} remain outstanding.
   *
   * <p>Ordering matters: ids are positional, so a batch must reach the collection in the order it was
   * read. Taking futures from the head of the deque preserves that even though they complete out of
   * order.
   */
  private static void drain(
      Deque<Future<Embedded>> inFlight,
      BlockingQueue<Embedded> toWrite,
      int keep,
      AtomicReference<Throwable> writerFailure)
      throws Exception {
    while (inFlight.size() > keep) {
      if (writerFailure.get() != null) {
        throw new IllegalStateException("the writer thread failed", writerFailure.get());
      }
      toWrite.put(inFlight.removeFirst().get());
    }
  }

  private static void report(long embedded, long tokens, long start) {
    long seconds = Math.max(1, (System.nanoTime() - start) / 1_000_000_000);
    System.out.printf("%,d embedded in %,ds (%,.0f docs/s, %,d tokens)%n",
        embedded, seconds, embedded / (double) seconds, tokens);
  }

  /** A batch and its vectors, carried back from the embedding pool. */
  record Embedded(List<Row> rows, List<TextSegment> inputs, List<Embedding> vectors, long tokens) {}

  private static Callable<Embedded> embedTask(OpenAiEmbeddingModel model, List<Row> rows) {
    return () -> {
      List<TextSegment> inputs = rows.stream()
          .map(r -> TextSegment.from(r.title() + ". " + r.content()))
          .toList();
      Response<List<Embedding>> response = model.embedAll(inputs);
      long used = response.tokenUsage() == null ? 0 : response.tokenUsage().inputTokenCount();
      return new Embedded(rows, inputs, response.content(), used);
    };
  }

  /** Writes one embedded batch. Called only from the main thread, in file order. */
  static long write(VectorCollection collection, Embedded done) {
    List<Document> documents = new ArrayList<>(done.rows().size());
    for (int i = 0; i < done.rows().size(); i++) {
      Row r = done.rows().get(i);
      documents.add(new Document(
          "dbpedia-" + r.index(),
          done.vectors().get(i).vector(),
          done.inputs().get(i).text(),         // store exactly what was embedded
          Map.of("category", MetadataValue.of(CATEGORIES[r.label()]),
                 "categoryId", MetadataValue.of(r.label()),
                 "title", MetadataValue.of(r.title()))));
    }
    collection.addAll(documents);
    return done.tokens();
  }

  record Row(long index, int label, String title, String content) {}
}
