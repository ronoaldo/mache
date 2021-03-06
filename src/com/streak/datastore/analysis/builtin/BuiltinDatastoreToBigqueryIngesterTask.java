/*
 * Copyright 2012 Rewardly Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.streak.datastore.analysis.builtin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.api.client.googleapis.extensions.appengine.auth.oauth2.AppIdentityCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.Bigquery.Jobs.Insert;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfiguration;
import com.google.api.services.bigquery.model.JobConfigurationLoad;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.TableReference;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.CompositeFilter;
import com.google.appengine.api.datastore.Query.CompositeFilterOperator;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.Query.FilterPredicate;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.streak.logging.utils.AnalysisConstants;
import com.streak.logging.utils.AnalysisUtility;

@SuppressWarnings("serial")
public class BuiltinDatastoreToBigqueryIngesterTask extends HttpServlet {
	private static final int MILLIS_TO_ENQUEUE = 60000;
	private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	private static final JsonFactory JSON_FACTORY = new JacksonFactory();
	
	private static final String BUILTIN_DATASTORE_TO_BIGQUERY_INGESTOR_TASK_PATH = "/builtinDatastoreToBigqueryIngestorTask";

	public static void enqueueTask(String baseUrl, BuiltinDatastoreExportConfiguration exporterConfig, long timestamp) {
		enqueueTask(baseUrl, exporterConfig, timestamp, 0);
	}	
	
	private static void enqueueTask(String baseUrl, BuiltinDatastoreExportConfiguration exporterConfig, long timestamp, long countdownMillis) {
		TaskOptions t = TaskOptions.Builder.withUrl(baseUrl + BUILTIN_DATASTORE_TO_BIGQUERY_INGESTOR_TASK_PATH);
		t.param(AnalysisConstants.TIMESTAMP_PARAM, Long.toString(timestamp));
		t.param(AnalysisConstants.BUILTIN_DATASTORE_EXPORT_CONFIG, exporterConfig.getClass().getName());
		
		t.method(Method.GET);
		if (countdownMillis > 0) {
			t.countdownMillis(countdownMillis);
		}
		QueueFactory.getQueue(exporterConfig.getQueueName()).add(t);
	}
	
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		resp.setContentType("application/json");

		String timestampStr = req.getParameter(AnalysisConstants.TIMESTAMP_PARAM);
		long timestamp = 0;
		if (AnalysisUtility.areParametersValid(timestampStr)) {
			try {
				timestamp = Long.parseLong(timestampStr);
			}
			catch (Exception e) {
				// leave it at default value
			}
		}
		if (timestamp == 0) {
			resp.getWriter().write(AnalysisUtility.failureJson("Missing required param: " + AnalysisConstants.TIMESTAMP_PARAM));
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		
		String builtinDatastoreExportConfig = req.getParameter(AnalysisConstants.BUILTIN_DATASTORE_EXPORT_CONFIG);
		if (!AnalysisUtility.areParametersValid(builtinDatastoreExportConfig)) {
			resp.getWriter().write(AnalysisUtility.failureJson("Missing required param: " + AnalysisConstants.BUILTIN_DATASTORE_EXPORT_CONFIG));
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		
		// Instantiate the export config 
		BuiltinDatastoreExportConfiguration exporterConfig = AnalysisUtility.instantiateDatastoreExportConfig(builtinDatastoreExportConfig);
		
		String keyOfCompletedBackup = checkAndGetCompletedBackup(AnalysisUtility.getPreBackupName(timestamp, exporterConfig.getBackupNamePrefix())); 
		if (keyOfCompletedBackup == null) {
			resp.getWriter().println(AnalysisUtility.successJson("backup incomplete, retrying in " + MILLIS_TO_ENQUEUE + " millis"));
			enqueueTask(AnalysisUtility.getRequestBaseName(req), exporterConfig, timestamp, MILLIS_TO_ENQUEUE);
		}
		else {
			resp.getWriter().println("backup complete, starting bigquery ingestion");
			AppIdentityCredential credential = new AppIdentityCredential(AnalysisConstants.SCOPES);
			
			Bigquery bigquery = new Bigquery.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential).setApplicationName("Streak Logs").build();
			
			
			String datatableSuffix = "";
			if (exporterConfig.appendTimestampToDatatables()) {
				datatableSuffix = Long.toString(timestamp);
			}
			else {
				datatableSuffix = "";
			}
			
			if (!exporterConfig.appendTimestampToDatatables()) {
				// we aren't appending the timestamps so delete the old tables if they exist
				for (String kind : exporterConfig.getEntityKindsToExport()) {
					boolean found = true;
					try {
						bigquery.tables().get(exporterConfig.getBigqueryProjectId(), exporterConfig.getBigqueryDatasetId(), kind).execute();
					}
					catch (IOException e) {
						// table not found so don't need to do anything
						found = false;
					}
						
					if (found) {
						bigquery.tables().delete(exporterConfig.getBigqueryProjectId(), exporterConfig.getBigqueryDatasetId(), kind).execute();
					}
				}
			}
			
			// now create the ingestion
			for (String kind : exporterConfig.getEntityKindsToExport()) {
				Job job = new Job();
				JobConfiguration config = new JobConfiguration();
				JobConfigurationLoad loadConfig = new JobConfigurationLoad();	
				
				String uri = "gs://" + exporterConfig.getBucketName() + "/" + keyOfCompletedBackup + "." + kind + ".backup_info";
				
				loadConfig.setSourceUris(Arrays.asList(uri));
				loadConfig.set("sourceFormat", "DATASTORE_BACKUP");
				loadConfig.set("allowQuotedNewlines", true);
				
				
				TableReference table = new TableReference();
				table.setProjectId(exporterConfig.getBigqueryProjectId());
				table.setDatasetId(exporterConfig.getBigqueryDatasetId());
				table.setTableId(kind + datatableSuffix);
				loadConfig.setDestinationTable(table);

				config.setLoad(loadConfig);
				job.setConfiguration(config);
				Insert insert = bigquery.jobs().insert(exporterConfig.getBigqueryProjectId(), job);
				
				JobReference jr = insert.execute().getJobReference();
				resp.getWriter().println("Uri: " + uri + ", JobId: " + jr.getJobId());
			}
		}
	}


	private String checkAndGetCompletedBackup(String backupName) throws IOException {
		System.err.println("backupName: " + backupName);
		
		DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
		
		Query q = new Query("_AE_Backup_Information");
		
		// for some reason the datastore admin code appends the date to the backup name even when creating programatically, 
		// so test for greater than or equal to and then take the first result
		FilterPredicate greater = new FilterPredicate("name", FilterOperator.GREATER_THAN_OR_EQUAL, backupName);
		FilterPredicate less = new FilterPredicate("name", FilterOperator.LESS_THAN_OR_EQUAL, backupName + "Z");
		List<Query.Filter> filters = new ArrayList<Query.Filter>();
		filters.add(greater);
		filters.add(less);
		CompositeFilter comp = new CompositeFilter(CompositeFilterOperator.AND, filters); 
		q.setFilter(comp);
		
		PreparedQuery pq = datastore.prepare(q);
		List<Entity> results = pq.asList(FetchOptions.Builder.withLimit(1));
		if (results.size() != 1 || !results.get(0).getProperty("name").toString().contains(backupName)) {
			System.err.println("BuiltinDatatoreToBigqueryIngesterTask: can't find backupName: " + backupName);
		}
		Entity result = results.get(0);
		
		Object completion = result.getProperty("complete_time");
		String keyResult = null;
		if (completion != null) {
			keyResult = KeyFactory.keyToString(result.getKey());
		}
		
		System.err.println("result: " + result);
		System.err.println("complete_time: " + completion);
		System.err.println("Backup complete: " + completion != null);
		System.err.println("keyResult: " + keyResult);
		return keyResult;
	}
}
