# CI on GitHub runners (ci/base)

`ci/base` is an orphan branch holding only the CI overlay: `.github/workflows/ci-run.yml`,
`scripts/github-ci-run` and this file. `app.yml` and `pr-check.yml` on `master` keep running on
pushes and PRs; this adds a way to test any ref without a PR, and without cutting a release.

    git fetch origin ci/base
    scripts/github-ci-run [--no-wait] [REF]     # REF defaults to HEAD

The wrapper makes a commit of REF's tree plus the overlay, force-pushes it to
`ci/run/<slug>-<sha8>` and watches the run (`--no-wait` prints the run URL and returns). Workflows
whose push trigger would fire on `ci/run/**` are dropped from that commit only.

The job runs: pre-push guard (static), `:manager:spotlessCheck`, `:manager:lint`, the unit tests
(`:manager:testShizukuplusDebugUnitTest :server:testDebugUnitTest :database:testDebugUnitTest`),
`scripts/dev/check-test-counts.py`, `:manager:assembleDebug`. Release signing (1Password) stays on
the Mac: `scripts/dev/build-release-local.sh`.

To change the workflow or wrapper, commit on `ci/base`, then copy `scripts/github-ci-run` to the
default branch too.
