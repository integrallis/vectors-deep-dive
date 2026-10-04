///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.integrallis:vectors:0.1.27
//RUNTIME_OPTIONS --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx12g

import com.integrallis.vectors.core.Document;
import com.integrallis.vectors.core.MetadataValue;
import com.integrallis.vectors.core.SimilarityFunction;
import com.integrallis.vectors.db.*;
import java.nio.file.*;
import java.util.*;

/** The two checks the ingest exists to answer: are the vectors unit length, and is the ingest sane. */
public class verify {

  static final int DIMENSION = 512;
  static final Path STORAGE =
      Path.of(System.getProperty("user.home")).resolve("vectors/dbpedia-14-3small-512");

  public static void main(String[] args) throws Exception {
    try (VectorCollection c = VectorCollection.builder()
        .dimension(DIMENSION).metric(SimilarityFunction.COSINE).indexType(IndexType.HNSW)
        .storagePath(STORAGE.toAbsolutePath()).build()) {

      System.out.printf("%,d documents, generation %d%n", c.size(), c.generationNumber());

      Path gen = Files.list(STORAGE).filter(Files::isDirectory)
          .filter(p -> p.getFileName().toString().startsWith("gen-"))
          .max(Comparator.comparing(p -> p.getFileName().toString())).orElseThrow();
      long onDisk = Files.size(gen.resolve("vectors.bin"));
      long raw = (long) c.size() * DIMENSION * Float.BYTES;
      System.out.printf("vectors.bin %,d bytes | %,d x %d x 4 = %,d | ratio %.4f (stride padding)%n",
          onDisk, c.size(), DIMENSION, raw, onDisk / (double) raw);

      // --- norms: the recipe claims normalized = true ---
      SplittableRandom rnd = new SplittableRandom(1);
      double min = 9, max = 0, sum = 0; int n = 0;
      for (int i = 0; i < 1_000; i++) {
        Document d = c.get("dbpedia-" + rnd.nextInt(c.size()));
        if (d == null || d.vector() == null) continue;
        double s = 0;
        for (float f : d.vector()) s += (double) f * f;
        double norm = Math.sqrt(s);
        min = Math.min(min, norm); max = Math.max(max, norm); sum += norm; n++;
      }
      System.out.printf("norms over %d vectors: min %.6f max %.6f mean %.6f  -> normalized=%s%n",
          n, min, max, sum / n, (min > 0.999 && max < 1.001) ? "true (correct)" : "FALSE in recipe");

      // --- 1-NN category agreement ---
      int probes = 2_000, agree = 0, judged = 0;
      SplittableRandom pick = new SplittableRandom(42);
      Map<String,Integer> perCategoryJudged = new TreeMap<>(), perCategoryAgree = new TreeMap<>();
      for (int i = 0; i < probes; i++) {
        String id = "dbpedia-" + pick.nextInt(c.size());
        Document self = c.get(id);
        if (self == null || self.vector() == null) continue;
        String own = category(self);
        SearchResult r = c.search(SearchRequest.builder(self.vector(), 2).build());
        for (SearchResult.Hit h : r.hits()) {
          if (h.id().equals(id)) continue;
          judged++;
          perCategoryJudged.merge(own, 1, Integer::sum);
          if (Objects.equals(category(h.document()), own)) {
            agree++; perCategoryAgree.merge(own, 1, Integer::sum);
          }
          break;
        }
      }
      System.out.printf("%n1-NN category agreement: %,d/%,d = %.4f%n", agree, judged, agree/(double) judged);
      System.out.println("per category:");
      for (var e : perCategoryJudged.entrySet()) {
        int a = perCategoryAgree.getOrDefault(e.getKey(), 0);
        System.out.printf("  %-24s %4d/%4d  %.3f%n", e.getKey(), a, e.getValue(), a/(double) e.getValue());
      }

      // --- a more-like-this a human can judge ---
      Document seed = c.get("dbpedia-7");
      System.out.printf("%nmore like \"%s\" [%s]:%n", trim(seed.text()), category(seed));
      for (SearchResult.Hit h : c.search(SearchRequest.builder(seed.vector(), 6).build()).hits()) {
        System.out.printf("  %.4f  %-24s %s%n", h.score(), category(h.document()), trim(h.document().text()));
      }
    }
  }

  static String category(Document d) {
    MetadataValue m = d.metadata().get("category");
    return m instanceof MetadataValue.Str s ? s.value() : "?";
  }
  static String trim(String s) {
    if (s == null) return "(no text)";
    s = s.replace('\n', ' ');
    return s.length() <= 68 ? s : s.substring(0, 67) + "…";
  }
}
