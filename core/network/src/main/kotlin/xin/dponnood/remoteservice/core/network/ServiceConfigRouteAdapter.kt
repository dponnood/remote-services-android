package xin.dponnood.remoteservice.core.network

import xin.dponnood.remoteservice.core.model.ServiceConfig

/** Adapter keeps persistence models independent from route selection policy. */
fun ServiceConfig.toRouteConfig(trustedSsids: Set<String>, probePath: String = "/"): ServiceRouteConfig =
    ServiceRouteConfig(
        internalUrl = lanUrl,
        publicUrl = wanUrl,
        trustedSsids = trustedSsids,
        probePath = probePath,
        serviceType = serviceType,
        connectionPolicy = connectionPolicy,
    )

/** Uses the SSIDs persisted with the service definition. */
fun ServiceConfig.toRouteConfig(probePath: String = "/"): ServiceRouteConfig =
    toRouteConfig(trustedSsids, probePath)
