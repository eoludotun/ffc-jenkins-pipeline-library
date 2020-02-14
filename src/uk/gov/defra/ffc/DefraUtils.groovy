package uk.gov.defra.ffc
def branch = ''
def pr = ''
def mergedPrNo = ''
def containerTag = ''
def repoUrl = ''
def commitSha = ''
def workspace

def getCSProjVersion(projName) {
  return sh(returnStdout: true, script: "xmllint ${projName}/${projName}.csproj --xpath '//Project/PropertyGroup/Version/text()'").trim()
}

def getCSProjVersionMaster(projName) {
  return sh(returnStdout: true, script: "git show origin/master:${projName}/${projName}.csproj | xmllint --xpath '//Project/PropertyGroup/Version/text()' -").trim()
}

def getPackageJsonVersion() {
  return sh(returnStdout: true, script: "jq -r '.version' package.json").trim()
}

def getPackageJsonVersionMaster() {
  return sh(returnStdout: true, script: "git show origin/master:package.json | jq -r '.version'").trim()
}

def verifyCSProjVersionIncremented(projectName) {
  def masterVersion = getCSProjVersionMaster(projectName)
  def version = getCSProjVersion(projectName)
  errorOnNoVersionIncrement(masterVersion, version)
}

def verifyPackageJsonVersionIncremented() {
  def masterVersion = getPackageJsonVersionMaster()
  def version = getPackageJsonVersion()
  errorOnNoVersionIncrement(masterVersion, version)
}

def errorOnNoVersionIncrement(masterVersion, version){
  if (versionHasIncremented(masterVersion, version)) {
    echo "version increment valid '$masterVersion' -> '$version'"
  } else {
    error( "version increment invalid '$masterVersion' -> '$version'")
  }
}

def replaceInFile(from, to, file) {
  sh "sed -i -e 's/$from/$to/g' $file"
}

def getMergedPrNo() {
  def mergedPrNo = sh(returnStdout: true, script: "git log --pretty=oneline --abbrev-commit -1 | sed -n 's/.*(#\\([0-9]\\+\\)).*/\\1/p'").trim()
  return mergedPrNo ? "pr$mergedPrNo" : ''
}

def getRepoUrl() {
  return sh(returnStdout: true, script: "git config --get remote.origin.url").trim()
}

def getCommitSha() {
  return sh(returnStdout: true, script: "git rev-parse HEAD").trim()
}

def getCommitMessage() {
  return sh(returnStdout: true, script: 'git log -1 --pretty=%B | cat')
}

def verifyCommitBuildable() {
  if (pr) {
    echo "Building PR$pr"
  } else if (branch == "master") {
    echo "Building master branch"
  } else {
    currentBuild.result = 'ABORTED'
    error('Build aborted - not a PR or a master branch')
  }
}

def getVariables(repoName, version) {
    branch = BRANCH_NAME
    // use the git API to get the open PR for a branch
    // Note: This will cause issues if one branch has two open PRs
    pr = sh(returnStdout: true, script: "curl https://api.github.com/repos/DEFRA/$repoName/pulls?state=open | jq '.[] | select(.head.ref == \"$branch\") | .number'").trim()
    verifyCommitBuildable()

    if (branch == "master") {
      containerTag = version
    } else {
      def rawTag = pr == '' ? branch : "pr$pr"
      containerTag = rawTag.replaceAll(/[^a-zA-Z0-9]/, '-').toLowerCase()
    }

    mergedPrNo = getMergedPrNo()
    repoUrl = getRepoUrl()
    commitSha = getCommitSha()
    return [pr, containerTag, mergedPrNo]
}

def updateGithubCommitStatus(message, state) {
  step([
    $class: 'GitHubCommitStatusSetter',
    reposSource: [$class: "ManuallyEnteredRepositorySource", url: repoUrl],
    commitShaSource: [$class: "ManuallyEnteredShaSource", sha: commitSha],
    errorHandlers: [[$class: 'ShallowAnyErrorHandler']],
    statusResultSource: [ $class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: message, state: state]] ]
  ])
}

def setGithubStatusSuccess(message = 'Build successful') {
  updateGithubCommitStatus(message, 'SUCCESS')
}

def setGithubStatusPending(message = 'Build started') {
  updateGithubCommitStatus(message, 'PENDING')
}

def setGithubStatusFailure(message = '') {
  updateGithubCommitStatus(message, 'FAILURE')
}

def lintHelm(projectName) {
  sh "helm lint ./helm/$projectName"
}

def buildTestImage(projectName, serviceName, buildNumber) {
  sh 'docker image prune -f || echo could not prune images'
  sh "docker-compose -p $projectName-$containerTag-$buildNumber -f docker-compose.yaml -f docker-compose.test.yaml build --no-cache $serviceName"
}

def runTests(projectName, serviceName, buildNumber) {
  try {
    sh 'mkdir -p test-output'
    sh 'chmod 777 test-output'
    sh "docker-compose -p $projectName-$containerTag-$buildNumber -f docker-compose.yaml -f docker-compose.test.yaml run $serviceName"
  } finally {
    sh "docker-compose -p $projectName-$containerTag-$buildNumber -f docker-compose.yaml -f docker-compose.test.yaml down -v"
  }
}

def createTestReportJUnit(){
  junit 'test-output/junit.xml'
}

def deleteTestOutput(name) {
    // clean up files created by node/ubuntu user that cannot be deleted by jenkins. Note: uses global environment variable
    sh "[ -d \"$WORKSPACE/test-output\" ] && docker run --rm -u node --mount type=bind,source='$WORKSPACE/test-output',target=/usr/src/app/test-output $name rm -rf test-output/*"
}

def analyseCode(sonarQubeEnv, sonarScanner, params) {
  def scannerHome = tool sonarScanner
  withSonarQubeEnv(sonarQubeEnv) {
    def args = ''
    params.each { param ->
      args = args + " -D$param.key=$param.value"
    }

    sh "${scannerHome}/bin/sonar-scanner$args"
  }
}

def waitForQualityGateResult(timeoutInMinutes) {
  timeout(time: timeoutInMinutes, unit: 'MINUTES') {
    def qualityGateResult = waitForQualityGate()
    if (qualityGateResult.status != 'OK') {
      error "Pipeline aborted due to quality gate failure: ${qualityGateResult.status}"
    }
  }
}

def buildAndPushContainerImage(credentialsId, registry, projectName, tag) {
  docker.withRegistry("https://$registry", credentialsId) {
    sh "docker-compose build --no-cache"
    sh "docker tag $projectName $registry/$projectName:$tag"
    sh "docker push $registry/$projectName:$tag"
  }
}

def deployChart(credentialsId, registry, projectName, tag, extraCommands) {
  withKubeConfig([credentialsId: credentialsId]) {
    def deploymentName = "$projectName-$tag"
    sh "kubectl get namespaces $deploymentName || kubectl create namespace $deploymentName"
    sh "helm upgrade $deploymentName --install --namespace $deploymentName --atomic ./helm/$projectName --set image=$registry/$projectName:$tag $extraCommands"
  }
}

def undeployChart(credentialsId, projectName, tag) {
  def deploymentName = "$projectName-$tag"
  echo "removing deployment $deploymentName"
  withKubeConfig([credentialsId: credentialsId]) {
    sh "helm delete --purge $deploymentName || echo error removing deployment $deploymentName"
    sh "kubectl delete namespaces $deploymentName || echo error removing namespace $deploymentName"
  }
}

def publishChart(registry, projectName, containerTag) {
  withCredentials([
    string(credentialsId: 'helm-chart-repo', variable: 'helmRepo')
  ]) {
    // jenkins doesn't tidy up folder, remove old charts before running
    sh "rm -rf helm-charts"
    sshagent(credentials: ['helm-chart-creds']) {
      sh "git clone $helmRepo"
      dir('helm-charts') {
        sh 'helm init -c'
        sh "sed -i -e 's/image: $projectName/image: $registry\\/$projectName:$containerTag/' ../helm/$projectName/values.yaml"
        sh "sed -i -e 's/version:.*/version: $containerTag/' ../helm/$projectName/Chart.yaml"
        sh "helm package ../helm/$projectName"
        sh 'helm repo index .'
        sh 'git config --global user.email "buildserver@defra.gov.uk"'
        sh 'git config --global user.name "buildserver"'
        sh 'git checkout master'
        sh 'git add -A'
        sh "git commit -m 'update $projectName helm chart from build job'"
        sh 'git push'
      }
    }
  }
}

def triggerDeploy(jenkinsUrl, jobName, token, params) {
  def url = "$jenkinsUrl/job/$jobName/buildWithParameters?token=$token"
  params.each { param ->
    url = url + "\\&amp;$param.key=$param.value"
  }
  echo "Triggering deployment for $url"
  sh(script: "curl -k $url")
}

def releaseExists(containerTag, repoName, token){
    try {
      def result = sh(returnStdout: true, script: "curl -s -H 'Authorization: token $token' https://api.github.com/repos/DEFRA/$repoName/releases/tags/$containerTag | jq '.tag_name'").trim().replaceAll (/"/, '') == "$containerTag" ? true : false
      return result
    }
      catch(Exception ex) {
      echo "Failed to check release status on github"
      throw new Exception (ex)
    }
}

def triggerRelease(containerTag, repoName, releaseDescription, token){
    if (releaseExists(containerTag, repoName, token)){
      echo "Release $containerTag already exists"
      return
    }

    echo "Triggering release $containerTag for $repoName"
    boolean result = false
    result = sh(returnStdout: true, script: "curl -s -X POST -H 'Authorization: token $token' -d '{ \"tag_name\" : \"$containerTag\", \"name\" : \"Release $containerTag\", \"body\" : \" Release $releaseDescription\" }' https://api.github.com/repos/DEFRA/$repoName/releases")
    echo "The release result is $result"

    if (releaseExists(containerTag, repoName, token)){
      echo "Release Successful"
    } else {
      throw new Exception("Release failed")
    }
}

def notifySlackBuildFailure(exception, channel) {

  def msg = """BUILD FAILED
          ${JOB_NAME}/${BUILD_NUMBER}
          ${exception}
          (<${BUILD_URL}|Open>)"""

  if(branch == "master") {
    msg = '@here '.concat(msg);
    channel = "#masterbuildfailures"
  }

  slackSend channel: channel,
            color: "#ff0000",
            message: msg.replace("  ", "")
}

def versionHasIncremented(currVers, newVers) {
  try {
    currVersList = currVers.tokenize('.').collect { it.toInteger() }
    newVersList = newVers.tokenize('.').collect { it.toInteger() }
    return currVersList.size() == 3 &&
           newVersList.size() == 3 &&
           [0, 1, 2].any { newVersList[it] > currVersList[it] }
  }
  catch (Exception ex) {
    return false
  }
}

return this
