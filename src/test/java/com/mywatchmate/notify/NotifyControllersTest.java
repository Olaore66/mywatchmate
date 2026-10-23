package com.mywatchmate.notify;

import static com.mywatchmate.web.WebLayerTestConfig.asGuest;
import static com.mywatchmate.web.WebLayerTestConfig.asUser;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.identity.DeviceToken;
import com.mywatchmate.notify.dto.ReminderSettingsResponse;
import com.mywatchmate.web.WebLayerTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({ReminderSettingsController.class, DeviceController.class})
@Import(WebLayerTestConfig.class)
class NotifyControllersTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final String REMINDERS = "/api/v1/spaces/" + SPACE + "/reminders";

    @Autowired MockMvc mvc;
    @MockitoBean ReminderService reminders;
    @MockitoBean DeviceService devices;

    @Test
    void readAndChangeMyReminderSetting() throws Exception {
        when(reminders.settings(AuthPrincipal.user(USER), SPACE)).thenReturn(new ReminderSettingsResponse(true, 10, true));
        when(reminders.updateSettings(eq(AuthPrincipal.user(USER)), eq(SPACE), argThat(r -> r.enabled() && r.minutesBefore() == 30)))
            .thenReturn(new ReminderSettingsResponse(true, 30, true));

        mvc.perform(get(REMINDERS).with(asUser(USER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.minutesBefore").value(10));
        mvc.perform(put(REMINDERS).with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"minutesBefore\":30}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.minutesBefore").value(30));
    }

    @Test
    void reminderSettingIsValidated() throws Exception {
        mvc.perform(put(REMINDERS).with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"minutesBefore\":5000}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.enabled").exists())
            .andExpect(jsonPath("$.errors.minutesBefore").exists());
        verifyNoInteractions(reminders);
    }

    @Test
    void guestsHaveNoRemindersOrDevices() throws Exception {
        UUID guest = UUID.randomUUID();
        mvc.perform(get(REMINDERS).with(asGuest(guest, SPACE))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/devices").with(asGuest(guest, SPACE)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"WEB\",\"token\":\"t\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(reminders, devices);
    }

    @Test
    void registerAndUnregisterADevice() throws Exception {
        mvc.perform(post("/api/v1/me/devices").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"ANDROID\",\"token\":\"fcm-1\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/me/devices?token=fcm-1").with(asUser(USER))).andExpect(status().isNoContent());

        verify(devices).register(eq(USER), argThat(r -> r.platform() == DeviceToken.Platform.ANDROID && r.token().equals("fcm-1")));
        verify(devices).unregister(USER, "fcm-1");
    }

    @Test
    void deviceNeedsPlatformAndToken() throws Exception {
        mvc.perform(post("/api/v1/me/devices").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.platform").exists())
            .andExpect(jsonPath("$.errors.token").exists());
        verifyNoInteractions(devices);
    }
}
