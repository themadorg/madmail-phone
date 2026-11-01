# Bundled engine patches

Base: `themadorg/madmail` revision `22db4602768364955670b0f08dd97f03db6e22cc` (2.30.5).

`0001-android-storage-copy-fallback.patch` retains normal hard-link storage where supported and copies the body when the filesystem/sandbox returns EPERM, EACCES, EXDEV or unsupported-operation errors. Other errors still fail. Copies are synced before successful delivery is reported, according to the existing storage durability policy. This covers CAS-to-mailbox links, local multi-recipient delivery and queued outbound fan-out. It does not weaken TLS, encryption checks, account isolation or Android permissions. Copies use additional disk space compared with hard links.

The Pixel 6a Android 17 app sandbox rejected a private-file hard-link probe with `Permission denied`. The original engine returned SMTP 451 for real Delta Chat SecureJoin messages. `LocalDeliveryDeviceTest` reproduces the failure against the original binary by delivering a fixture to two recipients, and requires successful SMTP acceptance and IMAP retrieval for both with the patched binary. Real Delta Chat verification is recorded separately in `docs/invitation-flow.md`.

Build with `bash scripts/build-engine.sh`; then run `scripts/prepare-engine.sh` with `ANDROID_NDK_HOME` set to build the launcher. Dependencies are the upstream Cargo.lock plus cargo-zigbuild 0.24.0. The output is a static ARM64 Linux-musl executable with 16 KiB ELF alignment, not an Android/Bionic target. The generated manifest records binary and patch SHA-256 values. Source changes remain under the upstream license. No upstream PR has been submitted by this task.

Separate upstream issue: `chatmail-auth::finish_successful_login` / `chatmail-db::registration_tokens::record_first_login` do not atomically settle a reservation across simultaneous IMAP/SMTP authentication. The Android wrapper mitigates this for its local Account QR path by completing one pinned-certificate IMAP login and verifying SMTP before exposing the QR. Remote and browser provisioning require an upstream fix; increasing token use counts is not a correct fix.
