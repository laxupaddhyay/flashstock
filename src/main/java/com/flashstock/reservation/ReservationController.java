package com.flashstock.reservation;

import com.flashstock.api.ApiException;
import com.flashstock.security.CurrentCustomer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {
    private final ReservationService service;
    public ReservationController(ReservationService service) { this.service=service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ReservationDtos.ReservationResponse create(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ReservationDtos.CreateRequest request, Authentication auth) {
        if (!key.matches("[A-Za-z0-9._~-]{1,200}")) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must contain 1 to 200 URL-safe characters");
        return service.create(CurrentCustomer.id(auth), key, request);
    }
    @GetMapping("/{id}") public ReservationDtos.ReservationResponse get(@PathVariable UUID id, Authentication auth) { return service.get(CurrentCustomer.id(auth), id); }
    @PostMapping("/{id}/confirm") public ReservationDtos.ConfirmResponse confirm(@PathVariable UUID id, Authentication auth) { return service.confirm(CurrentCustomer.id(auth), id); }
    @PostMapping("/{id}/cancel") public ReservationDtos.ReservationResponse cancel(@PathVariable UUID id, Authentication auth) { return service.cancel(CurrentCustomer.id(auth), id); }
}
