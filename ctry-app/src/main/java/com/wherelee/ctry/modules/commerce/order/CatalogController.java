package com.wherelee.ctry.modules.commerce.order;

import com.wherelee.ctry.modules.CtryPermissionCodes;
import com.wherelee.edu.modules.platform.aop.Measured;
import com.wherelee.edu.modules.platform.aop.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/catalog/series")
public class CatalogController {

    private final OrderService orderService;

    public CatalogController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @RequirePermission(CtryPermissionCodes.COURSE_CATALOG_READ)
    @Measured("catalog.series")
    public List<CatalogItem> list(@RequestAttribute("userId") Long userId) {
        return orderService.catalog(userId);
    }
}
