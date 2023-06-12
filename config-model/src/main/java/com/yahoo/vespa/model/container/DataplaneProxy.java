// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.container;

import com.yahoo.cloud.config.DataplaneProxyConfig;
import com.yahoo.container.jdisc.DataplaneProxyConfigurator;
import com.yahoo.vespa.model.container.component.SimpleComponent;

public class DataplaneProxy extends SimpleComponent implements DataplaneProxyConfig.Producer {

    private final Integer port;
    private final String serverCertificate;
    private final String serverKey;
    private final String mTlsEndpoint;
    private final String tokenEndpoint;

    public DataplaneProxy(Integer port, String serverCertificate, String serverKey, String mTlsEndpoint, String tokenEndpoint) {
        super(DataplaneProxyConfigurator.class.getName());
        this.port = port;
        this.serverCertificate = serverCertificate;
        this.serverKey = serverKey;
        this.mTlsEndpoint = mTlsEndpoint;
        this.tokenEndpoint = tokenEndpoint;
    }

    @Override
    public void getConfig(DataplaneProxyConfig.Builder builder) {
        builder.port(port);
        builder.serverCertificate(serverCertificate);
        builder.serverKey(serverKey);
        builder.mTlsEndpoint(mTlsEndpoint);
        builder.tokenEndpoint(tokenEndpoint);
    }

}