package com.roshan.seat_reservation.show;

import com.roshan.seat_reservation.auth.AuthUser;
import com.roshan.seat_reservation.show.ShowDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping
    public ResponseEntity<ShowResponse> create(@RequestAttribute(AuthUser.REQUEST_ATTR) AuthUser user,
                                               @Valid @RequestBody CreateShowRequest req) {
        if (!user.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_only");
        }
        ShowResponse created = showService.create(req);
        return ResponseEntity.created(URI.create("/shows/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable UUID id) {
        return showService.get(id);
    }
}