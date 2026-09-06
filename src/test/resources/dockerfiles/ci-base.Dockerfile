# buildctl comes out of the SAME pinned image the platform's buildkitd runs (qits-containers'
# qits.containers.buildkit.image), copied rather than apk-installed so client and daemon move in one
# campaign: bump the tag here and there together. A stage + COPY --from instead of a curl to
# github.com because an image pull rides the registry mirror and a tarball download rides nothing —
# the offline posture (README, "the platform's own mirror") only covers pulls. The tag is a LITERAL
# on purpose: the release pipeline's SBOM generator reads FROM lines with sed and fails the release
# on an ARG-parameterized one.
FROM moby/buildkit:v0.33.0 AS buildkit

FROM docker:cli

RUN apk add --no-cache bash curl git jq

# The client for the platform-owned buildkitd. A converted recipe reads $BUILDKIT_HOST and calls
# this instead of the docker CLI beside it; the docker CLI stays until the last recipe converts.
COPY --from=buildkit /usr/bin/buildctl /usr/local/bin/buildctl
