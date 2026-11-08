package ru.quard0r.kcrbackend.dto.response;

import lombok.Builder;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;

@Builder
public record UserResponse (
        Long id,
        String username,
        Role role
){
    public static UserResponse from(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .build();
    }
}
