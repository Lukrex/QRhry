# Online catalogue

The online catalogue is optional. Local SQLite games, play sessions and
`.qrhry` import/export remain independent of Firebase and usable offline. The
catalogue uses Firebase Authentication, Cloud Firestore, Cloud Storage and
callable Cloud Functions; Android's separate `data.online` repository does
not replace `GameRepository`. Downloads are staged and confirmed through the
existing `GameArchiveService` and strict `GameArchiveCodec`.

## Firebase project setup

1. Create a Firebase project in an EU region and register Android package
   `com.qrhry.app` with the correct signing fingerprints.
2. Enable Email/Password in Authentication and configure the verification
   message. E-mail addresses are never public catalogue fields.
3. Create Firestore and the default Storage bucket in the selected region.
4. Link billing (Blaze is required for Storage and Functions) and set budget
   alerts in Google Cloud Billing. Alerts do not cap spending.
5. Install Node.js 22, npm and Firebase CLI. Run `npm --prefix functions install`,
   `npm --prefix functions test`, `firebase login`, and `firebase use --add`.
6. Copy `catalog.properties.example` to the ignored `catalog.properties` and
   set the public Android API key, app ID, project ID and bucket name from the
   Firebase app settings. Do not put service-account keys in Android config.
   Copy `functions/.env.example` to the ignored `functions/.env` and keep
   `ENFORCE_APP_CHECK=false` while checking client metrics; set it to `true`
   before the production deployment once the SOČ signing path is confirmed.
7. Run `firebase emulators:start --only auth,functions,firestore,storage` for
   local backend development. To point a debug Android build at the emulators,
   set `firebase.emulatorHost=10.0.2.2` in `catalog.properties` when using the
   Android emulator, or use the computer's LAN IP for a phone on the same Wi-Fi.
   Emulator services bind to `0.0.0.0` for phone access; keep this development
   setup on a trusted network and stop the emulators when finished. This redirect
   is compiled into debug builds only; release builds always use the configured
   Firebase project.
8. Deploy with `firebase deploy --only firestore:rules,firestore:indexes,storage,functions`.
9. Configure App Check for the actual SOČ distribution. Debug uses the debug
   provider and release uses Play Integrity. Callable enforcement is initially
   off while the distribution path is validated; enable it before production.
10. Enable Firestore TTL for the configured `expiresAt` field overrides, then
    configure a Cloud Storage lifecycle rule to delete abandoned `staging/`
    uploads after one day. Review function logs and billing regularly.

No Firebase project id, service-account credential, private key, or production
configuration is included. Without a complete `catalog.properties`, Firebase
initialization is skipped and the app explains that the catalogue is not
configured.

## Collections, objects and permissions

- `publicGames/{publicationId}` contains only published display metadata,
  generated search tokens, active revision details and its storage object path.
  The private owner UID and e-mail are not exposed publicly.
- `users/{uid}/publications/{publicationId}` is the private owner record and
  remains after unpublishing, so the same author can publish a later revision.
- `users/{uid}/requests/{requestId}` stores idempotent function results;
  `uploads` reservations and `rateLimits` counters are server-only.
- `beginPublicationUpload` reserves an expiring ID and consumes the account's
  rate-limit slot before Storage accepts bytes. Storage checks that reservation,
  owner UID, size and declared content type. Staging files use
  `staging/{uid}/{requestId}/{requestId}.qrhry`; immutable revisions use
  `published/{publicationId}/{revisionId}.qrhry`.
- Because Storage rules read the reservation from Firestore, enable Firebase's
  cross-service Rules permission when prompted. The Firebase Storage service
  agent must have `Firebase Rules Firestore Service Agent`
  (`roles/firebaserules.firestoreServiceAgent`); otherwise valid staging
  uploads are denied with HTTP 403. This permission is for Rules evaluation,
  not for app users.
- Firestore client writes are denied. Public reads only match `PUBLISHED`.
  Storage permits an owner to create a bounded, non-overwriting staging upload
  and delete their own staging object. Public reads require that Firestore
  points at the exact active revision. All other client writes are denied.
- Callable functions require sign-in and verified e-mail. App Check is
  installed in Android, but enforcement is initially off until the app's
  distribution is validated. Server functions enforce five publication attempts per account per hour, validate
  metadata and the full v4 ZIP, compute archive metadata and SHA-256, then make
  the new revision visible. Updates store a new object before changing the
  catalogue pointer. Unpublish removes the public pointer before deleting the
  current object.
- The three callable Cloud Run services must allow the `allUsers` principal
  the `Cloud Run Invoker` role so Firebase client SDK calls can reach the
  callable protocol. This is only gateway reachability: every handler still
  requires a valid Firebase-authenticated user, and publishing also requires a
  verified e-mail. Do not grant public access to Firestore or Storage writes.

Search is accent-insensitive token matching over server-generated description
tokens and title prefixes. Results sort newest first in pages of 20. This is a
small Firestore search, not fuzzy search. The client checks downloaded size and
SHA-256, then the Kotlin codec validates the full archive again before the
existing user-confirmed import. SQLite v11 stores the source publication and
revision; a unique partial index prevents importing one publication twice.

The TypeScript ZIP validator must be kept aligned with `GameArchiveCodec` when
the archive format changes. Run both back-end and Android archive tests after
format changes. Emulator tests do not replace review of production IAM,
App Check, security rules or billing configuration.

## Scope and operating limits

The initial release has no online gameplay, cross-device progress, comments,
ratings or moderation portal. Firebase Blaze is billing-enabled and charges
depend on region and usage; review current provider pricing and actual usage in
the Firebase/Google Cloud consoles before public deployment.
