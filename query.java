///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.integrallis:vectors:0.1.27
//DEPS com.integrallis:vectors-storage-s3:0.1.27
//DEPS dev.langchain4j:langchain4j-open-ai:1.21.0
//SOURCES CollectionLocation.java
//RUNTIME_OPTIONS --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx12g

import com.integrallis.vectors.core.MetadataValue;
import com.integrallis.vectors.db.SearchRequest;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;

/** Embed a query once, save its exact float32 values, then reuse it for local/R2 comparisons. */
public class query {
  public static void main(String[] args) throws Exception {
    if (args.length < 2) throw new IllegalArgumentException(
        "Usage: query.java COLLECTION QUERY.f32 [text to embed and save]");
    Path file = Path.of(args[1]).toAbsolutePath();
    try (var opened = CollectionLocation.open(args[0])) {
      var collection = opened.collection();
      var recipe = collection.config().recipe().orElseThrow(
          () -> new IllegalArgumentException("This example requires the ingest script's recipe"));
      if (!recipe.modelId().equals("openai/text-embedding-3-small")
          || recipe.dimension() != 512 || recipe.queryPrefix().isPresent()) {
        throw new IllegalArgumentException("Query model does not match the stored recipe");
      }
      float[] vector = new float[512];
      if (args.length > 2) {
        if (Files.exists(file)) throw new IllegalArgumentException(
            file + " already exists; omit the text to reuse it, or choose a new filename");
        var model = OpenAiEmbeddingModel.builder()
            .apiKey(CollectionLocation.env("OPENAI_API_KEY"))
            .modelName("text-embedding-3-small").dimensions(512).build();
        vector = model.embed(String.join(" ", Arrays.copyOfRange(args, 2, args.length)))
            .content().vector();
        if (vector.length != 512) throw new IllegalStateException("Unexpected embedding dimension");
        var bytes = ByteBuffer.allocate(512 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) bytes.putFloat(value);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes.array(), StandardOpenOption.CREATE_NEW);
      } else {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != 512 * Float.BYTES) throw new IllegalArgumentException("Expected 512 float32 values");
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
      }
      System.err.printf("%,d documents; generation %d; recipe %s%n", collection.size(),
          collection.generationNumber(), recipe.recipeHash());
      var result = collection.search(SearchRequest.builder(vector, 5).searchListSize(100).build());
      System.out.println("id\tscore\tscoreBits\tcategory\ttitle");
      for (var hit : result.hits()) {
        System.out.printf(Locale.ROOT, "%s\t%.7f\t%08x\t%s\t%s%n", hit.id(), hit.score(),
            Float.floatToRawIntBits(hit.score()),
            value(hit.document().metadata().get("category")),
            value(hit.document().metadata().get("title")));
      }
    }
  }
  static String value(MetadataValue value) {
    return value instanceof MetadataValue.Str text ? text.value().replace('\t', ' ').replace('\n', ' ') : "";
  }
}
