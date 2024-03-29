// Copyright 2017-present Open Networking Foundation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// voltha-2.x e2e tests
// uses kind-voltha to deploy voltha-2.X
// uses bbsim to simulate OLT/ONUs

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 90, unit: 'MINUTES')
  }

  stages {

    stage('Repo') {
      steps {
        checkout(changelog: false, \
          poll: false,
          scm: [$class: 'RepoScm', \
            manifestRepositoryUrl: "${params.manifestUrl}", \
            manifestBranch: "${params.manifestBranch}", \
            currentBranch: true, \
            destinationDir: 'voltha', \
            forceSync: true,
            resetFirst: true, \
            quiet: true, \
            jobs: 4, \
            showAllChanges: true] \
          )
      }
    }
    stage('Patch') {
      steps {
        sh """
           pushd voltha
           PROJECT_PATH=\$(xmllint --xpath "string(//project[@name=\\\"${gerritProject}\\\"]/@path)" .repo/manifest.xml)
           repo download "\$PROJECT_PATH" "${gerritChangeNumber}/${gerritPatchsetNumber}"
           popd
           """
      }
    }
    stage('Create K8s Cluster') {
      steps {
        sh """
           git clone https://github.com/ciena/kind-voltha.git
           cd kind-voltha/
           DEPLOY_K8S=y JUST_K8S=y FANCY=0 ./voltha up
           """
      }
    }

    stage('Build Images') {
      steps {
        sh """
           if ! [[ "${gerritProject}" =~ ^(voltha-helm-charts|voltha-system-tests)\$ ]]; then
             cd $WORKSPACE/voltha/${gerritProject}/
             make DOCKER_REPOSITORY=voltha/ DOCKER_TAG=citest docker-build
           fi
           """
      }
    }

    stage('Push Images') {
      steps {
        sh '''
           if ! [[ "${gerritProject}" =~ ^(voltha-helm-charts|voltha-system-tests)\$ ]]; then
             export GOROOT=/usr/local/go
             export GOPATH=\$(pwd)
             export TYPE=minimal
             export KUBECONFIG="$(./bin/kind get kubeconfig-path --name="voltha-minimal")"
             export VOLTCONFIG="/home/jenkins/.volt/config-minimal"
             export PATH=/w/workspace/${gerritProject}_sanity-system-test/kind-voltha/bin:$PATH
             docker images | grep citest
             for image in \$(docker images -f "reference=*/*citest" --format "{{.Repository}}"); do echo "Pushing \$image to nodes"; kind load docker-image \$image:citest --name voltha-\$TYPE --nodes voltha-\$TYPE-worker,voltha-\$TYPE-worker2; done
           fi
           '''
      }
    }
    stage('Deploy Voltha') {
      steps {
        sh '''
           HELM_FLAG="${extraHelmFlags} "

           if [ "${gerritProject}" = "voltha-go" ]; then
             HELM_FLAG+="-f $WORKSPACE/voltha/voltha-system-tests/tests/data/ci-test.yaml "
           fi

           if [ "${gerritProject}" = "voltha-openolt-adapter" ]; then
             HELM_FLAG+="--set images.adapter_open_olt.tag=citest,images.adapter_open_olt.pullPolicy=Never "
           fi

           if [ "${gerritProject}" = "voltha-openonu-adapter" ]; then
             HELM_FLAG+="--set images.adapter_open_onu.tag=citest,images.adapter_open_onu.pullPolicy=Never "
           fi

           if [ "${gerritProject}" = "bbsim" ]; then
             HELM_FLAG+="--set images.bbsim.tag=citest,images.bbsim.pullPolicy=Never "
           fi

           if [ "${gerritProject}" = "voltha-api-server" ]; then
             HELM_FLAG+="--set images.afrouter.tag=citest,images.afrouter.pullPolicy=Never,images.afrouterd.tag=citest,images.afrouterd.pullPolicy=Never "
           else
             # afrouter only has master branch at present
             HELM_FLAG+="--set images.afrouter.tag=master,images.afrouterd.tag=master "
           fi

           if [ "${gerritProject}" = "voltha-helm-charts" ]; then
             export CHART_PATH=$WORKSPACE/voltha/voltha-helm-charts
             export VOLTHA_CHART=\$CHART_PATH/voltha
             export VOLTHA_ADAPTER_OPEN_OLT_CHART=\$CHART_PATH/voltha-adapter-openolt
             export VOLTHA_ADAPTER_OPEN_ONU_CHART=\$CHART_PATH/voltha-adapter-openonu
             helm dep update \$VOLTHA_CHART
             helm dep update \$VOLTHA_ADAPTER_OPEN_OLT_CHART
             helm dep update \$VOLTHA_ADAPTER_OPEN_ONU_CHART
           fi

           cd $WORKSPACE/kind-voltha/
           echo \$HELM_FLAG
           EXTRA_HELM_FLAGS=\$HELM_FLAG VOLTHA_LOG_LEVEL=DEBUG TYPE=minimal WITH_RADIUS=y WITH_BBSIM=y INSTALL_ONOS_APPS=y CONFIG_SADIS=y FANCY=0 WITH_SIM_ADAPTERS=n ./voltha up
           '''
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''
           cd kind-voltha/
           export KUBECONFIG="$(./bin/kind get kubeconfig-path --name="voltha-minimal")"
           export VOLTCONFIG="/home/jenkins/.volt/config-minimal"
           export PATH=/w/workspace/${gerritProject}_sanity-system-test/kind-voltha/bin:$PATH
           make -C $WORKSPACE/voltha/voltha-system-tests sanity-kind || true
           '''
      }
    }
  }

  post {
    always {
      sh '''
         set +e
         # copy robot logs
         if [ -d RobotLogs ]; then rm -r RobotLogs; fi; mkdir RobotLogs
         cp -r $WORKSPACE/voltha/voltha-system-tests/tests/*/*.html ./RobotLogs || true
         cp -r $WORKSPACE/voltha/voltha-system-tests/tests/*/*.xml ./RobotLogs || true
         cd kind-voltha/
         cp install-minimal.log $WORKSPACE/
         export KUBECONFIG="$(./bin/kind get kubeconfig-path --name="voltha-minimal")"
         export VOLTCONFIG="/home/jenkins/.volt/config-minimal"
         export PATH=/w/workspace/${gerritProject}_sanity-system-test/kind-voltha/bin:$PATH
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
         kubectl get nodes -o wide
         kubectl get pods -o wide
         kubectl get pods -n voltha -o wide
         ## get default pod logs
         for pod in \$(kubectl get pods --no-headers | awk '{print \$1}');
         do
           if [[ \$pod == *"onos"* && \$pod != *"onos-service"* ]]; then
             kubectl logs \$pod onos> $WORKSPACE/\$pod.log;
           else
             kubectl logs \$pod> $WORKSPACE/\$pod.log;
           fi
         done
         ## get voltha pod logs
         for pod in \$(kubectl get pods --no-headers -n voltha | awk '{print \$1}');
         do
           if [[ \$pod == *"-api-"* ]]; then
             kubectl logs \$pod arouter -n voltha > $WORKSPACE/\$pod.log;
           elif [[ \$pod == "bbsim-"* ]]; then
             kubectl logs \$pod -n voltha -p > $WORKSPACE/\$pod.log;
           else
             kubectl logs \$pod -n voltha > $WORKSPACE/\$pod.log;
           fi
         done
         ## clean up node
	 FANCY=0 WAIT_ON_DOWN=y ./voltha down
	 cd $WORKSPACE/
	 rm -rf kind-voltha/ voltha/ || true
         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/output*.xml',
            outputPath: '.',
            passThreshold: 80,
            reportFileName: 'RobotLogs/report*.html',
            unstableThreshold: 0]);
         archiveArtifacts artifacts: '*.log'

    }
  }
}

