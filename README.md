# hobom-jenkins-library

Jenkins Shared Library for the HoBom project. Provides a Docker-based build-deploy pipeline in a single function call.

## Usage

### Jenkinsfile

```groovy
@Library('hobom-jenkins-library') _

hobomPipeline(
    serviceName:    'my-service',
    hostPort:       '8080',
    containerPort:  '3000',
    memory:         '512m',
    cpus:           '0.5',
)
```

### Parameters

#### Required

| Parameter | Description |
|---|---|
| `serviceName` | Service name (used as Docker container name and image tag) |
| `hostPort` | Port to bind on the host |
| `containerPort` | Port inside the container |
| `memory` | Container memory limit (e.g. `512m`, `1g`) |
| `cpus` | Container CPU limit (e.g. `0.5`, `1`) |

#### Optional

| Parameter | Default | Description |
|---|---|---|
| `envPath` | `null` | Path to env file on the deploy server. Passed via `--env-file` |
| `addHost` | `false` | Add `host.docker.internal` host mapping |
| `submodules` | `false` | Initialize Git submodules on checkout |
| `smokeCheckPath` | `null` | Health check path after deploy (e.g. `/health`) |
| `preBuild` | `null` | Closure to execute before the build stage |
| `extraPorts` | `[]` | Additional port mappings (e.g. `['9090:9090']`) |
| `buildEnvCredId` | `null` | Jenkins file credential ID to copy as `.env` during build |
| `buildEnvPath` | `null` | File path to copy as `.env` during build (`buildEnvCredId` takes precedence) |
| `dockerfilePath` | `.` | Directory containing the Dockerfile (for monorepo support) |

### Full Example

```groovy
@Library('hobom-jenkins-library') _

hobomPipeline(
    serviceName:    'api-server',
    hostPort:       '8080',
    containerPort:  '3000',
    memory:         '1g',
    cpus:           '1',
    envPath:        '/home/infra-admin/envs/api-server.env',
    addHost:        true,
    submodules:     true,
    smokeCheckPath: '/health',
    extraPorts:     ['9229:9229'],
    buildEnvCredId: 'api-build-env',
    dockerfilePath: 'apps/api',
    preBuild:       { sh 'npm ci && npm run generate' },
)
```

## Pipeline Stages

```
Checkout → Pre-Build → Build & Push → Deploy → Smoke Check
```

1. **Checkout** - Source checkout (initializes submodules if enabled)
2. **Pre-Build** - Runs `preBuild` closure (only when configured)
3. **Build & Push** - Builds Docker image and pushes to Docker Hub
4. **Deploy** - Connects to deploy server via SSH and replaces the container (`develop` and `main` branches only)
5. **Smoke Check** - Health check after deploy (only when `smokeCheckPath` is set)

## Project Structure

```
vars/
  hobomPipeline.groovy   # Pipeline definition
```
