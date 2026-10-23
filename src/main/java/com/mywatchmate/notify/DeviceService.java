package com.mywatchmate.notify;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.identity.DeviceToken;
import com.mywatchmate.identity.DeviceTokenRepository;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.notify.dto.RegisterDeviceRequest;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remembers which phones/browsers can receive push notifications. Push sending itself arrives
 * with the mobile app; collecting tokens now means it can be switched on without app changes.
 */
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceTokenRepository devices;
    private final UserRepository users;
    private final Clock clock;

    /** Registering again is harmless. A token that moved to another account (shared phone) follows the new user. */
    @Transactional
    public void register(UUID userId, RegisterDeviceRequest request) {
        User user = users.findById(userId)
            .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "This account no longer exists."));
        DeviceToken device = devices.findByToken(request.token())
            .orElseGet(() -> devices.save(new DeviceToken(user, request.platform(), request.token())));
        device.setUser(user);
        device.setPlatform(request.platform());
        device.setLastSeenAt(clock.instant());
    }

    /** Sign-out on a device: stop pushing to it. Only your own devices; unknown tokens are ignored. */
    @Transactional
    public void unregister(UUID userId, String token) {
        devices.findByToken(token)
            .filter(d -> d.getUser().getId().equals(userId))
            .ifPresent(devices::delete);
    }
}
