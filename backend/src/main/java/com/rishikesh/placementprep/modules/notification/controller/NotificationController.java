package com.rishikesh.placementprep.modules.notification.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.rishikesh.placementprep.modules.auth.service.UserService;
import com.rishikesh.placementprep.modules.notification.dto.NotificationDTO;
import com.rishikesh.placementprep.modules.notification.service.NotificationService;

/**
 * The student's notification bell.
 *
 * <p>Restricted to students and coordinators by SecurityConfig - they are the only ones
 * notifications are written for. Every path resolves the caller from the token, so there
 * is no id in the URL that could reach someone else's notifications.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final UserService userService;

    public NotificationController(NotificationService notificationService, UserService userService) {
        this.notificationService = notificationService;
        this.userService = userService;
    }

    /** My newest 50 notifications, newest first. */
    @GetMapping("/me")
    public List<NotificationDTO> mine(@AuthenticationPrincipal UserDetails principal) {
        return notificationService.findMine(callerId(principal));
    }

    /** Marks one of mine read. Someone else's is a 404, like one that does not exist. */
    @PostMapping("/{id}/read")
    public NotificationDTO markRead(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        return notificationService.markRead(id, callerId(principal))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No notification of yours with id " + id));
    }

    /** Marks all of mine read. 204, whether or not anything was unread. */
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal UserDetails principal) {
        notificationService.markAllRead(callerId(principal));
    }

    private Long callerId(UserDetails principal) {
        return userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account no longer exists"))
                .id();
    }
}
