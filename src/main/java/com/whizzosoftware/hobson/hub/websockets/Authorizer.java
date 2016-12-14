/*
 *******************************************************************************
 * Copyright (c) 2016 Whizzo Software, LLC.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************
*/
package com.whizzosoftware.hobson.hub.websockets;

import com.whizzosoftware.hobson.api.hub.HubManager;
import com.whizzosoftware.hobson.api.user.HobsonRole;
import com.whizzosoftware.hobson.api.user.HobsonUser;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Channel handler that verifies the presence of an access token and passes along the request for further
 * processing if found and valid.
 *
 * @author Dan Noguerol
 */
public class Authorizer extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger logger = LoggerFactory.getLogger(Authorizer.class);

    private HubManager hubManager;

    Authorizer(HubManager hubManager) {
        super();
        this.hubManager = hubManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest message) throws Exception {
        logger.trace("channelRead0: {}", message);

        // attempt to get token from header and then from cookie
        String token = null;
        String h = HttpHeaders.getHeader(message, "Authorization");
        if (h != null && h.startsWith("Bearer ") && h.length() > 7) {
            token = h.substring(7, h.length()).trim();
        } else {
            h = HttpHeaders.getHeader(message, "Cookie");
            if (h != null) {
                Set<Cookie> cookies = CookieDecoder.decode(h);
                if (cookies != null) {
                    for (Cookie c : cookies) {
                        if ("Token".equalsIgnoreCase(c.getName())) {
                            token = c.getValue();
                        }
                    }
                }
            }
        }

        // if we found a token, process the message
        if (token != null) {
            try {
                HobsonUser user = hubManager.convertTokenToUser(token);
                if (user != null && (user.hasRole(HobsonRole.administrator.name()) || user.hasRole(HobsonRole.userRead.name()))) {
                    logger.trace("Found token, passing message along");
                    ctx.fireChannelRead(message.retain());
                }
            } catch (Exception e) {
                logger.debug("Token decryption error", e);
            }
        } else {
            logger.debug("No token found; closing connection");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
            response.headers().add("Content-Length", 0);
            ctx.writeAndFlush(response);
            ctx.close();
        }
    }
}
