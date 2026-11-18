/**
 * 交易模块：课程商店、订单、履约和权益。
 *
 * <p>支付能力来自 btry-payment（内核），通过 PaymentOperations 发起、
 * 通过 PaymentSettledListener 监听事件后自行履约。</p>
 */
@org.springframework.modulith.ApplicationModule(displayName = "Commerce", allowedDependencies = {"contracts"})

package com.wherelee.ctry.modules.commerce;
