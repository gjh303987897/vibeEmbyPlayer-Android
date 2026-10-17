# Synthetic TSSL device fixtures

All keys in this directory are synthetic. The real Qt/miniz ZIP originates
from Qt commit `35e9b5e`; its provenance is recorded in
`app/src/test/resources/tssl/README.md`.

`legacy-v3.tssl` and `legacy-v4.tssl` were produced by the Android fixture writer
with root digests `42` and `43` repeated 32 times. The Android ZIP contains the
Qt v2 package plus those v3/v4 packages. The damaged ZIP changes the second
manifest SHA-256 to zeroes while keeping valid ZIP CRCs, so validation must
reject the entire archive before importing any package.

The device tests use private temporary TSSL stores, a temporary synthetic
credential per MockWebServer, and an explicitly selected empty SAF folder.
They do not clear the app or read existing media keys.
