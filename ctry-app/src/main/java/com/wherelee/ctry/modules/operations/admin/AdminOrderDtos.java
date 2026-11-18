package com.wherelee.ctry.modules.operations.admin;

import com.wherelee.ctry.contracts.commerce.OrderDtos;
import com.wherelee.edu.modules.contracts.payment.PaymentDtos;

import java.util.List;

public final class AdminOrderDtos {

    private AdminOrderDtos() {
    }

    public record Detail(
            AdminOrderVO order,
            OrderDtos.OrderDetail orderDetail,
            List<PaymentDtos.AttemptView> attempts,
            List<PaymentDtos.EventView> events,
            PaymentDtos.RefundView refund
    ) {
    }
}
