package com.wherelee.ctry.modules.operations.admin;

import com.wherelee.base.modules.contracts.payment.AdminReconciliationOperations;
import com.wherelee.base.modules.contracts.payment.AdminReconciliationOperations.DiffView;
import com.wherelee.base.modules.contracts.payment.AdminReconciliationOperations.ReconSummary;
import com.wherelee.base.modules.platform.aop.AdminAudit;
import com.wherelee.base.modules.platform.aop.Idempotent;
import com.wherelee.base.modules.platform.aop.Measured;
import com.wherelee.base.modules.platform.aop.RateLimit;
import com.wherelee.base.modules.platform.aop.PermissionCodes;
import com.wherelee.base.modules.platform.aop.RequirePermission;
import com.wherelee.base.modules.platform.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 运营端对账台：触发对账、查看差异、人工处理。
 *
 * <p>只依赖 {@link AdminReconciliationOperations} 契约，不接触对账库表与渠道账单实现。</p>
 */
@RestController
@RequestMapping("/api/admin/recon")
public class AdminReconciliationController {

    private final AdminReconciliationOperations operations;

    public AdminReconciliationController(AdminReconciliationOperations operations) {
        this.operations = operations;
    }

    @PostMapping("/run")
    @RequirePermission(PermissionCodes.ADMIN_RECON_MANAGE)
    @RateLimit(key = "recon-run", limit = 10, windowSeconds = 60)
    @Idempotent(key = "recon-run", windowSeconds = 5)
    @Measured("admin.recon.run")
    public ReconSummary run(@RequestParam(defaultValue = "MOCK") String channel,
                            @RequestParam(required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate) {
        return operations.run(channel, billDate);
    }

    @GetMapping("/diffs")
    @RequirePermission(PermissionCodes.ADMIN_RECON_MANAGE)
    @Measured("admin.recon.diffs")
    public PageResult<DiffView> diffs(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return operations.pageDiffs(status, page, size);
    }

    @PutMapping("/diffs/{id}/handle")
    @RequirePermission(PermissionCodes.ADMIN_RECON_MANAGE)
    @AdminAudit(action = "RECON_HANDLE", targetType = "RECON_DIFF", targetIdArg = 0)
    @Measured("admin.recon.handle")
    public void handle(@RequestAttribute("userId") Long operatorId,
                       @PathVariable Long id,
                       @Valid @RequestBody HandleRequest request) {
        operations.handle(id, operatorId, request.status());
    }

    public record HandleRequest(
            @NotBlank(message = "缺少处理状态")
            @Size(max = 16, message = "处理状态过长")
            String status
    ) {
    }
}
