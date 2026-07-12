def call(Map config) {
    // ── Required parameters ──
    def serviceName    = config.serviceName
    def hostPort       = config.hostPort
    def containerPort  = config.containerPort
    def memory         = config.memory
    def cpus           = config.cpus

    // ── Optional parameters ──
    def envPath        = config.get('envPath')
    def addHost        = config.get('addHost', false)
    def submodules     = config.get('submodules', false)
    def smokeCheckPath = config.get('smokeCheckPath')
    def preBuild       = config.get('preBuild')
    def extraPorts     = config.get('extraPorts', [])
    def extraVolumes   = config.get('extraVolumes', [])
    def buildEnvCredId = config.get('buildEnvCredId')
    def buildEnvPath   = config.get('buildEnvPath')
    def dockerfilePath = config.get('dockerfilePath', '.')

    // ── Live environment overrides ──
    def liveHostPort   = config.get('liveHostPort')
    def liveEnvPath    = config.get('liveEnvPath')
    def liveExtraPorts = config.get('liveExtraPorts', [])
    def livePreBuild   = config.get('livePreBuild')

    // ── Constants (hard-coded) ──
    def REGISTRY      = 'docker.io'
    def IMAGE_REPO    = 'jjockrod/hobom-system'
    def REGISTRY_CRED = 'dockerhub-cred'
    def READ_CRED_ID  = 'dockerhub-readonly'
    def DEPLOY_HOST   = 'ishisha.iptime.org'
    def DEPLOY_PORT   = '22223'
    def DEPLOY_USER   = 'infra-admin'
    def SSH_CRED_ID   = 'deploy-ssh-key'

    pipeline {
        agent any

        options {
            timestamps()
            disableConcurrentBuilds()
        }

        stages {

            stage('Checkout') {
                steps {
                    checkout scm
                    script {
                        if (submodules) {
                            sh '''
                                set -eux
                                git config --global --add safe.directory "$WORKSPACE" || true
                                git submodule sync --recursive
                                git submodule update --init --recursive
                            '''
                        }
                    }
                }
            }

            stage('Pre-Build') {
                when { expression { preBuild != null || livePreBuild != null } }
                steps {
                    script {
                        if (env.BRANCH_NAME == 'main' && livePreBuild != null) {
                            livePreBuild()
                        } else if (preBuild != null) {
                            preBuild()
                        }
                    }
                }
            }

            stage('Build & Push') {
                steps {
                    script {
                        def isLive = (env.BRANCH_NAME == 'main' && liveHostPort != null)
                        def effectiveContainer = isLive ? serviceName.replaceFirst('^dev-', 'live-') : serviceName

                        def imageTag    = "${REGISTRY}/${IMAGE_REPO}:${effectiveContainer}-${env.BUILD_NUMBER}"
                        def imageLatest = "${REGISTRY}/${IMAGE_REPO}:${effectiveContainer}-latest"

                        env.EFFECTIVE_CONTAINER = effectiveContainer
                        env.EFFECTIVE_IMAGE     = imageLatest

                        def doBuild = {
                            withCredentials([usernamePassword(
                                credentialsId: REGISTRY_CRED,
                                usernameVariable: 'REG_USER',
                                passwordVariable: 'REG_PASS'
                            )]) {
                                sh """
                                    set -eu
                                    export DOCKER_BUILDKIT=1
                                    set +x
                                    echo "\$REG_PASS" | docker login "${REGISTRY}" -u "\$REG_USER" --password-stdin
                                    set -x
                                    docker build -t "${imageTag}" -t "${imageLatest}" -f "${dockerfilePath}/Dockerfile" .
                                    docker push "${imageTag}"
                                    docker push "${imageLatest}"
                                """
                            }
                        }

                        if (buildEnvCredId) {
                            withCredentials([file(credentialsId: buildEnvCredId, variable: 'BUILD_ENV_FILE')]) {
                                sh 'cp "$BUILD_ENV_FILE" .env'
                                doBuild()
                                sh 'rm -f .env'
                            }
                        } else if (buildEnvPath) {
                            sh "cp '${buildEnvPath}' .env"
                            doBuild()
                            sh 'rm -f .env'
                        } else {
                            doBuild()
                        }
                    }
                }
            }

            stage('Deploy') {
                when { anyOf { branch 'develop'; branch 'main' } }
                steps {
                    script {
                        def isLive = (env.BRANCH_NAME == 'main' && liveHostPort != null)

                        def effectiveContainer  = env.EFFECTIVE_CONTAINER ?: serviceName
                        def effectiveHostPort   = isLive ? liveHostPort : hostPort
                        def effectiveEnvPath    = isLive ? (liveEnvPath ?: envPath) : envPath
                        def effectiveNetwork    = isLive ? 'hobom-live-net' : 'hobom-net'
                        def effectiveExtraPorts = isLive ? liveExtraPorts : extraPorts
                        def imageLatest         = env.EFFECTIVE_IMAGE ?: "${REGISTRY}/${IMAGE_REPO}:${serviceName}-latest"

                        def envFileFlag = effectiveEnvPath ? "--env-file \"${effectiveEnvPath}\"" : ''
                        def addHostFlag = addHost ? '--add-host=host.docker.internal:host-gateway' : ''
                        def extraPortsFlag = effectiveExtraPorts.collect { "-p \"127.0.0.1:${it}\"" }.join(' \\\n  ')
                        def extraVolumesFlag = extraVolumes.collect { "-v \"${it}\"" }.join(' \\\n  ')
                        def envCheck = effectiveEnvPath ? """
if [ ! -f "${effectiveEnvPath}" ]; then
  echo "[REMOTE][ERROR] ${effectiveEnvPath} not found."
  exit 1
fi""" : ''

                        sshagent(credentials: [SSH_CRED_ID]) {
                            withCredentials([usernamePassword(
                                credentialsId: READ_CRED_ID,
                                usernameVariable: 'PULL_USER',
                                passwordVariable: 'PULL_PASS'
                            )]) {
                                sh """
set -eux
ssh -o StrictHostKeyChecking=no -p "${DEPLOY_PORT}" "${DEPLOY_USER}@${DEPLOY_HOST}" \\
  IMAGE="${imageLatest}" \\
  CONTAINER="${effectiveContainer}" \\
  NETWORK="${effectiveNetwork}" \\
  PULL_USER="\$PULL_USER" \\
  PULL_PASS="\$PULL_PASS" \\
  bash -s <<'EOS'
set -euo pipefail
echo "[REMOTE] Deploying \$CONTAINER with image \$IMAGE on network \$NETWORK"

if ! command -v docker >/dev/null 2>&1; then
  echo "[REMOTE][ERROR] docker not found."
  exit 1
fi

echo "\$PULL_PASS" | docker login docker.io -u "\$PULL_USER" --password-stdin
${envCheck}

docker pull "\$IMAGE" || (echo "[REMOTE][ERROR] docker pull failed" && exit 1)

if docker ps -a --format '{{.Names}}' | grep -w "\$CONTAINER" >/dev/null 2>&1; then
  docker stop "\$CONTAINER" || true
  docker rm "\$CONTAINER" || true
fi

docker network create \$NETWORK || true
docker run -d --name "\$CONTAINER" \\
  --network \$NETWORK \\
  --restart unless-stopped \\
  --memory=${memory} --cpus=${cpus} \\
  ${envFileFlag} \\
  ${addHostFlag} \\
  -p "127.0.0.1:${effectiveHostPort}:${containerPort}" \\
  ${extraPortsFlag} \\
  ${extraVolumesFlag} \\
  "\$IMAGE"

docker ps --filter "name=\$CONTAINER" --format "table {{.Names}}\\t{{.Image}}\\t{{.Status}}\\t{{.Ports}}"
EOS
"""
                            }
                        }
                    }
                }
            }

            stage('Smoke Check') {
                when {
                    allOf {
                        anyOf { branch 'develop'; branch 'main' }
                        expression { smokeCheckPath != null }
                    }
                }
                steps {
                    script {
                        def isLive = (env.BRANCH_NAME == 'main' && liveHostPort != null)
                        def effectiveHostPort = isLive ? liveHostPort : hostPort

                        sshagent(credentials: [SSH_CRED_ID]) {
                            sh """
                                ssh -o StrictHostKeyChecking=no -p ${DEPLOY_PORT} ${DEPLOY_USER}@${DEPLOY_HOST} '
                                    curl -fsS http://localhost:${effectiveHostPort}${smokeCheckPath} || true
                                '
                            """
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "Build #${env.BUILD_NUMBER} - ${env.EFFECTIVE_CONTAINER ?: serviceName} deployed on ${DEPLOY_HOST}"
            }
            failure {
                echo "Build failed (${env.BRANCH_NAME})"
            }
        }
    }
}
