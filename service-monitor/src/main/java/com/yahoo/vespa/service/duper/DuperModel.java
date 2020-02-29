// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.service.duper;

import com.yahoo.config.model.api.ApplicationInfo;
import com.yahoo.config.model.api.HostInfo;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.HostName;
import com.yahoo.log.LogLevel;
import com.yahoo.vespa.service.monitor.DuperModelListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A non-thread-safe mutable container of ApplicationInfo, also taking care of listeners on changes.
 *
 * @author hakonhall
 */
public class DuperModel {
    private static Logger logger = Logger.getLogger(DuperModel.class.getName());

    private final Map<ApplicationId, ApplicationInfo> applicationsById = new HashMap<>();
    private final Map<HostName, ApplicationInfo> applicationsByHostname = new HashMap<>();
    private final Map<ApplicationId, Set<HostName>> hostnamesById = new HashMap<>();

    private final List<DuperModelListener> listeners = new ArrayList<>();
    private boolean isComplete = false;

    public void registerListener(DuperModelListener listener) {
        applicationsById.values().forEach(listener::applicationActivated);
        listeners.add(listener);
    }

    void setComplete() {
        if (!isComplete) {
            logger.log(LogLevel.INFO, "Bootstrap done - duper model is complete");
            isComplete = true;

            listeners.forEach(DuperModelListener::bootstrapComplete);
        }
    }

    public boolean isComplete() { return isComplete; }

    public int numberOfApplications() {
        return applicationsById.size();
    }

    public int numberOfHosts() {
        return applicationsByHostname.size();
    }

    public boolean contains(ApplicationId applicationId) {
        return applicationsById.containsKey(applicationId);
    }

    public Optional<ApplicationInfo> getApplicationInfo(ApplicationId applicationId) {
        return Optional.ofNullable(applicationsById.get(applicationId));
    }

    public Optional<ApplicationInfo> getApplicationInfo(HostName hostName) {
        return Optional.ofNullable(applicationsByHostname.get(hostName));
    }

    public List<ApplicationInfo> getApplicationInfos() {
        return List.copyOf(applicationsById.values());
    }

    public void add(ApplicationInfo applicationInfo) {
        ApplicationInfo oldApplicationInfo = applicationsById.put(applicationInfo.getApplicationId(), applicationInfo);

        final String logPrefix;
        if (oldApplicationInfo == null) {
            logPrefix = isComplete ? "New application " : "Bootstrapped application ";
        } else {
            logPrefix = isComplete ? "Reactivated application " : "Rebootstrapped application ";
        }
        logger.log(LogLevel.INFO, logPrefix + applicationInfo.getApplicationId());

        Set<HostName> oldHostnames = hostnamesById.remove(applicationInfo.getApplicationId());
        if (oldHostnames != null) {
            oldHostnames.forEach(applicationsByHostname::remove);
        }

        Set<HostName> hostnames = applicationInfo.getModel().getHosts().stream()
                .map(HostInfo::getHostname)
                .map(HostName::from)
                .collect(Collectors.toSet());

        hostnamesById.put(applicationInfo.getApplicationId(), hostnames);
        hostnames.forEach(hostname -> applicationsByHostname.put(hostname, applicationInfo));

        listeners.forEach(listener -> listener.applicationActivated(applicationInfo));
    }

    public void remove(ApplicationId applicationId) {
        Set<HostName> hostnames = hostnamesById.remove(applicationId);
        if (hostnames != null) {
            hostnames.forEach(applicationsByHostname::remove);
        }

        ApplicationInfo application = applicationsById.remove(applicationId);
        if (application != null) {
            logger.log(LogLevel.INFO, "Removed application " + applicationId);
            listeners.forEach(listener -> listener.applicationRemoved(applicationId));
        }
    }
}
