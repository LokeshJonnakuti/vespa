// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.AuthMethod;
import com.yahoo.config.provision.zone.RoutingMethod;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.flags.BooleanFlag;
import com.yahoo.vespa.flags.FetchVector;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificate;
import com.yahoo.vespa.hosted.controller.api.integration.dns.Record;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordData;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordName;
import com.yahoo.vespa.hosted.controller.application.Endpoint;
import com.yahoo.vespa.hosted.controller.application.Endpoint.Port;
import com.yahoo.vespa.hosted.controller.application.Endpoint.Scope;
import com.yahoo.vespa.hosted.controller.application.EndpointId;
import com.yahoo.vespa.hosted.controller.application.EndpointList;
import com.yahoo.vespa.hosted.controller.application.GeneratedEndpoint;
import com.yahoo.vespa.hosted.controller.application.SystemApplication;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.application.pkg.BasicServicesXml;
import com.yahoo.vespa.hosted.controller.dns.NameServiceQueue.Priority;
import com.yahoo.vespa.hosted.controller.routing.GeneratedEndpoints;
import com.yahoo.vespa.hosted.controller.routing.PreparedEndpoints;
import com.yahoo.vespa.hosted.controller.routing.RoutingId;
import com.yahoo.vespa.hosted.controller.routing.RoutingPolicies;
import com.yahoo.vespa.hosted.controller.routing.RoutingPolicy;
import com.yahoo.vespa.hosted.controller.routing.RoutingPolicyList;
import com.yahoo.vespa.hosted.controller.routing.context.DeploymentRoutingContext;
import com.yahoo.vespa.hosted.controller.routing.context.DeploymentRoutingContext.ExclusiveDeploymentRoutingContext;
import com.yahoo.vespa.hosted.controller.routing.context.DeploymentRoutingContext.SharedDeploymentRoutingContext;
import com.yahoo.vespa.hosted.controller.routing.context.ExclusiveZoneRoutingContext;
import com.yahoo.vespa.hosted.controller.routing.context.RoutingContext;
import com.yahoo.vespa.hosted.controller.routing.context.SharedZoneRoutingContext;
import com.yahoo.vespa.hosted.controller.routing.rotation.Rotation;
import com.yahoo.vespa.hosted.controller.routing.rotation.RotationLock;
import com.yahoo.vespa.hosted.controller.routing.rotation.RotationRepository;
import com.yahoo.vespa.hosted.rotation.config.RotationsConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;

/**
 * The routing controller is owned by {@link Controller} and encapsulates state and methods for inspecting and
 * manipulating deployment endpoints in a hosted Vespa system.
 *
 * The one-stop shop for all your routing needs!
 *
 * @author mpolden
 */
public class RoutingController {

    private final Controller controller;
    private final RoutingPolicies routingPolicies;
    private final RotationRepository rotationRepository;
    private final BooleanFlag randomizedEndpoints;

    public RoutingController(Controller controller, RotationsConfig rotationsConfig) {
        this.controller = Objects.requireNonNull(controller, "controller must be non-null");
        this.routingPolicies = new RoutingPolicies(controller);
        this.rotationRepository = new RotationRepository(Objects.requireNonNull(rotationsConfig, "rotationsConfig must be non-null"),
                                                         controller.applications(),
                                                         controller.curator());
        this.randomizedEndpoints = Flags.RANDOMIZED_ENDPOINT_NAMES.bindTo(controller.flagSource());
    }

    /** Create a routing context for given deployment */
    public DeploymentRoutingContext of(DeploymentId deployment) {
        if (usesSharedRouting(deployment.zoneId())) {
            return new SharedDeploymentRoutingContext(deployment,
                                                      this,
                                                      controller.serviceRegistry().configServer(),
                                                      controller.clock());
        }
        return new ExclusiveDeploymentRoutingContext(deployment, this);
    }

    /** Create a routing context for given zone */
    public RoutingContext of(ZoneId zone) {
        if (usesSharedRouting(zone)) {
            return new SharedZoneRoutingContext(zone, controller.serviceRegistry().configServer());
        }
        return new ExclusiveZoneRoutingContext(zone, routingPolicies);
    }

    public RoutingPolicies policies() {
        return routingPolicies;
    }

    public RotationRepository rotations() {
        return rotationRepository;
    }

    /** Prepares and returns the endpoints relevant for given deployment */
    public PreparedEndpoints prepare(DeploymentId deployment, BasicServicesXml services, Optional<EndpointCertificate> certificate, LockedApplication application) {
        EndpointList endpoints = EndpointList.EMPTY;

        // Assign rotations to application
        for (var deploymentInstanceSpec : application.get().deploymentSpec().instances()) {
            if (deploymentInstanceSpec.concerns(Environment.prod)) {
                application = controller.routing().assignRotations(application, deploymentInstanceSpec.name());
            }
        }

        // Add zone-scoped endpoints
        final GeneratedEndpoints generatedEndpoints;
        if (randomizedEndpointsEnabled(deployment.applicationId())) { // TODO(mpolden): Remove this guard once config-models < 8.220 are gone
            Map<ClusterSpec.Id, List<GeneratedEndpoint>> generatedEndpointsByCluster = new HashMap<>();
            RoutingPolicyList deploymentPolicies = policies().read(deployment);
            for (var container : services.containers()) {
                ClusterSpec.Id clusterId = ClusterSpec.Id.from(container.id());
                boolean tokenSupported = container.authMethods().contains(BasicServicesXml.Container.AuthMethod.token);
                // Use already existing generated endpoints, if any
                List<GeneratedEndpoint> generatedForCluster = deploymentPolicies.cluster(clusterId)
                                                                                .first()
                                                                                .map(RoutingPolicy::generatedEndpoints)
                                                                                .orElseGet(List::of);
                if (generatedForCluster.isEmpty()) {
                    generatedForCluster = certificate.flatMap(EndpointCertificate::randomizedId)
                                                     .map(id -> generateEndpoints(id, deployment.applicationId(), tokenSupported))
                                                     .orElseGet(List::of);
                }
                if (!generatedForCluster.isEmpty()) {
                    generatedEndpointsByCluster.put(clusterId, generatedForCluster);
                }
                endpoints = endpoints.and(endpointsOf(deployment, clusterId, generatedForCluster).scope(Scope.zone));
            }
            generatedEndpoints = new GeneratedEndpoints(generatedEndpointsByCluster);
        } else {
            generatedEndpoints = GeneratedEndpoints.empty;
        }

        // Add global- and application-scoped endpoints
        endpoints = endpoints.and(declaredEndpointsOf(application.get().id(), application.get().deploymentSpec(), generatedEndpoints).targets(deployment));
        PreparedEndpoints prepared = new PreparedEndpoints(deployment,
                                                           endpoints,
                                                           application.get().require(deployment.applicationId().instance()).rotations(),
                                                           certificate);

        // Register rotation-backed endpoints in DNS
        registerRotationEndpointsInDns(prepared);

        return prepared;
    }

    // -------------- Implicit endpoints (scopes 'zone' and 'weighted') --------------

    /** Returns the zone- and region-scoped endpoints of given deployment */
    public EndpointList endpointsOf(DeploymentId deployment, ClusterSpec.Id cluster, List<GeneratedEndpoint> generatedEndpoints) {
        boolean tokenSupported = generatedEndpoints.stream().anyMatch(ge -> ge.authMethod() == AuthMethod.token);
        RoutingMethod routingMethod = controller.zoneRegistry().routingMethod(deployment.zoneId());
        boolean isProduction = deployment.zoneId().environment().isProduction();
        List<Endpoint> endpoints = new ArrayList<>();
        Endpoint.EndpointBuilder zoneEndpoint = Endpoint.of(deployment.applicationId())
                                                        .routingMethod(routingMethod)
                                                        .on(Port.fromRoutingMethod(routingMethod))
                                                        .target(cluster, deployment);
        endpoints.add(zoneEndpoint.in(controller.system()));
        Endpoint.EndpointBuilder regionEndpoint = Endpoint.of(deployment.applicationId())
                                                          .routingMethod(routingMethod)
                                                          .on(Port.fromRoutingMethod(routingMethod))
                                                          .targetRegion(cluster, deployment.zoneId());
        // Region endpoints are only used by global- and application-endpoints and are thus only needed in
        // production environments
        if (isProduction) {
            endpoints.add(regionEndpoint.in(controller.system()));
        }
        for (var generatedEndpoint : generatedEndpoints) {
            boolean include = switch (generatedEndpoint.authMethod()) {
                case token -> tokenSupported;
                case mtls -> true;
                case none -> false;
            };
            if (include) {
                endpoints.add(zoneEndpoint.generatedFrom(generatedEndpoint)
                                          .authMethod(generatedEndpoint.authMethod())
                                          .in(controller.system()));
                // Only a single region endpoint is needed, not one per auth method
                if (isProduction && generatedEndpoint.authMethod() == AuthMethod.mtls) {
                    endpoints.add(regionEndpoint.generatedFrom(generatedEndpoint)
                                                .authMethod(AuthMethod.none)
                                                .in(controller.system()));
                }
            }
        }
        return EndpointList.copyOf(endpoints);
    }

    /** Read routing policies and return zone- and region-scoped endpoints for given deployment */
    public EndpointList readEndpointsOf(DeploymentId deployment) {
        Set<Endpoint> endpoints = new LinkedHashSet<>();
        for (var policy : routingPolicies.read(deployment)) {
            endpoints.addAll(endpointsOf(deployment, policy.id().cluster(), policy.generatedEndpoints()).asList());
        }
        return EndpointList.copyOf(endpoints);
    }

    // -------------- Declared endpoints (scopes 'global' and 'application') --------------

    /** Returns global endpoints pointing to given deployments */
    public EndpointList declaredEndpointsOf(RoutingId routingId, ClusterSpec.Id cluster, List<DeploymentId> deployments, GeneratedEndpoints generatedEndpoints) {
        var endpoints = new ArrayList<Endpoint>();
        var directMethods = 0;
        var availableRoutingMethods = routingMethodsOfAll(deployments);
        for (var method : availableRoutingMethods) {
            if (method.isDirect() && ++directMethods > 1) {
                throw new IllegalArgumentException("Invalid routing methods for " + routingId + ": Exceeded maximum " +
                                                   "direct methods");
            }
            Endpoint.EndpointBuilder builder = Endpoint.of(routingId.instance())
                                                       .target(routingId.endpointId(), cluster, deployments)
                                                       .on(Port.fromRoutingMethod(method))
                                                       .routingMethod(method);
            endpoints.add(builder.in(controller.system()));
            for (var ge : generatedEndpoints.cluster(cluster)) {
                endpoints.add(builder.generatedFrom(ge).authMethod(ge.authMethod()).in(controller.system()));
            }
        }
        return EndpointList.copyOf(endpoints);
    }

    /** Returns application endpoints pointing to given deployments */
    public EndpointList declaredEndpointsOf(TenantAndApplicationId application, EndpointId endpoint, ClusterSpec.Id cluster,
                                            Map<DeploymentId, Integer> deployments, GeneratedEndpoints generatedEndpoints) {
        ZoneId zone = deployments.keySet().iterator().next().zoneId(); // Where multiple zones are possible, they all have the same routing method.
        RoutingMethod routingMethod = usesSharedRouting(zone) ? RoutingMethod.sharedLayer4 : RoutingMethod.exclusive;
        Endpoint.EndpointBuilder builder = Endpoint.of(application)
                                                   .targetApplication(endpoint,
                                                                      cluster,
                                                                      deployments)
                                                   .routingMethod(routingMethod)
                                                   .on(Port.fromRoutingMethod(routingMethod));
        List<Endpoint> endpoints = new ArrayList<>();
        endpoints.add(builder.in(controller.system()));
        for (var ge : generatedEndpoints.cluster(cluster)) {
            endpoints.add(builder.generatedFrom(ge).authMethod(ge.authMethod()).in(controller.system()));
        }
        return EndpointList.copyOf(endpoints);
    }

    /** Read application and return endpoints for all instances in application */
    public EndpointList readDeclaredEndpointsOf(Application application) {
        return declaredEndpointsOf(application.id(), application.deploymentSpec(), readMultiDeploymentGeneratedEndpoints(application.id()));
    }

    /** Read application and return declared endpoints for given instance */
    public EndpointList readDeclaredEndpointsOf(ApplicationId instance) {
        if (SystemApplication.matching(instance).isPresent()) return EndpointList.EMPTY;
        Application application = controller.applications().requireApplication(TenantAndApplicationId.from(instance));
        return readDeclaredEndpointsOf(application).instance(instance.instance());
    }

    private EndpointList declaredEndpointsOf(TenantAndApplicationId application, DeploymentSpec deploymentSpec, GeneratedEndpoints generatedEndpoints) {
        Set<Endpoint> endpoints = new LinkedHashSet<>();
        // Global endpoints
        for (var spec : deploymentSpec.instances()) {
            ApplicationId instance = application.instance(spec.name());
            for (var declaredEndpoint : spec.endpoints()) {
                RoutingId routingId = RoutingId.of(instance, EndpointId.of(declaredEndpoint.endpointId()));
                List<DeploymentId> deployments = declaredEndpoint.regions().stream()
                                                                 .map(region -> new DeploymentId(instance,
                                                                                                 ZoneId.from(Environment.prod, region)))
                                                                 .toList();
                ClusterSpec.Id cluster = ClusterSpec.Id.from(declaredEndpoint.containerId());
                endpoints.addAll(declaredEndpointsOf(routingId, cluster, deployments, generatedEndpoints).asList());
            }
        }
        // Application endpoints
        for (var declaredEndpoint : deploymentSpec.endpoints()) {
            Map<DeploymentId, Integer> deployments = declaredEndpoint.targets().stream()
                                                                     .collect(toMap(t -> new DeploymentId(application.instance(t.instance()),
                                                                                                          ZoneId.from(Environment.prod, t.region())),
                                                                                    t -> t.weight()));
            ClusterSpec.Id cluster = ClusterSpec.Id.from(declaredEndpoint.containerId());
            endpoints.addAll(declaredEndpointsOf(application, EndpointId.of(declaredEndpoint.endpointId()), cluster,
                                                 deployments, generatedEndpoints).asList());
        }
        return EndpointList.copyOf(endpoints);
    }

    // -------------- Other gunk related to endpoints and routing --------------

    /** Read endpoints for use in deployment steps, for given deployments, grouped by their zone */
    public Map<ZoneId, List<Endpoint>> readStepRunnerEndpointsOf(Collection<DeploymentId> deployments) {
        TreeMap<ZoneId, List<Endpoint>> endpoints = new TreeMap<>(Comparator.comparing(ZoneId::value));
        for (var deployment : deployments) {
            EndpointList zoneEndpoints = readEndpointsOf(deployment).scope(Endpoint.Scope.zone)
                                                                    .authMethod(AuthMethod.mtls)
                                                                    .not().legacy();
            EndpointList directEndpoints = zoneEndpoints.direct();
            if (!directEndpoints.isEmpty()) {
                zoneEndpoints = directEndpoints; // Use only direct endpoints if we have any
            }
            EndpointList generatedEndpoints = zoneEndpoints.generated();
            if (!generatedEndpoints.isEmpty()) {
                zoneEndpoints = generatedEndpoints; // Use generated endpoints if we have any
            }
            if  ( ! zoneEndpoints.isEmpty()) {
                endpoints.put(deployment.zoneId(), zoneEndpoints.asList());
            }
        }
        return Collections.unmodifiableSortedMap(endpoints);
    }

    /** Returns certificate DNS names (CN and SAN values) for given deployment */
    public List<String> certificateDnsNames(DeploymentId deployment, DeploymentSpec deploymentSpec) {
        List<String> endpointDnsNames = new ArrayList<>();

        // We add first an endpoint name based on a hash of the application ID,
        // as the certificate provider requires the first CN to be < 64 characters long.
        endpointDnsNames.add(commonNameHashOf(deployment.applicationId(), controller.system()));

        List<Endpoint.EndpointBuilder> builders = new ArrayList<>();
        if (deployment.zoneId().environment().isProduction()) {
            // Add default and wildcard names for global endpoints
            builders.add(Endpoint.of(deployment.applicationId()).target(EndpointId.defaultId()));
            builders.add(Endpoint.of(deployment.applicationId()).wildcard());

            // Add default and wildcard names for each region targeted by application endpoints
            List<DeploymentId> deploymentTargets = deploymentSpec.endpoints().stream()
                                                                 .map(com.yahoo.config.application.api.Endpoint::targets)
                                                                 .flatMap(Collection::stream)
                                                                 .map(com.yahoo.config.application.api.Endpoint.Target::region)
                                                                 .distinct()
                                                                 .map(region -> new DeploymentId(deployment.applicationId(), ZoneId.from(Environment.prod, region)))
                                                                 .toList();
            TenantAndApplicationId application = TenantAndApplicationId.from(deployment.applicationId());
            for (var targetDeployment : deploymentTargets) {
                builders.add(Endpoint.of(application).targetApplication(EndpointId.defaultId(), targetDeployment));
                builders.add(Endpoint.of(application).wildcardApplication(targetDeployment));
            }
        }

        // Add default and wildcard names for zone endpoints
        builders.add(Endpoint.of(deployment.applicationId()).target(ClusterSpec.Id.from("default"), deployment));
        builders.add(Endpoint.of(deployment.applicationId()).wildcard(deployment));

        // Build all certificate names
        for (var builder : builders) {
            Endpoint endpoint = builder.certificateName()
                                       .routingMethod(RoutingMethod.exclusive)
                                       .on(Port.tls())
                                       .in(controller.system());
            endpointDnsNames.add(endpoint.dnsName());
        }
        return Collections.unmodifiableList(endpointDnsNames);
    }

    /** Remove endpoints in DNS for all rotations assigned to given instance */
    public void removeRotationEndpointsFromDns(Application application, InstanceName instanceName) {
        Set<Endpoint> endpointsToRemove = new LinkedHashSet<>();
        Instance instance = application.require(instanceName);
        // Compute endpoints from rotations. When removing DNS records for rotation-based endpoints we cannot use the
        // deployment spec, because submitting an empty deployment spec is the first step of removing an application
        for (var rotation : instance.rotations()) {
            var deployments = rotation.regions().stream()
                                      .map(region -> new DeploymentId(instance.id(), ZoneId.from(Environment.prod, region)))
                                      .toList();
            endpointsToRemove.addAll(declaredEndpointsOf(RoutingId.of(instance.id(), rotation.endpointId()),
                                                         rotation.clusterId(), deployments, readMultiDeploymentGeneratedEndpoints(application.id())).asList());
        }
        endpointsToRemove.forEach(endpoint -> controller.nameServiceForwarder()
                                                        .removeRecords(Record.Type.CNAME,
                                                                       RecordName.from(endpoint.dnsName()),
                                                                       Priority.normal,
                                                                       Optional.of(application.id())));
    }

    private void registerRotationEndpointsInDns(PreparedEndpoints prepared) {
        TenantAndApplicationId owner = TenantAndApplicationId.from(prepared.deployment().applicationId());
        EndpointList globalEndpoints = prepared.endpoints().scope(Scope.global);
        for (var assignedRotation : prepared.rotations()) {
            EndpointList rotationEndpoints = globalEndpoints.named(assignedRotation.endpointId(), Scope.global)
                                                            .requiresRotation();
            // Skip rotations which do not apply to this zone
            if (!assignedRotation.regions().contains(prepared.deployment().zoneId().region())) {
                continue;
            }
            // Register names in DNS
            Rotation rotation = rotationRepository.requireRotation(assignedRotation.rotationId());
            for (var endpoint : rotationEndpoints) {
                controller.nameServiceForwarder().createRecord(
                        new Record(Record.Type.CNAME, RecordName.from(endpoint.dnsName()), RecordData.fqdn(rotation.name())),
                        Priority.normal,
                        Optional.of(owner)
                );
            }
        }
        for (var endpoint : prepared.endpoints().scope(Scope.application).shared()) { // DNS for non-shared application endpoints is handled by RoutingPolicies
            Set<ZoneId> targetZones = endpoint.targets().stream()
                                              .map(t -> t.deployment().zoneId())
                                              .collect(Collectors.toUnmodifiableSet());
            if (targetZones.size() != 1) throw new IllegalArgumentException("Endpoint '" + endpoint.name() +
                                                                            "' must target a single zone, got " +
                                                                            targetZones);
            ZoneId targetZone = targetZones.iterator().next();
            String vipHostname = controller.zoneRegistry().getVipHostname(targetZone)
                                           .orElseThrow(() -> new IllegalArgumentException("No VIP configured for zone " + targetZone));
            controller.nameServiceForwarder().createRecord(
                    new Record(Record.Type.CNAME, RecordName.from(endpoint.dnsName()), RecordData.fqdn(vipHostname)),
                    Priority.normal,
                    Optional.of(owner));
        }
    }

    /** Generate endpoints for all authentication methods, using given application part */
    private List<GeneratedEndpoint> generateEndpoints(String applicationPart, ApplicationId instance, boolean token) {
        if (!randomizedEndpointsEnabled(instance)) {
            return List.of();
        }
        return Arrays.stream(AuthMethod.values())
                     .filter(method -> switch (method) {
                         case token -> token;
                         case mtls -> true;
                         case none -> false;
                     })
                     .map(method -> new GeneratedEndpoint(GeneratedEndpoint.createPart(controller.random(true)),
                                                          applicationPart,
                                                          method))
                     .toList();
    }

    /** Returns generated endpoint suitable for use in endpoints whose scope is {@link Scope#multiDeployment()} */
    private GeneratedEndpoints readMultiDeploymentGeneratedEndpoints(TenantAndApplicationId application) {
        Map<ClusterSpec.Id, List<GeneratedEndpoint>> endpoints = new HashMap<>();
        for (var policy : policies().read(application)) {
            // The cluster part is not used in this context because multi-deployment endpoints have a user-controlled name
            endpoints.putIfAbsent(policy.id().cluster(), policy.generatedEndpoints().stream().toList());
        }
        return new GeneratedEndpoints(endpoints);
    }

    /**
     * Assigns one or more global rotations to given application, if eligible. The given application is implicitly
     * stored, ensuring that the assigned rotation(s) are persisted when this returns.
     */
    private LockedApplication assignRotations(LockedApplication application, InstanceName instanceName) {
        try (RotationLock rotationLock = rotationRepository.lock()) {
            var rotations = rotationRepository.getOrAssignRotations(application.get().deploymentSpec(),
                                                                    application.get().require(instanceName),
                                                                    rotationLock);
            application = application.with(instanceName, instance -> instance.with(rotations));
            controller.applications().store(application); // store assigned rotation even if deployment fails
        }
        return application;
    }

    private boolean usesSharedRouting(ZoneId zone) {
        return controller.zoneRegistry().routingMethod(zone).isShared();
    }

    /** Returns the routing methods that are available across all given deployments */
    private List<RoutingMethod> routingMethodsOfAll(Collection<DeploymentId> deployments) {
        Map<RoutingMethod, Set<DeploymentId>> deploymentsByMethod = new HashMap<>();
        for (var deployment : deployments) {
            RoutingMethod routingMethod = controller.zoneRegistry().routingMethod(deployment.zoneId());
            deploymentsByMethod.computeIfAbsent(routingMethod, k -> new LinkedHashSet<>())
                               .add(deployment);
        }
        List<RoutingMethod> routingMethods = new ArrayList<>();
        deploymentsByMethod.forEach((method, supportedDeployments) -> {
            if (supportedDeployments.containsAll(deployments)) {
                routingMethods.add(method);
            }
        });
        return Collections.unmodifiableList(routingMethods);
    }

    public boolean randomizedEndpointsEnabled(ApplicationId instance) {
        return randomizedEndpoints.with(FetchVector.Dimension.APPLICATION_ID, instance.serializedForm()).value();
    }

    /** Create a common name based on a hash of given application. This must be less than 64 characters long. */
    private static String commonNameHashOf(ApplicationId application, SystemName system) {
        @SuppressWarnings("deprecation") // for Hashing.sha1()
        HashCode sha1 = Hashing.sha1().hashString(application.serializedForm(), StandardCharsets.UTF_8);
        String base32 = BaseEncoding.base32().omitPadding().lowerCase().encode(sha1.asBytes());
        return 'v' + base32 + Endpoint.internalDnsSuffix(system);
    }

}
