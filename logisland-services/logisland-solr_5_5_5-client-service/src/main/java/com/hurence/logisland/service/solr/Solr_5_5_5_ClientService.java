/**
 * Copyright (C) 2016 Hurence (support@hurence.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hurence.logisland.service.solr;

import com.hurence.logisland.annotation.documentation.CapabilityDescription;
import com.hurence.logisland.annotation.documentation.Tags;
import com.hurence.logisland.annotation.lifecycle.OnDisabled;
import com.hurence.logisland.annotation.lifecycle.OnEnabled;
import com.hurence.logisland.component.InitializationException;
import com.hurence.logisland.component.PropertyDescriptor;
import com.hurence.logisland.controller.AbstractControllerService;
import com.hurence.logisland.controller.ControllerServiceInitializationContext;
import com.hurence.logisland.processor.ProcessException;
import com.hurence.logisland.record.Field;
import com.hurence.logisland.record.FieldDictionary;
import com.hurence.logisland.record.Record;
import com.hurence.logisland.service.datastore.DatastoreClientService;
import com.hurence.logisland.service.datastore.DatastoreClientServiceException;
import com.hurence.logisland.service.datastore.MultiGetQueryRecord;
import com.hurence.logisland.service.datastore.MultiGetResponseRecord;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.CursorMarkParams;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

@Tags({ "solr", "client"})
@CapabilityDescription("Implementation of ElasticsearchClientService for Solr 5.5.5.")
public class Solr_5_5_5_ClientService extends AbstractControllerService implements DatastoreClientService {

    protected volatile SolrClient solrClient;
    private static org.slf4j.Logger logger = LoggerFactory.getLogger(Solr_5_5_5_ClientService.class);

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {

        List<PropertyDescriptor> props = new ArrayList<>();
//        props.add(BULK_BACK_OFF_POLICY);
//        props.add(BULK_THROTTLING_DELAY);
//        props.add(BULK_RETRY_NUMBER);
//        props.add(BATCH_SIZE);
//        props.add(BULK_SIZE);
//        props.add(FLUSH_INTERVAL);
//        props.add(CONCURRENT_REQUESTS);
//        props.add(CLUSTER_NAME);
//        props.add(PING_TIMEOUT);
//        props.add(SAMPLER_INTERVAL);
//        props.add(USERNAME);
//        props.add(PASSWORD);
//        props.add(PROP_SHIELD_LOCATION);
//        props.add(HOSTS);
//        props.add(PROP_SSL_CONTEXT_SERVICE);
//        props.add(CHARSET);

        return Collections.unmodifiableList(props);
    }

    @Override
    @OnEnabled
    public void init(ControllerServiceInitializationContext context) throws InitializationException  {
        synchronized(this) {
            try {
                createSolrClient(context);
                //createBulkProcessor(context);
            }catch (Exception e){
                throw new InitializationException(e);
            }
        }
    }

    /**
     * Instantiate ElasticSearch Client. This chould be called by subclasses' @OnScheduled method to create a client
     * if one does not yet exist. If called when scheduled, closeClient() should be called by the subclasses' @OnStopped
     * method so the client will be destroyed when the processor is stopped.
     *
     * @param context The context for this processor
     * @throws ProcessException if an error occurs while creating an Elasticsearch client
     */
    protected void createSolrClient(ControllerServiceInitializationContext context) throws ProcessException {
        if (solrClient != null) {
            return;
        }

//        try {
//            final String clusterName = context.getPropertyValue(CLUSTER_NAME).asString();
//            final String pingTimeout = context.getPropertyValue(PING_TIMEOUT).asString();
//            final String samplerInterval = context.getPropertyValue(SAMPLER_INTERVAL).asString();
//            final String username = context.getPropertyValue(USERNAME).asString();
//            final String password = context.getPropertyValue(PASSWORD).asString();
//
//          /*  final SSLContextService sslService =
//                    context.getPropertyValue(PROP_SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);
//*/
//            Settings.Builder settingsBuilder = Settings.builder()
//                    .put("cluster.name", clusterName)
//                    .put("client.transport.ping_timeout", pingTimeout)
//                    .put("client.transport.nodes_sampler_interval", samplerInterval);
//
//            String shieldUrl = context.getPropertyValue(PROP_SHIELD_LOCATION).asString();
//          /*  if (sslService != null) {
//                settingsBuilder.setField("shield.transport.ssl", "true")
//                        .setField("shield.ssl.keystore.path", sslService.getKeyStoreFile())
//                        .setField("shield.ssl.keystore.password", sslService.getKeyStorePassword())
//                        .setField("shield.ssl.truststore.path", sslService.getTrustStoreFile())
//                        .setField("shield.ssl.truststore.password", sslService.getTrustStorePassword());
//            }*/
//
//            // Set username and password for Shield
//            if (!StringUtils.isEmpty(username)) {
//                StringBuffer shieldUser = new StringBuffer(username);
//                if (!StringUtils.isEmpty(password)) {
//                    shieldUser.append(":");
//                    shieldUser.append(password);
//                }
//                settingsBuilder.put("shield.user", shieldUser);
//
//            }
//
//            TransportClient transportClient = getTransportClient(settingsBuilder, shieldUrl, username, password);
//
//            final String hosts = context.getPropertyValue(HOSTS).asString();
//            esHosts = getEsHosts(hosts);
//
//            if (esHosts != null) {
//                for (final InetSocketAddress host : esHosts) {
//                    try {
//                        transportClient.addTransportAddress(new InetSocketTransportAddress(host));
//                    } catch (IllegalArgumentException iae) {
//                        getLogger().error("Could not add transport address {}", new Object[]{host});
//                    }
//                }
//            }
//            esClient = transportClient;
//
//        } catch (Exception e) {
//            getLogger().error("Failed to create Elasticsearch client due to {}", new Object[]{e}, e);
//            throw new RuntimeException(e);
//        }
    }

//    private TransportClient getTransportClient(Settings.Builder settingsBuilder, String shieldUrl,
//                                               String username, String password)
//            throws MalformedURLException {
//
//        // See if the Elasticsearch Shield JAR location was specified, and add the plugin if so. Also create the
//        // authorization token if username and password are supplied.
//        final ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
//        if (!StringUtils.isBlank(shieldUrl)) {
//            ClassLoader shieldClassLoader =
//                    new URLClassLoader(new URL[]{new File(shieldUrl).toURI().toURL()}, this.getClass().getClassLoader());
//            Thread.currentThread().setContextClassLoader(shieldClassLoader);
//
//            try {
//                //Class shieldPluginClass = Class.forName("org.elasticsearch.shield.ShieldPlugin", true, shieldClassLoader);
//                //builder = builder.addPlugin(shieldPluginClass);
//
//                if (!StringUtils.isEmpty(username) && !StringUtils.isEmpty(password)) {
//
//                    // Need a couple of classes from the Shield plugin to build the token
//                    Class usernamePasswordTokenClass =
//                            Class.forName("org.elasticsearch.shield.authc.support.UsernamePasswordToken", true, shieldClassLoader);
//
//                    Class securedStringClass =
//                            Class.forName("org.elasticsearch.shield.authc.support.SecuredString", true, shieldClassLoader);
//
//                    Constructor<?> securedStringCtor = securedStringClass.getConstructor(char[].class);
//                    Object securePasswordString = securedStringCtor.newInstance(password.toCharArray());
//
//                    Method basicAuthHeaderValue = usernamePasswordTokenClass.getMethod("basicAuthHeaderValue", String.class, securedStringClass);
//                    authToken = (String) basicAuthHeaderValue.invoke(null, username, securePasswordString);
//                }
//            } catch (ClassNotFoundException
//                    | NoSuchMethodException
//                    | InstantiationException
//                    | IllegalAccessException
//                    | InvocationTargetException shieldLoadException) {
//                getLogger().debug("Did not detect Elasticsearch Shield plugin, secure connections and/or authorization will not be available");
//            }
//        } else {
//            //logger.debug("No Shield plugin location specified, secure connections and/or authorization will not be available");
//        }
//        TransportClient transportClient = new PreBuiltTransportClient(settingsBuilder.build());
//        Thread.currentThread().setContextClassLoader(originalClassLoader);
//        return transportClient;
//    }

    private SolrClient getClient() {
        return solrClient;
    }

    public void createCollection(String name) throws DatastoreClientServiceException {
        createCollection(name, 0, 0);
    }

    @Override
    public void createCollection(String name, int partitionsCount, int replicationFactor) throws DatastoreClientServiceException {
        try {
            CoreAdminResponse aResponse = CoreAdminRequest.getStatus(name, getClient());

            if (aResponse.getCoreStatus(name).size() < 1)
            {
                CoreAdminRequest.Create aCreateRequest = new CoreAdminRequest.Create();
                aCreateRequest.setCoreName(name);
                aCreateRequest.setConfigSet("basic_configs");
                aCreateRequest.process(getClient());
            }
        } catch (Exception e) {
            System.out.println("plop2");
        }
    }

    @Override
    public void dropCollection(String name)throws DatastoreClientServiceException {
        try {
            CoreAdminResponse aResponse = CoreAdminRequest.getStatus(name, getClient());

            if (aResponse.getCoreStatus(name).size() > 0)
            {
                CoreAdminRequest.Unload unloadRequest = new CoreAdminRequest.Unload(true);
                unloadRequest.setCoreName(name);
                unloadRequest.setDeleteDataDir(true);
                unloadRequest.setDeleteInstanceDir(true);
                unloadRequest.process(getClient());
            }
        } catch (Exception e) {
            System.out.println("plop2");
        }

    }

    @Override
    public long countCollection(String name) throws DatastoreClientServiceException {
        try {
            SolrQuery q = new SolrQuery("*:*");
            q.setRows(0);  // don't actually request any data

            return getClient().query(name, q).getResults().getNumFound();
        } catch (Exception e) {
            throw new DatastoreClientServiceException(e);
        }
    }

    @Override
    public boolean existsCollection(String name) throws DatastoreClientServiceException {
        try
        {
            // Request core list
            CoreAdminRequest request = new CoreAdminRequest();
            request.setAction(CoreAdminParams.CoreAdminAction.STATUS);
            CoreAdminResponse cores = request.process(solrClient);

            // List of the cores
            List<String> coreList = new ArrayList<String>();
            for (int i = 0; i < cores.getCoreStatus().size(); i++) {
                coreList.add(cores.getCoreStatus().getName(i));
            }
            CoreAdminResponse aResponse = CoreAdminRequest.getStatus(name, getClient());

            return aResponse.getCoreStatus(name).size() > 1;
        } catch (Exception e) {
            System.out.println("plop");
        }

        return false;
    }

    @Override
    public void refreshCollection(String name) throws DatastoreClientServiceException {
        try {
            CoreAdminResponse aResponse = CoreAdminRequest.getStatus(name, getClient());

            if (aResponse.getCoreStatus(name).size() > 0)
            {
                CoreAdminRequest.reloadCore(name, getClient());
            }
        } catch (Exception e) {
            System.out.println("plop2");
        }
    }

    @Override
    public void copyCollection(String reindexScrollTimeout, String src, String dst) throws DatastoreClientServiceException {
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setRows(1000);
        solrQuery.setQuery("*:*");
        solrQuery.addSort("id", SolrQuery.ORDER.asc);  // Pay attention to this line
        String cursorMark = CursorMarkParams.CURSOR_MARK_START;
        boolean done = false;
        QueryResponse response;
        try {
            do {
                solrQuery.set(CursorMarkParams.CURSOR_MARK_PARAM, cursorMark);
                response = getClient().query(src, solrQuery);
                List<SolrInputDocument> documents = new ArrayList<>();
                for (SolrDocument document: response.getResults()) {
                    // TODO - Use Backup/Restore in Solr 6 ?
                    SolrInputDocument inputDocument = ClientUtils.toSolrInputDocument(document);
                    inputDocument.removeField("_version_");
                    documents.add(inputDocument);
                }


                getClient().add(dst, documents);

            } while (cursorMark.equals(response.getNextCursorMark()));


            getClient().commit(dst);
        } catch (Exception e) {
            throw new DatastoreClientServiceException(e);
        }
    }


    @Override
    public void createAlias(String collection, String alias)throws DatastoreClientServiceException {
        try {
            CollectionAdminRequest.CreateAlias createAlias = new CollectionAdminRequest.CreateAlias();
            createAlias.setAliasedCollections(collection);
            createAlias.setAliasName(alias);
            createAlias.process(getClient());
        } catch (Exception e) {
            throw new DatastoreClientServiceException(e);
        }
    }

    public boolean putMapping(String collectionName, List<Map<String, Object>> mapping)
            throws DatastoreClientServiceException {
        Boolean result = true;
        try {
            for (Map<String, Object> field: mapping) {
                SchemaRequest.AddField schemaRequest = new SchemaRequest.AddField(field);
                SchemaResponse.UpdateResponse response = schemaRequest.process(getClient(), collectionName);
                result = result && response.getStatus() == 0 && response.getResponse().get("errors") == null;

            }

            getClient().commit(collectionName);
            refreshCollection(collectionName);

            return result;
        } catch (Exception e) {
            //throw new DatastoreClientServiceException(e);
            System.out.println("plop");
        }

        return false;
    }

    @Override
    public boolean putMapping(String indexName, String doctype, String mappingAsJsonString)
            throws DatastoreClientServiceException {

        return false;
    }

    /* ********************************************************************
     * Put handling section
     * ********************************************************************/

    public void bulkFlush(String collectionName) throws DatastoreClientServiceException {
        try {
            getClient().commit(collectionName);
        } catch (Exception e) {
            throw new DatastoreClientServiceException(e);
        }
    }

    @Override
    public void bulkFlush() throws DatastoreClientServiceException {
        try {
            getClient().commit();
        } catch (Exception e) {
            throw new DatastoreClientServiceException(e);
        }
    }

    @Override
    public void bulkPut(String collectionName, Record record) throws DatastoreClientServiceException {
        try {
            _put(collectionName, record);
        } catch (Exception e) {
            throw new DatastoreClientServiceException(e);
        }

    }

    public String getUniqueKey(String collectionName) throws IOException, SolrServerException {
        SchemaRequest.UniqueKey keyRequest = new SchemaRequest.UniqueKey();
        SchemaResponse.UniqueKeyResponse keyResponse = keyRequest.process(getClient(), collectionName);

        return keyResponse.getUniqueKey();
    }

    public void put(String collectionName, Record record) throws DatastoreClientServiceException {
        put(collectionName, record, false);
    }

    @Override
    public void put(String collectionName, Record record, boolean asynchronous) throws DatastoreClientServiceException {
        try {
            _put(collectionName, record);

            getClient().commit(collectionName);
        } catch (Exception e) {
            throw new DatastoreClientServiceException(e);
        }
    }

    protected void _put(String collectionName, Record record) throws IOException, SolrServerException {
        SolrInputDocument document = new SolrInputDocument();

        document.addField(getUniqueKey(collectionName), record.getId());
        for (Field field : record.getAllFields()) {
            if (field.isReserved()) {
                continue;
            }

            document.addField(field.getName(), field.getRawValue());
        }

        getClient().add(collectionName, document);
    }

    /* ********************************************************************
     * Get handling section
     * ********************************************************************/

    @Override
    public List<MultiGetResponseRecord> multiGet(List<MultiGetQueryRecord> multiGetQueryRecords) throws DatastoreClientServiceException {
        return null;
    }

    @Override
    public Record get(String collectionName, Record record) throws DatastoreClientServiceException {
        return null;
    }

    @Override
    public Collection<Record> query(String queryString) {
        try {
            SolrQuery query = new SolrQuery();
            query.setQuery(queryString);

            QueryResponse response = getClient().query(query);

            //response.getResults().forEach(doc -> doc.);

        } catch (SolrServerException | IOException e) {
            logger.error(e.toString());
            throw new DatastoreClientServiceException(e);
        }

        return null;
    }

    public long queryCount(String collectionName, String queryString) {
        try {
            SolrQuery query = new SolrQuery();
            query.setQuery(queryString);

            QueryResponse response = getClient().query(collectionName, query);

            return response.getResults().getNumFound();

        } catch (SolrServerException | IOException e) {
            logger.error(e.toString());
            throw new DatastoreClientServiceException(e);
        }
    }

    @Override
    public long queryCount(String queryString) {
        try {
            SolrQuery query = new SolrQuery();
            query.setQuery(queryString);

            QueryResponse response = getClient().query(query);

            return response.getResults().getNumFound();

        } catch (SolrServerException | IOException e) {
            logger.error(e.toString());
            throw new DatastoreClientServiceException(e);
        }
    }

    @OnDisabled
    public void shutdown() {

    }

}