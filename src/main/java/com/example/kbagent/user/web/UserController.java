package com.example.kbagent.user.web;

import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.service.CurrentUserView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    /** 返回角色和区域范围。数据来自服务端加载的 Actor，而不是 Token 里的声明。 */
    @GetMapping("/me")
    public ApiResponse<CurrentUserView> me(@AuthenticationPrincipal Actor actor) {
        return ApiResponse.ok(CurrentUserView.of(actor));
    }
}
