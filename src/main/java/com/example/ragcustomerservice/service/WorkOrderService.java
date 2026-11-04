package com.example.ragcustomerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ragcustomerservice.entity.WorkOrder;
import com.example.ragcustomerservice.mapper.WorkOrderMapper;
import com.example.ragcustomerservice.util.PushService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkOrderService {

    private final WorkOrderMapper workOrderMapper;
    private final PushService pushService;

    /**
     * 创建工单
     */
    public Long createOrder(String sessionId, String question) {
        WorkOrder order = new WorkOrder();
        order.setSessionId(sessionId);
        order.setQuestion(question);
        order.setStatus(WorkOrder.STATUS_PENDING);
        workOrderMapper.insert(order);
        return order.getId();
    }

    /**
     * 查询所有工单
     */
    public List<WorkOrder> listAll() {
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(WorkOrder::getCreateTime);
        return workOrderMapper.selectList(wrapper);
    }

    /**
     * 条件查询工单
     */
    public List<WorkOrder> list(LambdaQueryWrapper<WorkOrder> wrapper) {
        return workOrderMapper.selectList(wrapper);
    }

    /**
     * 认领工单:1待处理 → 2处理中(防止多个客服重复处理)
     */
    public void claimOrder(Long id, String handler) {
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new RuntimeException("工单不存在");
        }
        if (order.getStatus() != WorkOrder.STATUS_PENDING) {
            throw new RuntimeException("工单已被认领或已完成");
        }
        order.setHandler(handler);
        order.setStatus(WorkOrder.STATUS_PROCESSING);
        workOrderMapper.updateById(order);
    }

    /**
     * 处理工单:1/2 → 3已完成(带离线补偿的消息推送)
     */
    public void handleOrder(Long id, String handler, String reply) {
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new RuntimeException("工单不存在");
        }
        if (order.getStatus() == WorkOrder.STATUS_DONE) {
            throw new RuntimeException("工单已完成，无需重复处理");
        }
        order.setHandler(handler);
        order.setReply(reply);
        order.setStatus(WorkOrder.STATUS_DONE);
        workOrderMapper.updateById(order);

        // 在线直接推送;离线/推送失败进入 Redis 补偿队列,用户重连后自动补发
        String pushMsg = "【人工客服回复】\n" + reply;
        pushService.sendOrQueue(order.getSessionId(), pushMsg);
    }

    /**
     * 删除工单
     */
    public void deleteOrder(Long id) {
        workOrderMapper.deleteById(id);
    }
}