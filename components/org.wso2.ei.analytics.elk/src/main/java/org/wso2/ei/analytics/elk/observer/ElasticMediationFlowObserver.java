/*
* Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.ei.analytics.elk.observer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;

import org.apache.synapse.aspects.flow.statistics.publishing.PublishingFlow;
import org.wso2.carbon.base.ServerConfiguration;
import org.wso2.carbon.das.messageflow.data.publisher.observer.MessageFlowObserver;
import org.wso2.securevault.SecretResolver;
import org.wso2.securevault.SecretResolverFactory;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.wso2.ei.analytics.elk.publisher.ElasticStatisticsPublisher;
import org.wso2.ei.analytics.elk.services.ElasticsearchPublisherThread;
import org.wso2.ei.analytics.elk.util.ElasticObserverConstants;

/**
 * This class is instantiated by MediationStatisticsComponent.
 * Gets stored in MessageFlowObserverStore and updateStatistics() is notified by the MessageFlowReporterThread.
 */
public class ElasticMediationFlowObserver implements MessageFlowObserver {

    private static final Log log = LogFactory.getLog(ElasticMediationFlowObserver.class);

    // Defines elasticsearch RestHighLevelClient as client
    private RestHighLevelClient client = null;
    private HttpHost elasticHost = null;

    // Thread to publish json strings to Elasticsearch
    private ElasticsearchPublisherThread publisherThread = null;

    // Whether the event queue exceeded or not, accessed by MessageFlowReporter threads
    private volatile boolean bufferExceeded = false;

    // ServerConfiguration
    private ServerConfiguration serverConf = ServerConfiguration.getInstance();

    // Keep all needed configurations (final configurations)
    private Map<String, Object> configurations = new HashMap<>();

    /**
     * Instantiates the RestHighLevelClient as this class is instantiated.
     */
    public ElasticMediationFlowObserver() {
        try {
            // Take config, resolve and validates , and put into configurations field
            getConfigurations();

            String username = (String) configurations.get(ElasticObserverConstants.USERNAME);
            String password = (String) configurations.get(ElasticObserverConstants.PASSWORD);
            String trustStorePath = (String) configurations.get(ElasticObserverConstants.TRUST_STORE_PATH);
            String trustStoreType = (String) configurations.get(ElasticObserverConstants.TRUST_STORE_TYPE);
            String trustStorePassword = (String) configurations.get(ElasticObserverConstants.TRUST_STORE_PASSWORD);
            String host = (String) configurations.get(ElasticObserverConstants.HOST);
            int port = (int) configurations.get(ElasticObserverConstants.PORT);
            boolean sslEnabled = (boolean) configurations.get(ElasticObserverConstants.SSL_ENABLED);

            if (sslEnabled) {
                if (trustStorePath != null && trustStoreType != null && trustStorePassword != null) {
                    elasticHost = new HttpHost(host, port, ElasticObserverConstants.HTTPS_PROTOCOL);
                } else {
                    throw new IOException("SSL is enabled, trustStore can not be found. Please provide trustStore details.");
                }
            } else {
                elasticHost = new HttpHost(host, port, ElasticObserverConstants.HTTP_PROTOCOL);
            }

            if (username != null && password != null) {
                // Can use password without ssl
                final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
                client = new RestHighLevelClient(RestClient.builder(elasticHost).
                        setHttpClientConfigCallback(httpClientBuilder -> {
                            if (sslEnabled) {
                                try {
                                    KeyStore trustStore = KeyStore.getInstance(trustStoreType);
                                    InputStream is = Files.newInputStream(Paths.get(trustStorePath));
                                    trustStore.load(is, trustStorePassword.toCharArray());
                                    SSLContextBuilder sslBuilder = SSLContexts.custom().loadTrustMaterial(trustStore, null);
                                    httpClientBuilder.setSSLContext(sslBuilder.build());
                                } catch (IOException e) {
                                    log.error("The trustStore password = " + trustStorePassword + " or trustStore path "
                                            + trustStorePath + " defined is incorrect while creating " + "sslContext");
                                } catch (CertificateException e) {
                                    log.error("Any of the certificates in the keystore could not be loaded " +
                                            "when loading trustStore" + e);
                                } catch (NoSuchAlgorithmException e) {
                                    log.error("\"Algorithm used to check the integrity of the trustStore cannot " +
                                            "be found for when loading trustStore", e);
                                } catch (KeyStoreException e) {
                                    log.error("The trustStore type truststore.type = " + "" + trustStoreType
                                            + " defined is incorrect", e);
                                } catch (KeyManagementException e) {
                                    log.error("Error occurred while builing sslContext", e);
                                }
                                if (log.isDebugEnabled()) {
                                    log.debug("SSL is configured with given truststore.");
                                }
                            }
                            return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                        }));
            } else {
                client = new RestHighLevelClient(RestClient.builder(elasticHost));
            }

            if (log.isDebugEnabled()) {
                log.debug("RestHighLevelClient is built with host and port number");
            }

            // Wrong cluster name provided or given cluster is down or wrong access credentials
            if (!client.ping(RequestOptions.DEFAULT)) {
                log.error("Can not connect to any Elasticsearch nodes. Please give correct configurations, " +
                        "run Elasticsearch and restart WSO2-EI.");
                client.close();

                if (log.isDebugEnabled()) {
                    log.debug("No nodes connected. Reasons:cluster is down/ " + "Wrong access credentials");
                }
            } else {
                /*
                    Client needs access rights to read and write to Elasticsearch cluster as described in the article.
                    If the given user credential has no access to write, it only can be identified when the first bulk
                    of events are published.
                    So, to check the access privileges before hand, here put a test json string and delete it.
                */
                IndexRequest request = new IndexRequest("eidata").id("1");

                String jsonString = "{" +
                        "\"test_att\":\"test\"" +
                        "}";

                request.source(jsonString, XContentType.JSON);

                client.index(request, RequestOptions.DEFAULT);

                DeleteRequest requestDel = new DeleteRequest("eidata", "1");
                client.delete(requestDel, RequestOptions.DEFAULT);

                if (log.isDebugEnabled()) {
                    log.debug("Access privileges for given user is sufficient.");
                }

                startPublishing();
                log.info("Elasticsearch mediation statistic publishing enabled.");
            }
        } catch (IOException e) {
            log.error("Elasticsearch connection error.", e);
        }
    }

    /**
     * RestHighLevelClient gets closed.
     */
    @Override
    public void destroy() {
        publisherThread.shutdown();

        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                log.error("Error shutting down the client. ", e);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Shutting down the mediation statistics observer of Elasticsearch");
        }
    }

    /**
     * Method is called when this observer is notified.
     * Invokes the process method considering about the queue size.
     *
     * @param publishingFlow PublishingFlow object is passed when notified.
     */
    @Override
    public void updateStatistics(PublishingFlow publishingFlow) {
        if (publisherThread != null) {
            int bufferSize = (int) configurations.get(ElasticObserverConstants.BUFFER_SIZE);
            if (bufferExceeded) {
                // If the queue has exceeded before, check the queue is not exceeded now
                if (ElasticStatisticsPublisher.getAllMappingsQueue().size() < bufferSize) {
                    // Log only once
                    log.info("Event buffering started.");
                    bufferExceeded = false;
                }
            } else {
                // If the queue has not exceeded before, check the queue is exceeded now
                if (ElasticStatisticsPublisher.getAllMappingsQueue().size() >= bufferSize) {
                    // Log only once
                    log.warn("Maximum buffer size reached. Dropping incoming events.");
                    bufferExceeded = true;
                }
            }

            if (!bufferExceeded) {
                try {
                    if (!(publisherThread.getShutdown())) {
                        ElasticStatisticsPublisher.process(publishingFlow);
                    }
                } catch (Exception e) {
                    log.error("Failed to update statistics from Elasticsearch publisher", e);
                }
            }
        }
    }

    /**
     * Instantiates the publisher thread, passes the RestHighLevelClient and starts.
     */
    private void startPublishing() {
        publisherThread = new ElasticsearchPublisherThread();
        publisherThread.setName("ElasticsearchPublisherThread");
        publisherThread.init(
                client,
                (int) configurations.get(ElasticObserverConstants.BULK_SIZE),
                (long) configurations.get(ElasticObserverConstants.BULK_TIME_OUT),
                (long) configurations.get(ElasticObserverConstants.BUFFER_EMPTY_SLEEP),
                (long) configurations.get(ElasticObserverConstants.NO_NODES_SLEEP)
        );
        publisherThread.start();
    }

    /**
     * Takes needed configurations for the client to connect from the carbon.xml file
     * Validates the configurations, resolves password and returns final settings
     *
     * @see ElasticObserverConstants for the keys of the configurations object
     */
    private void getConfigurations() {
        String host = ElasticObserverConstants.DEFAULT_HOSTNAME;
        int port = ElasticObserverConstants.DEFAULT_PORT;
        String username = ElasticObserverConstants.DEFAULT_USERNAME;
        String password = ElasticObserverConstants.DEFAULT_PASSWORD;
        boolean sslEnabled = ElasticObserverConstants.DEFAULT_SSL_ENABLED;
        String trustStorePassword = ElasticObserverConstants.DEFAULT_TRUSTSTORE_PASSWORD;
        String trustStoreType = ElasticObserverConstants.DEFAULT_TRUSTSTORE_TYPE;
        // Event buffering queue size = 5000
        int bufferSize = ElasticObserverConstants.DEFAULT_BUFFER_SIZE;

        // Size of the event publishing bulk = 500
        int bulkSize = ElasticObserverConstants.DEFAULT_PUBLISHING_BULK_SIZE;

        // Time out for collecting configured fixed size bulk = 5000
        long bulkTimeOut = ElasticObserverConstants.DEFAULT_BULK_COLLECTING_TIMEOUT;

        // PublisherThread sleep time when the buffer is empty = 1000 (in millis)
        long bufferEmptySleep = ElasticObserverConstants.DEFAULT_BUFFER_EMPTY_SLEEP_TIME;

        // PublisherThread sleep time when the Elasticsearch server is down = 5000 (in millis)
        long noNodesSleep = ElasticObserverConstants.DEFAULT_NO_NODES_SLEEP_TIME;

        // Takes configuration details form carbon.xml
        String hostInConfig = serverConf.getFirstProperty(ElasticObserverConstants.HOST_CONFIG);
        String portString = serverConf.getFirstProperty(ElasticObserverConstants.PORT_CONFIG);
        String bufferSizeString = serverConf.getFirstProperty(ElasticObserverConstants.BUFFER_SIZE_CONFIG);
        String bulkSizeString = serverConf.getFirstProperty(ElasticObserverConstants.BULK_SIZE_CONFIG);
        String bulkCollectingTimeOutString = serverConf.getFirstProperty(
                ElasticObserverConstants.BULK_COLLECTING_TIME_OUT_CONFIG);
        String bufferEmptySleepString = serverConf.getFirstProperty(
                ElasticObserverConstants.BUFFER_EMPTY_SLEEP_TIME_CONFIG);
        String noNodesSleepString = serverConf.getFirstProperty(ElasticObserverConstants.NO_NODES_SLEEP_TIME_CONFIG);
        String usernameInConfig = serverConf.getFirstProperty(ElasticObserverConstants.USERNAME_CONFIG);
        String passwordInConfig = serverConf.getFirstProperty(ElasticObserverConstants.PASSWORD_CONFIG);
        String trustStorePath = serverConf.getFirstProperty(ElasticObserverConstants.TRUST_STORE_PATH_CONFIG);
        String trustStoreTypeInConfig = serverConf.getFirstProperty(ElasticObserverConstants.TRUST_STORE_TYPE_CONFIG);
        String trustStorePasswordInConfig = serverConf.getFirstProperty(ElasticObserverConstants.TRUST_STORE_PASSWORD_CONFIG);
        String sslEnabledInConfig = serverConf.getFirstProperty(ElasticObserverConstants.SSL_ENABLED_CONFIG);

        if (log.isDebugEnabled()) {
            log.debug("Configurations taken from carbon.xml.");
        }

        // If the value is not in config, keep the default value defined in constants
        if (hostInConfig != null && !hostInConfig.isEmpty()) {
            host = hostInConfig;
        }

        if (portString != null && !portString.isEmpty()) {
            port = Integer.parseInt(portString);
        }

        if (usernameInConfig != null && !usernameInConfig.isEmpty()) {
            username = usernameInConfig;
        }

        if (passwordInConfig != null && !passwordInConfig.isEmpty()) {
            password = passwordInConfig;
        }

        if (sslEnabledInConfig != null && !sslEnabledInConfig.isEmpty()) {
            sslEnabled = Boolean.parseBoolean(sslEnabledInConfig);
        }

        if (trustStorePasswordInConfig != null && !trustStorePasswordInConfig.isEmpty()) {
            trustStorePassword = trustStorePasswordInConfig;
        }

        if (trustStoreTypeInConfig != null && !trustStoreTypeInConfig.isEmpty()) {
            trustStoreType = trustStoreTypeInConfig;
        }

        if (bufferSizeString != null && !bufferSizeString.isEmpty()) {
            bufferSize = Integer.parseInt(bufferSizeString);
        }

        if (bulkSizeString != null && !bulkSizeString.isEmpty()) {
            bulkSize = Integer.parseInt(bulkSizeString);
        }

        if (bulkCollectingTimeOutString != null && !bulkCollectingTimeOutString.isEmpty()) {
            bulkTimeOut = Integer.parseInt(bulkCollectingTimeOutString);
        }

        if (bufferEmptySleepString != null && !bufferEmptySleepString.isEmpty()) {
            bufferEmptySleep = Integer.parseInt(bufferEmptySleepString);
        }

        if (noNodesSleepString != null && !noNodesSleepString.isEmpty()) {
            noNodesSleep = Integer.parseInt(noNodesSleepString);
        }

        if (log.isDebugEnabled()) {
            log.debug("Host: " + host);
            log.debug("Port: " + port);
            log.debug("Buffer Size: " + bufferSize + " events");
            log.debug("Bullk Size: " + bulkSize + " events");
            log.debug("Bulk Timeout: " + bulkTimeOut + " millis");
            log.debug("Buffer Empty Sleep Time: " + bufferEmptySleep + " millis");
            log.debug("No Nodes Sleep Time: " + noNodesSleep + " millis");
            log.debug("Username: " + username);
            log.debug("Trust Store Path: " + trustStorePath);
            log.debug("Trust Store Type: " + trustStoreType);
            log.debug("Trust Store Pass: " + trustStorePassword);
            log.debug("SSL Enabled: " + sslEnabled);
        }

        // Put resolved configurations into configuration field
        configurations.put(ElasticObserverConstants.HOST, host);
        configurations.put(ElasticObserverConstants.PORT, port);
        configurations.put(ElasticObserverConstants.BUFFER_SIZE, bufferSize);
        configurations.put(ElasticObserverConstants.BULK_SIZE, bulkSize);
        configurations.put(ElasticObserverConstants.BULK_TIME_OUT, bulkTimeOut);
        configurations.put(ElasticObserverConstants.BUFFER_EMPTY_SLEEP, bufferEmptySleep);
        configurations.put(ElasticObserverConstants.NO_NODES_SLEEP, noNodesSleep);
        configurations.put(ElasticObserverConstants.USERNAME, username);
        configurations.put(ElasticObserverConstants.PASSWORD, password);
        configurations.put(ElasticObserverConstants.TRUST_STORE_PATH, trustStorePath);
        configurations.put(ElasticObserverConstants.TRUST_STORE_TYPE, trustStoreType);
        configurations.put(ElasticObserverConstants.TRUST_STORE_PASSWORD, trustStorePassword);
        configurations.put(ElasticObserverConstants.SSL_ENABLED, sslEnabled);

        // If username is not null; password can be in plain or Secure Vault
        // <Password> in config must be present
        if (username != null && passwordInConfig != null) {
            // Resolve password and add to configurations
            configurations.put(ElasticObserverConstants.PASSWORD, resolvePassword(passwordInConfig, ElasticObserverConstants.PASSWORD_ALIAS));
        }

        if (trustStorePassword != null) {
            configurations.put(ElasticObserverConstants.TRUST_STORE_PASSWORD, resolvePassword(trustStorePassword,
                    ElasticObserverConstants.TRUST_STORE_PASSWORD_ALIAS));
        }
    }

    /**
     * Checks whether the password is configured from Secure Vault or not
     * Resolves the password or directly assigns the passwordInConfig
     *
     * @param passwordInConfig value of the <Password> element found in carbon.xml
     * @return final plain text password
     */
    private String resolvePassword(String passwordInConfig, String alias) {
        // Plain password resolved/directly from carbon.xml
        String password;

        boolean secureVaultPassword = false;

        NodeList nodeList = null;

        // Checking the <Password> element for the attribute "svns:secretAlias" to identify whether the
        // password is to be taken from Secure Vault or as plain text

        // nodeList contains all the nodes with the tag <Password>
        if (alias.equals(ElasticObserverConstants.PASSWORD_ALIAS)) {
            nodeList = serverConf.getDocumentElement().getElementsByTagName("Password");
        }
        // nodeList contains all the nodes with the tag <TrustStorePassword>
        if (alias.equals(ElasticObserverConstants.TRUST_STORE_PASSWORD_ALIAS)) {
            nodeList = serverConf.getDocumentElement().getElementsByTagName("TrustStorePassword");
        }

        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            // Find the node which has <ElasticObserver> as parent node
            if ("ElasticObserver".equals(node.getParentNode().getLocalName())) {
                // Take attributes map of the node and look into it for the defined secret alias
                NamedNodeMap attributeMap = node.getAttributes();
                for (int j = 0; j < attributeMap.getLength(); j++) {
                    if ("svns:secretAlias".equals(attributeMap.item(j).getNodeName())
                            && alias.equals(attributeMap.item(j).getNodeValue())) {
                        secureVaultPassword = true;
                        break;
                    }
                }
            }
        }

        if (secureVaultPassword) {
            // Creates Secret Resolver from carbon.xml document element
            SecretResolver secretResolver = SecretResolverFactory.create(serverConf.getDocumentElement(),
                    true);

            // Resolves password using the defined alias
            password = secretResolver.resolve(alias);

            // If the alias is wrong and there is no password, resolver returns the alias string again
            if (alias.equals(password)) {
                log.error("Wrong password alias in Secure Vault. Use alias: " +
                        alias);
                password = null;
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Password resolved from Secure Vault.");
                }
            }
        } else {
            // If not secure vault password take directly
            password = passwordInConfig;
            if (log.isDebugEnabled()) {
                log.debug("Password taken directly from carbon.xml");
            }
        }

        return password;
    }
}
