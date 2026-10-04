///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS dev.hardwood:hardwood-core:1.0.0.Final
//DEPS org.xerial.snappy:snappy-java:1.1.10.8
//RUNTIME_OPTIONS --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/** Downloads the whole DBpedia-14 dataset and reports what arrived. */
public class dbpedia {

  static final String BASE =
      "https://huggingface.co/api/datasets/fancyzhx/dbpedia_14/parquet/dbpedia_14/";
  static final Path CORPUS = Path.of("corpus");

  static final String[] CATEGORIES = {
    "Company", "EducationalInstitution", "Artist", "Athlete", "OfficeHolder",
    "MeanOfTransportation", "Building", "NaturalPlace", "Village", "Animal",
    "Plant", "Album", "Film", "WrittenWork"
  };

  public static void main(String[] args) throws Exception {
    report(download());
  }

  /** Two files, because the dataset ships a train/test division we have no use for. */
  static Path[] download() throws IOException, InterruptedException {
    Files.createDirectories(CORPUS);
    HttpClient http =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

    Path[] files = new Path[2];
    int i = 0;
    for (String split : new String[] {"train", "test"}) {
      Path file = CORPUS.resolve("dbpedia_14-" + split + ".parquet");
      files[i++] = file;
      if (Files.exists(file)) {
        System.out.printf("have   %s  %,d bytes%n", file, Files.size(file));
        continue;
      }
      System.out.printf("get    %s%n", BASE + split + "/0.parquet");
      HttpResponse<Path> response =
          http.send(
              HttpRequest.newBuilder(URI.create(BASE + split + "/0.parquet")).build(),
              HttpResponse.BodyHandlers.ofFile(file));
      if (response.statusCode() != 200) {
        Files.deleteIfExists(file);
        throw new IOException("download failed: HTTP " + response.statusCode());
      }
      System.out.printf("wrote  %s  %,d bytes%n", file, Files.size(file));
    }
    return files;
  }

  /** Reads every row of both files as one corpus: the train/test division is not ours. */
  static void report(Path... files) throws IOException {
    long[] perCategory = new long[CATEGORIES.length];
    long rows = 0;
    long characters = 0;
    long start = System.nanoTime();

    for (Path file : files) {
      try (ParquetFileReader parquet = ParquetFileReader.open(InputFile.of(file));
          RowReader reader = parquet.rowReader()) {
        while (reader.hasNext()) {
          reader.next();
          perCategory[(int) reader.getLong("label")]++;
          characters += reader.getString("title").length() + reader.getString("content").length();
          rows++;
        }
      }
    }

    long millis = (System.nanoTime() - start) / 1_000_000;
    System.out.printf("%n%,d documents, %.0f characters on average, read in %,d ms%n",
        rows, (double) characters / rows, millis);
    for (int c = 0; c < CATEGORIES.length; c++) {
      System.out.printf("  %-24s %,7d%n", CATEGORIES[c], perCategory[c]);
    }
  }
}
