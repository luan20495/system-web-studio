package com.systemwebstudio.identity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class UserEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true, length = 120)
    var username: String = "",
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = "",
    @Column(nullable = false)
    var enabled: Boolean = true,
    @Column(name = "display_name", length = 160)
    var displayName: String? = null,
    @Column(name = "system_admin", nullable = false)
    var systemAdmin: Boolean = false,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

interface UserRepository : JpaRepository<UserEntity, UUID> {
    fun findByUsername(username: String): UserEntity?
}

/** Principal carried in the session; holds the user id so controllers need no username lookup. */
class StudioUserDetails(
    val userId: UUID,
    username: String,
    password: String,
    enabled: Boolean,
    val displayName: String?,
    val systemAdmin: Boolean,
    authorities: Collection<GrantedAuthority>
) : org.springframework.security.core.userdetails.User(username, password, enabled, true, true, true, authorities)

@Service
class DatabaseUserDetailsService(private val users: UserRepository) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        val user = users.findByUsername(username) ?: throw UsernameNotFoundException("Invalid username or password")
        val authorities = buildList<GrantedAuthority> {
            add(SimpleGrantedAuthority("ROLE_USER"))
            if (user.systemAdmin) add(SimpleGrantedAuthority("ROLE_ADMIN"))
        }
        return StudioUserDetails(user.id, user.username, user.passwordHash, user.enabled, user.displayName, user.systemAdmin, authorities)
    }
}
