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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import com.urbancode.ud.client.ApplicationClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build step: Create or update a snapshot in IBM UrbanCode Deploy.
 *
 * Can create a snapshot of an environment's current state, or create
 * a snapshot from specific component versions.
 *
 * Pipeline usage:
 * <pre>
 * step([$class: 'UcdCreateSnapshot',
 *       siteName: 'myServer',
 *       snapshotName: 'snapshot-${BUILD_NUMBER}',
 *       applicationName: 'MyApp',
 *       environmentName: 'TEST'])
 * </pre>
 */
@SuppressWarnings("deprecation") // Triggered by DefaultHttpClient
public class UcdCreateSnapshot extends UcdBuildStep {

    private static final Logger log = LoggerFactory.getLogger(UcdCreateSnapshot.class);

    private String snapshotName;
    private String applicationName;
    private String environmentName;
    private String description;
    private String componentVersions;
    private Boolean useComponentVersions;
    private Boolean updateExisting;

    @DataBoundConstructor
    public UcdCreateSnapshot(String snapshotName, String applicationName) {
        this.snapshotName = snapshotName;
        this.applicationName = applicationName;
    }

    public String getSnapshotName() {
        return snapshotName != null ? snapshotName : "";
    }

    public String getApplicationName() {
        return applicationName != null ? applicationName : "";
    }

    public String getEnvironmentName() {
        return environmentName != null ? environmentName : "";
    }

    @DataBoundSetter
    public void setEnvironmentName(String environmentName) {
        this.environmentName = environmentName;
    }

    public String getDescription() {
        return description != null ? description : "";
    }

    @DataBoundSetter
    public void setDescription(String description) {
        this.description = description;
    }

    public String getComponentVersions() {
        return componentVersions != null ? componentVersions : "";
    }

    @DataBoundSetter
    public void setComponentVersions(String componentVersions) {
        this.componentVersions = componentVersions;
    }

    public Boolean getUseComponentVersions() {
        return useComponentVersions != null ? useComponentVersions : false;
    }

    @DataBoundSetter
    public void setUseComponentVersions(Boolean useComponentVersions) {
        this.useComponentVersions = useComponentVersions;
    }

    public Boolean getUpdateExisting() {
        return updateExisting != null ? updateExisting : false;
    }

    @DataBoundSetter
    public void setUpdateExisting(Boolean updateExisting) {
        this.updateExisting = updateExisting;
    }

    @Override
    protected void execute(Run<?, ?> build, FilePath workspace, Launcher launcher,
                           TaskListener listener, UCDeploySite udSite, EnvVars envVars)
            throws AbortException, InterruptedException, IOException {

        String snapshot = envVars.expand(getSnapshotName());
        String app = envVars.expand(getApplicationName());
        String env = envVars.expand(getEnvironmentName());
        String desc = envVars.expand(getDescription());
        String versions = envVars.expand(getComponentVersions());

        if (snapshot.isEmpty()) {
            throw new AbortException("Snapshot Name is a required field (must be 1-255 characters).");
        }
        if (snapshot.length() > 255) {
            throw new AbortException("Snapshot Name must not exceed 255 characters (current: " + snapshot.length() + ").");
        }
        if (app.isEmpty()) {
            throw new AbortException("Application Name is a required field.");
        }

        DefaultHttpClient udClient = resolveClient(udSite, listener);
        ApplicationClient appClient = new ApplicationClient(udSite.getUri(), udClient);
        SnapshotHelper snapshotHelper = new SnapshotHelper(udSite.getUri(), udClient, appClient);

        listener.getLogger().println("Creating snapshot '" + snapshot + "' for application '" + app + "'.");

        try {
            if (getUseComponentVersions() && !versions.isEmpty()) {
                Map<String, List<String>> compVersions = readComponentVersions(versions);
                appClient.createSnapshot(snapshot, desc, app, compVersions);
                listener.getLogger().println("Created snapshot from specified component versions.");
            } else if (!env.isEmpty()) {
                appClient.createSnapshotOfEnvironment(env, app, snapshot, desc);
                listener.getLogger().println("Created snapshot of environment '" + env + "'.");
            } else {
                Map<String, List<String>> emptyVersions = new HashMap<String, List<String>>();
                if (!versions.isEmpty()) {
                    emptyVersions = readComponentVersions(versions);
                }
                appClient.createSnapshot(snapshot, desc, app, emptyVersions);
                listener.getLogger().println("Created snapshot.");
            }
        } catch (Exception ex) {
            String msg = ex.getMessage();
            String checkStr = "Snapshot with name " + snapshot + " already exists for this application";
            if (msg != null && msg.contains(checkStr) && getUpdateExisting()) {
                listener.getLogger().println("Snapshot already exists. Updating component versions...");
                updateSnapshotVersions(snapshotHelper, snapshot, app, versions, listener);
            } else {
                throw new AbortException("Failed to create snapshot: " + msg);
            }
        }

        listener.getLogger().println("DevOps Deploy Create Snapshot completed successfully.");
    }

    private void updateSnapshotVersions(SnapshotHelper snapshotHelper, String snapshot, String app,
                                         String versions, TaskListener listener)
            throws AbortException {
        if (versions == null || versions.isEmpty()) {
            return;
        }

        Map<String, List<String>> componentVersions = readComponentVersions(versions);

        try {
            JSONArray snapshotVersions = snapshotHelper.getSnapshotVersions(snapshot, app);
            Map<String, JSONArray> compVersionMap = new HashMap<String, JSONArray>();

            for (int i = 0; i < snapshotVersions.length(); i++) {
                JSONObject snapshotComponent = snapshotVersions.getJSONObject(i);
                String name = snapshotComponent.getString("name");
                JSONArray vers = snapshotComponent.getJSONArray("desiredVersions");
                compVersionMap.put(name, vers);
            }

            for (Map.Entry<String, List<String>> entry : componentVersions.entrySet()) {
                String component = entry.getKey();
                JSONArray oldVersions = compVersionMap.get(component);

                if (oldVersions != null) {
                    for (int i = 0; i < oldVersions.length(); i++) {
                        JSONObject old = oldVersions.getJSONObject(i);
                        listener.getLogger().println("Removing version '" + old.getString("name")
                                + "' from component '" + component + "' in snapshot.");
                        snapshotHelper.removeVersionFromSnapshot(snapshot, app, old.getString("id"), component);
                    }
                }

                for (String version : entry.getValue()) {
                    listener.getLogger().println("Adding version '" + version
                            + "' of component '" + component + "' to snapshot.");
                    snapshotHelper.addVersionToSnapshot(snapshot, app, version, component);
                }
            }
        } catch (JSONException ex) {
            throw new AbortException("Error updating snapshot versions: " + ex.getMessage());
        } catch (IOException ex) {
            throw new AbortException("Error updating snapshot versions: " + ex.getMessage());
        }
    }

    private Map<String, List<String>> readComponentVersions(String raw) throws AbortException {
        Map<String, List<String>> result = new HashMap<String, List<String>>();
        for (String line : raw.split("\n")) {
            if (line != null && !line.trim().isEmpty()) {
                int delim = line.indexOf(':');
                if (delim <= 0) {
                    throw new AbortException("Component/version pairs must be: {Component}:{Version}");
                }
                String comp = line.substring(0, delim).trim();
                String ver = line.substring(delim + 1).trim();
                List<String> versions = result.get(comp);
                if (versions == null) {
                    versions = new ArrayList<String>();
                    result.put(comp, versions);
                }
                versions.add(ver);
            }
        }
        return result;
    }

    @Extension
    public static class DescriptorImpl extends UcdBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "DevOps Deploy - Create Snapshot";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/ibm-ucdeploy-build-steps/steps/create-snapshot/help.html";
        }
    }
}
