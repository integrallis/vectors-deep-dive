# Live R2 integration suite

From the repository root, run:

```bash
jbang tests/liveR2.java /absolute/path/to/.env
```

The credential file contains the S3 key pair and endpoint:

```dotenv
R2_ENDPOINT=https://YOUR_ACCOUNT_ID.r2.cloudflarestorage.com
R2_ACCESS_KEY_ID=your-access-key-id
R2_SECRET_ACCESS_KEY=your-secret-access-key
```

Create an R2 **Account API token** with **Admin Read & Write** and copy its
generated Access Key ID and Secret Access Key. The separate Token value is not
used. With these three variables, the suite creates and removes its own buckets.

Or use exported `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, and
`R2_SECRET_ACCESS_KEY` variables:

```bash
jbang tests/liveR2.java
```

The optional second argument selects the report directory. The default is
`build/r2-live-reports`, with separate JUnit XML reports for `run-1` and `run-2`.
Missing credentials fail the run; tests are not silently skipped.

The `.env` loader also accepts the repository's `VECTORS_R2_ACCOUNT_ID`,
`VECTORS_R2_ENDPOINT`, `VECTORS_R2_ACCESS_KEY`, and `VECTORS_R2_SECRET_KEY` names.
Only the R2 credentials are passed from that file to the test process. Exported
environment variables take precedence. Credentials need **Admin Read & Write**
because the complete suite exercises bucket creation and deletion. If
`R2_TEST_BUCKET` or `VECTORS_R2_BUCKET` is supplied, object tests use a unique
prefix inside that bucket. Otherwise they create a disposable bucket too.
With bucket-scoped credentials, the object tests still run, but the separate
bucket-creation test fails explicitly; this is not reported as a full pass.

## Coverage

The suite compiles and invokes the actual article scripts, including `bucket`,
`r2`, `query`, and `CollectionLocation`. It checks:

- Repeated creation of the same bucket preserves an existing object.
- Publication and listing include every snapshot file, with exact byte equality.
- A second publication to an occupied prefix refuses to overwrite anything.
- Fresh and repeated restores return identical IDs, ordering, raw score bits,
  document vectors, text, metadata, generation and embedding recipe.
- Missing `CURRENT`, malformed `CURRENT`, a missing generation or vector file,
  a missing recipe, a changed recipe, and an interrupted publication are rejected.
- A nonexistent local path does not silently create a collection.
- A 256 MiB + 1 byte file takes the library's two-part multipart upload path,
  downloads with the same SHA-256, and leaves no pending multipart upload.

The 64-document, 512-dimensional fixture is generated with a fixed seed. Its
recipe explicitly identifies the vectors as synthetic. No corpus download or
embedding call is involved; the runner removes `OPENAI_API_KEY` from the child
environment. These are storage and query-equivalence checks, not a semantic
quality or performance benchmark.

## Repeatability and cleanup

One command runs the entire suite **twice**, including after a failed first run.
The bucket test uses a uniquely named `vectors-article-it-*` bucket and confirms
it did not already exist. Object tests write only inside a unique
`vectors-article-tests/<UUID>/` prefix. Teardown removes the test objects, pending
uploads, temporary collection and restore caches. It deletes only buckets that
this run created; an existing configured bucket is retained. Cleanup runs after
test failures too, and cleanup failures fail the run. Bucket deletion and empty
test prefixes are verified with subsequent requests. The article's DBpedia
collection and objects outside the test prefix are untouched.

Each run transfers roughly 257 MiB in each direction for the multipart check,
plus the small fixture snapshots. A forced process kill or a network outage
during cleanup can leave test resources behind; JUnit teardown cannot run after
an uncatchable termination. Logs identify the test bucket and object prefix.
