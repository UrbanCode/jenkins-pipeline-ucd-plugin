/**
 * (c) Copyright IBM Corporation 2017.
 * This is licensed under the following license.
 * The Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
 * U.S. Government Users Restricted Rights:  Use, duplication or disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 */

package com.urbancode.jenkins.plugins.ucdeploy;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Job;
import hudson.util.CopyOnWriteList;
import hudson.util.FormFieldValidator;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;


/**
 * This class stores the global list of all user configured site objects
 *
 */
public class GlobalConfig extends JobProperty<Job<?, ?>> {
    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public GlobalConfigDescriptor getDescriptor() {
        return (GlobalConfigDescriptor) Jenkins.getInstance().getDescriptor(getClass());
    }

    /**
     * Explicitly returns the GlobalConfigDescriptor
     *
     * @return The GlobalConfigDescriptor
     */
    public static GlobalConfigDescriptor getGlobalConfigDescriptor() {
        return (GlobalConfigDescriptor) Jenkins.getInstance().getDescriptor(GlobalConfig.class);
    }

    @Extension
    public static final class GlobalConfigDescriptor extends JobPropertyDescriptor {

        private CopyOnWriteList<UCDeploySite> sites = new CopyOnWriteList<UCDeploySite>();

        public GlobalConfigDescriptor() {
            super(GlobalConfig.class);
            load();
        }

        public UCDeploySite[] getSites() {
            Iterator<UCDeploySite> it = sites.iterator();
            int size = 0;
            while (it.hasNext()) {
                it.next();
                size++;
            }
            return sites.toArray(new UCDeploySite[size]);
        }

        /**
         * Replace sites with user defined sites
         *
         * @param req {@inheritDoc}
         * @param formData {@inheritDoc}
         * @return {@inheritDoc}
         * @throws FormException
         */
        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            sites.replaceBy(req.bindJSONToList(UCDeploySite.class, formData.get("sites")));
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
            return "UrbanCode Deploy Server";
        }

        /**
         * Verify connectivity to the UCD site
         * @param req
         * @param rsp
         * @param url
         * @param user
         * @param password
         * @param trustAllCerts
         * @throws IOException
         * @throws ServletException
         * @deprecated FormFieldValidator
         */
        @Deprecated
        public void doTestConnection(
                StaplerRequest req,
                StaplerResponse rsp,
                @QueryParameter("url") final String url,
                @QueryParameter("user") final String user,
                @QueryParameter("password") final String password,
                @QueryParameter("trustAllCerts") final boolean trustAllCerts)
        throws IOException, ServletException {
            new FormFieldValidator(req, rsp, true) {
                @Override
                protected void check() throws IOException, ServletException {
                    try {
                        UCDeploySite site = new UCDeploySite(null, url, user, password, trustAllCerts);
                        site.verifyConnection();
                        ok("Success");
                    }
                    catch (Exception e) {
                        error(e.getMessage());
                    }
                }
            }.process();
        }
    }
}
