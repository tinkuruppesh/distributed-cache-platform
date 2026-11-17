package com.distributedcache.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "application_settings")
public class AppSettingEntity {
    @Id public String settingKey;
    @Column(nullable = false, length = 4000) public String settingValue;
    @Column(nullable = false) public long updatedAt;
}
