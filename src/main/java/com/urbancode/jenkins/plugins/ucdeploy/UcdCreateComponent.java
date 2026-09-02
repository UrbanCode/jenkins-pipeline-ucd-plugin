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
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;

import java.io.IOException;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import com.urbancode.ud.client.ApplicationClient;
import com.urbancode.ud.client.ComponentClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build step: Create a component in IBM UrbanCode Deploy.
 *
 * Creates a new component (if it doesn't already exist) and optionally
 * adds it to an application.
 *
 * Pipeline usage:
 * <pre>
 * step([$class: 'UcdCreateComponent',
 *       siteName: 'myServer',
 *       componentName: 'MyComp',
 *       componentApplication: 'MyApp'])
 * </pre>
 */
@SuppressWarnings("deprecation") // Triggered by DefaultHttpClient
public class UcdCreateComponent extends UcdBuildStep {

    private static final Logger log = LoggerFactory.getLogger(UcdCreateComponent.class);

    private String componentName;
    private String componentTemplate;
    private String componentApplication;
    private String componentTag;
    private String defaultVersionType;

    @DataBoundConstructor
    public UcdCreateComponent(String componentName) {
        this.componentName = componentName;
    }

    public String getComponentName() {
        return componentName != null ? componentName : "";
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

    public String getComponentTag() {
        return componentTag != null ? componentTag : "";
    }

    @DataBoundSetter
    public void setComponentTag(String componentTag) {
        this.componentTag = componentTag;
    }

    public String getDefaultVersionType() {
        return defaultVersionType != null ? defaultVersionType : "FULL";
    }

    @DataBoundSetter
    public void setDefaultVersionType(String defaultVersionType) {
        this.defaultVersionType = defaultVersionType;
    }

    @Override
    protected void execute(Run<?, ?> build, FilePath workspace, Launcher launcher,
                           TaskListener listener, UCDeploySite udSite, EnvVars envVars)
            throws AbortException, InterruptedException, IOException {

        String name = envVars.expand(getComponentName());
        String template = envVars.expand(getComponentTemplate());
        String application = envVars.expand(getComponentApplication());
        String tag = envVars.expand(getComponentTag());
        String versionType = envVars.expand(getDefaultVersionType());

        if (name.isEmpty()) {
            throw new AbortException("Component Name is a required field.");
        }

        DefaultHttpClient udClient = resolveClient(udSite, listener);
        ApplicationClient appClient = new ApplicationClient(udSite.getUri(), udClient);
        ComponentClient compClient = new ComponentClient(udSite.getUri(), udClient);

        ComponentHelper componentHelper = new ComponentHelper(appClient, compClient, listener, envVars);

        // Build a CreateComponentBlock and a minimal delivery block for component creation
        ComponentHelper.CreateComponentBlock createBlock =
                new ComponentHelper.CreateComponentBlock(template, application);

        // Create a minimal Push delivery to satisfy ComponentHelper.createComponent
        DeliveryHelper.Push dummyDelivery = new DeliveryHelper.Push(
                "", "", "", "", "", "", "", "",
                "INCREMENTAL".equalsIgnoreCase(versionType));

        componentHelper.createComponent(name, createBlock, dummyDelivery);

        // Tag component if specified
        if (!tag.isEmpty()) {
            componentHelper.addTag(name, tag);
            listener.getLogger().println("Tagged component '" + name + "' with '" + tag + "'.");
        }

        listener.getLogger().println("DevOps Deploy Create Component completed successfully.");
    }

    @Extension
    public static class DescriptorImpl extends UcdBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "DevOps Deploy - Create Component";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/ibm-ucdeploy-build-steps/steps/create-component/help.html";
        }
    }
}
