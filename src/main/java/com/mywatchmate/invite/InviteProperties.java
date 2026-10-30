package com.mywatchmate.invite;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxPerRequest  addresses in one request
 * @param maxPerDay      invitations a room may queue (or resend) in 24 hours: we send to whatever is typed,
 *                       so this keeps us from becoming a spam tool (and our domain from being blocked)
 * @param lifetime       an invitation shows as pending this long (the room's link itself keeps working)
 * @param resendCooldown how soon the same invitation can be resent
 * @param maxAttempts    send attempts before an invitation is FAILED
 * @param retryDelay     wait before the next attempt, times the attempts so far
 */
@ConfigurationProperties("mywatchmate.invites")
public record InviteProperties(int maxPerRequest, int maxPerDay, Duration lifetime, Duration resendCooldown,
                               int maxAttempts, Duration retryDelay) {
}
