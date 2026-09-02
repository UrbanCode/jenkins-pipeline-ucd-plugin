/**
 * (c) Copyright IBM Corporation 2017.
 * This is licensed under the following license.
 * The Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
 * U.S. Government Users Restricted Rights:  Use, duplication or disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 */
 
package com.urbancode.jenkins.plugins.ucdeploy;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.TaskListener;

import java.io.IOException;
import java.util.UUID;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;

import com.urbancode.ud.client.ApplicationClient;

/**
 * This class provides the structure and function around application process control in
 * IBM UrbanCode Deploy via uDeployRestClient abstracted REST callsimport org.codehaus.jettison.json.JSONException;
 *
 */
@SuppressWarnings("deprecation") // Triggered by DefaultHttpClient
public class ProcessHelper {
    private ApplicationClient appClient;
    private TaskListener listener;
    private EnvVars envVars;
    private String description = "Created from Jenkins";

    public ProcessHelper(ApplicationClient appClient, TaskListener listener, EnvVars envVars) {
        this.appClient = appClient;
        this.listener = listener;
        this.envVars = envVars;
    }

    public static class CreateProcessBlock {
        private String processComponent;

        @DataBoundConstructor
        public CreateProcessBlock(String processComponent) {
            this.processComponent = processComponent;
        }

        public String getProcessComponent() {
            return processComponent;
        }
    }

    /**
     * Create an application process in IBM UrbanCode Deploy
     *
     * @param application The name of the application to add the process to
     * @param applicationProcess The name of the application process to create
     * @param processBlock The CreateProcessBlock containing the strucutre of the application process information
     */
    public void createProcess(String application,
                              String applicationProcess,
                              CreateProcessBlock processBlock)
    throws AbortException {
        String description = this.description;
        String componentProcess = envVars.expand(processBlock.getProcessComponent());

        // check if application process already exists
        JSONObject appProcObj = null;
        try {
            listener.getLogger().println("Checking the DevOps Deploy server for existing application process '" + applicationProcess + "'");
            appProcObj = appClient.getApplicationProcess(application, applicationProcess);
            listener.getLogger().println("The application process already exists on the DevOps Deploy server");
        }
        catch (IOException ex) {
            listener.getLogger().println("The application process does not exist on the DevOps Deploy server");
        }
        catch (JSONException ex) {
            throw new AbortException("An error occurred while checking the DevOps Deploy server for the application process : "
                    + ex.getMessage());
        }

        if (appProcObj == null) {
            listener.getLogger().println("Creating new application process '" + applicationProcess + "'");
            try {
                String applicationProcessJson = constructAppProcJson(
                    applicationProcess,
                    application,
                    description,
                    componentProcess);
                UUID appProcUUID = appClient.createApplicationProcess(applicationProcessJson);
                listener.getLogger().println("Successfully created the application process with UUID '" + appProcUUID);
            }
            catch (IOException ex) {
                throw new AbortException("An error occurred while creating a new application process: " + ex.getMessage());
            }
            catch (JSONException ex) {
                throw new AbortException("An error occurred while processing the JSON object for a new application process : "
                        + ex.getMessage());
            }
        }
    }

    /**
     * Construct a JSON representation of an Application Process
     * to pass to the ApplictionClient
     *
     * @param applicationProcess The name of the new Application Process to create
     * @param application The name of the application to create the Application Process in
     * @param description The description to give the new application process
     * @param componentProcess The component process to run in the new application process
     * @return The string of the JSON representation of the new Application Process
     * @throws AbortException
     */
    private String constructAppProcJson(
        String applicationProcess,
        String application,
        String description,
        String componentProcess)
    throws AbortException {
        /* Relationship between JSON objects:
         * appProc {
         *     procActivity {
         *         procSteps [
         *             finishStep,
         *             installStep [
         *                 compEnvIt [
         *                     invVerDiff [
         *                         compProc
         *                     ]
         *                 ]
         *             ]
         *         ],
         *         edge [
         *             installEdge,
         *             finishEdge
         *         ],
         *         offsets [
         *             installOffset,
         *             finishOffset
         *         ]
         *     }
         * }
         */
        String INSTALL_STEP_NAME = "Install Components";
        JSONObject appProc = new JSONObject();
        try {
            // base application process
            appProc.put("name", applicationProcess);
            appProc.put("application", application);
            appProc.put("description", description);
            appProc.put("inventoryManagementType", "AUTOMATIC");
            appProc.put("offlineAgentHandling", "PRE_EXECUTION_CHECK");

            // application process root activity
            JSONObject procActivity = new JSONObject();
            procActivity.put("type", "graph");
            procActivity.put("name", "GRAPH");

            // application process steps
            JSONArray procSteps = new JSONArray();

            // finish step
            JSONObject finishStep = new JSONObject();
            finishStep.put("type", "finish");
            finishStep.put("name", "FINISH");
            procSteps.put(finishStep);

            // install step
            JSONObject installStep = new JSONObject();
            installStep.put("name", INSTALL_STEP_NAME);
            installStep.put("componentProcessName", componentProcess);
            installStep.put("activity.componentProcess.name", componentProcess);
            installStep.put("type", "multiComponentEnvironmentIterator");
            installStep.put("failFast", "false");
            installStep.put("runOnlyOnFirst", "false");
            installStep.put("preconditionScript", "");
            installStep.put("maxIteration", "-1");

            // component environment iterator element
            JSONObject compEnvIt = new JSONObject();
            compEnvIt.put("type", "componentEnvironmentIterator");
            compEnvIt.put("name", UUID.randomUUID().toString().replaceAll("-", ""));
            compEnvIt.put("tagId", "");
            compEnvIt.put("runOnlyOnFirst", "false");

            // inventory version diff element
            JSONObject invVerDiff = new JSONObject();
            invVerDiff.put("type", "inventoryVersionDiff");
            invVerDiff.put("status", "Active");
            invVerDiff.put("name", UUID.randomUUID().toString().replaceAll("-", ""));

            // component process
            JSONObject compProc = new JSONObject();
            compProc.put("type", "componentProcess");
            compProc.put("name", applicationProcess);
            compProc.put("componentProcessName", componentProcess);
            compProc.put("activity.componentProcess.name", componentProcess);
            compProc.put("allowFailure", "false");
            compProc.put("children", new JSONObject());

            // add children to parent elements
            invVerDiff.put("children", new JSONArray().put(compProc));
            compEnvIt.put("children", new JSONArray().put(invVerDiff));
            installStep.put("children", new JSONArray().put(compEnvIt));
            procSteps.put(installStep);
            procActivity.put("children", procSteps);

            // install step edges
            JSONObject installEdge = new JSONObject();
            installEdge.put("to", INSTALL_STEP_NAME);
            installEdge.put("type", "ALWAYS");
            installEdge.put("value", "");

            // finish step edges
            JSONObject finishEdge = new JSONObject();
            finishEdge.put("to", "FINISH");
            finishEdge.put("from", INSTALL_STEP_NAME);
            finishEdge.put("type", "ALWAYS");
            finishEdge.put("value", "");

            // edges
            JSONArray edges = new JSONArray();
            edges.put(installEdge);
            edges.put(finishEdge);
            procActivity.put("edges", edges);

            // install step offset
            JSONObject installOffset = new JSONObject();
            installOffset.put("name", INSTALL_STEP_NAME);
            installOffset.put("x", "-21");
            installOffset.put("y", "191");
            installOffset.put("h", "50");
            installOffset.put("w", "330");

            // finish step offset
            JSONObject finishOffset = new JSONObject();
            finishOffset.put("name", "FINISH");
            finishOffset.put("x", "-5");
            finishOffset.put("y", "420");
            finishOffset.put("h", "50");
            finishOffset.put("w", "90");

            // edges
            JSONArray offsets = new JSONArray();
            offsets.put(installOffset);
            offsets.put(finishOffset);
            procActivity.put("offsets", offsets);
            appProc.put("rootActivity", procActivity);
        }
        catch (JSONException ex) {
            throw new AbortException("An error occured while creating the Application Process JSON structure: "
                + ex.getMessage());
        }

        return appProc.toString();
    }
}
