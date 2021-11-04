package io.grpc.examples.helloworldxds;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v2.SimpleCache;
import io.envoyproxy.controlplane.cache.v2.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.Cluster.DiscoveryType;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.core.*;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.api.v2.listener.Filter;
import io.envoyproxy.envoy.api.v2.listener.FilterChain;
import io.envoyproxy.envoy.config.listener.v2.ApiListener;
import io.envoyproxy.envoy.config.route.v3.*;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.route.v3.NonForwardingAction;
import io.grpc.xds.shaded.io.envoyproxy.envoy.service.load_stats.v3.LoadReportingServiceGrpc;
import io.grpc.xds.shaded.io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest;
import io.grpc.xds.shaded.io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class XdsControlPlane {

    public static final Logger logger = LoggerFactory.getLogger(XdsControlPlane.class);

    private static final String GROUP = "key";

    /**
     * Example minimal xDS implementation using the java-control-plane lib. This example configures
     * a DiscoveryServer with a v2 cache, but handles v2 or v3 requests from data planes.
     *
     * @param arg command-line args
     */
    public static void main(String[] arg) throws IOException, InterruptedException {
        logger.info("Staring control plane...");

        SimpleCache<String> cache = new SimpleCache<>(new NodeGroup<String>() {
            @Override
            public String hash(Node node) {
                return GROUP;
            }

            @Override
            public String hash(io.envoyproxy.envoy.config.core.v3.Node node) {
                return GROUP;
            }
        });

        RouteConfiguration localRoute = RouteConfiguration.newBuilder()
                .setName("local_route")
                .addVirtualHosts(
                        VirtualHost.newBuilder()
                                .setName("sc-virt-host")
                                .addDomains("*")
                                .addRoutes(
                                        Route.newBuilder()
                                                .setName("seriescache")
                                                .setRoute(RouteAction.newBuilder()
                                                        .setCluster("cluster0")
                                                        .build())
                                                .setMatch(
                                                        RouteMatch.newBuilder()
                                                                .setPrefix("/")
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                )
                .build();

        var nonForwarding = io.grpc.xds.shaded.io.envoyproxy.envoy.config.route.v3.RouteConfiguration.newBuilder()
                .setName("non_forwarding_route")
                .addVirtualHosts(
                        io.grpc.xds.shaded.io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
                                .setName("sc-virt-host")
                                .addDomains("*")
                                .addRoutes(
                                        io.grpc.xds.shaded.io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
                                                .setName("seriescache")
                                                .setNonForwardingAction(NonForwardingAction.newBuilder().build())
                                                .setMatch(
                                                        io.grpc.xds.shaded.io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                                                                .setPrefix("/")
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                )
                .build();
        Listener listener = Listener.newBuilder()
                .setName("seriescache")
                .setTrafficDirection(TrafficDirection.INBOUND)
                .setApiListener(
                        ApiListener.newBuilder()
                                .setApiListener(Any.pack(
                                        HttpConnectionManager.newBuilder()
                                                .addHttpFilters(
                                                        HttpFilter.newBuilder()
                                                                .setName("f1")
                                                                .setTypedConfig(Any.pack(
                                                                                Router.newBuilder()
                                                                                        .build()
                                                                        )
                                                                )
                                                                .build()
                                                )
                                                .setRouteConfig(localRoute)
                                                .build()
                                ))
                                .build()
                )
                .setAddress(
                        Address.newBuilder()
                                .setSocketAddress(
                                        SocketAddress.newBuilder()
                                                .setAddress("127.0.0.1")
                                                .setPortValue(8000)
                                                .build()
                                )
                                .build()
                )
                .addFilterChains(
                        FilterChain.newBuilder()
                                .addFilters(
                                        Filter.newBuilder()
                                                .setTypedConfig(
                                                        Any.pack(
                                                                HttpConnectionManager.newBuilder()
                                                                        .setRouteConfig(
                                                                                localRoute
                                                                        )
                                                                        .build()
                                                        )
                                                )
                                )
                                .build()
                ).build();
        Listener listener2 = Listener.newBuilder()
                .setName("seriescache-server")
                .setTrafficDirection(TrafficDirection.INBOUND)
                .addFilterChains(
                        FilterChain.newBuilder()
                                .addFilters(
                                        Filter.newBuilder()
                                                .setTypedConfig(
                                                        Any.pack(
                                                                io.grpc.xds.shaded.io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager.newBuilder()
                                                                        .setRouteConfig(
                                                                                nonForwarding
                                                                        )
                                                                        .build()
                                                        )
                                                )
                                )
                                .build()
                )
                .build();
        Snapshot snapshot = Snapshot.create(
                ImmutableList.of(
                        Cluster.newBuilder()
                                .setName("cluster0")
                                .setConnectTimeout(Duration.newBuilder().setSeconds(5))
                                .setType(DiscoveryType.EDS)
                                .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
                                .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
                                        .setEdsConfig(
                                                ConfigSource.newBuilder()
                                                        .setAds(
                                                                AggregatedConfigSource.newBuilder()
                                                                        .build()
                                                        ).build()
                                        )
                                        .build())
                                .build()),
                List.of(
                        ClusterLoadAssignment.newBuilder()
                                .setClusterName("cluster0")
                                .addEndpoints(LocalityLbEndpoints.newBuilder()
                                        .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100).build())
                                        .setLocality(Locality.newBuilder()
                                                .setRegion("US").build())
                                        .addLbEndpoints(
                                                LbEndpoint.newBuilder()
                                                        .setHealthStatus(HealthStatus.HEALTHY)
                                                        .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(50).build())
                                                        .setEndpoint(
                                                                Endpoint.newBuilder()
                                                                        .setAddress(
                                                                                Address.newBuilder()
                                                                                        .setSocketAddress(
                                                                                                SocketAddress.newBuilder()
                                                                                                        .setAddress("127.0.0.1")
                                                                                                        .setPortValue(50051)
                                                                                                        .build()
                                                                                        )
                                                                        )
                                                                        .build()
                                                        )
                                                        .build()
                                        )
                                        .addLbEndpoints(
                                                LbEndpoint.newBuilder()
                                                        .setHealthStatus(HealthStatus.HEALTHY)
                                                        .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(50).build())
                                                        .setEndpoint(
                                                                Endpoint.newBuilder()
                                                                        .setAddress(
                                                                                Address.newBuilder()
                                                                                        .setSocketAddress(
                                                                                                SocketAddress.newBuilder()
                                                                                                        .setAddress("127.0.0.1")
                                                                                                        .setPortValue(50052)
                                                                                                        .build()
                                                                                        )
                                                                        )
                                                                        .build()
                                                        )
                                                        .build()
                                        )
                                        .build())
                                .build()
                ),
                List.of(listener, listener2),
                List.of(),
                ImmutableList.of(),
                "1");

        cache.setSnapshot(GROUP, snapshot);

        V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);

        ServerBuilder builder = NettyServerBuilder.forPort(8000)
                .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                .addService(new LoadReportingServiceGrpc.LoadReportingServiceImplBase() {
                    @Override
                    public StreamObserver<LoadStatsRequest> streamLoadStats(StreamObserver<LoadStatsResponse> responseObserver) {
                        return new StreamObserver<LoadStatsRequest>() {
                            @Override
                            public void onNext(LoadStatsRequest value) {
                                responseObserver.onNext(LoadStatsResponse.newBuilder()
                                        .build());
                            }

                            @Override
                            public void onError(Throwable t) {
                                responseObserver.onError(t);
                            }

                            @Override
                            public void onCompleted() {
                                responseObserver.onCompleted();
                            }
                        };
                    }
                });

        Server server = builder.build();

        server.start();

        System.out.println("Server has started on port " + server.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        server.awaitTermination();
    }
}