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
        if (altUser != null) {
            return true;
        }

        return false;
    }

    public String getAltUsername() {
        String altUsername = "";

        if (altUser != null) {
            altUsername = altUser.getAltUsername();
        }

        return altUsername;
    }

    public Secret getAltPassword() {
        Secret altPassword = Secret.fromString("");

        if (altUser != null) {
            altPassword = altUser.getAltPassword();
        }

        return altPassword;
    }

    public VersionBlock getComponent() {
        return component;
    }

    public Boolean componentChecked() {
        if (component != null) {
            return true;
        }

        return false;
    }

    public String getComponentName() {
        String componentName = "";

        if (component != null) {
            componentName = component.getComponentName();
        }

        return componentName;
    }

    public CreateComponentBlock getCreateComponent() {
        if (component != null) {
            return component.getCreateComponent();
        }
        else {
            return null;
        }
    }

    public Boolean createComponentChecked() {
        if (getCreateComponent() != null) {
            return true;
        }

        return false;
    }

    public String getComponentTemplate() {
        String componentTemplate = "";

        if (getCreateComponent() != null) {
            componentTemplate = getCreateComponent().getComponentTemplate();
        }

        return componentTemplate;
    }

    public String getComponentApplication() {
        String componentApplication = "";

        if (getCreateComponent() != null) {
            componentApplication = getCreateComponent().getComponentApplication();
        }

        return componentApplication;
    }

    public DeliveryBlock getDelivery() {
        if (component != null) {
            return component.getDelivery();
        }
        else {
            return null;
        }
    }

    public String getDeliveryType() {
        String deliveryType = "";

        if (getDelivery() != null) {
            deliveryType = getDelivery().getDeliveryType().name();
        }

        return deliveryType;
    }

    public String getPushVersion() {
        String pushVersion = "";

        if (getDelivery() != null && getDelivery() instanceof Push) {
            pushVersion = ((Push)getDelivery()).getPushVersion();
        }

        return pushVersion;
    }

    public String getBaseDir() {
        String baseDir = "";

        if (getDelivery() != null && getDelivery() instanceof Push) {
            baseDir = ((Push)getDelivery()).getBaseDir();
        }

        return baseDir;
    }

    public String getFileIncludePatterns() {
        String fileIncludePatterns = "";

        if (getDelivery() != null && getDelivery() instanceof Push) {
            fileIncludePatterns = ((Push)getDelivery()).getFileIncludePatterns();
        }

        return fileIncludePatterns;
    }

    public String getFileExcludePatterns() {
        String fileExcludePatterns = "";

        if (getDelivery() != null && getDelivery() instanceof Push) {
            fileExcludePatterns = ((Push)getDelivery()).getFileExcludePatterns();
        }

        return fileExcludePatterns;
    }

    public String getPushProperties() {
        String pushProperties = "";

        if (getDelivery() != null && getDelivery() instanceof Push) {
            pushProperties = ((Push)getDelivery()).getPushProperties();
        }

        return pushProperties;
    }

    public String getPushDescription() {
        String pushDescription = "";

        if (getDelivery() != null && getDelivery() instanceof Push) {
            pushDescription = ((Push)getDelivery()).getPushDescription();
        }

        return pushDescription;
    }

    public Boolean getPushIncremental() {
        if (getDelivery() != null && getDelivery() instanceof Push) {
            return ((Push)getDelivery()).getPushIncremental();
        }

        return false;
    }

    public String getPullProperties() {
        String pullProperties = "";

        if (getDelivery() != null && getDelivery() instanceof Pull) {
            pullProperties = ((Pull)getDelivery()).getPullProperties();
        }

        return pullProperties;
    }

    public String getpullSourceType() {
        String pullSourceType = "";

        if (getDelivery() != null && getDelivery() instanceof Pull) {
            pullSourceType = ((Pull)getDelivery()).getPullSourceType();
        }

        return pullSourceType;
    }

    public String getPullSourceProperties() {
        String pullSourceProperties = "";

        if (getDelivery() != null && getDelivery() instanceof Pull) {
            pullSourceProperties = ((Pull)getDelivery()).getPullSourceProperties();
        }

        return pullSourceProperties;
    }

    public Boolean getPullIncremental() {
        if (getDelivery() != null && getDelivery() instanceof Pull) {
            return ((Pull)getDelivery()).getPullIncremental();
        }

        return false;
    }

    public DeployBlock getDeploy() {
        return deploy;
    }

    public Boolean deployChecked() {
        if (deploy != null) {
            return true;
        }

        return false;
    }

    public String getDeployApp() {
        String deployApp = "";

        if (deploy != null) {
            deployApp = deploy.getDeployApp();
        }

        return deployApp;
    }

    public String getDeployEnv() {
        String deployEnv = "";

        if (deploy != null) {
            deployEnv = deploy.getDeployEnv();
        }

        return deployEnv;
    }

    public String getDeployProc() {
        String deployProc = "";

        if (deploy != null) {
            deployProc = deploy.getDeployProc();
        }

        return deployProc;
    }

    public Boolean getSkipWait() {
        if (deploy != null) {
            return deploy.getSkipWait();
        }

        return false;
    }

    public CreateProcessBlock getCreateProcess() {
        return deploy.getCreateProcess();
    }

    public Boolean createProcessChecked() {
        if (getCreateProcess() != null) {
            return true;
        }

        return false;
    }


    public String getProcessComponent() {
        String processComponent = "";

        if (getCreateProcess() != null) {
            processComponent = getCreateProcess().getProcessComponent();
        }

        return processComponent;
    }

    public CreateSnapshotBlock getCreateSnapshot() {
        return deploy.getCreateSnapshot();
    }

    public Boolean createSnapshotChecked() {
        if (getCreateSnapshot() != null) {
            return true;
        }

        return false;
    }


    public String getSnapshotName() {
        String snapshotName = "";

        if (getCreateSnapshot() != null) {
            snapshotName = getCreateSnapshot().getSnapshotName();
        }

        return snapshotName;
    }

    public Boolean getDeployWithSnapshot() {
        if (getCreateSnapshot() != null) {
            return ((getCreateSnapshot()).getDeployWithSnapshot());
        }

        return false;
    }

    public String getDeployVersions() {
        String deployVersions = "";

        if (deploy != null) {
            deployVersions = deploy.getDeployVersions();
        }

        return deployVersions;
    }

    public String getDeployReqProps() {
        String deployReqProps = "";

        if (deploy != null) {
            deployReqProps = deploy.getDeployReqProps();
        }

        return deployReqProps;
    }

    public String getDeployDesc() {
        String deployDesc = "";

        if (deploy != null) {
            deployDesc = deploy.getDeployDesc();
        }

        return deployDesc;
    }

    public Boolean getDeployOnlyChanged() {
        if (deploy.getDeployOnlyChanged() == null) {
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

        if (altUserChecked()) {
            if (getAltUsername().equals("")) {
                throw new AbortException("Alternative username is a required property when specifying the optional"
                        + "'Run as Alternative User' property.");
            }

            listener.getLogger().println("Running job as alternative user '" + getAltUsername() + "'.");

            udClient = udSite.getTempClient(getAltUsername(), getAltPassword());
        }
        else {
            udClient = udSite.getClient();
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

            /* Throw AbortException so that Jenkins will mark job as faulty */
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
