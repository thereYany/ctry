package com.wherelee.ctry.modules.commerce.order;

import com.wherelee.ctry.modules.CtryPermissionCodes;
import com.wherelee.ctry.contracts.commerce.OrderDtos;
import com.wherelee.base.modules.platform.aop.Measured;
import com.wherelee.base.modules.platform.aop.RateLimit;
import com.wherelee.base.modules.platform.aop.RequirePermission;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderPaymentController {

    private final OrderPaymentService paymentService;

    public OrderPaymentController(OrderPaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/api/orders/{orderNo}/pay")
    @RequirePermission(CtryPermissionCodes.ORDER_PAY)
    @RateLimit(key = "order:pay", limit = 20, windowSeconds = 60)
    @Measured("order.pay")
    public OrderDtos.PayResponse pay(@RequestAttribute("userId") Long userId,
                                     @PathVariable String orderNo) {
        return paymentService.pay(userId, orderNo);
    }
}
