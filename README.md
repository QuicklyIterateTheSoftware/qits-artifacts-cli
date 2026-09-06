# qits-artifacts-cli

`qits-publish` — the platform's one publish client for
[qits-artifacts](https://github.com/QuicklyIterateTheSoftware/qits-artifacts-service).

    ./mvnw verify     # a clone of this repo alone builds and tests green — no monorepo, no docker

Every direct HTTP interaction with the artifacts store, and the policy around it, lives in this one
static binary. A release step downloads it, calls it, and reads its exit code; it publishes nothing
itself that an ecosystem tool already publishes — `buildctl push`, `mvn deploy` and `npm publish`
stay where they are.

## Why it exists

A survey across 47 repositories and 78 pipeline files found the release cycle implemented by hand in
every one of them. The parts this binary takes over were the parts that had quietly stopped agreeing:

| Was | Now |
|---|---|
| SBOM base URLs derived by chopping three different environment variables three different ways | one `Store`, one derivation, one place to change it |
| a 409 that is a hard failure in one file and a success in the file beside it | **one idempotency policy**, below |
| ~100 lines of `sed`/`case`/`printf` hand-writing CycloneDX from `FROM` lines, in two repositories | `sbom from-dockerfile`, byte-identical output, with `ARG` resolution the shell refused |
| a `node -p` replay comparator inlined in a jslib pipeline | `npm plan` |
| a two-expression `sed` over `package-lock.json`, whose correctness rested on flag order, copied into 45 files | `npm rewrite-lockfile-origin` |

The acceptance criterion behind all of it: **a change to the release cycle is a change in one place,
never a 47-repo sweep.** This binary is where the publish half of that one place is. (The other half
is qits-ci's release-slot composer and the wrapper's archetype recipes.)

## The one idempotency policy

Every publish command follows the same four-case rule, and none of them is allowed a variation:

1. **Absent** → PUT, and say what landed.
2. **Occupied, same bytes** → success, with a line saying so. A release run re-fires, a rebootstrap
   replays a tag, a step is retried: all three go green, because the coordinate holds exactly what
   this run was asked to put there.
3. **Occupied, different bytes** → hard failure naming **both** digests. Publishing may add a
   coordinate and may never redefine one, so a run that would need to redefine one has found two
   builds of one version, and the only honest thing to do is stop and show them.
4. **Occupied, bytes not comparable** → skip, with a **WARN naming the degradation**. Exactly one
   surface is in this state: docs, because the store explodes a bundle into per-file blobs and keeps
   no archive digest. The WARN is what stops that reading as case 2.

Anything else — a 4xx that is not the store's "already there", a 5xx, an unreadable body — fails with
the status and a bounded excerpt of the response.

The store answers an occupied coordinate three different ways on purpose, and the policy is uniform
because it is expressed here rather than at each call site: **sboms** answer `200` carrying the stored
digest (one request settles it), **daemons** answer `409` and a `HEAD` supplies
`Docker-Content-Digest`, **docs** answer `409` and expose no bundle digest at any route.

Idempotent repairs — the SBOM PUT, `npm dist-tag` — always run, never guarded behind "did the publish
happen this time". A redelivery whose npm half landed and whose tag move did not would otherwise
report green with `@qits/ui-components@main` a release behind and nothing saying so. That happened.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | published, or already published with the same bytes |
| 1 | refused, and re-running will not help: invalid arguments, a 4xx, an occupied coordinate holding different bytes. `exists` also uses 1 for **absent**. |
| 2 | could not ask, or could not be answered: no store configured, an I/O failure, a 5xx |

**1 is ours, 2 is theirs.** A step may retry a 2 and must not retry a 1.

## Commands

```
qits-publish sbom submit --type <npm|maven|docker|daemon> --name <n> --version <v> --file <path>
qits-publish sbom from-dockerfile --root-name <n> --root-version <v> \
                  [--dockerfile <path>]... [--build-arg NAME=value]... -o <out.json>
qits-publish docs submit --site <name> --version <v> --archive <tgz> [--meta key=value]...
qits-publish daemon submit --name <n> --version <v> --file <bin>
qits-publish exists <daemon|docs|npm|sbom> <name> <version>
qits-publish npm plan --package <n> --version <v>
qits-publish npm dist-tag --package <n> --version <v> --tag <t>
qits-publish npm rewrite-lockfile-origin [--lockfile package-lock.json]
```

Every flag takes a value; there is no boolean flag and none should be added — `--force` is exactly the
shape of thing that turns one idempotency policy into two. An unknown flag is an error rather than an
ignored token: a publish invocation is written once and then runs unattended for a year, and a typo
that dropped `--version` would publish to a coordinate nobody meant.

**stdout is for answers a script reads; stderr is for sentences a human reads.** Only `npm plan`'s
one word and `--help` go to stdout, so `plan=$(qits-publish npm plan …)` captures the word and
nothing else.

### `sbom from-dockerfile`

The SBOM for an image that layers configuration onto an upstream *is* its `FROM` lines. This produces
the document the two OCI repositories' shell produced — byte-identical, verified against both — and
keeps every decision that shell got right: stage aliases are not upstream images, two stages from one
upstream are one dependency, a digest pins harder than a tag so the digest is the version, and the
colon in `host:5000/image` belongs to the port.

Two things it does that the shell could not: `${BUILDER_IMAGE}` in a `FROM` **resolves** (from the
file's own `ARG` default, or from `--build-arg`, which is the same precedence a real build has)
instead of being refused by the charset guard, and `FROM scratch` produces no component, because the
empty base names no image.

Not buildkit attestations and not syft: either would change the build's inputs, and the QA build and
the release build must stay byte-identical LLB or a release stops being a stack of cache hits.

### `exists`

`<type>` is `daemon`, `docs`, `npm` or `sbom`. An sbom coordinate genuinely has two name parts, so it
is written `<packageType>/<packageName>` — `qits-publish exists sbom docker/qits/qits-ci 2026.906.1`.

Three answers, not two: a step that reads an unreachable store as "absent" republishes on every
outage, and one that reads it as "present" skips a publish that never happened.

## Environment

| Variable | What |
|---|---|
| `QITS_ARTIFACTS_URL` | The store's origin. Required by everything that talks to it. |
| `QITS_DOCS_URL` | The docs root **including** its `docs` repository segment — the docs wire has one and the sbom and daemon wires do not. Derived from the origin when unset. |
| `QITS_NPM_REGISTRY_URL` | The hosted npm registry (the `@qits` scope), path included. |
| `QITS_NPM_PROXY_URL` | The npmjs pull-through cache. Two services since the byte plane was split, so neither is derivable from the other. |
| `QITS_COMMISSIONED_CLIENT_ID` / `_SECRET` | Read, and deliberately **not** sent. See below. |

Empty is absent: qits-ci spells "off" as empty-never-absent for several of the variables that reach a
step container, so distinguishing the two would branch on a difference the platform does not make.

**No credential is sent to any publish surface.** qits-artifacts' sbom, docs, daemon and npm routes
take none in either direction — exactly as `npm publish`, `mvn deploy` and `docker push` do here — and
the store's own suite pins that with the machine-token gate switched on. What keeps it honest is
immutability. The commissioned pair is a build-secret pair for resolving dependencies inside an image
build, not an HTTP credential. When machine auth arrives it arrives for every surface at once, and
because the HTTP lives here it becomes **one release of this binary** rather than a sweep of forty
pipelines. That is the whole reason for the placement.

While a deployment's qits-ci does not yet inject `QITS_ARTIFACTS_URL`, the store root is derived from
`QITS_NPM_REGISTRY_URL` or `QITS_MAVEN_REGISTRY_URL` **with a WARN**. One derivation in one binary is
the point; it goes when every deployment carries the variable.

## Building

    ./mvnw verify                          # the gate: compile + the whole suite, JVM mode
    ./mvnw package -Dnative -DskipTests    # the binary, on a GraalVM (target/qits-publish)

The shipping form is a **fully static musl** GraalVM native image, because a glibc-linked binary does
not execute at all on an alpine-family image and the step images this has to run in are
alpine-family. `docker/Dockerfile` is how CI builds it — it compiles inside the musl toolchain image
as a build stage and ends on a `FROM scratch` holding the one file, so both pipelines are
`buildctl build --opt target=binary --output type=local,dest=out` and the binary comes back as
`out/qits-publish`. A second stage exports the CycloneDX document, so a release's SBOM is a cache hit
rather than a second compile.

Checking a build: `file` reports `static-pie linked`, **not** the literal `statically linked` —
GraalVM emits a position-independent static executable. Assert on `ldd`, which the build already
does.

The toolchain image (`graalvmce-musl-builder:jdk-25`) is **not built here**. qits-ci-daemon authors
it, and its pipelines push the tag on every fold and every release; copying that Dockerfile here would
give the platform two spellings of one toolchain and two pipelines racing to overwrite one fixed tag.

## Its own release

`.config/qits/ci-event-release.yml` publishes the binary to `/artifacts/daemons/qits-artifacts-cli/<version>`
through the existing daemon-binary train, and **it makes that publish with the binary it just built**.
That is the one deliberate difference from qits-ci-daemon's release file, which spells the same PUT as
curl and a hand-written status `case`: restating the policy in shell would be a second opinion about
the rule this repository exists to own, and running the artifact is the only smoke test that can say
it executes statically on an alpine image and completes a real conversation with a real store. There
is no fallback to curl, deliberately — a publish client that cannot publish must not be published.

The two `.config/qits/ci-event-*.yml` files are legacy-shaped on purpose and become a one-line
`.config/qits/release.yml` naming the `cli` archetype in the fleet sweep. They stay hand-written until
then for the same reason the engine ships before the repositories migrate: the thing that publishes
the publisher must not depend on the publisher being published.

## Layout

Single module, no framework, and no main-scope dependency at all. JSON is read and written by
`Json`, HTTP is `java.net.http`, hashing is `MessageDigest`. `Main` is the only class that knows
there is a JVM to exit — everything else is `Cli.run(argv)` returning an integer, which is what the
suite drives against a real `com.sun.net.httpserver` on an ephemeral port.

| Class | What |
|---|---|
| `Cli` / `Args` | The argv surface, and the refusal of anything it did not ask for. |
| `Store` | Roots from environment, coordinates to URLs, and the charset guard on the way in. |
| `Publisher` | The three publishes, the existence probe, and **the policy**. |
| `Npm` | `plan`, `dist-tag`, `rewrite-lockfile-origin`. |
| `DockerfileSbom` | `FROM` lines to CycloneDX 1.6. |
| `Http` / `Json` / `Sha256` / `VersionOrder` | The wire, the documents, the digest, the replay comparator. |
