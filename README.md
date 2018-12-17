# Overview
This is the [IBM UrbanCode Deploy](https://developer.ibm.com/urbancode/products/urbancode-deploy/) plugin for Jenkins Pipeline (Jenkins 2.0). This plugin is also referred to as the Build Steps plugin since you are able to interact with UrbanCode Deploy via a job step in Jenkins versus a post-processing action. The plugin allows you to upload component versions, create snapshots, and run processes among other things.

More information about this plugin is available [here](https://developer.ibm.com/urbancode/plugin/jenkins-2-0/) and [here](https://developer.ibm.com/urbancode/plugindoc/ibmucd/jenkins-pipeline-formerly-jenkins-2-0/).

## Installation
The compiled plugin is available for download on the [IBM UrbanCode website](https://developer.ibm.com/urbancode/plugin/jenkins-2-0/). Download the plugin from our website if you wish to skip the manual build step. No special steps are required for installation. Otherwise, clone this repository and run the `ant` command in the top level folder. This should compile the code and create a .hpi file within the /dist folder. Use this command if you wish to make local changes to the plugin. The build process will automatically install Apache Ivy if it is not previously installed.

### Support
Plug-ins downloaded directly from the [IBM UrbanCode Plug-ins microsite](https://developer.ibm.com/urbancode/plugins) are fully supported by IBM. Create a GitHub Issue or Pull Request for minor requests and bug fixes. For time sensitive issues that require immediate assistance, [file a PMR](https://www-947.ibm.com/support/servicerequest/newServiceRequest.action) through the normal IBM support channels. Plug-ins built externally or modified with custom code are supported on a best-effort-basis using GitHub Issues.

### Locally Build the Plug-in
This open source plug-in uses Gradle as its build tool. [Install the latest version of Gradle](https://gradle.org/install) to build the plug-in locally. Build the plug-in by running the `gradle jpi` command in the plug-in's root directory. The plug-in distributable will be placed under the `build/libs` folder.

## Pipeline Examples
Full explanation of these Pipeline syntax examples can be found on our [Jenkins Pipeline Syntax](https://developer.ibm.com/urbancode/plugindoc/ibmucd/jenkins-pipeline-formerly-jenkins-2-0/2-2/jenkins-pipeline-syntax-overview/) documentation.

### Create Component Version
```groovy
node {
   step([$class: 'UCDeployPublisher',
        siteName: 'local',
        component: [
            $class: 'com.urbancode.jenkins.plugins.ucdeploy.VersionHelper$VersionBlock',
            componentName: 'Jenkins',
            createComponent: [
                $class: 'com.urbancode.jenkins.plugins.ucdeploy.ComponentHelper$CreateComponentBlock',
                componentTemplate: '',
                componentApplication: 'Jenkins'
            ],
            delivery: [
                $class: 'com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper$Push',
                pushVersion: '${BUILD_NUMBER}',
                baseDir: 'jobs\\test-ucd\\workspace\\build\\distributions',
                fileIncludePatterns: '*.zip',
                fileExcludePatterns: '',
                pushProperties: 'jenkins.server=Local\njenkins.reviewed=false',
                pushDescription: 'Pushed from Jenkins'
            ]
        ]
    ])
}
```

### Deploy Component
```groovy
node {
   step([$class: 'UCDeployPublisher',
        siteName: 'local',
        component: [
            $class: 'com.urbancode.jenkins.plugins.ucdeploy.VersionHelper$VersionBlock',
            componentName: 'Jenkins'
        ],
        deploy: [
            $class: 'com.urbancode.jenkins.plugins.ucdeploy.DeployHelper$DeployBlock',
            deployApp: 'Jenkins',
            deployEnv: 'Test',
            deployProc: 'Deploy Jenkins',
            createProcess: [
                $class: 'com.urbancode.jenkins.plugins.ucdeploy.ProcessHelper$CreateProcessBlock',
                processComponent: 'Deploy'
            ],
            deployVersions: 'Jenkins:${BUILD_NUMBER}',
            deployOnlyChanged: false
        ]
    ])
}
```

### Trigger Version Import
```groovy
node {
   step([$class: 'UCDeployPublisher',
        siteName: 'local',
        component: [
            $class: 'com.urbancode.jenkins.plugins.ucdeploy.VersionHelper$VersionBlock',
            componentName: 'Jenkins',
            createComponent: [
                $class: 'com.urbancode.jenkins.plugins.ucdeploy.ComponentHelper$CreateComponentBlock',
                componentTemplate: '',
                componentApplication: 'Local'
            ],
            delivery: [
                $class: 'com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper$Pull',
                pullProperties: 'FileSystemImportProperties/name=${BUILD_NUMBER}\nFileSystemImportProperties/description=Pushed from Jenkins',
                pullSourceType: 'File System',
                pullSourceProperties: 'FileSystemComponentProperties/basePath=C:\\Test',
                pullIncremental: false
            ]
        ]
    ])
}
```

## Release Notes
### Version 2.10
Added functionality to preemptively create environment snapshot to use during deployment.

### Version 2.9
Fixed 401 http error when setting version properties by using latest uDeployRestClient.

### Version 2.8
Fixed all Null pointer and bad casting exceptions returned in Jenkins system logs upon saving a job.
Removed administrative checkbox from global and job configuration of user credentials.

### Version 2.7
Fixed APAR PI91900 - Unfilled application process properties unable to be checked with a snapshot.
### Version 2.6
Fixed APAR PI85407 - Importing component versions no longer fails when runtime properties aren't provided.

### Version 2.5
RFE 104275 - Support for Description and Application Request Properties on deployments.

### Version 2.4
Fixed APAR PI80038 - Snapshot names no longer require a leading equals sign.

### Version 2.3
Fixed APAR PI77548 - Component process properties failing to resolve on deployment.

### Version 2.2
RFE 98375 - Jenkins Plugin only allows Global credentials instead of job-based credentials.

Fixed PI75045 - UCD server maintenance mode check requires admin privileges.

### Version 2.1
Fixed PI61971 - Connection pool leak in Jenkins ibm-ucdeploy-build-steps.

### Older Versions
Fixed PI32899 - Jenkins plugin fails on slave nodes with an UnserializbleException

Fixed PI36005 - Jenkins plugin 1.2.1 not compatible with builds created with earlier versions of the plugin

Fixed PI37957 - Pulled in a fix for excludes options not being handled by a common library.
