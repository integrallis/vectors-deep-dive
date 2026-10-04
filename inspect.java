///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.integrallis:vectors:0.1.27
//RUNTIME_OPTIONS --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED

import com.integrallis.vectors.db.storage.FileFormat;
import com.integrallis.vectors.db.storage.GenerationDirectory;
import com.integrallis.vectors.db.storage.Manifest;
import com.integrallis.vectors.db.storage.MemorySegmentVectors;
import com.integrallis.vectors.db.storage.RecipeStore;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read CURRENT, describe its generation, and map one vector without opening the full collection. */
public class inspect {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Usage: inspect.java LOCAL_COLLECTION");
    Path root = Path.of(args[0]).toAbsolutePath();
    long current = GenerationDirectory.readCurrent(root);
    if (current < 0) throw new IllegalArgumentException("No committed collection at " + root);
    Path generation = root.resolve(FileFormat.generationDirName(current));
    Manifest manifest = Manifest.readFrom(generation.resolve("manifest.bin"));
    System.out.printf("CURRENT -> %s%n%,d live rows; %d dimensions; %s; %s%n", generation.getFileName(),
        manifest.liveCount(), manifest.dimension(), manifest.metric(), manifest.indexType());
    System.out.println("Recipe hash: " + manifest.recipeHash().orElse("none"));
    RecipeStore.read(root, manifest.recipeHash());
    try (var files = Files.list(generation)) {
      for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
        System.out.printf("%-18s %,14d bytes%n", p.getFileName(), Files.size(p));
      }
    }
    if (manifest.liveCount() == 0) return;
    try (var arena = Arena.ofConfined()) {
      var vectors = MemorySegmentVectors.open(generation.resolve("vectors.bin"),
          Math.toIntExact(manifest.liveCount() + manifest.tombstoneCount()), manifest.dimension(), arena);
      System.out.printf("Row stride: %,d bytes; first vector begins:%n", vectors.stride());
      var row = vectors.vectorSlice(0);
      for (int i = 0; i < Math.min(8, manifest.dimension()); i++) {
        System.out.printf("%+.6f ", row.getAtIndex(ValueLayout.JAVA_FLOAT, i));
      }
      System.out.println();
    }
  }
}
