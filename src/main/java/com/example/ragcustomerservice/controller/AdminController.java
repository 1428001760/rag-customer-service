package com.example.ragcustomerservice.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ragcustomerservice.entity.ChatLog;
import com.example.ragcustomerservice.entity.WorkOrder;
import com.example.ragcustomerservice.service.ChatLogService;
import com.example.ragcustomerservice.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final WorkOrderService workOrderService;
    private final ChatLogService chatLogService;

    /**
     * 全部工单列表
     */
    @GetMapping("/order/list")
    public Map<String, Object> orderList() {
        Map<String, Object> result = new HashMap<>();
        List<WorkOrder> list = workOrderService.listAll();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    /**
     * 根据会话ID查询自己的工单（用户端用）
     */
    @GetMapping("/order/listBySession")
    public Map<String, Object> orderListBySession(@RequestParam String sessionId) {
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkOrder::getSessionId, sessionId);
        wrapper.orderByDesc(WorkOrder::getCreateTime);
        List<WorkOrder> list = workOrderService.list(wrapper);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    /**
     * 创建工单（用户转人工）
     */
    @PostMapping("/order/create")
    public Map<String, Object> createOrder(@RequestParam String sessionId, @RequestParam String question) {
        Long orderId = workOrderService.createOrder(sessionId, question);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("orderId", orderId);
        result.put("message", "工单创建成功，客服将尽快处理");
        return result;
    }

    /**
     * 认领工单（坐席接单，状态置为处理中）
     */
    @PostMapping("/order/claim")
    public Map<String, Object> claimOrder(@RequestParam Long id, @RequestParam String handler) {
        workOrderService.claimOrder(id, handler);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "工单认领成功，开始处理");
        return result;
    }

    /**
     * 处理工单（管理端用，处理完自动推送给用户）
     */
    @PostMapping("/order/handle")
    public Map<String, Object> handleOrder(@RequestParam Long id,
                                           @RequestParam String handler,
                                           @RequestParam String reply) {
        workOrderService.handleOrder(id, handler, reply);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "工单处理完成");
        return result;
    }

    /**
     * 全部对话记录
     */
    @GetMapping("/log/list")
    public Map<String, Object> logList() {
        Map<String, Object> result = new HashMap<>();
        List<ChatLog> list = chatLogService.listAll();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    /**
     * 删除工单
     */
    @PostMapping("/order/delete")
    public Map<String, Object> deleteOrder(@RequestParam Long id) {
        workOrderService.deleteOrder(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "删除成功");
        return result;
    }
}