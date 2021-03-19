// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;


import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.api.integration.noderepository.NodeMembership;
import com.yahoo.vespa.hosted.controller.api.integration.noderepository.NodeOwner;
import com.yahoo.vespa.hosted.controller.api.integration.noderepository.NodeRepositoryNode;
import com.yahoo.vespa.hosted.controller.api.integration.noderepository.NodeState;
import com.yahoo.vespa.hosted.controller.integration.NodeRepositoryMock;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ChangeManagementAssessorTest {

    private ChangeManagementAssessor changeManagementAssessor = new ChangeManagementAssessor(new NodeRepositoryMock());

    @Test
    public void empty_input_variations() {
        ZoneId zone = ZoneId.from("prod", "eu-trd");
        List<String> hostNames = new ArrayList<>();
        List<NodeRepositoryNode> allNodesInZone = new ArrayList<>();

        // Both zone and hostnames are empty
        ChangeManagementAssessor.Assessment assessment
                = changeManagementAssessor.assessmentInner(hostNames, allNodesInZone, zone);
        assertEquals(0, assessment.getClusterAssessments().size());
    }

    @Test
    public void one_host_one_cluster_no_groups() {
        ZoneId zone = ZoneId.from("prod", "eu-trd");
        List<String> hostNames = Collections.singletonList("host1");
        List<NodeRepositoryNode> allNodesInZone = new ArrayList<>();
        allNodesInZone.add(createNode("node1", "host1", "myapp", "default", 0 ));
        allNodesInZone.add(createNode("node2", "host1", "myapp", "default", 0 ));
        allNodesInZone.add(createNode("node3", "host1", "myapp", "default", 0 ));

        // Add an not impacted hosts
        allNodesInZone.add(createNode("node4", "host2", "myapp", "default", 0 ));

        // Make Assessment
        List<ChangeManagementAssessor.ClusterAssessment> assessments
                = changeManagementAssessor.assessmentInner(hostNames, allNodesInZone, zone).getClusterAssessments();

        // Assess the assessment :-o
        assertEquals(1, assessments.size());
        assertEquals(3, assessments.get(0).clusterImpact);
        assertEquals(4, assessments.get(0).clusterSize);
        assertEquals(1, assessments.get(0).groupsImpact);
        assertEquals(1, assessments.get(0).groupsTotal);
        assertEquals("content:default", assessments.get(0).cluster);
        assertEquals("mytenant:myapp:default", assessments.get(0).app);
        assertEquals("prod.eu-trd", assessments.get(0).zone);
    }

    @Test
    public void one_of_two_groups_in_one_of_two_clusters() {
        ZoneId zone = ZoneId.from("prod", "eu-trd");
        List<String> hostNames = Arrays.asList("host1", "host2");
        List<NodeRepositoryNode> allNodesInZone = new ArrayList<>();

        // Two impacted nodes on host1
        allNodesInZone.add(createNode("node1", "host1", "myapp", "default", 0 ));
        allNodesInZone.add(createNode("node2", "host1", "myapp", "default", 0 ));

        // One impacted nodes on host2
        allNodesInZone.add(createNode("node3", "host2", "myapp", "default", 0 ));

        // Another group on hosts not impacted
        allNodesInZone.add(createNode("node4", "host3", "myapp", "default", 1 ));
        allNodesInZone.add(createNode("node5", "host3", "myapp", "default", 1 ));
        allNodesInZone.add(createNode("node6", "host3", "myapp", "default", 1 ));

        // Another cluster on hosts not impacted - this one also with three different groups (should all be ignored here)
        allNodesInZone.add(createNode("node4", "host4", "myapp", "myman", 4 ));
        allNodesInZone.add(createNode("node5", "host4", "myapp", "myman", 5 ));
        allNodesInZone.add(createNode("node6", "host4", "myapp", "myman", 6 ));

        // Make Assessment
        ChangeManagementAssessor.Assessment assessment
                = changeManagementAssessor.assessmentInner(hostNames, allNodesInZone, zone);

        // Assess the assessment :-o
        List<ChangeManagementAssessor.ClusterAssessment> clusterAssessments = assessment.getClusterAssessments();
        assertEquals(1, clusterAssessments.size()); //One cluster is impacted
        assertEquals(3, clusterAssessments.get(0).clusterImpact);
        assertEquals(6, clusterAssessments.get(0).clusterSize);
        assertEquals(1, clusterAssessments.get(0).groupsImpact);
        assertEquals(2, clusterAssessments.get(0).groupsTotal);
        assertEquals("content:default", clusterAssessments.get(0).cluster);
        assertEquals("mytenant:myapp:default", clusterAssessments.get(0).app);
        assertEquals("prod.eu-trd", clusterAssessments.get(0).zone);

        List<ChangeManagementAssessor.HostAssessment> hostAssessments = assessment.getHostAssessments();
        assertEquals(2, hostAssessments.size());
        assertEquals("host1", hostAssessments.get(0).hostName);
        assertEquals(2, hostAssessments.get(0).numberOfChildren);
        assertEquals(2, hostAssessments.get(0).numberOfProblematicChildren);
    }

    private NodeOwner createOwner(String tenant, String application, String instance) {
        NodeOwner owner = new NodeOwner();
        owner.tenant = tenant;
        owner.application = application;
        owner.instance = instance;
        return owner;
    }

    private NodeMembership createMembership(String clusterId, int group) {
        NodeMembership membership = new NodeMembership();
        membership.group = "" + group;
        membership.clusterid = clusterId;
        membership.clustertype = "content";
        membership.index = 2;
        membership.retired = false;
        return membership;
    }

    private NodeRepositoryNode createNode(String nodename, String hostname, String appName, String clusterId, int group) {
        NodeRepositoryNode node = new NodeRepositoryNode();
        node.setHostname(nodename);
        node.setParentHostname(hostname);
        node.setState(NodeState.active);
        node.setOwner(createOwner("mytenant", appName, "default"));
        node.setMembership(createMembership(clusterId, group));

        return node;
    }
}
