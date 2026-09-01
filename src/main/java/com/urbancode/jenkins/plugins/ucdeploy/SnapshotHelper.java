/**
 * (c) Copyright IBM Corporation 2017.
 * This is licensed under the following license.
 * The Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
 * U.S. Government Users Restricted Rights:  Use, duplication or disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 */

package com.urbancode.jenkins.plugins.ucdeploy;

import java.io.IOException;
import java.net.URI;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.urbancode.ud.client.ApplicationClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper for snapshot operations not available in the uDeployRestClient library.
 * Provides getSnapshotVersions (with app name lookup), addVersionToSnapshot,
 * and removeVersionFromSnapshot using direct REST calls.
 */
@SuppressWarnings("deprecation") // Triggered by DefaultHttpClient
public class SnapshotHelper {

    private static final Logger log = LoggerFactory.getLogger(SnapshotHelper.class);

    private final URI ucdUrl;
    private final DefaultHttpClient httpClient;
    private final ApplicationClient appClient;

    public SnapshotHelper(URI ucdUrl, DefaultHttpClient httpClient, ApplicationClient appClient) {
        this.ucdUrl = ucdUrl;
        this.httpClient = httpClient;
        this.appClient = appClient;
    }

    /**
     * Get snapshot versions by snapshot name and application name.
     * Uses getSnapshot to resolve the snapshot ID, then calls getSnapshotVersions.
     */
    public JSONArray getSnapshotVersions(String snapshotName, String applicationName)
            throws IOException, JSONException {
        JSONObject snapshot = appClient.getSnapshot(applicationName, snapshotName);
        String snapshotId = snapshot.getString("id");
        return appClient.getSnapshotVersions(snapshotId);
    }

    /**
     * Add a component version to a snapshot.
     * REST: PUT /rest/deploy/snapshot/{snapshotId}/versions
     */
    public void addVersionToSnapshot(String snapshotName, String applicationName,
                                      String versionName, String componentName)
            throws IOException, JSONException {
        JSONObject snapshot = appClient.getSnapshot(applicationName, snapshotName);
        String snapshotId = snapshot.getString("id");

        URI uri = URI.create(ucdUrl.toString() + "/rest/deploy/snapshot/" + snapshotId + "/versions");

        JSONObject body = new JSONObject();
        body.put("component", componentName);
        body.put("name", versionName);

        HttpPut method = new HttpPut(uri);
        method.setEntity(new StringEntity(body.toString(), "application/json", "UTF-8"));
        try {
            HttpResponse response = httpClient.execute(method);
            int code = response.getStatusLine().getStatusCode();
            EntityUtils.consumeQuietly(response.getEntity());
            if (code < 200 || code >= 300) {
                throw new IOException("Failed to add version '" + versionName
                        + "' to snapshot (HTTP " + code + ")");
            }
        } finally {
            method.releaseConnection();
        }
    }

    /**
     * Remove a component version from a snapshot.
     * REST: DELETE /rest/deploy/snapshot/{snapshotId}/versions/{versionId}
     */
    public void removeVersionFromSnapshot(String snapshotName, String applicationName,
                                           String versionId, String componentName)
            throws IOException, JSONException {
        JSONObject snapshot = appClient.getSnapshot(applicationName, snapshotName);
        String snapshotId = snapshot.getString("id");

        URI uri = URI.create(ucdUrl.toString() + "/rest/deploy/snapshot/" + snapshotId + "/versions/" + versionId);

        HttpDelete method = new HttpDelete(uri);
        try {
            HttpResponse response = httpClient.execute(method);
            int code = response.getStatusLine().getStatusCode();
            EntityUtils.consumeQuietly(response.getEntity());
            if (code < 200 || code >= 300) {
                throw new IOException("Failed to remove version from snapshot (HTTP " + code + ")");
            }
        } finally {
            method.releaseConnection();
        }
    }
}
