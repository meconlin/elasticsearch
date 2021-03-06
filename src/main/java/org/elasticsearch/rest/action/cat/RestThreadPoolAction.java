/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.rest.action.cat;

import com.google.common.collect.Maps;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.node.info.NodeInfo;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.action.admin.cluster.node.stats.NodeStats;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsRequest;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.Table;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.XContentThrowableRestResponse;
import org.elasticsearch.rest.action.support.RestTable;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.ThreadPoolStats;

import java.io.IOException;
import java.util.*;

import static org.elasticsearch.rest.RestRequest.Method.GET;

public class RestThreadPoolAction extends AbstractCatAction {

    private final static String[] SUPPORTED_NAMES = new String[] {
            ThreadPool.Names.BULK,
            ThreadPool.Names.FLUSH,
            ThreadPool.Names.GENERIC,
            ThreadPool.Names.GET,
            ThreadPool.Names.INDEX,
            ThreadPool.Names.MANAGEMENT,
            ThreadPool.Names.MERGE,
            ThreadPool.Names.OPTIMIZE,
            ThreadPool.Names.PERCOLATE,
            ThreadPool.Names.REFRESH,
            ThreadPool.Names.SEARCH,
            ThreadPool.Names.SNAPSHOT,
            ThreadPool.Names.SUGGEST,
            ThreadPool.Names.WARMER
    };

    private final static String[] SUPPORTED_ALIASES = new String[] {
            "b",
            "f",
            "ge",
            "g",
            "i",
            "ma",
            "m",
            "o",
            "p",
            "r",
            "s",
            "sn",
            "su",
            "w"
    };

    private final static String[] DEFAULT_THREAD_POOLS = new String[] {
            ThreadPool.Names.BULK,
            ThreadPool.Names.INDEX,
            ThreadPool.Names.SEARCH,
    };

    private final static Map<String, String> ALIAS_TO_THREAD_POOL;
    private final static Map<String, String> THREAD_POOL_TO_ALIAS;

    static {
        ALIAS_TO_THREAD_POOL = Maps.newHashMapWithExpectedSize(SUPPORTED_NAMES.length);
        for (String supportedThreadPool : SUPPORTED_NAMES) {
            ALIAS_TO_THREAD_POOL.put(supportedThreadPool.substring(0, 3), supportedThreadPool);
        }
        THREAD_POOL_TO_ALIAS = Maps.newHashMapWithExpectedSize(SUPPORTED_NAMES.length);
        for (int i = 0; i < SUPPORTED_NAMES.length; i++) {
            THREAD_POOL_TO_ALIAS.put(SUPPORTED_NAMES[i], SUPPORTED_ALIASES[i]);
        }
    }

    @Inject
    public RestThreadPoolAction(Settings settings, Client client, RestController controller) {
        super(settings, client);
        controller.registerHandler(GET, "/_cat/thread_pool", this);
    }

    @Override
    void documentation(StringBuilder sb) {
        sb.append("/_cat/thread_pool\n");
    }

    @Override
    public void doRequest(final RestRequest request, final RestChannel channel) {
        final ClusterStateRequest clusterStateRequest = new ClusterStateRequest();
        clusterStateRequest.clear().nodes(true);
        clusterStateRequest.local(request.paramAsBoolean("local", clusterStateRequest.local()));
        clusterStateRequest.masterNodeTimeout(request.paramAsTime("master_timeout", clusterStateRequest.masterNodeTimeout()));
        final String[] pools = fetchSortedPools(request, DEFAULT_THREAD_POOLS);

        client.admin().cluster().state(clusterStateRequest, new ActionListener<ClusterStateResponse>() {
            @Override
            public void onResponse(final ClusterStateResponse clusterStateResponse) {
                NodesInfoRequest nodesInfoRequest = new NodesInfoRequest();
                nodesInfoRequest.clear().process(true);
                client.admin().cluster().nodesInfo(nodesInfoRequest, new ActionListener<NodesInfoResponse>() {
                    @Override
                    public void onResponse(final NodesInfoResponse nodesInfoResponse) {
                        NodesStatsRequest nodesStatsRequest = new NodesStatsRequest();
                        nodesStatsRequest.clear().threadPool(true);
                        client.admin().cluster().nodesStats(nodesStatsRequest, new ActionListener<NodesStatsResponse>() {
                            @Override
                            public void onResponse(NodesStatsResponse nodesStatsResponse) {
                                try {
                                    channel.sendResponse(RestTable.buildResponse(buildTable(request, clusterStateResponse, nodesInfoResponse, nodesStatsResponse, pools), request, channel));
                                } catch (Throwable e) {
                                    onFailure(e);
                                }
                            }

                            @Override
                            public void onFailure(Throwable e) {
                                try {
                                    channel.sendResponse(new XContentThrowableRestResponse(request, e));
                                } catch (IOException e1) {
                                    logger.error("Failed to send failure response", e1);
                                }
                            }
                        });
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        try {
                            channel.sendResponse(new XContentThrowableRestResponse(request, e));
                        } catch (IOException e1) {
                            logger.error("Failed to send failure response", e1);
                        }
                    }
                });
            }

            @Override
            public void onFailure(Throwable e) {
                try {
                    channel.sendResponse(new XContentThrowableRestResponse(request, e));
                } catch (IOException e1) {
                    logger.error("Failed to send failure response", e1);
                }
            }
        });
    }

    @Override
    Table getTableWithHeader(final RestRequest request) {
        Table table = new Table();
        table.startHeaders();
        table.addCell("id", "default:false;alias:id,nodeId;desc:unique node id");
        table.addCell("pid", "default:false;alias:p;desc:process id");
        table.addCell("host", "alias:h;desc:host name");
        table.addCell("ip", "alias:i;desc:ip address");
        table.addCell("port", "default:false;alias:po;desc:bound transport port");

        final String[] requestedPools = fetchSortedPools(request, DEFAULT_THREAD_POOLS);
        for (String pool : SUPPORTED_NAMES) {
            String poolAlias = THREAD_POOL_TO_ALIAS.get(pool);
            boolean display = false;
            for (String requestedPool : requestedPools) {
                if (pool.equals(requestedPool)) {
                    display = true;
                    break;
                }
            }

            String defaultDisplayVal = Boolean.toString(display);
            table.addCell(
                    pool + ".active",
                    "alias:" + poolAlias + "a;default:" + defaultDisplayVal + ";text-align:right;desc:number of active " + pool + " threads"
            );
            table.addCell(
                    pool + ".size",
                    "alias:" + poolAlias + "s;default:false;text-align:right;desc:number of active " + pool + " threads"
            );
            table.addCell(
                    pool + ".queue",
                    "alias:" + poolAlias + "q;default:" + defaultDisplayVal + ";text-align:right;desc:number of " + pool + " threads in queue"
            );
            table.addCell(
                    pool + ".rejected",
                    "alias:" + poolAlias + "r;default:" + defaultDisplayVal + ";text-align:right;desc:number of rejected " + pool + " threads"
            );
            table.addCell(
                    pool + ".largest",
                    "alias:" + poolAlias + "l;default:false;text-align:right;desc:highest number of seen active " + pool + " threads"
            );
            table.addCell(
                    pool + ".completed",
                    "alias:" + poolAlias + "c;default:false;text-align:right;desc:number of completed " + pool + " threads"
            );
        }

        table.endHeaders();
        return table;
    }


    private Table buildTable(RestRequest req, ClusterStateResponse state, NodesInfoResponse nodesInfo, NodesStatsResponse nodesStats, String[] pools) {
        boolean fullId = req.paramAsBoolean("full_id", false);
        DiscoveryNodes nodes = state.getState().nodes();
        Table table = getTableWithHeader(req);

        for (DiscoveryNode node : nodes) {
            NodeInfo info = nodesInfo.getNodesMap().get(node.id());
            NodeStats stats = nodesStats.getNodesMap().get(node.id());
            table.startRow();

            table.addCell(fullId ? node.id() : Strings.substring(node.getId(), 0, 4));
            table.addCell(info == null ? null : info.getProcess().id());
            table.addCell(node.getHostName());
            table.addCell(node.getHostAddress());
            if (node.address() instanceof InetSocketTransportAddress) {
                table.addCell(((InetSocketTransportAddress) node.address()).address().getPort());
            } else {
                table.addCell("-");
            }

            final Map<String, ThreadPoolStats.Stats> poolThreadStats;
            if (stats == null) {
                poolThreadStats = Collections.emptyMap();
            } else {
                poolThreadStats = new HashMap<String, ThreadPoolStats.Stats>(14);
                ThreadPoolStats threadPoolStats = stats.getThreadPool();
                for (ThreadPoolStats.Stats threadPoolStat : threadPoolStats) {
                    poolThreadStats.put(threadPoolStat.getName(), threadPoolStat);
                }
            }
            for (String pool : SUPPORTED_NAMES) {
                ThreadPoolStats.Stats poolStats = poolThreadStats.get(pool);
                table.addCell(poolStats == null ? null : poolStats.getActive());
                table.addCell(poolStats == null ? null : poolStats.getThreads());
                table.addCell(poolStats == null ? null : poolStats.getQueue());
                table.addCell(poolStats == null ? null : poolStats.getRejected());
                table.addCell(poolStats == null ? null : poolStats.getLargest());
                table.addCell(poolStats == null ? null : poolStats.getCompleted());
            }

            table.endRow();
        }

        return table;
    }

    // The thread pool columns should always be in the same order.
    private String[] fetchSortedPools(RestRequest request, String[] defaults) {
        String[] headers = request.paramAsStringArray("h", null);
        if (headers == null) {
            return defaults;
        } else {
            Set<String> requestedPools = new LinkedHashSet<String>(headers.length);
            for (String header : headers) {
                int dotIndex = header.indexOf('.');
                if (dotIndex != -1) {
                    String headerPrefix = header.substring(0, dotIndex);
                    if (THREAD_POOL_TO_ALIAS.containsKey(headerPrefix)) {
                        requestedPools.add(headerPrefix);
                    }
                } else if (ALIAS_TO_THREAD_POOL.containsKey(header)) {
                    requestedPools.add(ALIAS_TO_THREAD_POOL.get(header));
                }

            }
            return requestedPools.toArray(new String[0]);
        }
    }
}
