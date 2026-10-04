///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.integrallis:vectors:0.1.27
//DEPS com.integrallis:vectors-storage-s3:0.1.27
//SOURCES CollectionLocation.java
//RUNTIME_OPTIONS --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx12g

import com.integrallis.vectors.core.Document;
import com.integrallis.vectors.core.MetadataValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.SplittableRandom;
import java.util.TreeMap;

/** A deterministic 200-per-category Studio view, reusing already stored embeddings. */
public class sample {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("Usage: sample.java LOCAL_COLLECTION NEW_VIEW");
    Path target = Path.of(args[1]).toAbsolutePath();
    if (Files.exists(target)) throw new IllegalArgumentException("Choose a new view directory: " + target);
    try (var opened = CollectionLocation.open(args[0])) {
      var source = opened.collection();
      if (source.size() != 630_000) throw new IllegalArgumentException("Finish the 630,000-row ingest first");
      var groups = new TreeMap<String, ArrayList<Document>>();
      var visited = new HashSet<Integer>();
      var random = new SplittableRandom(42);
      int selected = 0;
      for (int attempt = 0; selected < 2800 && attempt < 100_000; attempt++) {
        int ordinal = random.nextInt(source.size());
        if (!visited.add(ordinal)) continue;
        Document document = source.get("dbpedia-" + ordinal);
        if (document == null) throw new IllegalStateException("Missing positional DBpedia ID " + ordinal);
        String category = ((MetadataValue.Str) document.metadata().get("category")).value();
        var rows = groups.computeIfAbsent(category, k -> new ArrayList<>());
        if (rows.size() < 200) { rows.add(document); selected++; }
      }
      if (groups.size() != 14 || groups.values().stream().anyMatch(rows -> rows.size() != 200)) {
        throw new IllegalStateException("Could not obtain 200 rows from each of 14 categories");
      }
      try (var view = CollectionLocation.builder(target)
          .embeddingRecipe(source.config().recipe().orElseThrow()).build()) {
        for (var rows : groups.values()) view.addAll(rows);
        view.commit();
        System.out.printf("%,d existing embeddings copied to %s; no model calls%n", view.size(), target);
      }
    }
  }
}
