import static org.junit.jupiter.api.Assertions.*;

import com.integrallis.vectors.core.Document;
import com.integrallis.vectors.core.EmbeddingRecipe;
import com.integrallis.vectors.core.MetadataValue;
import com.integrallis.vectors.core.SimilarityFunction;
import com.integrallis.vectors.storage.backend.S3StorageBackend;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Live R2 integration tests: real article entry points, disposable data, no model calls. */
public class R2LiveTests {
  @TempDir static Path work;
  static final String BUCKET = System.getenv("R2_TEST_BUCKET") == null
      ? "vectors-article-it-" + UUID.randomUUID().toString().replace("-", "")
      : System.getenv("R2_TEST_BUCKET");
  static final String PREFIX = "vectors-article-tests/" + UUID.randomUUID() + "/";
  static final List<Path> caches = new ArrayList<>();
  static final List<Document> documents = new ArrayList<>();
  static S3Client s3;
  static boolean ownsBucket;
  static boolean ownsPrefix;
  static String createdBucket;
  static Path source;

  @BeforeAll static void setUp() throws Exception {
    s3 = S3Client.builder().endpointOverride(URI.create(CollectionLocation.env("R2_ENDPOINT")))
        .region(Region.of("auto")).forcePathStyle(true)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
            CollectionLocation.env("R2_ACCESS_KEY_ID"), CollectionLocation.env("R2_SECRET_ACCESS_KEY"))))
        .build();
    if (System.getenv("R2_TEST_BUCKET") == null) {
      S3Exception missing = assertThrows(S3Exception.class, () -> s3.headBucket(b -> b.bucket(BUCKET)));
      assertEquals(404, missing.statusCode(), "Need R2 Admin Read & Write credentials for a new test bucket");
      ownsBucket = true; // The name was absent; cleanup also covers a lost create response.
      bucket.main(new String[]{BUCKET});
    }
    s3.headBucket(b -> b.bucket(BUCKET));
    try (var remote = CollectionLocation.backend(URI.create("r2://" + BUCKET + "/" + PREFIX))) {
      assertTrue(remote.list(PREFIX).isEmpty(), "Test prefix must be new");
    }
    ownsPrefix = true;
    System.out.println("Test objects: r2://" + BUCKET + "/" + PREFIX);
    source = work.resolve("source");
    // Deliberately synthetic, seeded vectors. The recipe's extra field identifies this fixture.
    var recipe = new EmbeddingRecipe("openai/text-embedding-3-small", "2024-01-25",
        Optional.empty(), 512, SimilarityFunction.COSINE, true, EmbeddingRecipe.Pooling.NONE,
        Optional.empty(), Optional.empty(), 8191, EmbeddingRecipe.Truncation.HEAD,
        Map.of("fixture", "synthetic-seed-42-NOT-model-output"));
    var random = new SplittableRandom(42);
    try (var collection = CollectionLocation.builder(source).embeddingRecipe(recipe).build()) {
      for (int n = 0; n < 64; n++) {
        float[] vector = new float[512];
        double norm = 0;
        for (int i = 0; i < vector.length; i++) { vector[i] = (float) random.nextDouble(-1, 1); norm += vector[i] * vector[i]; }
        norm = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) vector[i] /= (float) norm;
        documents.add(new Document("fixture-" + n, vector, "Synthetic document " + n,
            Map.of("title", new MetadataValue.Str("Title " + n),
                   "category", new MetadataValue.Str("Category " + n % 4))));
      }
      collection.addAll(documents);
      collection.commit();
    }
  }

  @Test void repeatedBucketCreationPreservesExistingObjects() throws Exception {
    String candidate = "vectors-article-it-" + UUID.randomUUID().toString().replace("-", "");
    S3Exception missing = assertThrows(S3Exception.class, () -> s3.headBucket(b -> b.bucket(candidate)));
    assertEquals(404, missing.statusCode(), "Bucket creation test requires Admin Read & Write credentials");
    createdBucket = candidate;
    bucket.main(new String[]{candidate});
    URI uri = URI.create("r2://" + candidate + "/bucket-repeat");
    try (var remote = CollectionLocation.backend(uri)) {
      String key = CollectionLocation.prefix(uri) + "sentinel";
      byte[] value = "keep me".getBytes(StandardCharsets.UTF_8);
      remote.put(key, value);
      bucket.main(new String[]{candidate});
      bucket.main(new String[]{candidate});
      assertArrayEquals(value, remote.get(key));
    }
  }

  @Test void publishedFilesAndListingMatchLocalSnapshotExactly() throws Exception {
    URI uri = publish("bytes");
    try (var remote = CollectionLocation.backend(uri)) {
      var expected = snapshot();
      assertEquals(expected.keySet(), remoteHashes(remote, CollectionLocation.prefix(uri)).keySet());
      for (var entry : expected.entrySet()) assertArrayEquals(Files.readAllBytes(entry.getValue()),
          remote.get(CollectionLocation.prefix(uri) + entry.getKey()), entry.getKey());
      String listing = stdout(() -> r2.main(new String[]{"list", uri.toString()}));
      for (String key : expected.keySet()) assertTrue(listing.contains(CollectionLocation.prefix(uri) + key));
      assertTrue(listing.contains("Published generation: "));
    }
  }

  @Test void secondPublishRefusesOverwriteAndLeavesSnapshotUnchanged() throws Exception {
    URI uri = publish("overwrite");
    try (var remote = CollectionLocation.backend(uri)) {
      var before = remoteHashes(remote, CollectionLocation.prefix(uri));
      var error = assertThrows(IllegalArgumentException.class,
          () -> r2.main(new String[]{"publish", source.toString(), uri.toString()}));
      assertTrue(error.getMessage().contains("refusing to overwrite"));
      assertEquals(before, remoteHashes(remote, CollectionLocation.prefix(uri)));
    }
  }

  @Test void freshAndRepeatedRestoresPreserveQueriesDocumentsAndRecipe() throws Exception {
    URI uri = publish("queries");
    assertNull(System.getenv("OPENAI_API_KEY"), "The runner must remove the model API key");
    for (int n : new int[]{0, 17, 63}) {
      Path queryFile = work.resolve("query-" + n + ".f32");
      ByteBuffer bytes = ByteBuffer.allocate(512 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
      for (float value : documents.get(n).vector()) bytes.putFloat(value);
      Files.write(queryFile, bytes.array());
      String expected = stdout(() -> query.main(new String[]{source.toString(), queryFile.toString()}));
      assertEquals(6, expected.lines().count(), "Header plus five hits");
      assertTrue(expected.lines().skip(1).findFirst().orElseThrow().startsWith("fixture-" + n + "\t"));
      for (int repeat = 0; repeat < 2; repeat++) {
        assertEquals(expected, stdout(() -> query.main(new String[]{uri.toString(), queryFile.toString()})),
            "IDs, ordering, metadata and raw score bits must match");
      }
    }
    try (var local = CollectionLocation.open(source.toString()); var restored = CollectionLocation.open(uri.toString())) {
      assertEquals(local.collection().config().recipe(), restored.collection().config().recipe());
      assertEquals(local.collection().generationNumber(), restored.collection().generationNumber());
      assertEquals(64, restored.collection().size());
      for (Document doc : documents) {
        var actual = restored.collection().get(doc.id());
        assertNotNull(actual);
        assertEquals(doc.text(), actual.text());
        assertEquals(doc.metadata(), actual.metadata());
        assertArrayEquals(local.collection().get(doc.id()).vector(), actual.vector());
      }
    }
  }

  @Test void missingCurrentIsNotTreatedAsAnEmptyCollection() throws Exception {
    URI uri = location("no-current");
    assertThrows(IllegalArgumentException.class, () -> { try (var ignored = CollectionLocation.open(uri.toString())) {} });
  }

  @Test void interruptedUploadIsNotPublishedOrOverwritten() throws Exception {
    URI uri = location("partial");
    try (var remote = CollectionLocation.backend(uri)) {
      String prefix = CollectionLocation.prefix(uri);
      remote.putFile(prefix + "recipe.json", source.resolve("recipe.json"));
      assertThrows(IllegalArgumentException.class, () -> r2.main(new String[]{"publish", source.toString(), uri.toString()}));
      assertNull(remote.get(prefix + "CURRENT"));
      assertThrows(IllegalArgumentException.class, () -> { try (var ignored = CollectionLocation.open(uri.toString())) {} });
    }
  }

  @Test void missingRecipeIsRejected() throws Exception {
    URI uri = publish("no-recipe");
    try (var remote = CollectionLocation.backend(uri)) { remote.delete(CollectionLocation.prefix(uri) + "recipe.json"); }
    var failure = assertThrows(IllegalStateException.class, () -> { try (var ignored = CollectionLocation.open(uri.toString())) {} });
    assertTrue(failure.getMessage().contains("recipe.json"));
  }

  @Test void recipeTamperingIsRejectedAgainstManifest() throws Exception {
    URI uri = publish("bad-recipe");
    String original = Files.readString(source.resolve("recipe.json"));
    String changed = original.replace("synthetic-seed-42", "synthetic-seed-43");
    assertNotEquals(original, changed);
    try (var remote = CollectionLocation.backend(uri)) {
      remote.put(CollectionLocation.prefix(uri) + "recipe.json", changed.getBytes(StandardCharsets.UTF_8));
    }
    assertThrows(Exception.class, () -> { try (var ignored = CollectionLocation.open(uri.toString())) {} });
  }

  @Test void malformedCurrentIsRejected() throws Exception {
    URI uri = publish("bad-current");
    try (var remote = CollectionLocation.backend(uri)) { remote.put(CollectionLocation.prefix(uri) + "CURRENT", new byte[]{1, 2, 3}); }
    assertThrows(IllegalStateException.class, () -> { try (var ignored = CollectionLocation.open(uri.toString())) {} });
  }

  @Test void publishedPointerWithoutGenerationIsRejected() throws Exception {
    URI uri = location("no-generation");
    try (var remote = CollectionLocation.backend(uri)) {
      String prefix = CollectionLocation.prefix(uri);
      remote.putFile(prefix + "recipe.json", source.resolve("recipe.json"));
      remote.putFile(prefix + "CURRENT", source.resolve("CURRENT"));
    }
    assertThrows(IllegalStateException.class, () -> { try (var ignored = CollectionLocation.open(uri.toString())) {} });
  }

  @Test void missingVectorPayloadIsRejected() throws Exception {
    URI uri = publish("no-vectors");
    try (var remote = CollectionLocation.backend(uri)) {
      remote.delete(CollectionLocation.prefix(uri) + CollectionLocation.currentGeneration(source).getFileName() + "/vectors.bin");
    }
    assertThrows(Exception.class, () -> { try (var ignored = CollectionLocation.open(uri.toString())) {} });
  }

  @Test void nonexistentLocalPathDoesNotCreateACollection() {
    Path absent = work.resolve("does-not-exist");
    assertThrows(IllegalArgumentException.class, () -> { try (var ignored = CollectionLocation.open(absent.toString())) {} });
    assertFalse(Files.exists(absent));
  }

  @Test void multipartUploadRoundTripsAndLeavesNoPendingParts() throws Exception {
    // The article's vectors.bin exceeds the library's 256 MiB multipart threshold.
    URI uri = location("multipart");
    Path file = work.resolve("multipart.bin");
    byte[] block = new byte[1024 * 1024];
    new java.util.Random(42).nextBytes(block);
    try (var out = Files.newOutputStream(file)) {
      for (int i = 0; i < 256; i++) out.write(block);
      out.write(42);
    }
    String expected;
    try (var in = Files.newInputStream(file)) { expected = digest(in); }
    try (var remote = CollectionLocation.backend(uri)) {
      String key = CollectionLocation.prefix(uri) + "payload.bin";
      System.out.println("Multipart test: uploading 256 MiB + 1 byte");
      remote.putFile(key, file);
      var head = s3.headObject(b -> b.bucket(BUCKET).key(key));
      assertEquals(Files.size(file), head.contentLength());
      assertTrue(head.eTag().contains("-2"), "Expected two-part multipart upload");
      try (var in = remote.open(key)) { assertEquals(expected, digest(in)); }
      assertTrue(s3.listMultipartUploads(b -> b.bucket(BUCKET).prefix(CollectionLocation.prefix(uri))).uploads().isEmpty());
    }
    Files.delete(file);
  }

  static URI publish(String name) throws Exception {
    URI uri = location(name);
    r2.main(new String[]{"publish", source.toString(), uri.toString()});
    return uri;
  }

  static URI location(String name) throws Exception {
    URI uri = URI.create("r2://" + BUCKET + "/" + PREFIX + name);
    String identity = CollectionLocation.env("R2_ENDPOINT") + "\n" + BUCKET + "/" + CollectionLocation.prefix(uri);
    String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    Path cache = Path.of(System.getProperty("user.home"), "vectors-r2-cache", "dbpedia-" + key);
    assertFalse(Files.exists(cache), "A test must never reuse or remove someone else's cache");
    caches.add(cache);
    return uri;
  }

  static Map<String, Path> snapshot() throws Exception {
    var files = new TreeMap<String, Path>();
    files.put("CURRENT", source.resolve("CURRENT"));
    files.put("recipe.json", source.resolve("recipe.json"));
    Path generation = CollectionLocation.currentGeneration(source);
    try (var entries = Files.list(generation)) {
      for (Path p : entries.filter(Files::isRegularFile).toList()) files.put(generation.getFileName() + "/" + p.getFileName(), p);
    }
    return files;
  }

  static Map<String, String> remoteHashes(S3StorageBackend remote, String prefix) throws Exception {
    var hashes = new TreeMap<String, String>();
    for (String key : remote.list(prefix)) try (var in = remote.open(key)) { hashes.put(key.substring(prefix.length()), digest(in)); }
    return hashes;
  }

  static String digest(InputStream in) throws Exception {
    assertNotNull(in);
    var digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[64 * 1024];
    for (int n; (n = in.read(buffer)) >= 0;) digest.update(buffer, 0, n);
    return HexFormat.of().formatHex(digest.digest());
  }

  @FunctionalInterface interface Action { void run() throws Exception; }
  static String stdout(Action action) throws Exception {
    PrintStream original = System.out;
    var bytes = new ByteArrayOutputStream();
    try (var capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      action.run();
    } finally { System.setOut(original); }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  @AfterAll static void cleanUp() throws Exception {
    List<Throwable> failures = new ArrayList<>();
    if (s3 != null && ownsPrefix) {
      try {
        // Existing buckets are cleaned only under this run's unique test prefix.
        for (var upload : s3.listMultipartUploads(b -> b.bucket(BUCKET).prefix(PREFIX)).uploads()) {
          assertTrue(upload.key().startsWith(PREFIX));
          s3.abortMultipartUpload(b -> b.bucket(BUCKET).key(upload.key()).uploadId(upload.uploadId()));
        }
        assertTrue(s3.listMultipartUploads(b -> b.bucket(BUCKET).prefix(PREFIX)).uploads().isEmpty());
      } catch (Throwable error) { failures.add(error); }
      try {
        try (var backend = CollectionLocation.backend(URI.create("r2://" + BUCKET + "/cleanup"))) {
          for (String key : backend.list(PREFIX)) {
            assertTrue(key.startsWith(PREFIX));
            try { backend.delete(key); } catch (Throwable error) { failures.add(error); }
          }
          assertTrue(backend.list(PREFIX).isEmpty(), "Remote test objects must be removed");
        }
        System.out.println("Cleanup verified: all objects removed under " + PREFIX);
      } catch (Throwable error) { failures.add(error); }
    }
    if (s3 != null && ownsBucket) {
      try { deleteBucketAndVerify(BUCKET); } catch (Throwable error) { failures.add(error); }
    }
    if (s3 != null && createdBucket != null) {
      try {
        try (var backend = CollectionLocation.backend(URI.create("r2://" + createdBucket + "/cleanup"))) {
          for (String key : backend.list("")) backend.delete(key);
        }
        deleteBucketAndVerify(createdBucket);
      } catch (Throwable error) { failures.add(error); }
    }
    if (s3 != null) try { s3.close(); } catch (Throwable error) { failures.add(error); }
    for (Path cache : caches) {
      try {
        if (Files.exists(cache)) try (var paths = Files.walk(cache)) {
          for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
        assertFalse(Files.exists(cache));
      } catch (Throwable error) { failures.add(error); }
    }
    if (failures.isEmpty()) System.out.println("Cleanup verified: all test restore caches removed");
    assertAll("Remote and local cleanup", failures.stream().map(error -> (org.junit.jupiter.api.function.Executable) () -> { throw error; }));
  }

  static void deleteBucketAndVerify(String name) {
    s3.deleteBucket(b -> b.bucket(name));
    S3Exception missing = assertThrows(S3Exception.class, () -> s3.headBucket(b -> b.bucket(name)));
    assertEquals(404, missing.statusCode(), "Bucket cleanup must be verified");
    System.out.println("Cleanup verified: test bucket removed: " + name);
  }
}
