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

package com.floragunn.searchguard.http;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Map;

import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.http.netty.NettyHttpRequest;
import org.elasticsearch.rest.RestRequest;

import com.floragunn.searchguard.action.configupdate.TransportConfigUpdateAction;
import com.floragunn.searchguard.configuration.ConfigChangeListener;
import com.floragunn.searchguard.support.ConfigConstants;

public class XFFResolver implements ConfigChangeListener{

    protected final ESLogger log = Loggers.getLogger(this.getClass());
    private volatile Settings settings;
    private volatile boolean enabled;
    private volatile RemoteIpDetector detector;
    
    @Inject
    public XFFResolver(final TransportConfigUpdateAction tcua) {
        super();
        tcua.addConfigChangeListener("config", this);
    }

    public TransportAddress resolve(final RestRequest request) {
        
        if(log.isDebugEnabled()) {
            log.debug("xff resolve {}", request.getRemoteAddress());
        }
        
        if(isInitialized() && enabled && request.getRemoteAddress() instanceof InetSocketAddress && request instanceof NettyHttpRequest) {
            InetSocketAddress isa =new InetSocketAddress(detector.detect((NettyHttpRequest) request), ((InetSocketAddress)request.getRemoteAddress()).getPort());
            TransportAddress retVal = new InetSocketTransportAddress(isa);
            request.putInContext(ConfigConstants.SG_XFF_DONE, Boolean.TRUE);
            log.debug("xff resolved {} to {}", request.getRemoteAddress(), isa);
            return retVal;
        } else if(request.getRemoteAddress() instanceof InetSocketAddress){
            log.debug("no xff done {},{},{},{}",isInitialized(), enabled, request.getClass());
            return new InetSocketTransportAddress((InetSocketAddress)request.getRemoteAddress());
        } else {
            throw new ElasticsearchSecurityException("cannot handle this request");
        }
    }

    @Override
    public void onChange(String event, Settings settings) {
        this.settings = settings;
        enabled = settings.getAsBoolean("searchguard.dynamic.http.xff.enabled", true);
        if(enabled) {
            detector = new RemoteIpDetector();
            detector.setInternalProxies(settings.get("searchguard.dynamic.http.xff.internalProxies", detector.getInternalProxies()));
            detector.setProxiesHeader(settings.get("searchguard.dynamic.http.xff.proxiesHeader", detector.getProxiesHeader()));
            detector.setRemoteIpHeader(settings.get("searchguard.dynamic.http.xff.proxiesHeader.remoteIpHeader", detector.getRemoteIpHeader()));
            detector.setTrustedProxies(settings.get("searchguard.dynamic.http.xff.proxiesHeader.trustedProxies", detector.getTrustedProxies()));
            
        } else {
            detector = null;
        }
        
    }

    @Override
    public void validate(String event, Settings settings) throws ElasticsearchSecurityException {
        
    }

    @Override
    public boolean isInitialized() {
        return this.settings != null;
    }
}




