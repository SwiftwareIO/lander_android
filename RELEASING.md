# Releasing lander-android

The SDK is developed in the Lander monorepo (`sdk/lander_android`) and published as `link.lander:lander-android` on Maven Central, through the Central Portal. `.github/workflows/sdk-android-release.yml` does the work:

- **mirror** job: on every push to `main` that touches `sdk/lander_android`, it pushes a `git subtree split` of the folder to `main` of the public, read only mirror [github.com/SwiftwareIO/lander_android](https://github.com/SwiftwareIO/lander_android). The POM's `scm` and issue links point there. Never commit to the mirror directly.
- **release** job: on a monorepo tag `android-vX.Y.Z`, it checks the version, runs the unit tests and lint, publishes to Maven Central (signed, released automatically) and pushes tag `vX.Y.Z` to the mirror.

Each part skips with a notice in the run summary when its secrets are missing, so the workflow is safe to merge before the setup below is done.

## One-time setup

### 1. Deploy key for the mirror

```bash
ssh-keygen -t ed25519 -N "" -C "lander monorepo to lander_android" -f lander_android_deploy
```

- In **SwiftwareIO/lander_android > Settings > Deploy keys**, add `lander_android_deploy.pub` with **Allow write access**.
- In **SwiftwareIO/lander > Settings > Secrets and variables > Actions**, add the private half as `SDK_MIRROR_ANDROID_DEPLOY_KEY`:

  ```bash
  gh secret set SDK_MIRROR_ANDROID_DEPLOY_KEY --repo SwiftwareIO/lander < lander_android_deploy
  ```

- Delete both files afterwards. Run the workflow once from the Actions tab (**Run workflow** on `main`) to fill the mirror.

### 2. The `link.lander` namespace on Maven Central

Maven Central only accepts a group ID whose namespace you have verified. A namespace is a reversed domain: `link.lander` belongs to whoever controls **`lander.link`**. Lander's own domain is `golander.link`, which maps to `link.golander`, not `link.lander`.

<!-- Checked on 2026-10-27: the registry reported lander.link as available, so nobody owns it yet. -->

Pick one before the first release:

- **Keep `link.lander`:** register `lander.link`, then sign in at [central.sonatype.com](https://central.sonatype.com), open **Namespaces > Add namespace**, enter `link.lander` and add the TXT record it shows to the DNS of `lander.link`. Click **Verify**.
- **Use `link.golander`:** add the namespace `link.golander` and verify it with a TXT record on `golander.link` (on Cloudflare). Then change `GROUP` in `gradle.properties` and the dependency line in `README.md` and `apps/docs/content/docs/android/` to `link.golander:lander-android`. Nothing else changes: the Kotlin package stays `link.lander.android`.

The TXT record can be deleted once the namespace shows as verified.

### 3. Central Portal user token

On [central.sonatype.com](https://central.sonatype.com), open **View Account > Generate User Token**. Save the two values as secrets:

```bash
gh secret set MAVEN_CENTRAL_USERNAME --repo SwiftwareIO/lander   # the token username
gh secret set MAVEN_CENTRAL_PASSWORD --repo SwiftwareIO/lander   # the token password
```

This is a token, not your account login. Regenerating it invalidates the old one.

### 4. Signing key

Maven Central requires every file to be signed with a GPG key whose public half is on a public key server.

```bash
gpg --quick-gen-key "Lander <your-team-email>" rsa4096 sign 3y   # asks for a passphrase
gpg --list-secret-keys --keyid-format long                           # note the key ID
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>

gpg --armor --export-secret-keys <KEY_ID> | gh secret set SIGNING_KEY --repo SwiftwareIO/lander
gh secret set SIGNING_KEY_PASSWORD --repo SwiftwareIO/lander       # the passphrase
```

Keep an offline backup of the key and its revocation certificate. Signing a later release with a different key works, but users who pinned the old key will notice.

## Release a version

1. On a branch, bump the version and describe it:
   - `VERSION_NAME` in `sdk/lander_android/gradle.properties` (it is also `Lander.VERSION` and the `sdkVersion` the SDK reports);
   - a `## X.Y.Z` heading in `sdk/lander_android/CHANGELOG.md`;
   - `apps/docs/content/docs/android/changelog.mdx`, and the version in the install lines of `README.md`, `apps/docs/content/docs/android/installation.mdx` and `index.mdx`.
2. Open a PR, let CI pass, merge it.
3. Tag the merge commit on `main` and push the tag:

   ```bash
   git fetch origin
   git tag android-vX.Y.Z origin/main
   git push origin android-vX.Y.Z
   ```

4. Watch **Actions > Android SDK release**. The job fails before publishing if the tag, `VERSION_NAME`, `Lander.VERSION` and the changelog heading disagree, if the tag is not on `main`, or if a test or lint check fails.
5. After the job, the version shows on [central.sonatype.com](https://central.sonatype.com/publishing/deployments) right away and on `repo1.maven.org` within about 30 minutes. The mirror gets tag `vX.Y.Z`.

Published versions cannot be changed or deleted. If a release is broken, publish a fix with the next patch version.

## Check a release locally

Without any keys, this builds everything Central receives (AAR, POM, sources and javadoc jars) into `~/.m2/repository/link/lander/lander-android/`, unsigned:

```bash
./gradlew :lander:publishToMavenLocal
```

With `signingInMemoryKey` and `signingInMemoryKeyPassword` set (as Gradle properties or `ORG_GRADLE_PROJECT_` environment variables), every file also gets an `.asc` signature. Do not run `publishAndReleaseToMavenCentral` by hand; tag instead.
