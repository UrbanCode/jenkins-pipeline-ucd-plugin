/**
 * © Copyright IBM Corporation 2017.
 * This is licensed under the following license.
 * The Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
 * U.S. Government Users Restricted Rights:  Use, duplication or disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 */
 
package com.urbancode.jenkins.plugins.ucdeploy;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.TaskListener;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.http.impl.client.DefaultHttpClient;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;

import com.urbancode.jenkins.plugins.ucdeploy.ComponentHelper.CreateComponentBlock;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.DeliveryBlock;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.Pull;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.Push;
import com.urbancode.ud.client.ApplicationClient;
import com.urbancode.ud.client.ComponentClient;
import com.urbancode.ud.client.PropertyClient;
import com.urbancode.ud.client.VersionClient;

/**
 * This class provides the structure and function around version control in IBM
 * UrbanCode Deploy via uDeployRestClient abstracted REST calls import
 * org.codehaus.jettison.json.JSONException;
 *
 */
@SuppressWarnings("deprecation") // Triggered by DefaultHttpClient
public class VersionHelper {
    private ApplicationClient appClient;
    private ComponentClient compClient;
    private PropertyClient propClient;
    private VersionClient verClient;
    private TaskListener listener;
    private EnvVars envVars;

    public VersionHelper(URI ucdUrl, DefaultHttpClient httpClient, TaskListener listener, EnvVars envVars) {
        appClient = new ApplicationClient(ucdUrl, httpClient);
        compClient = new ComponentClient(ucdUrl, httpClient);
        propClient = new PropertyClient(ucdUrl, httpClient);
        verClient = new VersionClient(ucdUrl, httpClient);
        this.listener = listener;
        this.envVars = envVars;
    }

    public static class VersionBlock implements Serializable {
        private String componentName;
        private String componentTag;
        private CreateComponentBlock createComponent;
        private DeliveryBlock delivery;

        @DataBoundConstructor
        public VersionBlock(String componentName,
                            String componentTag,
                            CreateComponentBlock createComponent,
                            DeliveryBlock delivery)
        {
            this.componentName = componentName;
            this.componentTag = componentTag;

            this.createComponent = createComponent;
            this.delivery = delivery;
        }

        public String getComponentName() {
            return componentName;
        }

        public String getComponentTag() {
            if (componentTag != null) {
                return componentTag;
            }
            else {
                return "";
            }
        }

        public CreateComponentBlock getCreateComponentBlock() {
            return createComponent;
        }

        public Boolean createComponentChecked() {
            if (getCreateComponentBlock() == null) {
                return false;
            }
            else {
                return true;
            }
        }

        public DeliveryBlock getDeliveryBlock() {
            return delivery;
        }
    }

    /**
     * Creates a new component version, either pushing from Jenkins or triggering source config pull
     *
     * @param versionBlock The object containing the data strucutre of the version
     * @param linkName The name to give the component version link
     * @param linkUrl The url to link as a component version
     * @throws AbortException
     *
     */
    public void createVersion(VersionBlock versionBlock, String linkName, String linkUrl) throws AbortException {
        ComponentHelper componentHelper = new ComponentHelper(appClient, compClient, listener, envVars);
        String componentName = envVars.expand(versionBlock.getComponentName());
        String componentTag = envVars.expand(versionBlock.getComponentTag());

        if (componentName == null || componentName.isEmpty()) {
            throw new AbortException("Component Name is a required property.");
        }

        // create component
        if (versionBlock.createComponentChecked()) {
            componentHelper.createComponent(componentName,
                                            versionBlock.getCreateComponentBlock(),
                                            versionBlock.getDeliveryBlock());
        }

        // tag component
        if (componentTag != null && !componentTag.isEmpty()) {
            componentHelper.addTag(componentName, componentTag);
        }

        // create version
        if (versionBlock.getDeliveryBlock().getDeliveryType() == DeliveryBlock.DeliveryType.Push) {
            Push pushBlock = (Push)versionBlock.getDeliveryBlock();
            String version = envVars.expand(pushBlock.getPushVersion());
            listener.getLogger().println("Creating new component version '" + version + "' on component '" + componentName +
                                         "'");
            if (version == null || version.isEmpty() || version.length() > 255) {
                throw new AbortException("Failed to create version '" + version + "' in UrbanCode Deploy. UrbanCode Deploy " +
                                         "version name length must be between 1 and  255 characters long. (Current length: " +
                                         version.length() + ")");
            }

            UUID versionId;
            try {
                versionId = verClient.createVersion(componentName, version, envVars.expand(pushBlock.getPushDescription()));
            }
            catch (Exception ex) {
                throw new AbortException("Failed to create component version: " + ex.getMessage());
            }
            listener.getLogger().println("Successfully created component version with UUID '" + versionId.toString() + "'");

            // upload files
            listener.getLogger().println("Uploading files to version '" + version + "' on component '" + componentName + "'");
            uploadVersionFiles(envVars.expand(pushBlock.getBaseDir()),
                               componentName,
                               version,
                               envVars.expand(pushBlock.getFileIncludePatterns()),
                               envVars.expand(pushBlock.getFileExcludePatterns()));
            listener.getLogger().println("Successfully uploaded files");

            // set version properties
            listener.getLogger().println("Setting properties for version '" + version + "' on component '" + componentName + "'");
            setComponentVersionProperties(componentName,
                                          version,
                                          DeliveryBlock.mapProperties(envVars.expand(pushBlock.getPushProperties())));

            // add link
            listener.getLogger().println("Creating component version link '" + linkName + "' to URL '" + linkUrl + "'");
            try {
                compClient.addComponentVersionLink(componentName, version, linkName, linkUrl);
            }
            catch (Exception ex) {
                throw new AbortException("Failed to add a version link: " + ex.getMessage());
            }
        }

        // import version
        else if (versionBlock.getDeliveryBlock().getDeliveryType() == DeliveryBlock.DeliveryType.Pull) {
            Pull pullBlock = (Pull)versionBlock.getDeliveryBlock();

            Map<String, String> mappedProperties = DeliveryBlock.mapProperties(envVars.expand(pullBlock.getPullProperties()));
            listener.getLogger().println("Using runtime properties " + mappedProperties);

            try {
                compClient.importComponentVersions(componentName, mappedProperties);
            }
            catch (IOException ex) {
                throw new AbortException("An error occurred while importing component versions on component '" + componentName +
                                         "' : " + ex.getMessage());
            }
            catch (JSONException ex) {
                throw new AbortException("An error occurred while creating JSON version import object : " + ex.getMessage());
            }
        }

        // invalid type
        else {
            throw new AbortException("Invalid Delivery Type: " + versionBlock.getDeliveryBlock().getDeliveryType());
        }
    }

    /**
     * Upload files to component version
     *
     * @param baseDir The base directory of the files to upload
     * @param component The component to upload the files to
     * @param version The version of the component to upload the files to
     * @param includePatterns The patterns to include in the upload
     * @param excludePatterns The patterns to exclude in the upload
     * @throws AbortException
     */
    public void uploadVersionFiles(
        String baseDir,
        String component,
        String version,
        String includePatterns,
        String excludePatterns)
    throws AbortException {
        String[] includes = splitFiles(includePatterns);
        String[] excludes = splitFiles(excludePatterns);

        File base = new File(baseDir);

        if (!base.exists()) {
            throw new AbortException("Base artifact directory " + base.getAbsolutePath() + " does not exist");
        }

        try {
            verClient.addVersionFiles(component,
                                      version,
                                      base,
                                      "",
                                      includes,
                                      excludes,
                                      true,
                                      true);
        }
        catch (Exception ex) {
            throw new AbortException("Failed to upload files: " + ex.getMessage());
        }
    }

    /**
     * Split a string of filenames by newline and remove empty/null entries
     *
     * @param patterns Newline separated list of file patterns
     * @return Array of file patterns
     */
    private String[] splitFiles(String patterns) {
        List<String> newList = new ArrayList<String>();

        String[] patternList = patterns.split("\n");

        for (String pattern : patternList) {
            if (pattern != null && pattern.trim().length() > 0) {
                newList.add(pattern.trim());
            }
        }

        return newList.toArray(new String[newList.size()]);
    }

    /**
     * Set properties on a component version, handling property definitions
     *
     * @param component The name of the component which contains the component version
     * @param version The name of the version on the component to set the properties for
     * @param properties the map of properties to set on the component version
     * @throws AbortException
     */
    private void setComponentVersionProperties(
        String component,
        String version,
        Map<String,String> properties)
    throws AbortException {
        if (!properties.isEmpty()) {
            JSONObject propSheetDef;
            String propSheetDefId;
            String propSheetDefPath;
            JSONArray existingPropDefJsonArray;

            // acquire prop sheet definition and it's existing propDefs
            try {
                propSheetDef = compClient.getComponentVersionPropSheetDef(component);
                propSheetDefId = (String) propSheetDef.get("id");
                propSheetDefPath = (String) propSheetDef.get("path");
                propSheetDefPath = propSheetDefPath.replaceAll("/", "&");
                listener.getLogger().println("Property Sheet Path is " + propSheetDefPath);
                existingPropDefJsonArray = propClient.getPropSheetDefPropDefs(propSheetDefPath);
            }
            catch (IOException ex) {
                throw new AbortException("An error occurred acquiring property sheets: " + ex.getMessage());
            }
            catch (JSONException e) {
                throw new AbortException("An error occurred while processing the JSON object of the version property sheet: " +
                                         e.getMessage());
            }

            // update existing properties
            for (int i = 0; i < existingPropDefJsonArray.length(); i++) {
                JSONObject propDef;
                String propName;

                try {
                    propDef = existingPropDefJsonArray.getJSONObject(i);
                    propName = propDef.getString("name");
                }
                catch (JSONException ex) {
                    throw new AbortException("An error occurred while processing the JSON object of an existing property " +
                                             "definition: " + ex.getMessage());
                }

                String propValue = properties.get(propName);

                if (propValue != null) {
                    try {
                        listener.getLogger().println("Updating version property '" + propName + "' to '" + propValue + "'");
                        verClient.setVersionProperty(version, component, propName, propValue, false);
                        listener.getLogger().println("Successfully updated version property");
                    }
                    catch (IOException ex) {
                        throw new AbortException("An error occurred while updating the property: " + ex.getMessage());
                    }
                }

                properties.remove(propName);
            }

            // create new properties
            if (!properties.isEmpty()) {
                UUID propSheetDefUUID = UUID.fromString(propSheetDefId);

                for (Map.Entry<String, String> property : properties.entrySet()) {
                    String propName = property.getKey();
                    String propDescription = "";
                    String propLabel = "";
                    Boolean required = false;
                    String propType = "TEXT";
                    String propValue = property.getValue();

                    try {
                        listener.getLogger().println("Creating property definition for '" + propName + "'");
                        propClient.createPropDef(propSheetDefUUID,
                                                 propSheetDefPath,
                                                 propName,
                                                 propDescription,
                                                 propLabel,
                                                 required,
                                                 propType,
                                                 "");
                        listener.getLogger().println("Setting version property '" + propName + "' to '" + propValue + "'");
                        verClient.setVersionProperty(version, component, propName, propValue, false);
                        listener.getLogger().println("Successfully set version property");
                    }
                    catch (IOException ex) {
                        throw new AbortException("An error occurred while setting the version property: " + ex.getMessage());
                    }
                    catch (JSONException ex) {
                        throw new AbortException("An error occurred while processing the JSON object for the property: " +
                                                 ex.getMessage());
                    }
                }
            }
        }
    }
}
