package com.example.paymentservice.controller;

import com.example.paymentservice.dto.CountResponse;
import com.example.paymentservice.dto.PaymentRequest;
import com.example.paymentservice.dto.PaymentResponse;
import com.example.paymentservice.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse created = paymentService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Total payments, count per status and summed amount; optionally only for ?orderId= or ?userId= */
    @GetMapping("/count")
    public CountResponse count(@RequestParam(required = false) Long orderId,
                               @RequestParam(required = false) Long userId) {
        return paymentService.count(orderId, userId);
    }

    @GetMapping("/{id}")
    public PaymentResponse getById(@PathVariable Long id) {
        return paymentService.getById(id);
    }

    /** List all payments, optionally filtered by ?orderId= or ?userId= */
    @GetMapping
    public Page<PaymentResponse> getAll(@RequestParam(required = false) Long orderId,
                                        @RequestParam(required = false) Long userId,
                                        @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC)
                                        Pageable pageable) {
        return paymentService.getAll(orderId, userId, pageable);
    }

    @PutMapping("/{id}")
    public PaymentResponse update(@PathVariable Long id, @Valid @RequestBody PaymentRequest request) {
        return paymentService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        paymentService.delete(id);
    }
}
