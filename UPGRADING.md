# Upgrade Guide

If you are skipping versions, work through these sections backwards from your
current version.

## 4.1.x → 5.0

1.  Note that the application is now packaged as a JAR file which can no longer
    run in a Servlet container.
2.  Note that Java 11 or later is now required.
3.  Add the following keys from the sample configuration:
    * `http.min_threads`
    * `http.max_threads`
    * `log_error_responses`
    * `meta_identifier.*`
    * `endpoint.iiif.3.enabled`
    * `endpoint.health.dependency_check`
    * `S3Source.region`
        * If you had set `S3Source.endpoint` to an AWS endpoint, unset that and
          set this key to your AWS region instead.
    * `processor.ManualSelectionStrategy.xpm`
    * `processor.downscale_linear`
    * `processor.imageio.xpm.reader`
    * `GrokProcessor.path_to_binaries`
    * `processor.pdf.*`
    * `S3Cache.region`
        * If you had set `S3Cache.endpoint` to an AWS endpoint, unset that and
          set this key to your AWS region instead.
    * `log.application.ConsoleAppender.logstash.enabled`
    * `log.application.FileAppender.logstash.enabled`
    * `log.application.RollingFileAppender.logstash.enabled`
    * `log.error.FileAppender.logstash.enabled`
    * `log.error.RollingFileAppender.logstash.enabled`
4.  Remove the following configuration keys:
    * `http.http2.enabled`
    * `https.http2.enabled`
    * `endpoint.iiif.content_disposition`
    * `delegate_script.cache.enabled`
    * `processor.metadata.*`
    * `GraphicsMagickProcessor.path_to_binaries`
    * `KakaduDemoProcessor.path_to_binaries`
    * `ImageMagickProcessor.path_to_binaries`
    * `S3Cache.max_connections`
    * `redaction.enabled`
5.  Rename the following configuration keys:
    * `endpoint.iiif.2.restrict_to_sizes` to `endpoint.iiif.restrict_to_sizes`
    * `overlays.enabled` to `overlays.BasicStrategy.enabled`
6.  Add the following methods from the sample delegate script:
    * `deserialize_meta_identifier()`
    * `serialize_meta_identifier()`
    * `pre_authorize()`
    * `extra_iiif3_information_response_keys()`
    * `metadata()`
7.  Note that the `pre_authorize()` delegate method added in step 6 may require
    refactoring the logic in `authorize()`. See the documentation of those
    methods for more information.
8.  In your delegate script, change any references to
   `edu.illinois.library.cantaloupe.script.Logger` to
   `edu.illinois.library.cantaloupe.delegate.Logger`.
9.  The `X-IIIF-ID` reverse proxy header is no longer supported. Use
    `X-Forwarded-ID` instead.
10. If you were using the `processor.metadata.preserve` key, you will need to
    use the new `metadata()` delegate method instead.
11. If you were using the `cookie` key in the delegate script context hash,
    note that its structure has changed to a hash of cookie name-value pairs.
    This is how it was documented to work, and how it was supposed to work, in
    previous versions.
12. Note that the default scale constraint delimiter has changed from a dash
    (`-`) to a semicolon (`;`). If you were using scale constraints in URI
    identifiers (e.g. `image.jpg-1:2`), and want to avoid breaking those URIs,
    you must change the value of
    `meta_identifier.transformer.StandardMetaIdentifierTransformer.delimiter`
    to `-`.
13. Note that the `page` URI query argument has been deprecated and will be
    removed in a future version. A page argument should instead be expressed in
    the identifier path component. For example, if
    `meta_identifier.transformer` is set to
    `StandardMetaIdentifierTransformer`, and the desired page is `3`, the new
    identifier would be: `image.jpg;3`.
14. Similar to above, the `time` URI query argument has been deprecated. A time
    argument should instead be expressed in the identifier path component as
    above, as an integer number of seconds rather than an `HH:MM::SS` string.
15. The source known as HttpSource2 from version 4.1.x is now known as
    HttpSource, and the old HttpSource has been removed. If you encounter
    errors from the new HttpSource like "PKIX path building failed," consult
    the HttpSource section of the user manual.
16. If you are using KakaduNativeProcessor, you must install the updated Kakadu
    shared library, contained in the `deps` folder.
17. KakaduDemoProcessor is no longer available. If you were using it, you must
    switch to either KakaduNativeProcessor, OpenJpegProcessor, or
    GrokProcessor.
18. If you are using a derivative cache, and are serving any images that have
    non-zero orientations, or embedded metadata with which you might want to
    use the new `metadata()` delegate method, you must purge all cached infos.
    Unfortunately there is no API method to do this yet, so this will require
    either deleting all `.json` files/objects manually, or else purging the
    whole cache (manually or via the API).

## 4.0.x → 4.1

1. Rename the following configuration keys:
    * `HttpSource.trust_all_certs` to `HttpSource.allow_insecure`
    * `processor.[format]` to `processor.ManualSelectionStrategy.[format]`
    * `processor.fallback` to `processor.ManualSelectionStrategy.fallback`
2. Add the following keys from the sample configuration:
    * `max_scale`
    * `HttpSource.chunking.*`
    * `S3Source.chunking.*`
    * `AzureStorageSource.chunking.*`
    * `processor.selection_strategy`
3. Remove the following configuration keys:
    * `allow_upscaling`
    * `endpoint.public.auth.*`
    * `S3Source.max_connections`
    * `processor.normalize`
4. If you have implemented the `redirect()` or `authorized?()` delegate
   methods, migrate their logic into `authorize()` and remove them.
5. If you were using the `endpoint.public.auth.*` keys, you will need to use
   the new `authorize()` delegate method instead.
6. Note that `KakaduDemoProcessor` has been deprecated and may be removed in a
   future version. Consider migrating now to either `KakaduNativeProcessor` or
   `OpenJpegProcessor`.

## 4.0 → 4.0.x

Nothing to do.

## 3.4.x → 4.0

1. Rename the following configuration keys:
    * Any key containing the string `Resolver` to contain `Source`
    * `HttpSource.auth.*` to `HttpSource.BasicLookupStrategy.auth.*`
    * `AmazonS3Source.*` to `S3Source.*`
    * `StreamProcessor.retrieval_strategy` to
      `processor.stream_retrieval_strategy`
    * `KakaduProcessor.path_to_binaries` to
      `KakaduDemoProcessor.path_to_binaries`
    * `AmazonS3Cache.*` to `S3Cache.*`
2. Add the following keys from the sample configuration:
    * `S3Source.endpoint`
    * `S3Source.BasicLookupStrategy.path_prefix`
    * `S3Source.BasicLookupStrategy.path_suffix`
    * `processor.fallback_retrieval_strategy`
    * `processor.imageio.*`
    * `S3Cache.endpoint`
    * `cache.server.source.ttl_seconds`
    * `cache.server.derivative.ttl_seconds`
3. Remove the following configuration keys:
    * `S3Source.bucket.region`
    * `processor.limit_to_8_bits`
    * `S3Cache.bucket.region`
    * `cache.server.source.enabled`
    * `cache.server.ttl_seconds`
4. Change any `AmazonS3Source` configuration values to `S3Source`.
5. Change any configuration values containing `Resolver` to contain `Source`.
6. Change any `AmazonS3Cache` configuration values to `S3Cache`.
7. The delegate script architecture has changed, and the 3.x script is not
   compatible. See the "Delegate Script" section of the user manual for
   migration info.
8. The deprecated `DELETE /cache/:identifier` HTTP API method has been removed.
   See the "Remote Management" section of the user manual for information about
   its successor.
9. The `X-IIIF-ID` reverse proxy header is deprecated and will be removed in a
   future version. Start using `X-Forwarded-ID` instead.
10. If you'd like to use the new KakaduNativeProcessor non-commercially for
   decoding JPEG2000 images, see the "Processors" section of the user manual
   for information on configuring it.

## 3.4 → 3.4.x

Nothing to do.

## 3.3.x → 3.4

1. Add the following keys from the sample configuration:
    * `temp_pathname`
    * `http.http2.enabled`
    * `https.http2.enabled`
    * `http.accept_queue_limit`
    * `endpoint.iiif.min_size`
    * `endpoint.admin.username`
    * `HttpResolver.trust_all_certs`
    * `HttpResolver.request_timeout`
    * `AmazonS3Resolver.max_connections`
    * `processor.flv`
    * `processor.limit_to_8_bits`
    * `cache.server.source.enabled`
    * `cache.server.derivative.enabled`
    * `cache.server.info.enabled`
    * `AmazonS3Cache.max_connections`
    * `HeapCache.*`
    * `RedisCache.*`
    * `log.error.*`
2. Rename the following keys:
    * `auth.*` to `endpoint.public.auth.*`
    * `admin.enabled` to `endpoint.admin.enabled`
    * `admin.password` to `endpoint.admin.secret`
    * `PdfBoxProcessor.dpi` to `processor.dpi`
    * `cache.source` to `cache.server.source`
    * `cache.derivative` to `cache.server.derivative`
    * `metadata.*` to `processor.metadata.*`
3. If you are using the delegate script, add a `context` argument to the method
   signature of any existing source delegates. See `delegates.rb.sample` for
   examples.
4. The `DELETE /cache/:identifier` HTTP API method is deprecated and will be
   removed in version 4. Begin migrating now to the updated equivalent.
5. Managing the cache on the command line with VM arguments is deprecated and
   will be removed in version 4. Begin migrating now to the cache management
   methods in the HTTP API.

## 3.3 → 3.3.5

Nothing to do.

## 3.2.x → 3.3

1. Add the following keys from the sample configuration:
    * `delegate_script.cache.enabled`
    * `endpoint.api.*`
    * `processor.dcm`
    * `processor.normalize`
    * `processor.background_color`
    * `processor.upscale_filter`
    * `processor.downscale_filter`
    * `processor.sharpen`
    * `processor.jpg.progressive`
    * `processor.jpg.quality`
    * `processor.tif.compression`
    * `cache.server.ttl_seconds`
    * `overlays.BasicStrategy.type`
    * `overlays.BasicStrategy.string`
    * `overlays.BasicStrategy.string.*`
2. Rename the following keys:
    * `watermark.*` to `overlays.*`
3. Remove the following configuration keys:
    * `JdbcResolver.max_pool_size`
    * `FfmpegProcessor.sharpen`
    * `GraphicsMagickProcessor.sharpen`
    * `GraphicsMagickProcessor.background_color`
    * `ImageMagickProcessor.sharpen`
    * `ImageMagickProcessor.background_color`
    * `JaiProcessor.sharpen`
    * `JaiProcessor.jpg.quality`
    * `JaiProcessor.tif.compression`
    * `Java2dProcessor.upscale_filter`
    * `Java2dProcessor.downscale_filter`
    * `Java2dProcessor.sharpen`
    * `Java2dProcessor.jpg.quality`
    * `Java2dProcessor.tif.compression`
    * `KakaduProcessor.upscale_filter`
    * `KakaduProcessor.downscale_filter`
    * `KakaduProcessor.sharpen`
    * `OpenJpegProcessor.upscale_filter`
    * `OpenJpegProcessor.downscale_filter`
    * `OpenJpegProcessor.sharpen`
    * `PdfBoxProcessor.upscale_filter`
    * `PdfBoxProcessor.downscale_filter`
    * `PdfBoxProcessor.sharpen`
    * `FilesystemCache.ttl_seconds`
    * `JdbcCache.max_pool_size`
    * `JdbcCache.ttl_seconds`
    * `AmazonS3Cache.ttl_seconds`
    * `AzureStorageCache.ttl_seconds`
4. If you are using FilesystemCache, purge your cache.
5. If you are using the `authorized?()` or `watermark()` delegate methods, note
   that the contents of the `operations` argument have changed; see the new
   sample delegate script for more information.
6. If you are using the `watermark()` delegate method, note that the `pathname`
   key in the returned hash must be changed to `image`.
7. Rename the `watermark()` delegate method to `overlay()`.

## 3.2 → 3.2.4

Nothing to do.

## 3.1.2 → 3.2

1. Add the following keys from the sample configuration:
    * `FfmpegProcessor.sharpen`
    * `GraphicsMagickProcessor.sharpen`
    * `ImageMagickProcessor.sharpen`
    * `JaiProcessor.sharpen`
    * `Java2dProcessor.upscale_filter`
    * `Java2dProcessor.downscale_filter`
    * `Java2dProcessor.sharpen`
    * `KakaduProcessor.upscale_filter`
    * `KakaduProcessor.downscale_filter`
    * `KakaduProcessor.sharpen`
    * `OpenJpegProcessor.upscale_filter`
    * `OpenJpegProcessor.downscale_filter`
    * `OpenJpegProcessor.sharpen`
    * `PdfBoxProcessor.upscale_filter`
    * `PdfBoxProcessor.downscale_filter`
    * `PdfBoxProcessor.sharpen`
    * `metadata.*`
2. Remove the following configuration keys:
    * `Java2dProcessor.scale_mode`
    * `KakaduProcessor.post_processor.*`
    * `OpenJpegProcessor.post_processor.*`
    * `PdfBoxProcessor.post_processor.*`

## 3.1.1 → 3.1.2

Nothing to do.

## 3.1 → 3.1.1

* Add the `http.host` and `https.host` keys from the sample configuration.

## 3.0.1 → 3.1

* Replace the `get_iiif2_service` delegate script method with
  `extra_iiif2_information_response_keys`.

## 3.0 → 3.0.1

Nothing to do.

## 2.2 → 3.0

1. Add the following keys from the sample configuration:
    * `admin.*`
    * `delegate_script.enabled`
    * `endpoint.iiif.2.restrict_to_sizes`
    * `StreamProcessor.retrieval_strategy`
    * `cache.source`
    * `AmazonS3Cache.*`
    * `AzureStorageCache.*`
    * `redaction.enabled`
2. Rename the following keys:
    * `delegate_script` to `delegate_script.pathname`
    * `cache.server` to `cache.derivative`
3. Remove the following keys:
    * `JdbcResolver.function.*`
4. Add the following methods from the sample delegate script:
    * `redactions`
    * `Cantaloupe::JdbcResolver::get_database_identifier`
    * `Cantaloupe::JdbcResolver::get_media_type`
    * `Cantaloupe::JdbcResolver::get_lookup_sql`
5. Rename the following delegate script methods:
    * `Cantaloupe::get_pathname` to
      `Cantaloupe::FilesystemResolver::get_pathname`
    * `Cantaloupe::get_url` to `Cantaloupe::HttpResolver::get_url`
    * `Cantaloupe::get_azure_storage_blob_key` to
      `Cantaloupe::AzureStorageResolver::get_blob_key`
    * `Cantaloupe::get_s3_object_key` to
      `Cantaloupe::AmazonS3Resolver::get_object_key`
6. If you are using JdbcResolver, be aware that the `JdbcResolver.function.*`
   configuration keys are now obsolete, and these functions will have to be
   rewritten as delegate script methods.
7. If you are using JdbcCache, modify your database schema:
   ```
   ALTER TABLE {JdbcCache.info_table} CHANGE COLUMN last_modified last_accessed;
   ALTER TABLE {JdbcCache.derivative_image_table} CHANGE COLUMN last_modified last_accessed;
   ```
8. Note that the information that used to be available on the landing page
   (`/`) has moved to the Control Panel (`/admin`). Log in with a username of
   `admin` and the password defined in the `admin.password` configuration
   option.
