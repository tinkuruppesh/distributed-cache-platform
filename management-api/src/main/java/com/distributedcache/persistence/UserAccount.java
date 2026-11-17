package com.distributedcache.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, unique = true) public String username;
    @Column(nullable = false) public String passwordHash;
    @Column(nullable = false) public String role;
    @Column(nullable = false) public boolean enabled = true;
    @Column(nullable = false) public long createdAt;
    @Column(nullable = false) public long updatedAt;
}
