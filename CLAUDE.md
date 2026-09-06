# qits-artifacts-cli — working notes

Read `README.md` first: it defines what `qits-publish` is for, the command surface, the environment
contract and **the one idempotency policy**. This file is the working conventions on top of it.

## The three rules that shape everything

**A clone of this repo alone builds and tests green.** No monorepo, no docker, no prior `mvn install`
elsewhere, no credentials, no network. `./mvnw verify` is the gate. That is why the suite stubs the
store with `com.sun.net.httpserver` rather than reaching for a container, and why nothing in it shells
`docker`, `npm` or `git`.

**It compiles to a fully static musl GraalVM native image.** Every dependency is a decision about
image size and about whether it links anything glibc-only, because a glibc-linked binary dies on every
alpine-family image and the step images this has to run in are alpine-family. **Main scope has no
dependency at all today, and that is the state to defend.** Before adding one, check whether the JDK
already does the job: `java.net.http` instead of a client library, `Json` here instead of Jackson,
`MessageDigest` instead of a hashing library. A JSON binder would earn nothing against six response
shapes from one service and would cost a reflection-configured image.

**No framework, and that is the difference from the sibling daemons.** They are Quarkus command-mode
apps because they resolve configuration, hold a lifecycle and want CDI to wire it. This program has
none of that: argv in, HTTP out, an exit code back, and the process ends. `Main` is three lines and
`Cli.run` is a function — which is what lets the suite exercise the whole surface, exit code included,
with no subprocess and no container.

## The policy is the product

Everything else here is plumbing around one rule, stated once in `Publisher`'s javadoc and nowhere
else. Two consequences worth stating plainly:

- **A command must not grow its own idempotency behaviour.** If a new surface answers an occupied
  coordinate in a fourth way, the answer is a new arm of the same four-case rule, argued in
  `Publisher`, not a special case at a call site. The fleet's two contradictory 409 policies are why
  this repository exists.
- **No `--force`, no `--skip-if-exists`, no `--allow-overwrite`.** Every one of those is a way to make
  a coordinate mean different bytes, which is the one thing the policy forbids. A flag that takes no
  value is the shape to be suspicious of; there are none, on purpose.

When bytes genuinely cannot be compared, **say so**. `Console.warn` exists for exactly that, and a
degradation that prints nothing is worse than one that fails — it is a verification that never
happened, reported as a verification.

## What lives here and what does not

`qits-publish` owns **direct HTTP to qits-artifacts and the policy around it**. It does not run
ecosystem tools: `npm publish`, `mvn deploy` and `buildctl push` stay in the wrapper's archetype
scripts, where the tool's own configuration already lives. The line is not stylistic — the moment this
binary shells `npm`, it needs node in every image that runs it, and being needed by every image is the
reason it is static and dependency-free.

It also does not know what a release is. There is no `release` command, no orchestration, no ordering
between the publishes: qits-ci's composer decides what to call and in what order. This binary answers
one question per invocation.

## Testing

Plain JUnit 5. **No Mockito, no framework test runner** — neither is used anywhere here and neither
should start being. Fakes are anonymous classes or nested records; the store's stub is a real HTTP
server on an ephemeral port, because the thing under test *is* the HTTP conversation and a fake client
would only prove the fake agrees with itself.

Test names are sentences describing the behaviour, not the method
(`anOccupiedCoordinateHoldingDifferentBytesFailsNamingBothDigests`).

**The idempotency matrix is the regression net.** Every publish surface carries the same four cases —
absent, occupied-identical, occupied-different, and the store failing — and a new surface arrives with
all four or it does not arrive.

**The Dockerfile fixtures are copies of real files**, from qits-database-oci, qits-build-images-oci
and qits-ci-daemon, because the shell this generator replaces was written against exactly those and
its output is the specification. `edges.Dockerfile` is the only invented one and holds the shapes that
shell handled by hand. If a fixture is refreshed, refresh it from the real file rather than editing
the copy.

## Adding a command

1. A method on `Publisher` or `Npm` that returns an exit code and throws `CliException` for anything
   else. Nothing below `Main` calls `System.exit`.
2. An arm in `Cli`, reading its flags through `Args` and ending with `args.done(…)` so an unknown flag
   is refused rather than ignored.
3. A URL builder on `Store` if it addresses a new route — never a string concatenation at a call site,
   and never one that skips the charset guard. Values arrive from a repository's pipeline
   configuration, which is not this program's to trust.
4. The usage block in `Cli.USAGE`, the command table in `README.md`.
5. Tests: the argv shape (missing flag, unknown flag), and — if it publishes — all four policy cases.

## Untrusted input

Everything this program is handed comes from a repository's own tree or its pipeline configuration:
an image reference read out of a Dockerfile, an artifact name from `artifacts:`, a metadata value from
a step's environment. None of it is trusted.

- **Values crossing into a URL path** go through `Store`'s charset guard, which is the same one the
  shell it replaces spelled as a `case` pattern. A name carrying `..` or `?` would address a
  coordinate nobody declared.
- **Values crossing into JSON** go through `Json.quote`, for the reason the shell sent every value
  through `%s`: a reference must never be able to end the string it is written into. There is a test
  that a `FROM` line carrying `"},{"` is refused.
- **Values crossing into a header** are checked for control characters before the request is built. A
  CR in a `--meta` value is header injection.
- **A response body is data.** A malformed one is a refusal naming the offset, never a silent empty
  map — because "said nothing" is how the policy spells "could not verify", and a truncated body must
  not be able to say it.

## Formatting

`google-java-format`, 100 columns, two-space indent. Javadoc explains *why* — the tradeoff, the
alternative rejected, the failure it prevents. What the code does is the code's job.
