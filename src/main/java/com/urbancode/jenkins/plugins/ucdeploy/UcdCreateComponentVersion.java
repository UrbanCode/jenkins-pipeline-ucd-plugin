/**
 * (c) Copyright IBM Corporation 2017.
 * This is licensed under the following license.
 * The Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
 * U.S. Government Users Restricted Rights:  Use, duplication or disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 */

package com.urbancode.jenkins.plugins.ucdeploy;

import org.apache.http.impl.client.DefaultHttpClient;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.Launcher;
import hudson.model.Hudson;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.util.Secret;

import java.io.File;
import java.io.IOException;

import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.DeliveryBlock;
import com.urbancode.jenkins.plugins.ucdeploy.VersionHelper.VersionBlock;
import com.urbancode.jenkins.plugins.ucdeploy.ComponentHelper.CreateComponentBlock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build step: Create a component version in IBM UrbanCode Deploy.
 *
 * Handles pushing artifact files from Jenkins to UCD, or triggering
 * a pull-based import from UCD's source configuration.
 *
 * Pipeline usage (push):
 * <pre>
 * step([$class: 'UcdCreateComponentVersion',
 *       siteName: 'myServer',
 *       componentName: 'MyComp',
 *       delivery: [$class: 'com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper$Push',
 *                  pushVersion: '${BUILD_NUMBER}',
 *                  baseDir: '${WORKSPACE}/build',
 *                  fileIncludePatterns: '*.zip',
 *                  fileExcludePatterns: '',
 *                  pushProperties: 'key=value',
 *                  pushDescription: 'Pushed from Jenkins']])
 * </pre>
 */
public class UcdCreateComponentVersion extends UcdBuildStep {

    private static final Logger log = LoggerFactory.getLogger(UcdCreateComponentVersion.class);

    private String componentName;
    private String componentTag;
    private DeliveryBlock delivery;
    private Boolean createComponent;
    private String componentTemplate;
    private String componentApplication;

    @DataBoundConstructor
    public UcdCreateComponentVersion(String componentName, DeliveryBlock delivery) {
        this.componentName = componentName;
        this.delivery = delivery;
    }

    public String getComponentName() {
        return componentName != null ? componentName : "";
    }

    @DataBoundSetter
    public void setComponentName(String componentName) {
        this.componentName = componentName;
    }

    public String getComponentTag() {
        return componentTag != null ? componentTag : "";
    }

    @DataBoundSetter
    public void setComponentTag(String componentTag) {
        this.componentTag = componentTag;
    }

    public DeliveryBlock getDelivery() {
        return delivery;
    }

    public String getDeliveryType() {
        if (delivery != null) {
            return delivery.getDeliveryType().name();
        }
        return "";
    }

    public Boolean getCreateComponent() {
        return createComponent != null ? createComponent : false;
    }

    @DataBoundSetter
    public void setCreateComponent(Boolean createComponent) {
        this.createComponent = createComponent;
    }

    public String getComponentTemplate() {
        return componentTemplate != null ? componentTemplate : "";
    }

    @DataBoundSetter
    public void setComponentTemplate(String componentTemplate) {
        this.componentTemplate = componentTemplate;
    }

    public String getComponentApplication() {
        return componentApplication != null ? componentApplication : "";
    }

    @DataBoundSetter
    public void setComponentApplication(String componentApplication) {
        this.componentApplication = componentApplication;
    }

    @Override
    protected void execute(Run<?, ?> build, FilePath workspace, Launcher launcher,
                           TaskListener listener, UCDeploySite udSite, EnvVars envVars)
            throws AbortException, InterruptedException, IOException {

        if (componentName == null || componentName.trim().isEmpty()) {
            throw new AbortException("Component Name is a required field.");
        }
        if (delivery == null) {
            throw new AbortException("Delivery mechanism (Push or Pull) is required.");
        }

        String buildUrl = Hudson.getInstance().getRootUrl() + build.getUrl();

        CreateComponentBlock createCompBlock = null;
        if (Boolean.TRUE.equals(createComponent)) {
            createCompBlock = new CreateComponentBlock(
                    componentTemplate != null ? componentTemplate : "",
                    componentApplication != null ? componentApplication : "");
        }

        VersionBlock versionBlock = new VersionBlock(
                componentName,
                componentTag,
                createCompBlock,
                delivery);

        PublishArtifactsCallable task = new PublishArtifactsCallable(
                buildUrl,
                build.getDisplayName(),
                udSite,
                getAltUsername(),
                getAltPassword(),
                versionBlock,
                envVars,
                listener);

        workspace.act(task);
        listener.getLogger().println("UCD Create Component Version completed successfully.");
    }

    /**
     * Callable that runs on the agent node where the workspace resides.
     */
    private static class PublishArtifactsCallable implements FileCallable<Boolean> {
        private static final long serialVersionUID = 2L;
        private final String buildUrl;
        private final String buildName;
        private final UCDeploySite udSite;
        private final String altUsername;
        private final Secret altPassword;
        private final VersionBlock component;
        private final EnvVars envVars;
        private final TaskListener listener;

        public PublishArtifactsCallable(String buildUrl, String buildName, UCDeploySite udSite,
                                        String altUsername, Secret altPassword,
                                        VersionBlock component, EnvVars envVars, TaskListener listener) {
            this.buildUrl = buildUrl;
            this.buildName = buildName;
            this.udSite = udSite;
            this.altUsername = altUsername;
            this.altPassword = altPassword;
            this.component = component;
            this.envVars = envVars;
            this.listener = listener;
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
        }

        @Override
        public Boolean invoke(File workspace, VirtualChannel node) throws IOException, InterruptedException {
            DefaultHttpClient udClient;
            if (altUsername != null && !altUsername.isEmpty()) {
                udClient = udSite.getTempClient(altUsername, altPassword);
            } else {
                udClient = udSite.getClient();
            }

            VersionHelper versionHelper = new VersionHelper(udSite.getUri(), udClient, listener, envVars);
            versionHelper.createVersion(component, "Jenkins Build " + buildName, buildUrl);
            return true;
        }
    }

    @Extension
    public static class DescriptorImpl extends UcdBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "UCD - Create Component Version";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/ibm-ucdeploy-build-steps/steps/create-version/help.html";
        }
    }
}
