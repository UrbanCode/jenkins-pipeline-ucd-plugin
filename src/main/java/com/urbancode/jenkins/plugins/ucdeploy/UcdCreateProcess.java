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

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import com.urbancode.ud.client.ApplicationClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build step: Create an application process in IBM UrbanCode Deploy.
 *
 * Automatically creates a standard deployment application process
 * wrapping a specified component process, if it doesn't already exist.
 *
 * Pipeline usage:
 * <pre>
 * step([$class: 'UcdCreateProcess',
 *       siteName: 'myServer',
 *       applicationName: 'MyApp',
 *       processName: 'Deploy',
 *       componentProcessName: 'Install'])
 * </pre>
 */
@SuppressWarnings("deprecation") // Triggered by DefaultHttpClient
public class UcdCreateProcess extends UcdBuildStep {

    private static final Logger log = LoggerFactory.getLogger(UcdCreateProcess.class);

    private String applicationName;
    private String processName;
    private String componentProcessName;

    @DataBoundConstructor
    public UcdCreateProcess(String applicationName, String processName, String componentProcessName) {
        this.applicationName = applicationName;
        this.processName = processName;
        this.componentProcessName = componentProcessName;
    }

    public String getApplicationName() {
        return applicationName != null ? applicationName : "";
    }

    public String getProcessName() {
        return processName != null ? processName : "";
    }

    public String getComponentProcessName() {
        return componentProcessName != null ? componentProcessName : "";
    }

    @Override
    protected void execute(Run<?, ?> build, FilePath workspace, Launcher launcher,
                           TaskListener listener, UCDeploySite udSite, EnvVars envVars)
            throws AbortException, InterruptedException, IOException {

        String app = envVars.expand(getApplicationName());
        String proc = envVars.expand(getProcessName());
        String compProc = envVars.expand(getComponentProcessName());

        if (app.isEmpty()) {
            throw new AbortException("Application Name is a required field.");
        }
        if (proc.isEmpty()) {
            throw new AbortException("Process Name is a required field.");
        }
        if (compProc.isEmpty()) {
            throw new AbortException("Component Process Name is a required field.");
        }

        DefaultHttpClient udClient = resolveClient(udSite, listener);
        ApplicationClient appClient = new ApplicationClient(udSite.getUri(), udClient);

        ProcessHelper processHelper = new ProcessHelper(appClient, listener, envVars);
        ProcessHelper.CreateProcessBlock processBlock = new ProcessHelper.CreateProcessBlock(compProc);
        processHelper.createProcess(app, proc, processBlock);

        listener.getLogger().println("UCD Create Process completed successfully.");
    }

    @Extension
    public static class DescriptorImpl extends UcdBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "UCD - Create Application Process";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/ibm-ucdeploy-build-steps/steps/create-process/help.html";
        }
    }
}
