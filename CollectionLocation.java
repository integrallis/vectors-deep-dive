import com.integrallis.vectors.core.RecipeCodecs;
import com.integrallis.vectors.core.SimilarityFunction;
import com.integrallis.vectors.db.IndexType;
import com.integrallis.vectors.db.VectorCollection;
import com.integrallis.vectors.db.VectorCollectionBuilder;
import com.integrallis.vectors.db.storage.FileFormat;
import com.integrallis.vectors.db.storage.GenerationDirectory;
import com.integrallis.vectors.db.storage.Manifest;
import com.integrallis.vectors.db.storage.RecipeStore;
import com.integrallis.vectors.storage.backend.S3StorageBackend;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Location handling shared by the article scripts. r2:// is this example's URI convention. */
final class CollectionLocation {
  static final int DIMENSION = 512;

  static String env(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name);
    return value;
  }

  static VectorCollectionBuilder builder(Path root) {
    return VectorCollection.builder().dimension(DIMENSION).metric(SimilarityFunction.COSINE)
        .indexType(IndexType.HNSW).storagePath(root.toAbsolutePath());
  }

  static Path currentGeneration(Path root) throws Exception {
    long generation = GenerationDirectory.readCurrent(root);
    if (generation < 0) throw new IllegalArgumentException("No committed collection at " + root);
    return root.resolve(FileFormat.generationDirName(generation));
  }

  static String prefix(URI uri) {
    if (!"r2".equals(uri.getScheme()) || uri.getHost() == null || uri.getQuery() != null
        || uri.getFragment() != null || uri.getUserInfo() != null) {
      throw new IllegalArgumentException("Expected r2://bucket/collection-prefix");
    }
    String path = uri.getPath().replaceFirst("^/", "");
    if (path.isBlank()) throw new IllegalArgumentException("Use a collection prefix inside the bucket");
    return path.endsWith("/") ? path : path + "/";
  }

  static S3StorageBackend backend(URI uri) {
    prefix(uri);
    return S3StorageBackend.create(URI.create(env("R2_ENDPOINT")), uri.getHost(), "auto",
        env("R2_ACCESS_KEY_ID"), env("R2_SECRET_ACCESS_KEY"));
  }

  static Opened open(String location) throws Exception {
    if (!location.startsWith("r2://")) {
      Path root = location.startsWith("file:") ? Path.of(URI.create(location)) : Path.of(location);
      root = root.toAbsolutePath();
      currentGeneration(root); // A typo must not silently create an empty collection.
      return new Opened(builder(root).build(), null, root);
    }
    URI uri = URI.create(location);
    String prefix = prefix(uri);
    S3StorageBackend remote = backend(uri);
    try {
      byte[] current = remote.get(prefix + "CURRENT");
      if (current == null) {
        throw new IllegalArgumentException("No published collection at " + location);
      }
      if (current.length != Long.BYTES) {
        throw new IllegalStateException("Remote CURRENT must contain exactly eight bytes");
      }
      long expectedGeneration = ByteBuffer.wrap(current).order(ByteOrder.LITTLE_ENDIAN).getLong();
      if (expectedGeneration < 0) throw new IllegalStateException("Remote CURRENT has a negative generation");
      byte[] manifestBytes = remote.get(prefix + FileFormat.generationDirName(expectedGeneration) + "/manifest.bin");
      if (manifestBytes == null) throw new IllegalStateException("Published generation is missing manifest.bin");
      var expectedManifest = Manifest.fromBytes(manifestBytes);
      if (expectedManifest.generationNumber() != expectedGeneration) {
        throw new IllegalStateException("Remote CURRENT disagrees with the published manifest");
      }
      byte[] body = remote.get(prefix + "recipe.json");
      if (body == null) throw new IllegalStateException("The snapshot is missing recipe.json");
      var recipe = RecipeCodecs.discover().decode(new String(body, StandardCharsets.UTF_8));
      String identity = env("R2_ENDPOINT") + "\n" + uri.getHost() + "/" + prefix;
      String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
      Path root = Path.of(System.getProperty("user.home"), "vectors-r2-cache", "dbpedia-" + key);
      Files.createDirectories(root);
      // The current shipping/pull API handles generation files; carry the root recipe separately.
      Path sidecar = root.resolve("recipe.json");
      if (Files.exists(sidecar) && !java.util.Arrays.equals(Files.readAllBytes(sidecar), body)) {
        throw new IllegalStateException("Cached recipe differs; use a fresh cache for this snapshot");
      }
      Files.write(sidecar, body);
      VectorCollection collection = builder(root).embeddingRecipe(recipe)
          .objectStore(remote, prefix).build();
      try {
        Path manifestPath = currentGeneration(root).resolve("manifest.bin");
        if (collection.generationNumber() != expectedGeneration
            || !java.util.Arrays.equals(manifestBytes, Files.readAllBytes(manifestPath))) {
          throw new IllegalStateException("Restored generation does not match the published snapshot");
        }
        var manifest = Manifest.readFrom(manifestPath);
        RecipeStore.read(root, manifest.recipeHash()); // Check the downloaded manifest's anchor.
        System.err.println("R2 cache: " + root);
        return new Opened(collection, remote, root);
      } catch (Exception failure) {
        collection.close();
        throw failure;
      }
    } catch (Exception failure) {
      remote.close();
      throw failure;
    }
  }

  record Opened(VectorCollection collection, S3StorageBackend remote, Path root)
      implements AutoCloseable {
    @Override public void close() throws Exception {
      try { collection.close(); } finally { if (remote != null) remote.close(); }
    }
  }
}
