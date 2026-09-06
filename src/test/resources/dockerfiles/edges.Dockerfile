# Not a real image. Every line here is a shape the two OCI repositories' hand-written shell either
# got right and must keep getting right, or refused outright and no longer has to.
#
# A digest ref, which pins harder than a tag, so the digest is the version.
FROM registry.example.com/library/alpine@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef AS pinned

# A registry host carrying a PORT and no tag. The colon is the port's, so this is `latest` — the one
# case a naive "everything after the last colon is the tag" gets wrong.
FROM host.example.com:5000/team/tool

# A --platform flag, dropped wherever it appears, and a repeated upstream: two stages built from one
# image are one dependency.
FROM --platform=linux/amd64 host.example.com:5000/team/tool AS tools

# This file's own earlier stage. A stage alias is not an upstream image.
FROM pinned AS final
COPY --from=tools /usr/bin/tool /usr/bin/tool
