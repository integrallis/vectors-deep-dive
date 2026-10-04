///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.integrallis:vectors:0.1.27
//DEPS com.integrallis:vectors-storage-s3:0.1.27
//DEPS dev.langchain4j:langchain4j-open-ai:1.21.0
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS io.github.cdimascio:dotenv-java:3.2.0
//SOURCES R2LiveTests.java ../CollectionLocation.java ../bucket.java ../r2.java ../query.java

import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Path;
import java.util.Map;

/** Runs the live suite twice, with credentials confined to the child environment. */
public class liveR2 {
  public static void main(String[] args) throws Exception {
    if (args.length > 2) throw new IllegalArgumentException(
        "Usage: liveR2.java [PATH_TO_ENV_FILE [REPORT_DIRECTORY]]");
    Dotenv dotenv = args.length == 0 ? null : Dotenv.configure()
        .directory(Path.of(args[0]).toAbsolutePath().getParent().toString())
        .filename(Path.of(args[0]).getFileName().toString()).load();
    Path reports = Path.of(args.length > 1 ? args[1] : "build/r2-live-reports").toAbsolutePath();
    String endpoint = first(dotenv, "R2_ENDPOINT", "VECTORS_R2_ENDPOINT");
    if (endpoint == null) endpoint = "https://" + required(dotenv, "VECTORS_R2_ACCOUNT_ID")
        + ".r2.cloudflarestorage.com";
    int finalStatus = 0;
    for (int run = 1; run <= 2; run++) {
      System.out.println("Live R2 suite: run " + run + "/2");
      var process = new ProcessBuilder(
          Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "--add-modules=jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED", "-Xmx2g",
          "-cp", System.getProperty("java.class.path"),
          "org.junit.platform.console.ConsoleLauncher", "execute",
          "--select-class=R2LiveTests", "--fail-if-no-tests", "--disable-ansi-colors",
          "--details=tree", "--reports-dir=" + reports.resolve("run-" + run));
      Map<String, String> env = process.environment();
      env.put("R2_ENDPOINT", endpoint);
      env.put("R2_ACCESS_KEY_ID", required(dotenv, "R2_ACCESS_KEY_ID", "VECTORS_R2_ACCESS_KEY"));
      env.put("R2_SECRET_ACCESS_KEY", required(dotenv, "R2_SECRET_ACCESS_KEY", "VECTORS_R2_SECRET_KEY"));
      String testBucket = first(dotenv, "R2_TEST_BUCKET", "VECTORS_R2_BUCKET");
      if (testBucket != null) env.put("R2_TEST_BUCKET", testBucket);
      env.remove("OPENAI_API_KEY"); // Saved synthetic query vectors only; no paid embedding calls.
      int status = process.inheritIO().start().waitFor();
      if (status != 0) finalStatus = status;
    }
    if (finalStatus != 0) System.exit(finalStatus);
    System.out.println("Both live runs passed, including cleanup. Reports: " + reports);
  }

  static String first(Dotenv dotenv, String... names) {
    for (String name : names) {
      String value = System.getenv(name);
      if (value != null && !value.isBlank()) return value;
    }
    if (dotenv != null) for (String name : names) {
      String value = dotenv.get(name);
      if (value != null && !value.isBlank()) return value;
    }
    return null;
  }

  static String required(Dotenv dotenv, String... names) {
    String value = first(dotenv, names);
    if (value == null) throw new IllegalArgumentException("Missing " + String.join(" or ", names));
    return value;
  }
}
