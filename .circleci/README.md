# CircleCI Setup for StreamBridge

## Pipeline overview

```
test  →  build-images  →  push-images (main branch only)
```

| Job             | Triggers on    | What it does                                              |
|-----------------|----------------|-----------------------------------------------------------|
| `test`          | every push     | Compile, generate proto stubs, run all JUnit 5 tests      |
| `build-images`  | after `test`   | Build both Docker images, verify they exist               |
| `push-images`   | `main` only    | Push both images to Docker Hub with SHA + `latest` tags   |

---

## One-time setup

### 1. Connect the repository

Go to [app.circleci.com](https://app.circleci.com), click **Projects**, find
your repo, and click **Set Up Project**. Select "use existing config" and point
it at `.circleci/config.yml`.

### 2. Create a Docker Hub access token

1. Log in to [hub.docker.com](https://hub.docker.com).
2. Go to **Account Settings → Security → New Access Token**.
3. Give it a name like `circleci-streambridge` and **Read & Write** permissions.
4. Copy the token — it is shown only once.

Using an access token instead of your login password limits the blast radius if
credentials are ever leaked: you can revoke the token without changing your
password.

### 3. Add environment variables in CircleCI

Go to **Project Settings → Environment Variables** and add:

| Name              | Value                                      |
|-------------------|--------------------------------------------|
| `DOCKER_USERNAME` | Your Docker Hub username (e.g. `johndoe`)  |
| `DOCKER_PASSWORD` | The access token you just created          |

Never commit these values to the repository.

---

## How the pipeline works

### `test` job — services sidecar

The `jvm` executor declares a **PostgreSQL sidecar** alongside the primary
OpenJDK container. A sidecar is a second Docker container that shares the same
private network as the primary container — it is reachable at `localhost:5432`,
just like a local process. There is no service-name lookup; the port is the
only identifier. This is different from `docker-compose` where containers talk
via service names on a named bridge network.

The postgres sidecar is available for integration tests that need a real
database. The unit tests run against SQLite by default (no env var set), so the
sidecar sits idle unless you add integration tests that set `DB_TYPE=postgres`.

### `build-images` and `push-images` — `setup_remote_docker`

These jobs cannot run `docker build` inside the primary container because
Docker-in-Docker requires a privileged container (a security risk). Instead,
CircleCI spins a **separate Docker daemon** and connects this job to it via the
Docker socket — that is what `setup_remote_docker` does.

The remote daemon supports `docker_layer_caching: true`, which preserves image
layers between pipeline runs. Unchanged layers (e.g. the dependency-download
layer) are reused, cutting rebuild time significantly after the first run.

The remote Docker daemon is **not shared between jobs**. The `build-images` job
verifies images exist, but `push-images` rebuilds them from scratch (layer cache
makes this fast). Alternatively, you could `docker save` / `docker load`
artefacts via workspace persistence, but rebuilding is simpler and the cache
handles the speed.

---

## Image tags

Every image pushed by `push-images` gets two tags:

| Tag                                         | Meaning                                     |
|---------------------------------------------|---------------------------------------------|
| `your-username/transform-service:<git-sha>` | Immutable — points to the exact commit       |
| `your-username/transform-service:latest`    | Mutable — always the newest main build       |

Use the SHA tag in production deployments so you always know exactly what is
running. Use `latest` for quick local testing (`docker pull` then `docker run`).

---

## Switching the build tool

The `transform-service` Dockerfile accepts a `BUILD_TOOL` build argument
(`gradle` or `maven`). The CI pipeline defaults to `gradle`. To test the Maven
path, edit the `docker build` command in `build-images`:

```yaml
- run:
    name: Build transform-service image (Maven path)
    command: |
      docker build \
        --build-arg BUILD_TOOL=maven \
        -f transform-service/Dockerfile \
        -t streambridge/transform-service:${CIRCLE_SHA1} \
        .
```

Or run it locally with `make docker-build-maven`.

---

## Running locally with the CircleCI CLI

Install the CLI: https://circleci.com/docs/local-cli/

```bash
# Run a single job locally (Docker must be running)
circleci local execute --job test
```

Local execution does not support `setup_remote_docker`, so `build-images` and
`push-images` cannot run locally. Use `make docker-build` for those instead.
