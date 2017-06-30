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

import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import com.urbancode.jenkins.plugins.ucdeploy.ComponentHelper.CreateComponentBlock;
import com.urbancode.jenkins.plugins.ucdeploy.ProcessHelper.CreateProcessBlock;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.DeliveryBlock;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.Pull;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.Push;
import com.urbancode.jenkins.plugins.ucdeploy.DeployHelper;
import com.urbancode.jenkins.plugins.ucdeploy.DeployHelper.DeployBlock;
import com.urbancode.jenkins.plugins.ucdeploy.DeployHelper.CreateSnapshotBlock;
import com.urbancode.jenkins.plugins.ucdeploy.SystemHelper;
import com.urbancode.jenkins.plugins.ucdeploy.VersionHelper;
import com.urbancode.jenkins.plugins.ucdeploy.VersionHelper.VersionBlock;
import com.urbancode.jenkins.plugins.ucdeploy.UCDeployPublisher.UserBlock;

public class UCDeployPublisher extends Builder implements SimpleBuildStep {

    public static final GlobalConfig.GlobalConfigDescriptor GLOBALDESCRIPTOR = GlobalConfig.getGlobalConfigDescriptor();

    private String siteName;
    private UserBlock altUser;
    private VersionBlock component;
    private DeployBlock deploy;

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
        if (getAltUser() != null) {
            return true;
        }

        return false;
    }

    public String getAltUsername() {
        String altUsername = "";
        String value = getAltUser().getAltUsername();

        if (value != null) {
            altUsername = value;
        }

        return altUsername;
    }

    public Secret getAltPassword() {
        Secret altPassword = Secret.fromString("");
        Secret value = getAltUser().getAltPassword();

        if (value != null) {
            altPassword = value;
        }

        return altPassword;
    }

    public boolean isAltUserAdmin() {
        return getAltUser().isAdminUser();
    }

    public VersionBlock getComponent() {
        return component;
    }

    public Boolean componentChecked() {
        if (getComponent() != null) {
            return true;
        }

        return false;
    }

    public String getComponentName() {
        String componentName = "";
        String value = getComponent().getComponentName();

        if (value != null) {
            componentName = value;
        }

        return componentName;
    }

    public CreateComponentBlock getCreateComponent() {
        return getComponent().getCreateComponentBlock();
    }

    public Boolean createComponentChecked() {
        if (getCreateComponent() != null) {
            return true;
        }

        return false;
    }

    public String getComponentTemplate() {
        String componentTemplate = "";
        String value = getCreateComponent().getComponentTemplate();

        if (value != null) {
            componentTemplate = value;
        }

        return componentTemplate;
    }

    public String getComponentApplication() {
        String componentApplication = "";
        String value = getCreateComponent().getComponentApplication();

        if (value != null) {
            componentApplication = value;
        }

        return componentApplication;
    }

    public DeliveryBlock getDelivery() {
        return getComponent().getDeliveryBlock();
    }

    public String getDeliveryType() {
        String deliveryType = "";
        String value = getDelivery().getDeliveryType().name();

        if (value != null) {
            deliveryType = value;
        }

        return deliveryType;
    }

    public String getPushVersion() {
        String pushVersion = "";
        String value = ((Push)getDelivery()).getPushVersion();

        if (value != null) {
            pushVersion = value;
        }

        return pushVersion;
    }

    public String getBaseDir() {
        String baseDir = "";
        String value = ((Push)getDelivery()).getBaseDir();

        if (value != null) {
            baseDir = value;
        }

        return baseDir;
    }

    public String getFileIncludePatterns() {
        String fileIncludePatterns = "";
        String value = ((Push)getDelivery()).getFileIncludePatterns();

        if (value != null) {
            fileIncludePatterns = value;
        }

        return fileIncludePatterns;
    }

    public String getFileExcludePatterns() {
        String fileExcludePatterns = "";
        String value = ((Push)getDelivery()).getFileExcludePatterns();

        if (value != null) {
            fileExcludePatterns = value;
        }

        return fileExcludePatterns;
    }

    public String getPushProperties() {
        String pushProperties = "";
        String value = ((Push)getDelivery()).getPushProperties();

        if (value != null) {
            pushProperties = value;
        }

        return pushProperties;
    }

    public String getPushDescription() {
        String pushDescription = "";
        String value = ((Push)getDelivery()).getPushDescription();

        if (value != null) {
            pushDescription = value;
        }

        return pushDescription;
    }

    public Boolean getPushIncremental() {
        if (((Push)getDelivery()).getPushIncremental() != null) {
            return false;
        }
        else {
            return ((Push)getDelivery()).getPushIncremental();
        }
    }

    public String getPullProperties() {
        String pullProperties = "";
        String value = ((Pull)getDelivery()).getPullProperties();

        if (value != null) {
            pullProperties = value;
        }

        return pullProperties;
    }

    public String getpullSourceType() {
        String pullSourceType = "";
        String value = ((Pull)getDelivery()).getPullSourceType();

        if (value != null) {
            pullSourceType = value;
        }

        return pullSourceType;
    }

    public String getPullSourceProperties() {
        String pullSourceProperties = "";
        String value = ((Pull)getDelivery()).getPullSourceProperties();

        if (value != null) {
            pullSourceProperties = value;
        }

        return pullSourceProperties;
    }

    public Boolean getPullIncremental() {
        if (((Pull)getDelivery()).getPullIncremental() == null) {
            return false;
        }
        else {
            return ((Pull)getDelivery()).getPullIncremental();
        }
    }

    public DeployBlock getDeploy() {
        return deploy;
    }

    public Boolean deployChecked() {
        if (getDeploy() != null) {
            return true;
        }

        return false;
    }

    public String getDeployApp() {
        String deployApp = "";
        String value = getDeploy().getDeployApp();

        if (value != null) {
            deployApp = value;
        }

        return deployApp;
    }

    public String getDeployEnv() {
        String deployEnv = "";
        String value = getDeploy().getDeployEnv();

        if (value != null) {
            deployEnv = value;
        }

        return deployEnv;
    }

    public String getDeployProc() {
        String deployProc = "";
        String value = getDeploy().getDeployProc();

        if (value != null) {
            deployProc = value;
        }

        return deployProc;
    }

    public CreateProcessBlock getCreateProcess() {
        return getDeploy().getCreateProcessBlock();
    }

    public Boolean createProcessChecked() {
        if (getCreateProcess() != null) {
            return true;
        }

        return false;
    }


    public String getProcessComponent() {
        String processComponent = "";
        String value = getCreateProcess().getProcessComponent();

        if (value != null) {
            processComponent = value;
        }

        return processComponent;
    }

    public CreateSnapshotBlock getCreateSnapshot() {
        return getDeploy().getCreateSnapshotBlock();
    }

    public Boolean createSnapshotChecked() {
        if (getCreateSnapshot() != null) {
            return true;
        }

        return false;
    }


    public String getSnapshotName() {
        String snapshotName = "";
        String value = getCreateSnapshot().getSnapshotName();

        if (value != null) {
            snapshotName = value;
        }

        return snapshotName;
    }

    public String getDeployVersions() {
        String deployVersions = "";
        String value = getDeploy().getDeployVersions();

        if (value != null) {
            deployVersions = value;
        }

        return deployVersions;
    }

    public String getDeployReqProps() {
        String deployReqProps = "";
        String value = getDeploy().getDeployReqProps();

        if (value != null) {
            deployReqProps = value;
        }

        return deployReqProps;
    }

    public String getDeployDesc() {
        String deployDesc = "";
        String value = getDeploy().getDeployDesc();

        if (value != null) {
            deployDesc = value;
        }

        return deployDesc;
    }

    public Boolean getDeployOnlyChanged() {
        if (getDeploy().getDeployOnlyChanged() == null) {
            return false;
        }
        else {
            return getDeploy().getDeployOnlyChanged();
        }
    }

    /**
     * This method returns the configured UCDeploySite object which match the
     * siteName of the UCDeployPublisherer instance. (see Manage Hudson and
     * System Configuration point UrbanDeploy)
     *
     * @return the matching UCDeploySite or null
     */
    public UCDeploySite getSite() {
        UCDeploySite[] sites = GLOBALDESCRIPTOR.getSites();
        if (siteName == null && sites.length > 0) {
            // default
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
     * {@inheritDoc}
     *
     * @param build
     * @param launcher
     * @param listener
     * @return A boolean to represent if the build can continue
     * @throws InterruptedException
     * @throws java.io.IOException
     *             {@inheritDoc}
     * @see hudson.tasks.BuildStep#perform(hudson.model.Build, hudson.Launcher,
     *      hudson.model.TaskListener)
     */
    @Override
    public void perform(final Run<?, ?> build, FilePath workspace, Launcher launcher, final TaskListener listener)
            throws AbortException, InterruptedException, IOException {
        if (build.getResult() == Result.FAILURE || build.getResult() == Result.ABORTED) {
            throw new AbortException("Skip artifacts upload to IBM UrbanCode Deploy - build failed or aborted.");
        }

        UCDeploySite udSite = getSite();
        DefaultHttpClient udClient;  // not serializable
        boolean adminUser = false;

        if (altUserChecked()) {
            if (getAltUsername().equals("")) {
                throw new AbortException("Alternative username is a required property when specifying the optional"
                        + "'Run as Alternative User' property.");
            }

            listener.getLogger().println("Running job as alternative user '" + getAltUsername() + "'.");

            udClient = udSite.getTempClient(getAltUsername(), getAltPassword());
            adminUser = isAltUserAdmin();
        }
        else {
            udClient = udSite.getClient();
            adminUser = udSite.isAdminUser();
        }

        if (adminUser) {
            SystemHelper sysHelper = new SystemHelper(udSite.getUri(), udClient, listener);

            listener.getLogger().println("Administrative user specified. Checking if server is running in "
                    + "maintenance mode.");
            if (sysHelper.isMaintenanceEnabled()) {
                throw new AbortException("The UrbanCode Deploy server is running in maintenance mode "
                        + "and no processes may be run.");
            }
        }

        EnvVars envVars = build.getEnvironment(listener);

        if (componentChecked() ) {
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
            DeployHelper deployHelper = new DeployHelper(udSite.getUri(), udClient, listener, envVars);
            deployHelper.deployVersion(getDeploy());
        }
    }

    public static class UserBlock implements Serializable {
        private String altUsername;
        private Secret altPassword;
        private boolean adminUser;

        @DataBoundConstructor
        public UserBlock(String altUsername, Secret altPassword, boolean adminUser) {
            this.altUsername = altUsername;
            this.altPassword = altPassword;
            this.adminUser = adminUser;
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

        public boolean isAdminUser() {
            return adminUser;
        }

        public void setAdminUser(boolean adminUser) {
            this.adminUser = adminUser;
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
            this.altUser = altUser; // used to acquire udClient in a serializable environment
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

            if (altUser != null) {
                udClient = udSite.getTempClient(altUser.getAltUsername(), altUser.getAltPassword());
            }
            else {
                udClient = udSite.getClient();
            }

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
