package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.ShiftCloseRequest;
import com.supermarket.dto.ShiftOpenRequest;
import com.supermarket.model.Shift;
import com.supermarket.model.Cashier;
import com.supermarket.model.CashierRole;
import com.supermarket.exception.PermissionDeniedException;
import com.supermarket.service.ShiftService;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

@RestController
@RequestMapping("/shifts")
public class ShiftController {

    private final ShiftService shiftService;
    private final SessionService sessionService;

    public ShiftController(ShiftService shiftService, SessionService sessionService) {
        this.shiftService = shiftService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<Shift>> findAll(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        Cashier cashier = sessionService.requireUser(token);
        List<Shift> shifts = cashier.getRole().isOperationalManager()
                ? shiftService.findAll()
                : shiftService.findForCashier(cashier.getId());
        return ApiResponse.ok("Shifts loaded", shifts);
    }

    @GetMapping("/open")
    public ApiResponse<Shift> findOpenShift(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestParam(required = false) Long cashierId
    ) {
        Cashier cashier = sessionService.requireUser(token);
        Long targetCashierId = cashierId == null ? cashier.getId() : cashierId;
        if (!cashier.getRole().isOperationalManager() && !targetCashierId.equals(cashier.getId())) {
            throw new PermissionDeniedException("shift.onlyOwnView");
        }
        return ApiResponse.ok("Open shift loaded", shiftService.findOpenShift(targetCashierId).orElse(null));
    }

    @PostMapping("/open")
    public ApiResponse<Shift> openShift(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestBody ShiftOpenRequest request
    ) {
        Cashier cashier = sessionService.requireUser(token);
        if (request.getCashierId() != null && !request.getCashierId().equals(cashier.getId())) {
            throw new PermissionDeniedException("shift.onlyOwnOpen");
        }
        return ApiResponse.ok("Shift opened", shiftService.openShift(request, cashier));
    }

    @PostMapping("/{shiftId}/close")
    public ApiResponse<Shift> closeShift(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @PathVariable Long shiftId,
            @RequestBody ShiftCloseRequest request
    ) {
        Cashier cashier = sessionService.requireUser(token);
        return ApiResponse.ok("Shift closed", shiftService.closeShift(shiftId, request, cashier));
    }
}
