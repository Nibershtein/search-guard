/*
 * Copyright 2015 floragunn UG (haftungsbeschränkt)
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
 * 
 */

package com.floragunn.searchguard.action.configupdate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.nodes.BaseNodeRequest;
import org.elasticsearch.action.support.nodes.TransportNodesAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.component.LifecycleListener;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Provider;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import com.floragunn.searchguard.action.configupdate.ConfigUpdateResponse.Node;
import com.floragunn.searchguard.auth.BackendRegistry;
import com.floragunn.searchguard.configuration.ConfigChangeListener;
import com.floragunn.searchguard.configuration.ConfigurationLoader;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;

public class TransportConfigUpdateAction
extends
TransportNodesAction<ConfigUpdateRequest, ConfigUpdateResponse, TransportConfigUpdateAction.NodeConfigUpdateRequest, ConfigUpdateResponse.Node> {

    private final ClusterService clusterService;
    private final ConfigurationLoader cl;
    private final Provider<BackendRegistry> backendRegistry;
    private final ListMultimap<String, ConfigChangeListener> multimap = Multimaps.synchronizedListMultimap(ArrayListMultimap
            .<String, ConfigChangeListener> create());

    @Inject
    public TransportConfigUpdateAction(final Client client, final Settings settings, final ClusterName clusterName,
            final ThreadPool threadPool, final ClusterService clusterService, final TransportService transportService,
            final ConfigurationLoader cl, final ActionFilters actionFilters, final IndexNameExpressionResolver indexNameExpressionResolver,
            Provider<BackendRegistry> backendRegistry) {
        super(settings, ConfigUpdateAction.NAME, clusterName, threadPool, clusterService, transportService, actionFilters,
                indexNameExpressionResolver, ConfigUpdateRequest.class, TransportConfigUpdateAction.NodeConfigUpdateRequest.class,
                ThreadPool.Names.MANAGEMENT);
        this.cl = cl;
        this.clusterService = clusterService;
        this.backendRegistry = backendRegistry;

        clusterService.addLifecycleListener(new LifecycleListener() {

            @Override
            public void afterStart() {

                super.afterStart();

                new Thread(new Runnable() {

                    @Override
                    public void run() {
                        logger.trace("Wait for yellow cluster status to set searchguard config initially");
                        client.admin().cluster().health(new ClusterHealthRequest("searchguard").waitForYellowStatus()).actionGet();
                        final Map<String, Settings> setn = cl.load(new String[] { "config", "roles", "rolesmapping", "internalusers",
                                "actiongroups" });
                        for (final String evt : setn.keySet()) {
                            for (final ConfigChangeListener cl : new ArrayList<ConfigChangeListener>(multimap.get(evt))) {
                                Settings settings = setn.get(evt);
                                if(settings != null) {
                                    cl.onChange(evt, settings);
                                    logger.debug("Updated {} for {}", evt, cl.getClass().getSimpleName());
                                }
                            }
                        }
                    }
                }).start();
            }
        });

    }

    public static class NodeConfigUpdateRequest extends BaseNodeRequest {

        ConfigUpdateRequest request;

        public NodeConfigUpdateRequest() {
        }

        public NodeConfigUpdateRequest(final String nodeId, final ConfigUpdateRequest request) {
            super(request, nodeId);
            this.request = request;
        }

        @Override
        public void readFrom(final StreamInput in) throws IOException {
            super.readFrom(in);
            request = new ConfigUpdateRequest();
            request.readFrom(in);
        }

        @Override
        public void writeTo(final StreamOutput out) throws IOException {
            super.writeTo(out);
            request.writeTo(out);
        }
    }

    @Override
    protected ConfigUpdateResponse newResponse(final ConfigUpdateRequest request, final AtomicReferenceArray nodesResponses) {
        
        final List<ConfigUpdateResponse> nodes = Lists.<ConfigUpdateResponse> newArrayList();
        for (int i = 0; i < nodesResponses.length(); i++) {
            final Object resp = nodesResponses.get(i);
            if (resp instanceof ConfigUpdateResponse) {
                nodes.add((ConfigUpdateResponse) resp);
            }
        }
        return new ConfigUpdateResponse(this.clusterName, nodes.toArray(new ConfigUpdateResponse.Node[nodes.size()]));

    }

    @Override
    protected NodeConfigUpdateRequest newNodeRequest(final String nodeId, final ConfigUpdateRequest request) {
        return new NodeConfigUpdateRequest(nodeId, request);
    }

    @Override
    protected Node newNodeResponse() {
        return new ConfigUpdateResponse.Node(clusterService.localNode());
    }

    @Override
    protected Node nodeOperation(final NodeConfigUpdateRequest request) {
        backendRegistry.get().invalidateCache();
        final Map<String, Settings> setn = cl.load(request.request.getConfigTypes());

        for (final String evt : setn.keySet()) {
            for (final ConfigChangeListener cl : new ArrayList<ConfigChangeListener>(multimap.get(evt))) {
                Settings settings = setn.get(evt);
                if(settings != null) {
                   cl.onChange(evt, settings);
                   logger.debug("Updated {} for {}", evt, cl.getClass().getSimpleName());
                }
            }
        }
        return new ConfigUpdateResponse.Node(clusterService.localNode());
    }

    public void addConfigChangeListener(final String event, final ConfigChangeListener listener) {
        logger.debug("Add config listener {}",listener.getClass());
        multimap.put(event, listener);
    }

    @Override
    protected boolean accumulateExceptions() {
        return false;
    }

}
