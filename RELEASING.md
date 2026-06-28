# Releasing MangaPagesSplitter

Releases are built and published automatically by GitHub Actions
(`.github/workflows/release.yml`) when a `v*` tag is pushed.

The single source of truth for distributed binaries is the
[GitHub Releases page](https://github.com/flochrislas/MangaPagesSplitter/releases).
Built artifacts are **not** committed to the repository.

## Cutting a new release

1. Update `CHANGELOG.md`: move items from `[Unreleased]` into a new
   `## [X.Y.Z] - YYYY-MM-DD` section, and add a new compare link at the bottom.
2. Bump `<version>` in `pom.xml` to `X.Y.Z`.
3. Commit:
   ```
   git add CHANGELOG.md pom.xml
   git commit -m "Release vX.Y.Z"
   ```
4. Tag and push:
   ```
   git tag -a vX.Y.Z -m "Release vX.Y.Z"
   git push --follow-tags
   ```
5. GitHub Actions will:
   - verify that `pom.xml` version matches the tag,
   - run `mvn package` (producing the JAR and a self-contained Windows
     portable ZIP via `jpackage`),
   - extract the matching section from `CHANGELOG.md` as release notes,
   - create the GitHub Release with these four assets attached:
     - `MangaPagesSplitter.jar`
     - `MangaPagesSplitter-windows-X.Y.Z.zip` (portable Windows bundle,
       includes a bundled Java 17 runtime — no Java needed on the user's PC)
     - `MangaPagesSplitter.bat`
     - `MangaPagesSplitter.sh`

Watch the run under the **Actions** tab. If it fails, the release is not
published — fix the issue, delete the tag (`git push --delete origin vX.Y.Z`
and `git tag -d vX.Y.Z`), and try again.

## Signed EXE releases (optional)

The workflow currently builds an unsigned launcher EXE inside the
portable ZIP. To produce a signed EXE, build locally with the
`sign-exe` profile and re-upload a signed ZIP manually to the GitHub
Release created by CI:

```
mvn package -Psign-exe -Dsigning.keystore=path\to\keystore.pfx -Dsigning.storepass=PASSWORD
```

The signed launcher is at `target\jpackage\MangaPagesSplitter\MangaPagesSplitter.exe`;
re-zip the `target\jpackage\MangaPagesSplitter\` folder before uploading.

## Keeping local tags in sync

To make sure `git tag` locally reflects the remote (so you don't get a stale
view like "last release is v2.1.0" when v2.2.0 is already out):

```
git config --global fetch.pruneTags true
git config --global remote.origin.tagOpt --tags
```
