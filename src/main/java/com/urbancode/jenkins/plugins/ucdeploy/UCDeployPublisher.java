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
import hudson.model.AbstractProject;
import hudson.model.Descriptor.FormException;
import hudson.remoting.VirtualChannel;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.util.Secret;

import jenkins.tasks.SimpleBuildStep;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;

import net.sf.json.JSONObject;

import org.codehaus.jettison.json.JSONException;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import com.urbancode.jenkins.plugins.ucdeploy.ComponentHelper.CreateComponentBlock;
import com.urbancode.jenkins.plugins.ucdeploy.ProcessHelper.CreateProcessBlock;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.DeliveryBlock;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.Pull;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.Push;
import com.urbancode.jenkins.plugins.ucdeploy.DeployHelper;
import com.urbancode.jenkins.plugins.ucdeploy.DeployHelper.DeployBlock;
import com.urbancode.jenkins.plugins.ucdeploy.DeployHelper.CreateSnapshotBlock;
import com.urbancode.jenkins.plugins.ucdeploy.DeployHelper.CreateSnapshotComponentBlock;
import com.urbancode.jenkins.plugins.ucdeploy.VersionHelper;
import com.urbancode.jenkins.plugins.ucdeploy.VersionHelper.VersionBlock;
import com.urbancode.jenkins.plugins.ucdeploy.UCDeployPublisher.UserBlock;

public class UCDeployPublisher extends Builder implements SimpleBuildStep {

    public static final GlobalConfig.GlobalConfigDescriptor GLOBALDESCRIPTOR = GlobalConfig.getGlobalConfigDescriptor();

    private String siteName;
    private UserBlock altUser;
    private VersionBlock component;
    private DeployBlock deploy;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UCDeployPublisher.class);

    /**
     * Constructor used for data-binding fields from the corresponding
     * config.jelly
     *
     * @param siteName
     *            The profile name of the UrbanDeploy site
     * @param component
     *            The object holding the Create Version Block structure
     * @param deploy
     *            The object holding the Deploy Block structure
     */
    @DataBoundConstructor
    public UCDeployPublisher(
            String siteName,
            UserBlock altUser,
            VersionBlock component,
            DeployBlock deploy) {
        this.siteName = siteName;
        this.altUser = altUser;
        this.component = component;
        this.deploy = deploy;
    }

    /*
     * Accessors and mutators required for data-binding access
     */

    public String getSiteName() {
        String name = siteName;
        if (name == null) {
            UCDeploySite[] sites = GLOBALDESCRIPTOR.getSites();
            if (sites.length > 0) {
                name = sites[0].getProfileName();
            }
        }
        return name;
    }

    public UserBlock getAltUser() {
        return altUser;
    }

    public Boolean altUserChecked() {
        return altUser != null;
    }

    public String getAltUsername() {
        return altUser != null ? altUser.getAltUsername() : "";
    }

    public Secret getAltPassword() {
        return altUser != null ? altUser.getAltPassword() : Secret.fromString("");
    }

    public VersionBlock getComponent() {
        return component;
    }

    public Boolean componentChecked() {
        return component != null;
    }

    public String getComponentName() {
        return component != null ? component.getComponentName() : "";
    }

    public CreateComponentBlock getCreateComponent() {
        return component != null ? component.getCreateComponent() : null;
    }

    public Boolean createComponentChecked() {
        return getCreateComponent() != null;
    }

    public String getComponentTemplate() {
        return getCreateComponent() != null ? getCreateComponent().getComponentTemplate() : "";
    }

    public String getComponentApplication() {
        return getCreateComponent() != null ? getCreateComponent().getComponentApplication() : "";
    }

    public DeliveryBlock getDelivery() {
        return component != null ? component.getDelivery() : null;
    }

    public String getDeliveryType() {
        return getDelivery() != null ? getDelivery().getDeliveryType().name() : "";
    }

    public String getPushVersion() {
        return (getDelivery() instanceof Push) ? ((Push)getDelivery()).getPushVersion() : "";
    }

    public String getBaseDir() {
        return (getDelivery() instanceof Push) ? ((Push)getDelivery()).getBaseDir() : "";
    }

    public String getFileIncludePatterns() {
        return (getDelivery() instanceof Push) ? ((Push)getDelivery()).getFileIncludePatterns() : "";
    }

    public String getFileExcludePatterns() {
        return (getDelivery() instanceof Push) ? ((Push)getDelivery()).getFileExcludePatterns() : "";
    }

    public String getExtensions() {
        return (getDelivery() instanceof Push) ? ((Push)getDelivery()).getExtensions() : "";
    }

    public String getCharset() {
        return (getDelivery() instanceof Push) ? ((Push)getDelivery()).getCharset() : "";
    }

    public String getPushProperties() {
        return (getDelivery() instanceof Push) ? ((Push)getDelivery()).getPushProperties() : "";
    }

    public String getPushDescription() {
        return (getDelivery() instanceof Push) ? ((Push)getDelivery()).getPushDescription() : "";
    }

    public Boolean getPushIncremental() {
        return (getDelivery() instanceof Push) ? ((Push)getDelivery()).getPushIncremental() : false;
    }

    public String getPullProperties() {
        return (getDelivery() instanceof Pull) ? ((Pull)getDelivery()).getPullProperties() : "";
    }

    public String getpullSourceType() {
        return (getDelivery() instanceof Pull) ? ((Pull)getDelivery()).getPullSourceType() : "";
    }

    public String getPullSourceProperties() {
        return (getDelivery() instanceof Pull) ? ((Pull)getDelivery()).getPullSourceProperties() : "";
    }

    public Boolean getPullIncremental() {
        return (getDelivery() instanceof Pull) ? ((Pull)getDelivery()).getPullIncremental() : false;
    }

    public DeployBlock getDeploy() {
        return deploy;
    }

    public Boolean deployChecked() {
        return deploy != null;
    }

    public String getDeployApp() {
        return deploy != null ? deploy.getDeployApp() : "";
    }

    public String getDeployEnv() {
        return deploy != null ? deploy.getDeployEnv() : "";
    }

    public String getDeployProc() {
        return deploy != null ? deploy.getDeployProc() : "";
    }

    public Boolean getSkipWait() {
        return deploy != null ? deploy.getSkipWait() : false;
    }

    public CreateProcessBlock getCreateProcess() {
        return deploy.getCreateProcess();
    }

    public Boolean createProcessChecked() {
        return getCreateProcess() != null;
    }

    public CreateSnapshotComponentBlock getSnapshotComponent() {
        return deploy.getSnapshotComponent();
    }

    public Boolean createSnapshotComponentChecked() {
        return getSnapshotComponent() != null;
    }

    public String getProcessComponent() {
        return getCreateProcess() != null ? getCreateProcess().getProcessComponent() : "";
    }

    public CreateSnapshotBlock getCreateSnapshot() {
        return deploy.getCreateSnapshot();
    }

    public Boolean createSnapshotChecked() {
        return getCreateSnapshot() != null;
    }

    public String getSnapshotName() {
        return getCreateSnapshot() != null ? getCreateSnapshot().getSnapshotName() : "";
    }

    public String getSnapshotNameForComp() {
        return getSnapshotComponent() != null ? getSnapshotComponent().getSnapshotNameForComp() : "";
    }

    public Boolean getDeployWithSnapshot() {
        return getCreateSnapshot() != null ? (getCreateSnapshot()).getDeployWithSnapshot() : false;
    }

    public Boolean getUpdateSnapshotComp() {
        return getCreateSnapshot() != null ? (getCreateSnapshot()).getUpdateSnapshotComp() : false;
    }

    public Boolean getIncludeOnlyDeployVersions() {
        return getCreateSnapshot() != null ? (getCreateSnapshot()).getIncludeOnlyDeployVersions() : false;
    }

    public String getDeployVersions() {
        return deploy != null ? deploy.getDeployVersions() : "";
    }

    public String getDeployReqProps() {
        return deploy != null ? deploy.getDeployReqProps() : "";
    }

    public String getDeployDesc() {
        return deploy != null ? deploy.getDeployDesc() : "";
    }

    public Boolean getDeployOnlyChanged() {
        return deploy.getDeployOnlyChanged() == null ? false : getDeploy().getDeployOnlyChanged();
    }

    public UCDeploySite getSite() {
        UCDeploySite[] sites = GLOBALDESCRIPTOR.getSites();
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

    @Override
    public void perform(final Run<?, ?> build, FilePath workspace, Launcher launcher, final TaskListener listener)
            throws AbortException, InterruptedException, IOException {
        if (build.getResult() == Result.FAILURE || build.getResult() == Result.ABORTED) {
            throw new AbortException("Skip artifacts upload to IBM UrbanCode Deploy - build failed or aborted.");
        }

        // Log requested site name and resolution
        listener.getLogger().println(String.format("[UCD] perform: requestedSiteName='%s'", getSiteName()));
        UCDeploySite udSite = getSite();
        listener.getLogger().println(String.format("[UCD] perform: resolvedSite='%s', uri='%s'",
                udSite != null ? udSite.getDisplayName() : "null",
                udSite != null ? String.valueOf(udSite.getUri()) : "null"));

        // Decide client and log effective credentials (LOCAL DEBUG ONLY)
        final boolean useAltUser = altUserChecked();
        final String effUser = useAltUser ? getAltUsername() : (udSite != null ? udSite.getUser() : "null");
        final String effPass = useAltUser
                ? (getAltPassword() != null ? getAltPassword().getPlainText() : "null")
                : (udSite != null && udSite.getPassword() != null ? udSite.getPassword().getPlainText() : "null");

        listener.getLogger().println(String.format(
                "[UCD] perform: clientSource='%s', user='%s', pass='%s', siteUri='%s'",
                useAltUser ? "temp" : "cached",
                effUser,
                effPass,
                udSite != null ? String.valueOf(udSite.getUri()) : "null"
        ));

        DefaultHttpClient udClient = useAltUser
                ? udSite.getTempClient(getAltUsername(), getAltPassword())
                : udSite.getClient();
        listener.getLogger().println("[UCD] perform: effClientId=" + System.identityHashCode(udClient) + ", usedPath=" + (altUser != null ? "tempClient" : "cachedClient"));
        EnvVars envVars = build.getEnvironment(listener);

        if (componentChecked()) {
            listener.getLogger().println("[UCD] component path: version create/push will run.");
            String buildUrl = Hudson.getInstance().getRootUrl() + build.getUrl();
            PublishArtifactsCallable task = new PublishArtifactsCallable(
                    buildUrl,
                    build.getDisplayName(),
                    udSite,
                    altUser,
                    getComponent(),
                    envVars,
                    listener);
            workspace.act(task);
        }

        if (deployChecked()) {
            listener.getLogger().println(String.format(
                    "[UCD] deploy path: app='%s', env='%s', proc='%s', versions='%s'",
                    getDeployApp(), getDeployEnv(), getDeployProc(), getDeployVersions()
            ));
            listener.getLogger().println(String.format(
                    "[UCD] deploy path: using user='%s', pass='%s', uri='%s'",
                    effUser, effPass, String.valueOf(udSite.getUri())
            ));

            DeployHelper deployHelper = new DeployHelper(udSite.getUri(), udClient, listener, envVars, udSite.isSkipProps());

            try {
                deployHelper.runDeployment(getDeploy());
            }
            catch (IOException ex) {
                throw new AbortException("Deployment has failed due to IOException " + ex.getMessage());
            }
            catch (JSONException ex) {
                throw new AbortException("Deployment has failed due to JSONException " +  ex.getMessage());
            }
        }
    }

    public static class UserBlock implements Serializable {
        private String altUsername;
        private Secret altPassword;

        @DataBoundConstructor
        public UserBlock(String altUsername, Secret altPassword) {
            this.altUsername = altUsername;
            this.altPassword = altPassword;
        }

        public String getAltUsername() {
            return altUsername;
        }

        public void setAltUsername(String altUsername) {
            this.altUsername = altUsername;
        }

        public Secret getAltPassword() {
            return altPassword;
        }

        public void setAltPassword(Secret altPassword) {
            this.altPassword = altPassword;
        }
    }

    /**
     * Callable class that can be serialized and executed on a remote node
     *
     */
    private static class PublishArtifactsCallable implements FileCallable<Boolean> {
        private static final long serialVersionUID = 1L;
        String buildUrl;
        String buildName;
        UCDeploySite udSite;
        UserBlock altUser;
        VersionBlock component;
        EnvVars envVars;
        TaskListener listener;

        public PublishArtifactsCallable(
                String buildUrl,
                String buildName,
                UCDeploySite udSite,
                UserBlock altUser,
                VersionBlock component,
                EnvVars envVars,
                TaskListener listener)
        {
            this.buildUrl = buildUrl;
            this.buildName = buildName;
            this.udSite = udSite;
            this.altUser = altUser;
            this.component = component;
            this.envVars = envVars;
            this.listener = listener;
        }

        /**
         * Check the role of the executing node to follow jenkins new file access rules
         */
        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
            this.checkRoles(checker);
        }

        @Override
        public Boolean invoke(File workspace, VirtualChannel node) throws IOException, InterruptedException {
            DefaultHttpClient udClient;
            final boolean useAlt = (altUser != null);
            final String effUser = useAlt ? altUser.getAltUsername() : udSite.getUser();
            final String effPass = useAlt
                    ? (altUser.getAltPassword() != null ? altUser.getAltPassword().getPlainText() : "null")
                    : (udSite.getPassword() != null ? udSite.getPassword().getPlainText() : "null");

            listener.getLogger().println(String.format(
                    "[UCD] push path: clientSource='%s', user='%s', pass='%s', siteUri='%s'",
                    useAlt ? "temp" : "cached",
                    effUser,
                    effPass,
                    String.valueOf(udSite.getUri())
            ));

            if (useAlt) {
                udClient = udSite.getTempClient(altUser.getAltUsername(), altUser.getAltPassword());
            }
            else {
                udClient = udSite.getClient();
            }

            // Summarize push params
            listener.getLogger().println(String.format(
                    "[UCD] push params: component='%s', deliveryType='%s', version='%s', baseDir='%s', includes='%s'",
                    component.getComponentName(),
                    (component.getDelivery() != null ? component.getDelivery().getDeliveryType().name() : "null"),
                    (component.getDelivery() instanceof Push ? ((Push)component.getDelivery()).getPushVersion() : ""),
                    (component.getDelivery() instanceof Push ? ((Push)component.getDelivery()).getBaseDir() : ""),
                    (component.getDelivery() instanceof Push ? ((Push)component.getDelivery()).getFileIncludePatterns() : "")
            ));

            VersionHelper versionHelper = new VersionHelper(udSite.getUri(), udClient, listener, envVars);
            versionHelper.createVersion(component, "Jenkins Build " + buildName, buildUrl);

            return true;
        }
    }

    /**
     * This class holds the metadata for the Publisher and allows it's data
     * fields to persist
     *
     */
    @Extension
    public static class UCDeployPublisherDescriptor extends BuildStepDescriptor<Builder> {

        public UCDeployPublisherDescriptor() {
            load();
        }

        /**
         * Return the location of the help document for this builder.
         * <p/>
         * {@inheritDoc}
         *
         * @return {@inheritDoc}
         * @see hudson.model.Descriptor#getHelpFile()
         */
        @Override
        public String getHelpFile() {
            return "/plugin/ibm-ucdeploy-build-steps/publish.html";
        }

        /**
         * Get all configured UCDeploySite objects
         *
         * @return The array of configured UCDeploySite objects
         */
        public UCDeploySite[] getSites() {
            return GLOBALDESCRIPTOR.getSites();
        }

        @DataBoundSetter
        public void setSites(UCDeploySite[] sitesArray) {
            GLOBALDESCRIPTOR.setSites(sitesArray);
        }

        /**
         * Bind data fields to user defined values {@inheritDoc}
         *
         * @param req
         *            {@inheritDoc}
         * @param formData
         *            {@inheritDoc}
         * @return {@inheritDoc}
         * @see hudson.model.Descriptor#configure(org.kohsuke.stapler.StaplerRequest)
         */
        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            req.bindJSON(this, formData);
            save();
            return super.configure(req, formData);
        }

        /**
         * {@inheritDoc}
         *
         * @return {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return "Publish Artifacts to IBM UrbanCode Deploy";
        }

        /**
         * {@inheritDoc}
         *
         * @param jobType
         *            {@inheritDoc}
         * @return {@inheritDoc}
         */
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}
