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
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;

import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.DeliveryBlock;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.Pull;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.Push;
import com.urbancode.ud.client.ApplicationClient;
import com.urbancode.ud.client.ComponentClient;
import org.apache.http.entity.ContentType;

/**
 * This class provides the structure and function around component control in
 * IBM UrbanCode Deploy via uDeployRestClient abstracted REST callsimport
 * org.codehaus.jettison.json.JSONException;
 *
 */
@SuppressWarnings("deprecation") // Triggered by DefaultHttpClient
public class ComponentHelper {
    private ApplicationClient appClient;
    private ComponentClient compClient;
    private TaskListener listener;
    private EnvVars envVars;
    private String description = "Created from Jekins";
    private int templateVersion = -1;
    private Boolean importAutomatically = false;
    private Boolean useVfs = true;

    public ComponentHelper(ApplicationClient appClient, ComponentClient compClient, TaskListener listener,
            EnvVars envVars) {
        this.appClient = appClient;
        this.compClient = compClient;
        this.listener = listener;
        this.envVars = envVars;
    }

    public static class CreateComponentBlock implements Serializable {
        private String componentTemplate;
        private String componentApplication;

        @DataBoundConstructor
        public CreateComponentBlock(String componentTemplate, String componentApplication) {
            this.componentTemplate = componentTemplate;
            this.componentApplication = componentApplication;
        }

        public String getComponentTemplate() {
            if (componentTemplate != null) {
                return componentTemplate;
            } else {
                return "";
            }
        }

        public String getComponentApplication() {
            if (componentApplication != null) {
                return componentApplication;
            } else {
                return "";
            }
        }
    }

    public void createComponent(String name, CreateComponentBlock componentBlock, DeliveryBlock deliveryBlock)
            throws AbortException {
        // definitions needed for ComponentClient createComponent method
        String componentName;
        String description;
        String sourceConfigPlugin;
        String defaultVersionType;
        String templateName;
        int templateVersion;
        Boolean importAutomatically;
        Boolean useVfs;
        Map<String, String> properties;

        // default values
        description = this.description;
        templateVersion = this.templateVersion;
        importAutomatically = this.importAutomatically;
        useVfs = this.useVfs;

        // shared properties
        componentName = envVars.expand(name);
        templateName = envVars.expand(componentBlock.getComponentTemplate());
        
        // Get path of the JAR file
        String jarPath = ContentType.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .toURI()
            .getPath();
        listener.getLogger().println("[JAR Path : ]" + jarPath);

        // properties based on delivery type
        if (deliveryBlock == null) {
            throw new AbortException("You must specify a Delivery Type.");
        } else if (deliveryBlock.getDeliveryType() == DeliveryBlock.DeliveryType.Push) {
            Push pushBlock = (Push) deliveryBlock;
            sourceConfigPlugin = "";
            if (pushBlock.getPushIncremental()) {
                defaultVersionType = "INCREMENTAL";
            } else {
                defaultVersionType = "FULL";
            }
            properties = new HashMap<String, String>();
        } else if (deliveryBlock.getDeliveryType() == DeliveryBlock.DeliveryType.Pull) {
            Pull pullBlock = (Pull) deliveryBlock;
            sourceConfigPlugin = envVars.expand(pullBlock.getPullSourceType());
            if (pullBlock.getPullIncremental()) {
                defaultVersionType = "INCREMENTAL";
            } else {
                defaultVersionType = "FULL";
            }
            properties = DeliveryBlock.mapProperties(envVars.expand(pullBlock.getPullSourceProperties()));
        } else {
            throw new AbortException("Invalid Delivery Type: " + deliveryBlock.getDeliveryType());
        }

        // check if comopnent already exists
        UUID componentUUID = null;
        try {
            listener.getLogger().println("Checking the UCD server for an existing component '" + componentName + "'");
            componentUUID = compClient.getComponentUUID(componentName);
            listener.getLogger()
                    .println("The component already exists on the UCD server with UUID '" + componentUUID + "'");
        } catch (IOException ex) {
            listener.getLogger().println("The component does not exist on the UCD server");
        } catch (JSONException ex) {
            throw new AbortException(
                    "An error occurred while checking the UCD server for the component : " + ex.getMessage());
        }

        // create new component
        if (componentUUID == null) {
            try {
                listener.getLogger().println("Creating new component '" + componentName + "'");
                componentUUID = compClient.createComponent(componentName, description, sourceConfigPlugin,
                        defaultVersionType, templateName, templateVersion, importAutomatically, useVfs, properties);
                listener.getLogger().println("Successfully created the component with UUID '" + componentUUID + "'");
            } catch (IOException ex) {
                throw new AbortException("Failed to create the component: " + ex.getMessage());
            } catch (JSONException ex) {
                throw new AbortException(
                        "An error occurred while processing the JSON object for a new component: " + ex.getMessage());
            }
        }

        // create component properties
        if (deliveryBlock.getDeliveryType() == DeliveryBlock.DeliveryType.Pull) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                try {
                    listener.getLogger().println("Setting component property '" + key + "' to '" + value + "'");
                    compClient.setComponentProperty(componentName, key, value, false);
                    listener.getLogger().println("Successfully set property");
                } catch (IOException ex) {
                    throw new AbortException("Failed to set component property: " + ex.getMessage());
                }
            }
        }

        // add to application
        String application = envVars.expand(componentBlock.getComponentApplication());
        if (!application.isEmpty()) {
            // check for component
            boolean componentExists = false;
            try {
                listener.getLogger().println("Checking the UCD server for existing component '" + componentName
                        + "' in " + "application '" + application + "'");
                JSONArray serverComponents = appClient.getApplicationComponents(application);
                for (int i = 0; i < serverComponents.length(); i++) {
                    JSONObject serverComponent = serverComponents.getJSONObject(i);
                    String serverComponentName = serverComponent.getString("name");
                    if (componentName.equals(serverComponentName)) {
                        componentExists = true;
                    }
                }
            } catch (IOException ex) {
                throw new AbortException(
                        "An error occurred while retrieving application components : " + ex.getMessage());
            } catch (JSONException ex) {
                throw new AbortException(
                        "An error occurred while processing the JSON object for the application components: "
                                + ex.getMessage());
            }

            if (componentExists) {
                listener.getLogger().println("Component already exists in the application, does not need to be added");
            }

            // add component
            else {
                try {
                    listener.getLogger()
                            .println("Adding component '" + componentName + "' to application '" + application + "'");
                    appClient.addComponentToApplication(application, componentName);
                    listener.getLogger().println("Successfully added component");
                } catch (IOException ex) {
                    throw new AbortException(
                            "An error occurred while adding the component to the application : " + ex.getMessage());
                }
            }
        }
    }

    public void addTag(String name, String tag) throws AbortException {
        try {
            compClient.addTagToComponent(name, tag);
        } catch (IOException ex) {
            throw new AbortException("An error occurred while tagging the component : " + ex.getMessage());
        }
    }
}
