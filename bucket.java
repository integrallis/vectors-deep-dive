///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.integrallis:vectors-storage-s3:0.1.27

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/** Create an R2 bucket. This setup step needs R2 Admin Read & Write S3 credentials. */
public class bucket {
  public static void main(String[] args) {
    if (args.length != 1) throw new IllegalArgumentException("Usage: bucket.java BUCKET_NAME");
    try (var s3 = S3Client.builder().endpointOverride(URI.create(env("R2_ENDPOINT")))
        .region(Region.of("auto")).forcePathStyle(true)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(env("R2_ACCESS_KEY_ID"), env("R2_SECRET_ACCESS_KEY"))))
        .build()) {
      try {
        s3.createBucket(CreateBucketRequest.builder().bucket(args[0]).build());
        System.out.println("Created R2 bucket: " + args[0]);
      } catch (BucketAlreadyOwnedByYouException alreadyExists) {
        System.out.println("R2 bucket already belongs to this account: " + args[0]);
      }
    }
  }
  static String env(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name);
    return value;
  }
}
