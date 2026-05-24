package com.sfquiz.repository;

import com.sfquiz.entity.User;
import com.sfquiz.entity.UserRole;
import com.sfquiz.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByRole(UserRole role);
    long countByRole(UserRole role);
    List<User> findByStatusOrderByCreatedAtAsc(UserStatus status);
    List<User> findAllByOrderByCreatedAtDesc();
    List<User> findByRoleAndStatus(UserRole role, UserStatus status);
}
