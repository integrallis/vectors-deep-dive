///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.integrallis:vectors:0.1.27
//DEPS com.integrallis:vectors-storage-s3:0.1.27
//SOURCES CollectionLocation.java
//RUNTIME_OPTIONS --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED

import com.integrallis.vectors.db.storage.Manifest;
import com.integrallis.vectors.db.storage.RecipeStore;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Publish a closed local snapshot to a new R2 prefix, or list that prefix. No embedding calls. */
public class r2 {
  public static void main(String[] args) throws Exception {
    if (args.length == 2 && args[0].equals("list")) {
      URI uri = URI.create(args[1]);
      try (var backend = CollectionLocation.backend(uri)) {
        String prefix = CollectionLocation.prefix(uri);
        for (String key : backend.list(prefix)) System.out.println(key);
        byte[] current = backend.get(prefix + "CURRENT");
        if (current != null && current.length == Long.BYTES) {
          System.out.println("Published generation: " + ByteBuffer.wrap(current)
              .order(ByteOrder.LITTLE_ENDIAN).getLong());
        }
      }
      return;
    }
    if (args.length != 3 || !args[0].equals("publish")) throw new IllegalArgumentException(
        "Usage: r2.java publish LOCAL_COLLECTION r2://bucket/prefix | r2.java list r2://bucket/prefix");
    Path root = Path.of(args[1]).toAbsolutePath();
    Path generation = CollectionLocation.currentGeneration(root);
    byte[] current = Files.readAllBytes(root.resolve("CURRENT"));
    var manifest = Manifest.readFrom(generation.resolve("manifest.bin"));
    RecipeStore.read(root, manifest.recipeHash()).orElseThrow(
        () -> new IllegalArgumentException("This article snapshot requires an embedding recipe"));
    URI uri = URI.create(args[2]);
    String prefix = CollectionLocation.prefix(uri);
    try (var backend = CollectionLocation.backend(uri)) {
      if (!backend.list(prefix).isEmpty()) throw new IllegalArgumentException(
          "Use a new, empty collection prefix; refusing to overwrite " + uri);
      // CURRENT is published last. Failed transfers do not advertise an incomplete generation.
      backend.putFile(prefix + "recipe.json", root.resolve("recipe.json"));
      try (var files = Files.list(generation)) {
        for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
          String key = prefix + generation.getFileName() + "/" + file.getFileName();
          System.out.printf("Uploading %,d bytes: %s%n", Files.size(file), key);
          backend.putFile(key, file); // Stream large files; do not load vectors.bin into a byte[].
        }
      }
      if (!Arrays.equals(current, Files.readAllBytes(root.resolve("CURRENT")))) {
        throw new IllegalStateException("Source changed during upload; CURRENT was not published");
      }
      backend.put(prefix + "CURRENT", current);
      if (!Arrays.equals(current, backend.get(prefix + "CURRENT"))) {
        throw new IllegalStateException("Remote CURRENT verification failed");
      }
      System.out.printf("Published %,d rows, generation %d, recipe %s%n", manifest.liveCount(),
          manifest.generationNumber(), manifest.recipeHash().orElseThrow());
    }
  }
}
