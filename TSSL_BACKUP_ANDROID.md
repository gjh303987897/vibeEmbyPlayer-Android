# Android TSSL backup and restore

The TSSL manager now supports the same remote targets as the Qt implementation:

- WebDAV: select an existing WebDAV service, then enter the remote folder. The
  service's saved password and self-signed-certificate policy are reused.
- S3-compatible storage: enter the HTTPS endpoint, bucket, region, object
  prefix, and access key. The secret key is encrypted with the Android
  Keystore and is never stored in DataStore or logs. Localhost endpoints may
  use HTTP for development.

Use **Back up all** to upload every locally validated package. Use **Restore
remote** to list `.tssl` objects, download them one at a time, validate them
with the TSSL parser, and store them by their content-derived root digest.
Existing local packages are not overwritten; the result reports restored,
duplicate, and failed files.

Remote object names match the desktop layout:

```text
WebDAV: <remote-folder>/<root-manifest-sha256>.tssl
S3:     <bucket>/<prefix>/<root-manifest-sha256>.tssl
```

The Android client applies the same 256 MiB remote transfer limit and uses
AWS Signature Version 4 for S3 requests.
