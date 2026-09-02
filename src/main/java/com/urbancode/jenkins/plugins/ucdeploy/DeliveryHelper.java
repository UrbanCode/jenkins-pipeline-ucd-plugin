/**
 * (c) Copyright IBM Corporation 2017.
 * This is licensed under the following license.
 * The Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
 * U.S. Government Users Restricted Rights:  Use, duplication or disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 */

package com.urbancode.jenkins.plugins.ucdeploy;

import hudson.AbortException;
import hudson.ExtensionPoint;
import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;

import jenkins.model.Jenkins;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * This class provides the structure and function around deployment control in
 * IBM UrbanCode Deploy via uDeployRestClient abstracted REST calls import
 * org.codehaus.jettison.json.JSONException;
 *
 */
@SuppressWarnings("deprecation") // Triggered by DefaultHttpClient
public class DeliveryHelper implements Serializable {

    public DeliveryHelper() {}

    public static abstract class DeliveryBlock implements ExtensionPoint, Describable<DeliveryBlock>, Serializable {
        public enum DeliveryType { Push, Pull }
        private DeliveryType deliveryType;

        public DeliveryBlock(DeliveryType deliveryType) {
            this.deliveryType = deliveryType;
        }

        public DeliveryType getDeliveryType() {
            return deliveryType;
        }

        public Descriptor<DeliveryBlock> getDescriptor() {
            return Jenkins.getInstance().getDescriptor(getClass());
        }

        /**
         * Load properties into a properties map
         *
         * @param properties The unparsed properties to load
         * @return The loaded properties map
         * @throws AbortException
         */
        public static Map<String, String> mapProperties(String properties) throws AbortException {
            Map<String, String> mappedProperties = new HashMap<String, String>();

            if (properties != null && !properties.isEmpty()) {
                for (String line : properties.split("\n")) {
                    String[] propDef = line.split("=", 2);

                    if (propDef.length >= 2) {
                        String propName = propDef[0].trim();
                        String propVal = propDef[1].trim();
                        mappedProperties.put(propName, propVal);
                    }
                    else {
                        throw new AbortException("Missing property delimiter '=' in property definition '" + line + "'");
                    }
                }
            }

            return mappedProperties;
        }
    }

    public static class DeliveryDescriptor extends Descriptor<DeliveryBlock> {
        public DeliveryDescriptor(Class<? extends DeliveryBlock> clazz) {
            super(clazz);
        }
        public String getDisplayName() {
            return clazz.getSimpleName();
        }
    }

    public static class Push extends DeliveryBlock {
        private String pushVersion;
        private String baseDir;
        private String fileIncludePatterns;
        private String fileExcludePatterns;
        private String extensions;
        private String charset;
        private String pushProperties;
        private String pushDescription;
        private Boolean pushIncremental;

        @DataBoundConstructor
        public Push(
            String pushVersion,
            String baseDir,
            String fileIncludePatterns,
            String fileExcludePatterns,
            String extensions,
            String charset,
            String pushProperties,
            String pushDescription,
            Boolean pushIncremental)
        {
            super(DeliveryType.Push);
            this.pushVersion = pushVersion;
            this.baseDir = baseDir;
            this.fileIncludePatterns = fileIncludePatterns;
            this.fileExcludePatterns = fileExcludePatterns;
            this.extensions = extensions;
            this.charset = charset;
            this.pushProperties = pushProperties;
            this.pushDescription = pushDescription;
            this.pushIncremental = pushIncremental;
        }

        public String getPushVersion() {
            if (pushVersion != null) {
                return pushVersion;
            }
            else {
                return "";
            }
        }

        public String getBaseDir() {
            if (baseDir != null) {
                return baseDir;
            }
            else {
                return "";
            }
        }

        public String getFileIncludePatterns() {
            if (fileIncludePatterns != null) {
                return fileIncludePatterns;
            }
            else {
                return "";
            }
        }

        public String getFileExcludePatterns() {
            if (fileExcludePatterns != null) {
                return fileExcludePatterns;
            }
            else {
                return "";
            }
        }

        public String getExtensions() {
            if (extensions != null) {
                return extensions;
            }
            else {
                return "";
            }
        }

        public String getCharset() {
            if (charset != null) {
                return charset;
            }
            else {
                return "";
            }
        }

        public String getPushProperties() {
            if (pushProperties != null) {
                return pushProperties;
            }
            else {
                return "";
            }
        }

        public String getPushDescription() {
            if (pushDescription != null) {
                return pushDescription;
            }
            else {
                return "";
            }
        }

        public Boolean getPushIncremental() {
            if (pushIncremental != null) {
                return pushIncremental;
            }
            else {
                return false;
            }
        }

        
        @Extension
        public static final DeliveryDescriptor D = new DeliveryDescriptor(Push.class);
    }

    public static class Pull extends DeliveryBlock {
        private String pullProperties;
        private String pullSourceType;
        private String pullSourceProperties;
        private Boolean pullIncremental;

        @DataBoundConstructor
        public Pull(
            String pullProperties,
            String pullSourceType,
            String pullSourceProperties,
            Boolean pullIncremental)
        {
            super(DeliveryType.Pull);
            this.pullProperties = pullProperties;
            this.pullSourceType = pullSourceType;
            this.pullSourceProperties = pullSourceProperties;
            this.pullIncremental = pullIncremental;
        }

        public String getPullProperties() {
            if (pullProperties != null) {
                return pullProperties;
            }
            else {
                return "";
            }
        }

        public String getPullSourceType() {
            if (pullSourceType != null) {
                return pullSourceType;
            }
            else {
                return "";
            }
        }

        public String getPullSourceProperties() {
            if (pullSourceProperties != null) {
                return pullSourceProperties;
            }
            else {
                return "";
            }
        }

        public Boolean getPullIncremental() {
            if (pullIncremental != null) {
                return pullIncremental;
            }
            else {
                return false;
            }
        }

        @Extension
        public static final DeliveryDescriptor D = new DeliveryDescriptor(Pull.class);
    }
}
