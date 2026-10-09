package com.example.ssds.api.admin;

import com.example.ssds.api.admin.AdminUserService.CreateUserRequest;
import com.example.ssds.api.admin.AdminUserService.PasswordRequest;
import com.example.ssds.api.admin.AdminUserService.RoleOption;
import com.example.ssds.api.admin.AdminUserService.StatusRequest;
import com.example.ssds.api.admin.AdminUserService.UpdateUserRequest;
import com.example.ssds.api.admin.AdminUserService.UserResponse;
import com.example.ssds.api.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** S-14 使用者管理分頁（§8.2 {@code GET/POST/PUT/PATCH /admin/users}，§2.1 權限列 23）。 */
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('SYS_ADMIN')")
public class AdminUserController {
    private final AdminUserService users;

    public AdminUserController(AdminUserService users) {
        this.users = users;
    }

    @GetMapping
    public ApiResponse<List<UserResponse>> list() {
        return ApiResponse.success(users.list());
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleOption>> roles() {
        return ApiResponse.success(users.roleOptions());
    }

    @PostMapping
    public ApiResponse<UserResponse> create(@RequestBody CreateUserRequest request, HttpServletRequest servlet) {
        return ApiResponse.success(users.create(request, servlet.getRemoteAddr()));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserResponse> update(
            @PathVariable Long id, @RequestBody UpdateUserRequest request, HttpServletRequest servlet) {
        return ApiResponse.success(users.update(id, request, servlet.getRemoteAddr()));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<UserResponse> changeStatus(
            @PathVariable Long id, @RequestBody StatusRequest request, HttpServletRequest servlet) {
        return ApiResponse.success(users.changeStatus(id, request.status(), servlet.getRemoteAddr()));
    }

    @PatchMapping("/{id}/password")
    public ApiResponse<UserResponse> resetPassword(
            @PathVariable Long id, @RequestBody PasswordRequest request, HttpServletRequest servlet) {
        return ApiResponse.success(users.resetPassword(id, request.password(), servlet.getRemoteAddr()));
    }

    @PatchMapping("/{id}/unlock")
    public ApiResponse<UserResponse> unlock(@PathVariable Long id, HttpServletRequest servlet) {
        return ApiResponse.success(users.unlock(id, servlet.getRemoteAddr()));
    }
}
