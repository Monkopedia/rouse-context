package com.rousecontext.mcp.health

/**
 * Thrown when Health Connect is not available on the device
 * or the required permissions have not been granted.
 */
class HealthConnectUnavailableException(message: String) : RuntimeException(message)
