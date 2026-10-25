# Editing server passwords

The Services home screen's edit dialog accepts a replacement password for WebDAV,
Emby and Jellyfin. The field starts empty, masks its contents, uses the password
keyboard and only keeps typed text in memory. It never reads an existing secret
into the form or saves the input into activity instance state.

WebDAV previously had neither a password field in this dialog nor a password
update in its save branch. It now writes a nonblank replacement through
`MediaServerRepository.savePassword`, on `Dispatchers.IO`, to the same
Keystore-backed `SecureSessionStore` that `WebDavRepository` reads for requests.
The server ID remains unchanged, preserving its history and TSSL associations.

Leaving the field blank preserves the existing password. Cancel does not submit
the form. Emby/Jellyfin retain the existing save-password option: opting out
removes their saved password, while opting in permits replacement or retention.
This does not introduce password fields for IPTV or link services.

A failed secure-store write does not proceed to save the server metadata. Failed
edits display an error and retain the editor's input; only a successful edit event
closes the dialog. Exceptions are reported without logging credentials or server
addresses. Repeated saves remain guarded by the existing per-server in-flight set.

The desktop reference keeps WebDAV passwords in `CredentialStore` separately from
the server-card configuration (`AppViewModel::editServiceCard` and
`VIBEDOCS/WebDAV.md`). Android retains its own stable service IDs and secure store.

## Verification

Application and Android test APK builds, all 164 JVM tests and lint pass.
`ServerPasswordEditDeviceTest` is compiled but has not been executed on a device
for this change. It drives the real Services screen against a UUID-owned local
WebDAV fixture, verifies the new password reaches a PROPFIND request, then checks
blank saves and cancelled edits retain it. It performs no media playback and
removes only its own service and credentials in cleanup.
