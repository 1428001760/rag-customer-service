package com.example.ragcustomerservice.util;

import com.example.ragcustomerservice.entity.OperationLog;
import com.example.ragcustomerservice.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LogUtil {

    private final OperationLogMapper operationLogMapper;

    public void record(String operator, String module, String operation, String content) {
        OperationLog log = new OperationLog();
        log.setOperator(operator);
        log.setModule(module);
        log.setOperation(operation);
        log.setContent(content);
        operationLogMapper.insert(log);
    }
}
