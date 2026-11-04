package com.example.ragcustomerservice.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ragcustomerservice.entity.SysUser;
import com.example.ragcustomerservice.mapper.SysUserMapper;
import com.example.ragcustomerservice.util.TokenUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class LoginController {

    private final SysUserMapper sysUserMapper;
    private final TokenUtil tokenUtil;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CHAT_HISTORY_PREFIX = "chat:history:";

    /**
     * 发送验证码（模拟）
     */
    @GetMapping("/sendCode")
    public Map<String, Object> sendCode(@RequestParam String username) {
        String code = String.format("%06d", new java.util.Random().nextInt(1000000));
        redisTemplate.opsForValue().set("login_code:" + username, code, 5, TimeUnit.MINUTES);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("msg", "验证码已发送（模拟测试）：" + code);
        return result;
    }

    /**
     * 登录接口（支持账号密码/验证码，自动合并游客会话历史）
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String username,
                                     @RequestParam(required = false) String password,
                                     @RequestParam(required = false) String code,
                                     @RequestParam(required = false) String fromSession) {
        Map<String, Object> result = new HashMap<>();
        String failKey = "login_fail:" + username;
        Object failCount = redisTemplate.opsForValue().get(failKey);
        if (failCount != null && Integer.parseInt(failCount.toString()) >= 5) {
            result.put("code", 400);
            result.put("message", "密码错误次数过多，账号锁定10分钟");
            return result;
        }

        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, username);
        SysUser user = sysUserMapper.selectOne(wrapper);
        if (user == null) {
            result.put("code", 400);
            result.put("message", "用户不存在");
            return result;
        }

        // 验证码登录校验
        if (code != null && !code.isEmpty()) {
            String realCode = (String) redisTemplate.opsForValue().get("login_code:" + username);
            if (!code.equals(realCode)) {
                result.put("code", 400);
                result.put("message", "验证码错误");
                return result;
            }
            redisTemplate.delete("login_code:" + username);
        } else if (!user.getPassword().equals(password)) {
            long newCount = failCount == null ? 1 : Long.parseLong(failCount.toString()) + 1;
            redisTemplate.opsForValue().set(failKey, newCount, 10, TimeUnit.MINUTES);
            result.put("code", 400);
            result.put("message", "用户名或密码错误，还可尝试" + (5 - newCount) + "次");
            return result;
        }

        redisTemplate.delete(failKey);

        // ========== 核心：合并游客会话历史到账号 ==========
        if (fromSession != null && !fromSession.isEmpty()) {
            String fromKey = CHAT_HISTORY_PREFIX + fromSession;
            String toKey = CHAT_HISTORY_PREFIX + "user_" + username;

            @SuppressWarnings("unchecked")
            List<String> fromHistory = (List<String>) redisTemplate.opsForValue().get(fromKey);
            if (fromHistory != null && !fromHistory.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> toHistory = (List<String>) redisTemplate.opsForValue().get(toKey);
                if (toHistory == null) {
                    // 账号没历史，直接搬过去
                    redisTemplate.opsForValue().set(toKey, fromHistory, 30, TimeUnit.MINUTES);
                } else {
                    // 账号有历史，合并追加
                    toHistory.addAll(fromHistory);
                    // 最多保留最近6轮
                    if (toHistory.size() > 6) {
                        toHistory = toHistory.subList(toHistory.size() - 6, toHistory.size());
                    }
                    redisTemplate.opsForValue().set(toKey, toHistory, 30, TimeUnit.MINUTES);
                }
            }
        }
        // ====================================================

        String token = tokenUtil.createToken(user.getUsername(), user.getRole());
        result.put("code", 200);
        result.put("token", token);
        result.put("role", user.getRole());
        result.put("username", user.getUsername());
        result.put("message", "登录成功");
        return result;
    }
}
