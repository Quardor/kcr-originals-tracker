package ru.quard0r.kcrbackend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.quard0r.kcrbackend.dto.response.UserResponse;
import ru.quard0r.kcrbackend.repository.UserRepository;

import java.util.List;

/**
 * Справочник пользователей для административных операций (переназначение
 * ответственного, §11.4): только админ, только идентификационные поля.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserResponse>> getUsers() {
        return ResponseEntity.ok(userRepository.findAllByOrderByUsernameAsc().stream()
                .map(UserResponse::from)
                .toList());
    }
}
