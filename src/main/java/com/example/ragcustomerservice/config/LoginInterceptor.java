package com.example.ragcustomerservice.config;

import com.example.ragcustomerservice.util.TokenUtil;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    private final TokenUtil tokenUtil;

    public LoginInterceptor(TokenUtil tokenUtil) {
        this.tokenUtil = tokenUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 跨域预检直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = request.getHeader("token");
        String userInfo = tokenUtil.checkToken(token);

        if (userInfo == null) {
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\"}");
            return false;
        }

        String[] parts = userInfo.split(":");
        request.setAttribute("username", parts[0]);
        request.setAttribute("role", parts[1]);
        String role = parts[1];
        String uri = request.getRequestURI();

        // 工单处理接口：仅管理员和客服坐席可访问
        if (uri.startsWith("/admin/order/handle")
                || uri.startsWith("/admin/order/delete")
                || uri.startsWith("/admin/order/claim")) {
            if (!"admin".equals(role) && !"cs_staff".equals(role)) {
                response.setContentType("application/json;charset=utf-8");
                response.getWriter().write("{\"code\":403,\"message\":\"无权限访问\"}");
                return false;
            }
        }

        // 知识库管理接口：仅管理员和知识库管理员可访问
        if (uri.startsWith("/admin/knowledge")) {
            if (!"admin".equals(role) && !"kb_admin".equals(role)) {
                response.setContentType("application/json;charset=utf-8");
                response.getWriter().write("{\"code\":403,\"message\":\"无权限访问\"}");
                return false;
            }
        }

        return true;
    }
}