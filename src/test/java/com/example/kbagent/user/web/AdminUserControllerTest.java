package com.example.kbagent.user.web;

import static com.example.kbagent.support.TestActors.ADMIN;
import static com.example.kbagent.support.TestActors.ANALYST;
import static com.example.kbagent.support.TestActors.USER;
import static com.example.kbagent.support.TestActors.as;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.support.SecuredWebMvcTest;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.service.AdminUserService;
import com.example.kbagent.user.service.CreateUserCommand;
import com.example.kbagent.user.service.UpdateUserCommand;
import com.example.kbagent.user.service.UserView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SecuredWebMvcTest(AdminUserController.class)
class AdminUserControllerTest {

    static final List<Actor> NON_ADMINS = List.of(USER, ANALYST);

    private static final UserView ANALYST_VIEW = new UserView(2L, "analyst", "华东分析员", Role.ANALYST,
            Set.of(Region.EAST), true, Instant.parse("2026-11-04T00:00:00Z"), Instant.parse("2026-11-04T00:00:00Z"));

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AdminUserService adminUserService;

    @Test
    void anonymousRequestIs401WithUnifiedBody() throws Exception {
        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        verifyNoInteractions(adminUserService);
    }

    @ParameterizedTest
    @FieldSource("NON_ADMINS")
    void nonAdminIs403AndServiceIsNeverCalled(Actor actor) throws Exception {
        mvc.perform(get("/api/v1/admin/users").with(as(actor)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mvc.perform(patch("/api/v1/admin/users/2").with(as(actor))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adminUserService);
    }

    @Test
    void adminCanListUsersWithOneBasedPaging() throws Exception {
        when(adminUserService.list("ana", 2, 10)).thenReturn(new PageResponse<>(List.of(ANALYST_VIEW), 2, 10, 11));

        mvc.perform(get("/api/v1/admin/users").param("keyword", "ana").param("page", "2").param("size", "10")
                        .with(as(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.items[0].username").value("analyst"))
                .andExpect(jsonPath("$.data.items[0].regionScope[0]").value("EAST"))
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());
    }

    @Test
    void pageParametersAreValidated() throws Exception {
        mvc.perform(get("/api/v1/admin/users").param("size", "101").with(as(ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/admin/users").param("page", "0").with(as(ADMIN)))
                .andExpect(status().isBadRequest());
        verify(adminUserService, never()).list(any(), anyInt(), anyInt());
    }

    @Test
    void createReturns201() throws Exception {
        when(adminUserService.create(any(CreateUserCommand.class))).thenReturn(ANALYST_VIEW);

        mvc.perform(post("/api/v1/admin/users").with(as(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"analyst","password":"Passw0rd!","displayName":"华东分析员",
                                 "role":"ANALYST","regionScope":["EAST"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(2));
    }

    @Test
    void createRejectsInvalidBodyPerField() throws Exception {
        mvc.perform(post("/api/v1/admin/users").with(as(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a b\",\"password\":\"short\",\"displayName\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data[*].field").value(hasItem("username")))
                .andExpect(jsonPath("$.data[*].field").value(hasItem("password")))
                .andExpect(jsonPath("$.data[*].field").value(hasItem("role")));
        verifyNoInteractions(adminUserService);
    }

    @Test
    void unknownRoleValueIs400() throws Exception {
        mvc.perform(post("/api/v1/admin/users").with(as(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","password":"Passw0rd!","displayName":"Bob","role":"ROOT"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unknownUserIs404() throws Exception {
        when(adminUserService.get(99L)).thenThrow(BusinessException.notFound("用户"));

        mvc.perform(get("/api/v1/admin/users/99").with(as(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("用户不存在"));
    }

    @Test
    void updatePassesCurrentAdminAsOperator() throws Exception {
        when(adminUserService.update(eq(2L), any(UpdateUserCommand.class), eq(ADMIN))).thenReturn(ANALYST_VIEW);

        mvc.perform(patch("/api/v1/admin/users/2").with(as(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        verify(adminUserService).update(2L, new UpdateUserCommand(null, null, null, false), ADMIN);
    }

    @Test
    void conflictFromServiceIs409() throws Exception {
        when(adminUserService.update(eq(1L), any(UpdateUserCommand.class), eq(ADMIN)))
                .thenThrow(new BusinessException(ErrorCode.CONFLICT, "不能停用自己的账号或修改自己的角色"));

        mvc.perform(patch("/api/v1/admin/users/1").with(as(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void blankDisplayNameInPatchIs400() throws Exception {
        mvc.perform(patch("/api/v1/admin/users/2").with(as(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data[0].field").value("displayName"));
    }
}
