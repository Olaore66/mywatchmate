package com.mywatchmate.notify;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mywatchmate.identity.DeviceToken;
import com.mywatchmate.identity.DeviceTokenRepository;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.notify.dto.RegisterDeviceRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock DeviceTokenRepository devices;
    @Mock UserRepository users;

    private DeviceService service;
    private User ada;

    @BeforeEach
    void setUp() {
        service = new DeviceService(devices, users, clockAt(MONDAY_MIDNIGHT));
        ada = user("Ada");
    }

    @Test
    void newDeviceIsSaved() {
        when(users.findById(ada.getId())).thenReturn(Optional.of(ada));
        when(devices.findByToken("fcm-1")).thenReturn(Optional.empty());
        when(devices.save(any(DeviceToken.class))).thenAnswer(i -> i.getArgument(0));

        service.register(ada.getId(), new RegisterDeviceRequest(DeviceToken.Platform.ANDROID, "fcm-1"));

        ArgumentCaptor<DeviceToken> saved = ArgumentCaptor.forClass(DeviceToken.class);
        verify(devices).save(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(ada);
        assertThat(saved.getValue().getPlatform()).isEqualTo(DeviceToken.Platform.ANDROID);
        assertThat(saved.getValue().getLastSeenAt()).isEqualTo(MONDAY_MIDNIGHT);
    }

    @Test
    void registeringAgainRefreshesInsteadOfDuplicating() {
        DeviceToken existing = new DeviceToken(ada, DeviceToken.Platform.WEB, "fcm-1");
        when(users.findById(ada.getId())).thenReturn(Optional.of(ada));
        when(devices.findByToken("fcm-1")).thenReturn(Optional.of(existing));

        service.register(ada.getId(), new RegisterDeviceRequest(DeviceToken.Platform.WEB, "fcm-1"));

        verify(devices, never()).save(any());
        assertThat(existing.getLastSeenAt()).isEqualTo(MONDAY_MIDNIGHT);
    }

    @Test
    void aSharedPhoneFollowsWhoeverSignedInLast() {
        User tunde = user("Tunde");
        DeviceToken adasPhone = new DeviceToken(ada, DeviceToken.Platform.IOS, "apns-1");
        when(users.findById(tunde.getId())).thenReturn(Optional.of(tunde));
        when(devices.findByToken("apns-1")).thenReturn(Optional.of(adasPhone));

        service.register(tunde.getId(), new RegisterDeviceRequest(DeviceToken.Platform.IOS, "apns-1"));

        assertThat(adasPhone.getUser()).isSameAs(tunde);
    }

    @Test
    void deletedAccountsCantRegister() {
        UUID gone = UUID.randomUUID();
        when(users.findById(gone)).thenReturn(Optional.empty());

        assertApiError(() -> service.register(gone, new RegisterDeviceRequest(DeviceToken.Platform.WEB, "t")),
            HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    @Test
    void youCanOnlyUnregisterYourOwnDevice() {
        DeviceToken adasPhone = new DeviceToken(ada, DeviceToken.Platform.IOS, "apns-1");
        when(devices.findByToken("apns-1")).thenReturn(Optional.of(adasPhone));

        service.unregister(UUID.randomUUID(), "apns-1");
        verify(devices, never()).delete(any());

        service.unregister(ada.getId(), "apns-1");
        verify(devices).delete(adasPhone);
    }
}
