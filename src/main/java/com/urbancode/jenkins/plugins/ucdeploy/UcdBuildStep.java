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
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.util.Secret;

import jenkins.tasks.SimpleBuildStep;

import java.io.IOException;

import org.kohsuke.stapler.DataBoundSetter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for all UCD build steps.
 * Provides shared UCD site selection and alternative user authentication.
 */
@SuppressWarnings("deprecation") // Triggered by DefaultHttpClient
public abstract class UcdBuildStep extends Builder implements SimpleBuildStep {

    private static final Logger log = LoggerFactory.getLogger(UcdBuildStep.class);

    /**
     * Lazily resolves the GlobalConfigDescriptor to avoid NPE when
     * Jenkins.getInstance() is null during early class loading.
     */
    public static GlobalConfig.GlobalConfigDescriptor getGlobalDescriptor() {
        return GlobalConfig.getGlobalConfigDescriptor();
    }

    private String siteName;
    private String altUsername;
    private Secret altPassword;

    public String getSiteName() {
        String name = siteName;
        if (name == null) {
            UCDeploySite[] sites = getGlobalDescriptor().getSites();
            if (sites.length > 0) {
                name = sites[0].getProfileName();
            }
        }
        return name;
    }

    @DataBoundSetter
    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public String getAltUsername() {
        return altUsername != null ? altUsername : "";
    }

    @DataBoundSetter
    public void setAltUsername(String altUsername) {
        this.altUsername = altUsername;
    }

    public Secret getAltPassword() {
        return altPassword != null ? altPassword : Secret.fromString("");
    }

    @DataBoundSetter
    public void setAltPassword(Secret altPassword) {
        this.altPassword = altPassword;
    }

    /**
     * Returns the configured UCDeploySite matching the siteName.
     */
    public UCDeploySite getSite() {
        UCDeploySite[] sites = getGlobalDescriptor().getSites();
        if (siteName == null && sites.length > 0) {
            return sites[0];
        }
        for (UCDeploySite site : sites) {
            if (site.getDisplayName().equals(siteName)) {
                return site;
            }
        }
        return null;
    }

    /**
     * Creates an authenticated HTTP client, using alternative credentials if provided.
     */
    protected DefaultHttpClient resolveClient(UCDeploySite udSite, TaskListener listener) throws AbortException {
        if (altUsername != null && !altUsername.isEmpty()) {
            listener.getLogger().println("Running job as alternative user '" + altUsername + "'.");
            return udSite.getTempClient(altUsername, altPassword);
        }
        return udSite.getClient();
    }

    @Override
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener)
            throws AbortException, InterruptedException, IOException {
        if (build.getResult() == Result.FAILURE || build.getResult() == Result.ABORTED) {
            throw new AbortException("Skipping UCD step - build already failed or aborted.");
        }

        UCDeploySite udSite = getSite();
        if (udSite == null) {
            throw new AbortException("No UCD server configured with name '" + siteName + "'. "
                    + "Check Manage Jenkins > System Configuration.");
        }

        EnvVars envVars = build.getEnvironment(listener);
        execute(build, workspace, launcher, listener, udSite, envVars);
    }

    /**
     * Subclasses implement their specific logic here.
     */
    protected abstract void execute(Run<?, ?> build, FilePath workspace, Launcher launcher,
                                    TaskListener listener, UCDeploySite udSite, EnvVars envVars)
            throws AbortException, InterruptedException, IOException;

    /**
     * Base descriptor for all UCD build steps.
     */
    public static abstract class UcdBuildStepDescriptor extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public UCDeploySite[] getSites() {
            return getGlobalDescriptor().getSites();
        }
    }
}
