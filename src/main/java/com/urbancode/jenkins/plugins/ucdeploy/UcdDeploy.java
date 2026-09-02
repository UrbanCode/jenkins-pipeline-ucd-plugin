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
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import com.urbancode.ud.client.ApplicationClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build step: Deploy an application in IBM UrbanCode Deploy.
 *
 * Runs an application process on a specified environment with given
 * component versions or a snapshot.
 *
 * Pipeline usage:
 * <pre>
 * step([$class: 'UcdDeploy',
 *       siteName: 'myServer',
 *       deployApp: 'MyApp',
 *       deployEnv: 'TEST',
 *       deployProc: 'Deploy',
 *       deployVersions: 'MyComp:1.0'])
 * </pre>
 */
@SuppressWarnings("deprecation") // Triggered by DefaultHttpClient
public class UcdDeploy extends UcdBuildStep {

    private static final Logger log = LoggerFactory.getLogger(UcdDeploy.class);

    private String deployApp;
    private String deployEnv;
    private String deployProc;
    private String deployVersions;
    private String deployReqProps;
    private String deployDesc;
    private Boolean deployOnlyChanged;
    private Boolean skipWait;

    @DataBoundConstructor
    public UcdDeploy(String deployApp, String deployEnv, String deployProc) {
        this.deployApp = deployApp;
        this.deployEnv = deployEnv;
        this.deployProc = deployProc;
    }

    public String getDeployApp() {
        return deployApp != null ? deployApp : "";
    }

    public String getDeployEnv() {
        return deployEnv != null ? deployEnv : "";
    }

    public String getDeployProc() {
        return deployProc != null ? deployProc : "";
    }

    public String getDeployVersions() {
        return deployVersions != null ? deployVersions : "";
    }

    @DataBoundSetter
    public void setDeployVersions(String deployVersions) {
        this.deployVersions = deployVersions;
    }

    public String getDeployReqProps() {
        return deployReqProps != null ? deployReqProps : "";
    }

    @DataBoundSetter
    public void setDeployReqProps(String deployReqProps) {
        this.deployReqProps = deployReqProps;
    }

    public String getDeployDesc() {
        return deployDesc != null ? deployDesc : "";
    }

    @DataBoundSetter
    public void setDeployDesc(String deployDesc) {
        this.deployDesc = deployDesc;
    }

    public Boolean getDeployOnlyChanged() {
        return deployOnlyChanged != null ? deployOnlyChanged : false;
    }

    @DataBoundSetter
    public void setDeployOnlyChanged(Boolean deployOnlyChanged) {
        this.deployOnlyChanged = deployOnlyChanged;
    }

    public Boolean getSkipWait() {
        return skipWait != null ? skipWait : false;
    }

    @DataBoundSetter
    public void setSkipWait(Boolean skipWait) {
        this.skipWait = skipWait;
    }

    @Override
    protected void execute(Run<?, ?> build, FilePath workspace, Launcher launcher,
                           TaskListener listener, UCDeploySite udSite, EnvVars envVars)
            throws AbortException, InterruptedException, IOException {

        String app = envVars.expand(getDeployApp());
        String env = envVars.expand(getDeployEnv());
        String proc = envVars.expand(getDeployProc());
        String versions = envVars.expand(getDeployVersions());
        String reqProps = envVars.expand(getDeployReqProps());
        String desc = envVars.expand(getDeployDesc());

        if (app.isEmpty()) {
            throw new AbortException("Application Name is a required field.");
        }
        if (env.isEmpty()) {
            throw new AbortException("Environment Name is a required field.");
        }
        if (proc.isEmpty()) {
            throw new AbortException("Application Process Name is a required field.");
        }

        DefaultHttpClient udClient = resolveClient(udSite, listener);
        URI ucdUrl = udSite.getUri();
        ApplicationClient appClient = new ApplicationClient(ucdUrl, udClient);

        // Parse component versions or snapshot
        String snapshot = "";
        Map<String, List<String>> componentVersions = new HashMap<String, List<String>>();

        if (versions.toUpperCase().startsWith("SNAPSHOT=")) {
            if (versions.contains("\n")) {
                throw new AbortException("Only a single SNAPSHOT can be specified.");
            }
            snapshot = versions.replaceFirst("(?i)SNAPSHOT=", "");
            listener.getLogger().println("Deploying SNAPSHOT '" + snapshot + "'");
        } else if (!versions.isEmpty()) {
            componentVersions = readComponentVersions(versions);
            listener.getLogger().println("Deploying component versions '" + componentVersions + "'");
        }

        // Parse request properties
        Map<String, String> requestProperties = readProperties(reqProps);

        // Validate unfilled properties
        try {
            JSONArray unfilledProps = appClient.checkUnfilledApplicationProcessRequestProperties(
                    app, proc, snapshot, requestProperties);
            if (unfilledProps.length() > 0) {
                List<String> props = new ArrayList<String>();
                for (int i = 0; i < unfilledProps.length(); i++) {
                    props.add(unfilledProps.getJSONObject(i).getString("name"));
                }
                throw new AbortException("Required application process request properties were not supplied: "
                        + props.toString());
            }
        } catch (JSONException ex) {
            throw new AbortException("Error checking request properties: " + ex.getMessage());
        }

        // Run deployment
        UUID appProcUUID;
        try {
            appProcUUID = appClient.requestApplicationProcess(
                    app, proc, desc, env, snapshot,
                    getDeployOnlyChanged(), componentVersions, requestProperties);
        } catch (JSONException ex) {
            throw new AbortException("Failed to request deployment: " + ex.getMessage());
        }

        listener.getLogger().println("Starting deployment process '" + proc + "' of application '"
                + app + "' in environment '" + env + "'");
        listener.getLogger().println("Deployment request id: '" + appProcUUID.toString() + "'");

        // Wait for result
        if (!getSkipWait()) {
            listener.getLogger().println("Waiting for DevOps Deploy Server feedback...");
            long startTime = new Date().getTime();
            String deploymentResult = waitForDeployment(appClient, appProcUUID.toString());
            long duration = (new Date().getTime() - startTime) / 1000;

            listener.getLogger().println("Finished deployment in " + duration + " seconds.");
            listener.getLogger().println("Deployment result: " + deploymentResult
                    + " — Details: " + ucdUrl + "/#applicationProcessRequest/" + appProcUUID.toString());
        } else {
            listener.getLogger().println("'Skip Wait' enabled. Returning immediately.");
        }

        // Fetch application properties as env vars (unless skip is set)
        if (!udSite.isSkipProps()) {
            fetchApplicationProperties(udSite, udClient, app, listener);
        }
    }

    private String waitForDeployment(ApplicationClient appClient, String procId)
            throws AbortException {
        while (true) {
            String result;
            try {
                result = appClient.getApplicationProcessStatus(procId);
            } catch (Exception ex) {
                throw new AbortException("Failed to check deployment status: " + ex.getMessage());
            }

            if (result != null && !result.isEmpty()
                    && !result.equalsIgnoreCase("NONE")
                    && !result.equalsIgnoreCase("SCHEDULED FOR FUTURE")) {

                if (result.equalsIgnoreCase("FAULTED")
                        || result.equalsIgnoreCase("FAILED TO START")
                        || result.equalsIgnoreCase("CANCELED")) {
                    throw new AbortException("Deployment failed with result: " + result);
                }
                return result;
            }

            try {
                Thread.sleep(3000);
            } catch (InterruptedException ex) {
                throw new AbortException("Interrupted while waiting for deployment: " + ex.getMessage());
            }
        }
    }

    private void fetchApplicationProperties(UCDeploySite udSite, DefaultHttpClient udClient,
                                             String deployApp, TaskListener listener) {
        try {
            URI ucdUrl = udSite.getUri();
            ApplicationClient fetchClient = new ApplicationClient(ucdUrl, udClient);

            // Use library's getApplication() instead of manually listing all apps
            JSONObject appObj = fetchClient.getApplication(deployApp);
            String applicationId = appObj.getString("id");

            JSONObject propSheet = appObj.getJSONObject("propSheet");
            String versionCount = propSheet.getString("versionCount");

            String propUri = ucdUrl.toString() + "/property/propSheet/applications%26"
                    + applicationId + "%26propSheet." + versionCount;

            HttpGet method = new HttpGet(propUri);
            try {
                HttpResponse response = udClient.execute(method);
                int code = response.getStatusLine().getStatusCode();
                if (code != 200) {
                    listener.getLogger().println("Warning: Could not fetch property sheet (HTTP " + code + ")");
                    return;
                }
                String propData = EntityUtils.toString(response.getEntity());
                JSONObject propObject = new JSONObject(propData);
                JSONArray properties = new JSONArray(propObject.getString("properties"));

                for (int i = 0; i < properties.length(); i++) {
                    JSONObject prop = properties.getJSONObject(i);
                    if ("false".equals(prop.getString("secure"))) {
                        String name = prop.getString("name");
                        String value = prop.getString("value");
                        listener.getLogger().println("Env: " + name + "=" + value);
                        DeployHelper.DeployBlock.createGlobalEnvVar(name, value);
                    }
                }
            } finally {
                method.releaseConnection();
            }
        } catch (Exception e) {
            listener.getLogger().println("Warning: Could not fetch application properties: " + e.getMessage());
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

    private Map<String, String> readProperties(String properties) throws AbortException {
        Map<String, String> result = new HashMap<String, String>();
        if (properties != null && !properties.isEmpty()) {
            for (String line : properties.split("\n")) {
                String[] parts = line.split("=", 2);
                if (parts.length >= 2) {
                    result.put(parts[0].trim(), parts[1].trim());
                } else {
                    throw new AbortException("Missing '=' in property: '" + line + "'");
                }
            }
        }
        return result;
    }

    @Extension
    public static class DescriptorImpl extends UcdBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "DevOps Deploy - Deploy Application";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/ibm-ucdeploy-build-steps/steps/deploy/help.html";
        }
    }
}
