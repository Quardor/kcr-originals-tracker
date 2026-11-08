package ru.quard0r.kcrbackend.users;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.quard0r.kcrbackend.users.dto.UserResponse;
import ru.quard0r.kcrbackend.users.repository.UserRepository;

import java.util.List;

/**
 * Справочник пользователей для административных операций (переназначение
 * ответственного, §11.4): только админ, только идентификационные поля.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public List<UserResponse> getAll() {
        return userRepository.findAllByOrderByUsernameAsc().stream()
                .map(UserResponse::from)
                .toList();
    }
}
