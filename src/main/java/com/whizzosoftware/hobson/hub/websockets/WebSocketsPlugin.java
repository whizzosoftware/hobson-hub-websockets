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

import com.whizzosoftware.hobson.api.event.EventHandler;
import com.whizzosoftware.hobson.api.event.device.DeviceAvailableEvent;
import com.whizzosoftware.hobson.api.event.device.DeviceEvent;
import com.whizzosoftware.hobson.api.event.device.DeviceUnavailableEvent;
import com.whizzosoftware.hobson.api.event.device.DeviceVariablesUpdateEvent;
import com.whizzosoftware.hobson.api.event.hub.HubConfigurationUpdateEvent;
import com.whizzosoftware.hobson.api.event.plugin.PluginStatusChangeEvent;
import com.whizzosoftware.hobson.api.event.presence.PresenceUpdateNotificationEvent;
import com.whizzosoftware.hobson.api.event.task.TaskEvent;
import com.whizzosoftware.hobson.api.event.task.TaskExecutionEvent;
import com.whizzosoftware.hobson.api.event.task.TaskUpdatedEvent;
import com.whizzosoftware.hobson.api.plugin.AbstractHobsonPlugin;
import com.whizzosoftware.hobson.api.property.PropertyContainer;
import com.whizzosoftware.hobson.api.property.TypedProperty;
import com.whizzosoftware.hobson.api.task.HobsonTask;
import com.whizzosoftware.hobson.api.task.TaskContext;
import com.whizzosoftware.hobson.api.variable.DeviceVariableUpdate;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class WebSocketsPlugin extends AbstractHobsonPlugin {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketsPlugin.class);

    private static final int PORT = 8184;

    private Channel channel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final ChannelGroup clientChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    public WebSocketsPlugin(String pluginId, String version, String description) {
        super(pluginId, version, description);
    }

    @Override
    public void onPluginConfigurationUpdate(PropertyContainer config) {
    }

    @Override
    protected TypedProperty[] getConfigurationPropertyTypes() {
        return null;
    }

    @Override
    public String getName() {
        return "WebSockets Plugin";
    }

    @EventHandler
    public void onDeviceEvent(DeviceEvent event) {
        if (channel != null && channel.isOpen()) {
            if (event instanceof DeviceVariablesUpdateEvent) {
                logger.trace("Writing event to client channels: " + event.toString());
                clientChannels.writeAndFlush(new TextWebSocketFrame(createVariableUpdateJSON((DeviceVariablesUpdateEvent)event).toString()));
            } else if (event instanceof DeviceUnavailableEvent) {
                logger.trace("Writing event to client channels: " + event.toString());
                clientChannels.writeAndFlush(new TextWebSocketFrame(createDeviceUnavailableJSON((DeviceUnavailableEvent)event).toString()));
            } else if (event instanceof DeviceAvailableEvent) {
                logger.trace("Writing event to client channels: " + event.toString());
                clientChannels.writeAndFlush(new TextWebSocketFrame(createDeviceAvailableJSON((DeviceAvailableEvent)event).toString()));
            }
        } else {
            logger.trace("Channel not open; ignoring event: " + event);
        }
    }

    @EventHandler
    public void onPluginStatusChangeEvent(PluginStatusChangeEvent event) {
        if (channel != null && channel.isOpen()) {
            logger.trace("Writing event to client channels: " + event.toString());
            clientChannels.writeAndFlush(new TextWebSocketFrame(createPluginStatusChangeJSON(event).toString()));
        } else {
            logger.trace("Channel not open; ignoring event: " + event);
        }
    }

    @EventHandler
    public void onPresenceUpdateEvent(PresenceUpdateNotificationEvent event) {
        if (channel != null && channel.isOpen()) {
            logger.trace("Writing event to client channels: " + event.toString());
            clientChannels.writeAndFlush(new TextWebSocketFrame(createPresenceUpdateJSON(event).toString()));
        } else {
            logger.trace("Channel not open; ignoring event: " + event);
        }
    }

    @EventHandler
    public void onTaskEvent(TaskEvent event) {
        if (channel != null && channel.isOpen()) {
            if (event instanceof TaskExecutionEvent) {
                logger.trace("Writing event to client channels: " + event.toString());
                clientChannels.writeAndFlush(new TextWebSocketFrame(createTaskExecutionJSON((TaskExecutionEvent)event).toString()));
            } else if (event instanceof TaskUpdatedEvent) {
                logger.trace("Writing event to client channels: " + event.toString());
                TaskUpdatedEvent e = (TaskUpdatedEvent)event;
                HobsonTask task = getTaskManager().getTask(e.getTask());
                if (task != null) {
                    clientChannels.writeAndFlush(new TextWebSocketFrame(createTaskUpdatedJSON(e, task).toString()));
                } else {
                    logger.error("Received task update for non-existent task: {}", e.getTask());
                }
            }
        } else {
            logger.trace("Channel not open; ignoring event: " + event);
        }
    }

    @EventHandler
    public void onHubConfigurationUpdateEvent(HubConfigurationUpdateEvent event) {
        if (channel != null && channel.isOpen()) {
            logger.trace("Writing event to client channels: " + event.toString());
            clientChannels.writeAndFlush(new TextWebSocketFrame(createHubConfigurationJSON(event).toString()));
        }
    }

    @Override
    public void onStartup(PropertyContainer config) {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();

        b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .handler(new LoggingHandler(LogLevel.INFO))
            .childHandler(new WebSocketServerInitializer(clientChannels, getHubManager()));

        b.bind(PORT).addListener(new GenericFutureListener<ChannelFuture>() {
           @Override
           public void operationComplete(ChannelFuture future) throws Exception {
               WebSocketsPlugin.this.channel = future.channel();
               logger.debug("WebSocket server started at port {}", PORT);
               getHubManager().getLocalManager().setWebSocketInfo("ws", PORT, null);
           }
        });
    }

    @Override
    public void onShutdown() {
        try {
            channel.close().sync();
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        } catch (InterruptedException e) {
            logger.debug("Error shutting down WebSocket plugin", e);
        }
    }

    private JSONObject createVariableUpdateJSON(DeviceVariablesUpdateEvent event) {
        JSONObject json = new JSONObject();
        json.put("id", event.getEventId());
        json.put("timestamp", event.getTimestamp());
        JSONObject props = new JSONObject();
        json.put("properties", props);
        JSONArray updates = new JSONArray();
        props.put("updates", updates);
        for (DeviceVariableUpdate u : event.getUpdates()) {
            JSONObject update = new JSONObject();
            update.put("id", "/api/v1/hubs/" + u.getContext().getHubId() + "/plugins/local/" + u.getPluginId() + "/devices/" + u.getDeviceId() + "/variables/" + u.getContext().getName());
            update.put("name", u.getContext().getName());
            update.put("oldValue", u.getOldValue());
            update.put("newValue", u.getNewValue());
            update.put("hubId", u.getContext().getHubId());
            update.put("pluginId", u.getPluginId());
            update.put("deviceId", u.getDeviceId());
            updates.put(update);
        }
        return json;
    }

    private JSONObject createPresenceUpdateJSON(PresenceUpdateNotificationEvent event) {
        JSONObject json = new JSONObject();
        json.put("id", event.getEventId());
        json.put("timestamp", event.getTimestamp());
        JSONObject props = new JSONObject();
        json.put("properties", props);
        props.put("hubId", event.getEntityContext().getHubId());
        props.put("entityId", event.getEntityContext().getEntityId());
        props.put("oldLocation", event.getOldLocation());
        props.put("newLocation", event.getNewLocation());
        return json;
    }

    private JSONObject createTaskExecutionJSON(TaskExecutionEvent event) {
        HobsonTask task = getTaskManager().getTask(event.getContext());

        JSONObject json = new JSONObject();
        json.put("id", event.getEventId());
        json.put("timestamp", event.getTimestamp());
        JSONObject props = new JSONObject();
        json.put("properties", props);
        props.put("id", "/api/v1/hubs/" + event.getContext().getHubId() + "/tasks/" + event.getContext().getTaskId());
        props.put("name", task.getName());
        return json;
    }

    private JSONObject createHubConfigurationJSON(HubConfigurationUpdateEvent event) {
        JSONObject json = new JSONObject();
        json.put("id", event.getEventId());
        json.put("timestamp", event.getTimestamp());
        JSONObject props = new JSONObject();
        json.put("configuration", props);
        Map<String,Object> p = event.getConfiguration();
        for (String key : p.keySet()) {
            props.put(key, p.get(key));
        }
        return json;
    }

    private JSONObject createDeviceUnavailableJSON(DeviceUnavailableEvent event) {
        JSONObject json = new JSONObject();
        json.put("id", event.getEventId());
        json.put("timestamp", event.getTimestamp());
        JSONObject props = new JSONObject();
        json.put("properties", props);
        props.put("id", "/api/v1/hubs/" + event.getDeviceContext().getHubId() + "/plugins/local/" + event.getDeviceContext().getPluginId() + "/devices/" + event.getDeviceContext().getDeviceId());
        return json;
    }

    private JSONObject createDeviceAvailableJSON(DeviceAvailableEvent event) {
        JSONObject json = new JSONObject();
        json.put("id", event.getEventId());
        json.put("timestamp", event.getTimestamp());
        JSONObject props = new JSONObject();
        json.put("properties", props);
        props.put("id", "/api/v1/hubs/" + event.getDeviceContext().getHubId() + "/plugins/local/" + event.getDeviceContext().getPluginId() + "/devices/" + event.getDeviceContext().getDeviceId());
        return json;
    }

    private JSONObject createPluginStatusChangeJSON(PluginStatusChangeEvent event) {
        JSONObject json = new JSONObject();
        json.put("id", event.getEventId());
        json.put("timestamp", event.getTimestamp());
        JSONObject props = new JSONObject();
        json.put("properties", props);
        props.put("id", "/api/v1/hubs/" + event.getContext().getHubId() + "/plugins/local/" + event.getContext().getPluginId());
        props.put("pluginId", event.getContext().getPluginId());
        JSONObject status = new JSONObject();
        status.put("code", event.getStatus().getCode());
        status.put("message", event.getStatus().getMessage());
        props.put("status", status);
        return json;
    }

    private JSONObject createTaskUpdatedJSON(TaskUpdatedEvent event, HobsonTask task) {
        TaskContext ctx = event.getTask();
        JSONObject json = new JSONObject();
        json.put("id", event.getEventId());
        json.put("timestamp", event.getTimestamp());
        JSONObject props = new JSONObject();
        json.put("properties", props);
        props.put("id", "/api/v1/hubs/" + ctx.getHubId() + "/tasks/" + ctx.getTaskId());
        props.put("name", task.getName());
        props.put("description", task.getDescription());
        props.put("enabled", task.isEnabled());
        if (task.hasProperties()) {
            JSONObject props2 = new JSONObject();
            props.put("taskProperties", props2);
            Map<String, Object> p = task.getProperties();
            for (String key : p.keySet()) {
                props2.put(key, p.get(key));
            }
        }
        return json;
    }
}
